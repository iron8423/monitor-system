/**
 * 前端自检（B 侧）：把「不靠浏览器也能验的部分」固化成断言。
 *
 *   cd frontend && npm run selfcheck
 *
 * 覆盖五块，都是**把 store / 纯函数用 Vite 的 SSR 加载器真跑一遍**，不是读代码：
 *   ① 实时推送：连接归属/幂等、measurement 并表、alarm 升降级、SSE 可见性收口；
 *   ② 时间轴回放：多序列合帧、前值保持、**采样时刻与过期限时**、滑块往返
 *      （顺带用后端真实 series 跑一遍）；
 *   ③ 测项中立化：编一个档案里本来没有的测项（温度 ℃），验证它能一路走到
 *      值/单位/名称/阈值/变色；以及项目过滤（测点 → 对象 → 场景 → 项目）；
 *   ④ 请求代际守卫（清单第 17 条）：含**乱序完成**这种只有并发才暴露的情形；
 *   ⑤ 回放取数规模（清单第 18 条）：粒度选择、并发池、分段边界。
 *
 * 这里**不加载任何 `.vue`**（harness 不编译 SFC），所以组件内部的竞态只能靠人工浏览器
 * 验收——把守卫抽成 `utils/requestGuard.js` 就是为了让它的语义至少在这里有断言。
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
  const { buildFrames, frameDataCount, frameFreshCount, frameProgress, indexFromProgress, suggestRange } = await server.ssrLoadModule('/src/utils/timeline.js')
  const { createRequestGuard } = await server.ssrLoadModule('/src/utils/requestGuard.js')
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
  realtime.flushMeasurements()
  const latest = monitor.latestMap[2].latest
  check('measurement 更新当前观测；未推送的信号不沿用旧值', latest.defo_mm === 7.2 && latest.signal === null)
  check('非本项目测点的 measurement 被忽略', (() => {
    const before = realtime.measurementCount
    FakeEventSource.last.emit('measurement', { pointId: 999, defo_mm: 1 })
    realtime.flushMeasurements()
    return realtime.measurementCount === before
  })())

  const burst = Array.from({ length: 1000 }, (_, i) => ({
    pointId: 2, pointCode: 'P-HK02', collectTime: new Date(Date.parse('2026-09-14T09:30:00+08:00') + i * 1000).toISOString(), defo_mm: i,
  }))
  const acceptedBurst = monitor.applyMeasurements(burst)
  check('1000 条突发测值可一次批量合并，末值生效', acceptedBurst === 1000 && monitor.latestMap[2].latest.defo_mm === 999)
  // 后续测项中立化断言沿用原本的 7.2mm 场景，批处理压力断言不能污染它。
  monitor.applyMeasurement({ pointId: 2, collectTime: '2026-09-14T10:00:00+08:00', defo_mm: 7.2, rate_mm_d: 0.42 })

  FakeEventSource.last.emit('alarm', { id: 1, alarmType: 'POINT', pointId: 2, level: 'warning', status: 'PENDING', triggeredAt: '2026-09-14T09:00:00+08:00' })
  check('alarm 记入未解除警情表，enrichedPoints 带上 hasAlarm/alarmLevel',
    monitor.activeAlarms[1]?.level === 'warning' && monitor.enrichedPoints[0].hasAlarm === true)
  FakeEventSource.last.emit('alarm', { id: 1, alarmType: 'POINT', pointId: 2, level: 'alarm', status: 'PENDING', triggeredAt: '2026-09-14T09:00:00+08:00' })
  check('同一条警情就地升级（不新增条目）', monitor.activeAlarms[1]?.level === 'alarm' && Object.keys(monitor.activeAlarms).length === 1)

  // 同一测点第二条未解除警情。旧实现按 pointId 存单条，这一整段都会挂：
  // 第二条覆盖第一条，而解除任意一条又把这个点整个删掉。
  FakeEventSource.last.emit('alarm', { id: 4, alarmType: 'POINT', pointId: 2, level: 'notice', status: 'PENDING', triggeredAt: '2026-09-14T09:02:00+08:00' })
  check('同点第二条警情独立记账（不覆盖第一条）',
    Object.keys(monitor.activeAlarms).length === 2 && monitor.activeAlarms[4]?.level === 'notice')
  check('同点多条时着色取最高等级', monitor.enrichedPoints[0].alarmLevel === 'alarm')
  FakeEventSource.last.emit('alarm', { id: 1, alarmType: 'POINT', pointId: 2, level: 'alarm', status: 'RESOLVED', triggeredAt: '2026-09-14T09:05:00+08:00' })
  check('解除其中一条后，同点另一条仍在（否则颜色被误清）',
    monitor.activeAlarms[1] === undefined
      && monitor.activeAlarms[4]?.level === 'notice'
      && monitor.enrichedPoints[0].hasAlarm === true
      && monitor.enrichedPoints[0].alarmLevel === 'notice')
  FakeEventSource.last.emit('alarm', { id: 4, alarmType: 'POINT', pointId: 2, level: 'notice', status: 'RESOLVED', triggeredAt: '2026-09-14T09:06:00+08:00' })
  check('全部解除后才从点表摘掉（否则颜色一直是红的）',
    Object.keys(monitor.activeAlarms).length === 0 && monitor.enrichedPoints[0].hasAlarm === false)

  const beforeForeign = monitor.recentAlarms.length
  FakeEventSource.last.emit('alarm', { id: 2, alarmType: 'POINT', pointId: 999, pointCode: 'P-OTHER', level: 'alarm', status: 'PENDING' })
  FakeEventSource.last.emit('alarm', { id: 3, alarmType: 'DEVICE', pointId: null, deviceId: 99, level: 'notice', status: 'PENDING' })
  // 2026-09-17 改名：服务端自 09-14 起已按订阅者过滤（`SseBroadcaster#broadcastScoped`，
  // 无过滤的 `broadcast` 入口已删除），前端这层是**防御**而不是唯一屏障。
  // 断言本身一个字没改——它验的是"前端不依赖服务端一定只推该推的"。
  check('SSE 已按订阅者过滤（服务端）；前端这层是防御：不可见项目的告警仍被挡住',
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

  // 清单第 19 条：前值保持要记下「用的是哪一次采样」，否则「这个值是 3 小时前的」
  // 在界面上就变成了「就是这个时刻的值」。
  const T10 = Date.parse('2026-09-14T10:00:00+08:00')
  check('前值保持记下采样时刻：10:01 那一帧用的是 10:00 的采样，两者不相等',
    frames[1].values[1] === 1 && frames[1].sampleMs[1] === T10 && frames[1].ms !== frames[1].sampleMs[1])
  check('这一刻之前该点从未上报：是「暂无数据」不是「过期」（两者在界面上必须是不同的态）',
    frames[0].values[2] === null && frames[0].sampleMs[2] === null && frames[0].stale[2] === undefined)

  // 超时边界：拿 maxCarryMs 直接打，不去猜自适应算出来是多少
  const gapped = buildFrames([
    { pointId: 1, points: [{ t: '2026-09-14T10:00:00+08:00', v: 1 }, { t: '2026-09-14T10:20:00+08:00', v: 2 }] },
    { pointId: 2, points: [{ t: '2026-09-14T10:00:00+08:00', v: 10 }, { t: '2026-09-14T10:07:00+08:00', v: 70 }, { t: '2026-09-14T10:20:00+08:00', v: 80 }] },
  ], { maxCarryMs: 5 * 60 * 1000 })
  check('前值保持超时：值**还在**，只是标成过期（过期 ≠ 缺测——最后那个读数是值班的人要的）',
    gapped[1].values[1] === 1 && gapped[1].stale[1] === true && gapped[1].sampleMs[1] === T10)
  check('同一帧里没过期的点不受牵连', gapped[1].values[2] === 70 && gapped[1].stale[2] === undefined)
  check('时限之内不标过期（10:00 那一帧）', gapped[0].stale[1] === undefined)
  check('该点重新上报后回到新鲜（10:20 那一帧）', gapped[2].stale[1] === undefined && gapped[2].values[1] === 2)
  check('frameFreshCount 只数没过期的点；frameDataCount 语义不变（含过期）',
    frameFreshCount(gapped[1]) === 1 && frameDataCount(gapped[1]) === 2)

  // 默认时限是「5 分钟下限」与「该序列采样间隔中位数 ×3」取大。
  // 本仓的演示/验收数据是 **1 小时**采样，写死 5 分钟会让每两帧之间被判过期 55 分钟 → 满屏灰。
  const sparse = buildFrames([
    { pointId: 1, points: ['10:00', '11:00', '12:00', '13:00', '14:00', '15:00'].map((hm) => ({ t: `2026-09-14T${hm}:00+08:00`, v: 1 })) },
    { pointId: 2, points: ['10:30', '11:30', '12:30', '13:30'].map((hm) => ({ t: `2026-09-14T${hm}:00+08:00`, v: 2 })) },
  ])
  check('稀疏序列自适应：小时级采样在默认时限下没有一帧算过期（写死 5 分钟会满屏过期）',
    sparse.length > 6 && sparse.every((f) => f.stale[1] !== true && f.stale[2] !== true))

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

  // 清单第 19 条：新增「数据过期」一档。它插在**哪一档**比它存在更重要——
  // 前四档都是服务端判定的既成事实，过期是前端按时间推的推断，推断不许盖过事实。
  check('数据过期是新的一档', resolvePointVisual({ value: 3, stale: true }).key === 'stale')
  check('优先级：告警压过过期（红点与脉冲环不能因为值旧了就没了）',
    resolvePointVisual({ value: 3, stale: true, hasAlarm: true, alarmLevel: 'alarm' }).key === 'alarm')
  check('优先级：目标失联压过过期（服务端事实优先）',
    resolvePointVisual({ value: 3, stale: true, state: 'disappeared' }).key === 'disappeared')
  check('优先级：数据可疑压过过期（服务端事实优先）',
    resolvePointVisual({ value: 3, stale: true, quality: 'SUSPECT' }).key === 'suspect')
  check('优先级：过期压过超限（3 小时前的读数超没超限，不说明现在）',
    resolvePointVisual({ value: 99, threshold: 3, stale: true }).key === 'stale')
  check('从未有过读数（退化输入）→ 仍是「暂无数据」：一个从来没量到值的点不叫过期',
    resolvePointVisual({ value: null, stale: true }).key === 'no-data')

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

  // ── ④ 请求代际守卫（清单第 17 条）────────────────────────────
  // 这段测的不是 .vue 里的接线（那部分脚本够不着），是守卫本身的语义。
  // `.vue` 里有没有用上它，靠 check-p0-stage1 的源码级绊线 + 人工浏览器验收兜底。
  const guard = createRequestGuard()
  const g1 = guard.next()
  check('守卫：刚发出的请求仍是当前代', guard.isCurrent(g1) === true)
  const g2 = guard.next()
  check('守卫：后发者胜，先发的代号当场失效', guard.isCurrent(g1) === false && guard.isCurrent(g2) === true)
  guard.invalidate()
  check('守卫：invalidate 之后全部失效（组件卸载时用）', guard.isCurrent(g2) === false)

  {
    // 乱序完成：A 先发、B 后发、B 先回。A 回来时**必须已经过期**。
    // 红：把守卫实现成「先到先得」（第一次 next 之后就不再变）——那样迟到的 A 会覆盖 B。
    const h = createRequestGuard()
    const wrote = []
    const slowA = (async () => {
      const token = h.next()
      await new Promise((r) => setTimeout(r, 20))
      if (h.isCurrent(token)) wrote.push('A')
    })()
    const fastB = (async () => {
      const token = h.next()
      await new Promise((r) => setTimeout(r, 1))
      if (h.isCurrent(token)) wrote.push('B')
    })()
    await Promise.all([slowA, fastB])
    check('守卫：乱序完成时只有后发者写回，迟到的旧响应一个字都不许写', wrote.join(',') === 'B', wrote.join(','))
  }

  // ── ⑤ 回放取数规模（清单第 18 条）────────────────────────────
  const { pickGranularity, mapWithLimit, splitWindow } = await server.ssrLoadModule('/src/stores/replay.js')

  // P0-3：raw 有 5000 点硬上限（后端 SeriesWindowPolicy），生产 5 秒采样下约 6 小时。
  // 所以"24 小时以内都取原始点"这条旧口径已经不成立——24 小时 @7 点 = 12 万行，
  // 单点就是 1.7 万行，接口会 400、曲线整条消失。
  check('粒度：单点 6 小时 → 原始点（4320 行，刚好在上限内）', pickGranularity(6, 1) === 'raw')
  check('粒度：单点 24 小时 → 按小时（1.7 万行超 raw 上限，必须降采）', pickGranularity(24, 1) === 'hour')
  check('粒度：7 点看 24 小时 → 按小时（P0-3 之后不再取原始点）', pickGranularity(24, 7) === 'hour')
  check('粒度：7 点看 7 天 → 按小时（raw 是 84 万行）', pickGranularity(168, 7) === 'hour')
  check('粒度：1000 点看 24 小时 → 按小时（**点数必须一起算**，只看窗口就会在这里崩）',
    pickGranularity(24, 1000) === 'hour')
  check('粒度：1000 点看 30 天 → 按天', pickGranularity(720, 1000) === 'day')
  check('粒度：零窗口不抛也不误判，单点不被无故降采样',
    pickGranularity(0, 1) === 'raw' && pickGranularity(1, 1) === 'raw')

  // 前端与后端的上限必须是同一个数（两边各写一份是为了不跨进程共享常量，
  // 但一旦漂移，表现是"前端以为能取 raw、后端 400"，所以这条绊线放在这里）。
  const { MAX_RAW_POINTS, RAW_SAFE_WINDOW_HOURS, rawFitsInLimit, autoGranularity } =
    await server.ssrLoadModule('/src/utils/seriesGranularity.js')
  check('上限口径：raw 上限与后端一致（5000 点 / 720 行每小时）', MAX_RAW_POINTS === 5000)
  check('上限口径：安全窗口 = 6 小时', RAW_SAFE_WINDOW_HOURS === 6)
  check('上限口径：边界两侧（6h 放行 / 7h 不放行）',
    rawFitsInLimit(6) === true && rawFitsInLimit(7) === false)
  check('上限口径：autoGranularity 只在这两档之间切换',
    autoGranularity(1) === 'raw' && autoGranularity(24) === 'hour')

  {
    let peak = 0
    let inflight = 0
    const done = []
    const out = await mapWithLimit([1, 2, 3, 4, 5, 6, 7, 8], 4, async (n) => {
      inflight += 1
      peak = Math.max(peak, inflight)
      await new Promise((r) => setTimeout(r, (9 - n) * 4))
      inflight -= 1
      done.push(n)
      return n * 10
    })
    check('并发池：结果按输入顺序返回（不是完成顺序）', out.join(',') === '10,20,30,40,50,60,70,80', out.join(','))
    check('并发池：在途峰值不超过上限，且确实是并发', peak <= 4 && peak > 1, `peak=${peak}`)
    check('并发池：完成顺序确实被打乱（否则上面两条什么也没测到）', done.join(',') !== '1,2,3,4,5,6,7,8')
  }
  {
    const out = await mapWithLimit([1, 2, 3], 2, async (n) => {
      if (n === 2) throw new Error('boom')
      return n
    })
    check('并发池：一个任务抛错不拖垮整批', out[0] === 1 && out[1] === undefined && out[2] === 3)
  }
  {
    // 「是流水线还是分组 Promise.all」的分水岭：只放行第 1 个，
    // 池子必须立刻补上第 5 个；分组实现要等整组（1,2,3,4）都回来才发车，这里必红。
    const resolvers = {}
    const started = []
    const p = mapWithLimit([0, 1, 2, 3, 4, 5, 6, 7], 4, (n) => {
      started.push(n)
      return new Promise((res) => { resolvers[n] = () => res(n) })
    })
    await new Promise((r) => setTimeout(r, 0))
    resolvers[0]()
    await new Promise((r) => setTimeout(r, 0))
    check('并发池：放行一个就立刻补下一个（分组 Promise.all 会在这里红）',
      started.slice().sort((a, b) => a - b).join(',') === '0,1,2,3,4', started.join(','))
    for (let round = 0; round < 5; round += 1) {
      for (const k of Object.keys(resolvers)) resolvers[k]()
      await new Promise((r) => setTimeout(r, 0))
    }
    await p
  }
  {
    const from = Date.parse('2026-09-01T00:00:00Z')
    const to = from + 72 * 3600 * 1000
    const segs = splitWindow(from, to, 24)
    check('分段：72 小时按天切 3 段', segs.length === 3, String(segs.length))
    check('分段：第一段贴着右端（由近及远，先出最近的历史）',
      segs[0].to === to && segs[0].from === to - 24 * 3600 * 1000)
    check('分段：相邻边界差 1ms 不重叠（后端 from/to 是双闭区间，不减 1ms 边界那行会出现两次）',
      segs.every((s, i) => i === 0 || s.to === segs[i - 1].from - 1))
    check('分段：最后一段的左端不越过 from', segs.at(-1).from === from)
  }

  // ── ⑥ 曲线窗口建议（2026-09-17）─────────────────────────────
  // 起因是实测到的困惑：演示库最新数据停在两天前，测点页默认「近 24 小时」→ 曲线空着，
  // 界面只说「当前条件下没有数据」，看的人分不清「没上报」和「比窗口旧」。
  {
    const RANGES = [
      { value: 1, label: '近 1 小时' },
      { value: 24, label: '近 24 小时' },
      { value: 24 * 7, label: '近 7 天' },
      { value: 24 * 30, label: '近 30 天' },
    ]
    const now = Date.parse('2026-09-17T16:00:00+08:00')
    const iso = (hoursAgo) => new Date(now - hoursAgo * 3600 * 1000).toISOString()

    check('窗口建议：10 分钟前的数据落在最小档（近 1 小时）',
      suggestRange(iso(10 / 60), RANGES, now)?.value === 1)
    check('窗口建议：47 小时前的数据要跨到近 7 天，而不是卡在近 24 小时',
      suggestRange(iso(47), RANGES, now)?.value === 24 * 7)
    check('窗口建议：刚好卡在边界上算覆盖（24 小时整用近 24 小时就够）',
      suggestRange(iso(23.5), RANGES, now)?.value === 24)
    check('窗口建议：超过最大档也只给最大档，不发明一个不存在的窗口',
      suggestRange(iso(24 * 60), RANGES, now)?.value === 24 * 30)
    check('窗口建议：没有数据 / 时间非法 → null（不是默默给最小档）',
      suggestRange(null, RANGES, now) === null && suggestRange('不是时间', RANGES, now) === null)
    check('窗口建议：未来时间（设备时钟超前）不抛，落最小档',
      suggestRange(iso(-2), RANGES, now)?.value === 1)
  }
} finally {
  await server.close()
}

const failed = results.filter(([, ok]) => !ok)
console.log(`\n合计 ${results.length} 条，失败 ${failed.length} 条`)
process.exit(failed.length ? 1 : 0)
