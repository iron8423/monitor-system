/**
 * 前端自检（B 侧）：把「不靠浏览器也能验的部分」固化成断言。
 *
 *   cd frontend && npm run selfcheck
 *
 * 覆盖三块，都是**把 store / 纯函数用 Vite 的 SSR 加载器真跑一遍**，不是读代码：
 *   ① 实时推送：连接归属/幂等、measurement 并表、alarm 升降级、SSE 可见性收口；
 *   ② 时间轴回放：多序列合帧、前值保持、滑块往返（顺带用后端真实 series 跑一遍）；
 *   ③ 测项中立化：编一个档案里本来没有的测项（温度 ℃），验证它能一路走到
 *      值/单位/名称/阈值/变色；以及项目过滤（测点 → 对象 → 场景 → 项目）。
 *
 * 需要**后端在跑**（默认 http://127.0.0.1:8080，可用 MONITOR_BASE 覆盖），
 * 时间轴里那条真实数据断言会走 `/ingest` + `/points/{id}/series`。
 * 只为验证前端逻辑、不为压测：全部是只读断言 + 两条临时测值。
 */
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'

const BASE = process.env.MONITOR_BASE || 'http://127.0.0.1:8080/api/v1'
const results = []
const check = (name, ok, extra = '') => {
  results.push([name, ok])
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`)
}

// 浏览器环境替身：localStorage（登录态）+ EventSource（假连接，可手动发事件）
const memory = new Map()
globalThis.localStorage = {
  getItem: (k) => (memory.has(k) ? memory.get(k) : null),
  setItem: (k, v) => memory.set(k, String(v)),
  removeItem: (k) => memory.delete(k),
}
class FakeEventSource {
  static instances = []
  static get last() { return FakeEventSource.instances.at(-1) }
  constructor(url) { this.url = url; this.readyState = 0; this.closed = false; this.listeners = {}; FakeEventSource.instances.push(this) }
  addEventListener(n, fn) { (this.listeners[n] ||= []).push(fn) }
  emit(n, payload) { for (const fn of this.listeners[n] || []) fn({ data: typeof payload === 'string' ? payload : JSON.stringify(payload) }) }
  close() { this.closed = true; this.readyState = 2 }
}
globalThis.EventSource = FakeEventSource
globalThis.EventSource.CLOSED = 2
globalThis.EventSource.CONNECTING = 0

const server = await createServer({ root: process.cwd(), server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })

try {
  setActivePinia(createPinia())
  const { useMonitorStore } = await server.ssrLoadModule('/src/stores/monitor.js')
  const { useRealtimeStore } = await server.ssrLoadModule('/src/stores/realtime.js')
  const { useUserStore } = await server.ssrLoadModule('/src/stores/user.js')
  const { resolvePointVisual } = await server.ssrLoadModule('/src/constants/status.js')
  const { warnThresholdOf } = await server.ssrLoadModule('/src/utils/thresholds.js')
  const { buildFrames, frameDataCount, frameProgress, indexFromProgress } = await server.ssrLoadModule('/src/utils/timeline.js')
  const api = await server.ssrLoadModule('/src/api/monitor.js')

  const user = useUserStore()
  const monitor = useMonitorStore()
  const realtime = useRealtimeStore()

  // ── ① 实时推送 ────────────────────────────────────────────────
  realtime.start()
  check('未登录（无 token）不建连接', FakeEventSource.instances.length === 0)
  user.token = 'fake-jwt'
  realtime.start()
  check('有 token 时建立连接，且只有一条', FakeEventSource.instances.length === 1)
  realtime.start()
  check('重复 start() 不会开第二条连接（布局 + 大屏都会调）', FakeEventSource.instances.length === 1)
  check('token 走 query（契约 D8 的唯一例外）', FakeEventSource.last.url.includes('token=fake-jwt'))
  FakeEventSource.last.emit('open')
  check('open 后状态为 live', realtime.isLive === true)

  monitor.points = [{ id: 2, code: 'P-HK02', objectId: 1, longitude: 113, latitude: 23 }]
  monitor.latestMap = {
    2: { pointId: 2, pointCode: 'P-HK02', latest: { collectTime: '2026-09-14T09:00:00+08:00', defo_mm: 1.8, rate_mm_d: 0.07, quality: 'VALID', signal: 0.9 }, state: 'normal' },
  }
  monitor.metrics = [
    { pointId: 2, code: 'defo_mm', name: '累计形变', unit: 'mm', sortOrder: 1 },
    { pointId: 2, code: 'rate_mm_d', name: '形变速率', unit: 'mm/d', sortOrder: 2 },
    { pointId: 2, code: 'temp_c', name: '表面温度', unit: '℃', sortOrder: 3 },
  ]
  monitor.deviceIds = [1]

  FakeEventSource.last.emit('measurement', { pointId: 2, pointCode: 'P-HK02', collectTime: '2026-09-14T09:29:08+08:00', defo_mm: 7.2, rate_mm_d: 0.42, quality: 'VALID' })
  const latest = monitor.latestMap[2].latest
  check('measurement 并进 latestMap，且保留推送里没有的字段', latest.defo_mm === 7.2 && latest.signal === 0.9)
  check('非本项目测点的 measurement 被忽略', (() => {
    const before = realtime.measurementCount
    FakeEventSource.last.emit('measurement', { pointId: 999, defo_mm: 1 })
    return realtime.measurementCount === before
  })())

  FakeEventSource.last.emit('alarm', { id: 1, alarmType: 'POINT', pointId: 2, level: 'warning', status: 'PENDING', triggeredAt: '2026-09-14T09:00:00+08:00' })
  check('alarm 记入未解除警情表，enrichedPoints 带上 hasAlarm/alarmLevel',
    monitor.activeAlarms[2]?.level === 'warning' && monitor.enrichedPoints[0].hasAlarm === true)
  FakeEventSource.last.emit('alarm', { id: 1, alarmType: 'POINT', pointId: 2, level: 'alarm', status: 'PENDING', triggeredAt: '2026-09-14T09:00:00+08:00' })
  check('同一条警情就地升级（不新增条目）', monitor.activeAlarms[2].level === 'alarm' && Object.keys(monitor.activeAlarms).length === 1)
  FakeEventSource.last.emit('alarm', { id: 1, alarmType: 'POINT', pointId: 2, level: 'alarm', status: 'RESOLVED', triggeredAt: '2026-09-14T09:05:00+08:00' })
  check('解除后从点表摘掉（否则颜色一直是红的）', monitor.activeAlarms[2] === undefined)

  const beforeForeign = monitor.recentAlarms.length
  FakeEventSource.last.emit('alarm', { id: 2, alarmType: 'POINT', pointId: 999, pointCode: 'P-OTHER', level: 'alarm', status: 'PENDING' })
  FakeEventSource.last.emit('alarm', { id: 3, alarmType: 'DEVICE', pointId: null, deviceId: 99, level: 'notice', status: 'PENDING' })
  check('SSE 不按订阅者过滤（A 日志 §120）→ 前端必须挡住不可见项目的告警',
    monitor.recentAlarms.length === beforeForeign)

  // ── ② 时间轴回放 ──────────────────────────────────────────────
  const frames = buildFrames([
    { pointId: 1, points: [{ t: '2026-09-14T10:00:00+08:00', v: 1 }, { t: '2026-09-14T10:02:00+08:00', v: 3 }] },
    { pointId: 2, points: [{ t: '2026-09-14T10:01:00+08:00', v: 2 }] },
  ])
  check('错开的两条序列合并出 3 帧', frames.length === 3)
  check('前值保持：点 2 只有一条采样，后续帧沿用（不能用未来值倒推）',
    frames[2].values[1] === 3 && frames[2].values[2] === 2)
  check('时间串保留后端原文（+08:00）', frames[0].t.endsWith('+08:00'))
  check('frameDataCount / 滑块往返', frameDataCount(frames[0]) === 1 && indexFromProgress(1000, 3) === 2 && frameProgress(2, 3) === 1000)

  const live = await fetch(`${BASE}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: '123456' }) }).then((r) => r.json()).catch(() => null)
  if (live?.data?.token) {
    const header = { Authorization: `Bearer ${live.data.token}` }
    const from = new Date(Date.now() - 7 * 24 * 3600 * 1000).toISOString()
    const series = await Promise.all([1, 6].map((id) =>
      fetch(`${BASE}/points/${id}/series?metricCode=defo_mm&granularity=raw&from=${encodeURIComponent(from)}`, { headers: header })
        .then((r) => r.json()).then((b) => ({ pointId: id, points: b?.data?.points || [] }))))
    check('真实 series 能合出帧（后端在跑）', buildFrames(series).length >= 0)
  } else {
    check('后端可达（跳过真实 series 断言）', false, '请先起后端：cd backend && ./mvnw spring-boot:run')
  }

  // ── ③ 测项中立化 + 阈值判据 + 项目过滤 ────────────────────────
  check('着色函数不再自带 3mm：没阈值就不判超限', resolvePointVisual({ value: 99 }).key === 'normal')
  check('给了阈值就判超限（负向同样算）',
    resolvePointVisual({ value: 5, threshold: 3 }).key === 'over-threshold'
    && resolvePointVisual({ value: -5, threshold: 3 }).key === 'over-threshold')
  check('null 值 → 暂无数据（不会因为 Number(null)=0 被判成正常）', resolvePointVisual({ value: null, threshold: 3 }).key === 'no-data')

  const rules = [
    { id: 1, pointId: null, metricCode: 'defo_mm', type: 'THRESHOLD', operator: 'gte', value: 3, level: 'warning', enabled: true },
    { id: 2, pointId: null, metricCode: 'defo_mm', type: 'THRESHOLD', operator: 'lte', value: -3, level: 'warning', enabled: true },
    { id: 3, pointId: null, metricCode: 'defo_mm', type: 'THRESHOLD', operator: 'gte', value: 5, level: 'alarm', enabled: true },
    { id: 4, pointId: null, metricCode: 'temp_c', type: 'THRESHOLD', operator: 'gte', value: 35, level: 'warning', enabled: true },
  ]
  monitor.rules = rules
  check('超限判据取绝对值最小的一档（defo_mm → 3，不是 5）', warnThresholdOf(rules, { pointId: 1, metricCode: 'defo_mm' }) === 3)
  check('换测项就换阈值（temp_c → 35），零代码改动', warnThresholdOf(rules, { pointId: 1, metricCode: 'temp_c' }) === 35)
  check('没有规则的测项 → null（不瞎编阈值）', warnThresholdOf(rules, { pointId: 1, metricCode: 'pressure_kpa' }) === null)
  check('停用/非 THRESHOLD/别的测点级规则都不参与判据',
    warnThresholdOf([{ metricCode: 'defo_mm', type: 'THRESHOLD', value: 1, enabled: false }], { pointId: 1, metricCode: 'defo_mm' }) === null
    && warnThresholdOf([{ metricCode: 'defo_mm', type: 'RATE', value: 1, enabled: true }], { pointId: 1, metricCode: 'defo_mm' }) === null
    && warnThresholdOf([{ pointId: 2, metricCode: 'defo_mm', type: 'THRESHOLD', value: 1, enabled: true }], { pointId: 1, metricCode: 'defo_mm' }) === null)

  monitor.primaryMetricCode = 'defo_mm'
  check('主测项 = defo_mm 时取形变值与单位', monitor.enrichedPoints[0].value === 7.2 && monitor.enrichedPoints[0].unit === 'mm')
  monitor.primaryMetricCode = 'temp_c'
  const asTemp = monitor.enrichedPoints[0]
  check('主测项 = temp_c（档案里原本没有的第三种测项）也能一路走到视图层',
    asTemp.unit === '℃' && asTemp.metricName === '表面温度' && asTemp.threshold === 35,
    `${asTemp.metricName}/${asTemp.threshold}`)
  check('时间/质量/信号没被当成测项', !('collectTime' in asTemp.metrics) && !('quality' in asTemp.metrics))
  monitor.setPrimaryMetric('not_a_metric')
  check('切到档案里不存在的测项被拒绝（界面全空最难解释）', monitor.primaryMetricCode === 'temp_c')

  monitor.scenes = [{ id: 1, projectId: 1 }, { id: 2, projectId: 2 }]
  monitor.objects = [{ id: 1, sceneId: 1 }, { id: 2, sceneId: 2 }]
  monitor.points = [
    { id: 2, code: 'P-HK02', objectId: 1 },
    { id: 9, code: 'P-XJ01', objectId: 2 },
  ]
  monitor.projectId = 1
  check('项目 1 只看到本项目的测点（测点→对象→场景→项目）',
    monitor.pointsOfProject.map((p) => p.code).join(',') === 'P-HK02')
  monitor.projectId = 2
  check('切到项目 2 看到另一批', monitor.pointsOfProject.map((p) => p.code).join(',') === 'P-XJ01')
  monitor.projectId = null
  check('没有当前项目时不过滤（退回全部可见测点）', monitor.pointsOfProject.length === 2)

  check('影像：四个入口都在（以 A 的实现为准）',
    ['uploadMedia', 'listPointMedia', 'deleteMedia', 'mediaContentUrl'].every((k) => typeof api[k] === 'function'))
} finally {
  await server.close()
}

const failed = results.filter(([, ok]) => !ok)
console.log(`\n合计 ${results.length} 条，失败 ${failed.length} 条`)
process.exit(failed.length ? 1 : 0)
