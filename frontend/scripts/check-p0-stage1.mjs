/**
 * P0 第 1 批回归：真实 Pinia store + 真实 Axios 拦截器 + 可控 HTTP 返回顺序。
 * 运行：cd frontend && node scripts/check-p0-stage1.mjs
 * 不连接数据库、不写测量数据、不需要启动后端。
 * 路由使用内存 history 和对话框替身；不冒充浏览器/WebGL 验收。
 *
 * 后来又接了三类（清单第 14/16/17/18/19 条）：
 *   - 回放取数：粒度、分段、并发池、代际守卫、去重（真跑 `stores/replay.js`）；
 *   - **源码级接线绊线**：`.vue` 编译不了（harness 不加载 SFC），所以「守卫接上了没」
 *     「字段搬对了没」只能用源码断言。**它只证明「用了」，不证明「对」**——
 *     组件内部的竞态一律以人工浏览器验收为准。
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'

const root = fileURLToPath(new URL('../', import.meta.url))
/** 读源文件用：`.vue` 加载不了，接线只能这么验 */
const src = (p) => readFileSync(fileURLToPath(new URL(`../src/${p}`, import.meta.url)), 'utf8')
const memory = new Map()
globalThis.localStorage = {
  getItem: (key) => memory.get(key) ?? null,
  setItem: (key, value) => memory.set(key, String(value)),
  removeItem: (key) => memory.delete(key),
}

const server = await createServer({
  root, server: { middlewareMode: true }, appType: 'custom', logLevel: 'silent',
  ssr: { noExternal: ['element-plus'] },
  plugins: [{
    name: 'p0-test-browser-boundaries', enforce: 'pre',
    resolveId(id) { if (id === 'element-plus') return '\0p0-dialog' },
    load(id) {
      if (id === '\0p0-dialog') return `
        export const ElMessage = { error() {} };
        export const ElMessageBox = { confirm(...args) { return globalThis.__p0Confirm(...args) } };
      `
    },
    transform(code, id) {
      if (id.replaceAll('\\', '/').endsWith('/src/router/index.js')) {
        return code.replaceAll('createWebHistory', 'createMemoryHistory')
      }
    },
  }],
})

let passed = 0
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const time = (seconds) => new Date(Date.parse('2026-09-16T00:00:00Z') + seconds * 1000).toISOString()
const row = (seconds, value, id = 1, extra = {}) => ({
  pointId: id, pointCode: `P-${id}`, state: 'normal',
  latest: { collectTime: time(seconds), defo_mm: value, rate_mm_d: value / 10, quality: 'VALID', signal: 0.9, ...extra },
})
const event = (seconds, value, id = 1) => ({ pointId: id, pointCode: `P-${id}`, collectTime: time(seconds), defo_mm: value, quality: 'VALID' })
const flush = () => new Promise((resolve) => setImmediate(resolve))

try {
  const { useMonitorStore } = await server.ssrLoadModule('/src/stores/monitor.js')
  const { default: http } = await server.ssrLoadModule('/src/api/http.js')
  let responder
  let calls = []
  let callConfigs = []
  http.defaults.adapter = async (config) => {
    calls.push(config.url)
    callConfigs.push(config)
    return { data: { code: 0, data: await responder(config) }, status: 200, statusText: 'OK', headers: {}, config }
  }
  function fresh() {
    memory.set('monitor_token', 'test-session-a')
    setActivePinia(createPinia())
    const store = useMonitorStore()
    store.projects = [{ id: 1 }, { id: 2 }]
    store.projectId = 1
    store.scenes = [{ id: 1, projectId: 1 }, { id: 2, projectId: 2 }]
    store.objects = [{ id: 1, sceneId: 1 }, { id: 2, sceneId: 2 }]
    store.points = [{ id: 1, code: 'P-1', objectId: 1 }, { id: 2, code: 'P-2', objectId: 2 }]
    store.latestMap = { 1: row(10, 10) }
    calls = []
    callConfigs = []
    responder = () => { throw new Error('未配置的测试请求') }
    return store
  }
  async function test(name, fn) {
    await fn(fresh())
    passed += 1
    console.log(`PASS ${String(passed).padStart(2, '0')} ${name}`)
  }
  /** 源码级绊线用的轻量壳：不需要 store，只数一项 */
  function ok(name, fn) {
    fn()
    passed += 1
    console.log(`PASS ${String(passed).padStart(2, '0')} ${name}`)
  }
  /** 取一个顶层函数的源码片段（绊线要按函数分别数，不能全文件数） */
  function fnBody(text, name) {
    const start = text.indexOf(`function ${name}(`)
    assert.ok(start >= 0, `找不到函数 ${name}`)
    const end = text.indexOf('\n}', start)
    assert.ok(end > start, `找不到 ${name} 的结尾`)
    return text.slice(start, end)
  }

  await test('晚到 SSE 不覆盖新值', (s) => {
    assert.equal(s.applyMeasurement(event(20, 20)), true)
    assert.equal(s.applyMeasurement(event(15, 999)), false)
    assert.equal(s.latestMap[1].latest.defo_mm, 20)
  })
  await test('同一批无序消息取最大采集时间', (s) => {
    assert.equal(s.applyMeasurements([event(30, 30), event(20, 20), event(40, 40), event(35, 35)]), 2)
    assert.equal(s.latestMap[1].latest.defo_mm, 40)
  })
  await test('同时间冲突 SSE 不按到达顺序覆盖', (s) => {
    assert.equal(s.applyMeasurement(event(10, 999)), false)
    assert.equal(s.latestMap[1].latest.defo_mm, 10)
  })
  await test('同一时刻不同时区表示不会误判为更新', (s) => {
    assert.equal(s.applyMeasurement({ ...event(10, 99), collectTime: '2026-09-16T08:00:10+08:00' }), false)
  })
  await test('缺失和非法采集时间均忽略', (s) => {
    for (const collectTime of [undefined, null, '', 'invalid', 123]) {
      assert.equal(s.applyMeasurement({ ...event(20, 99), collectTime }), false)
    }
  })
  await test('首屏快照未返回也能接收已建档测点', (s) => {
    s.latestMap = {}
    assert.equal(s.applyMeasurement(event(20, 20)), true)
    assert.equal(s.latestMap[1].latest.defo_mm, 20)
  })
  await test('未知测点和其他项目事件不会进入画面', (s) => {
    s.latestMap[2] = row(10, 10, 2)
    assert.equal(s.applyMeasurement(event(20, 99, 2)), false)
    assert.equal(s.applyMeasurement(event(20, 99, 999)), false)
  })
  await test('新观测不混用旧速率、信号和状态', (s) => {
    s.latestMap[1].state = 'disappeared'
    s.applyMeasurement(event(20, 20))
    assert.equal(s.latestMap[1].latest.rate_mm_d, undefined)
    assert.equal(s.latestMap[1].latest.signal, null)
    assert.equal(s.latestMap[1].state, null)
  })
  await test('HTTP 请求期间到达的新 SSE 不被旧响应覆盖', async (s) => {
    const pending = deferred()
    responder = () => pending.promise
    const refresh = s.refreshLatest()
    s.applyMeasurement(event(30, 30))
    pending.resolve([row(20, 20)])
    await refresh
    assert.equal(s.latestMap[1].latest.defo_mm, 30)
  })
  await test('在途同时间冲突快照不得覆盖刚到的 SSE', async (s) => {
    const pending = deferred()
    responder = () => pending.promise
    const refresh = s.refreshLatest()
    s.applyMeasurement(event(20, 20))
    pending.resolve([row(20, 999)])
    await refresh
    assert.equal(s.latestMap[1].latest.defo_mm, 20)
  })
  await test('后续稳定快照对账同时间值并补齐附加字段', async (s) => {
    s.applyMeasurement(event(20, 20))
    responder = () => [row(20, 21)]
    await s.refreshLatest()
    assert.equal(s.latestMap[1].latest.defo_mm, 21)
    assert.equal(s.latestMap[1].latest.signal, 0.9)
    assert.equal(s.latestMap[1].state, 'normal')
  })
  await test('空快照或坏时间不能擦掉已有测值', async (s) => {
    for (const latest of [null, { collectTime: 'broken', defo_mm: 999 }]) {
      responder = () => [{ pointId: 1, latest }]
      await s.refreshLatest()
      assert.equal(s.latestMap[1].latest.defo_mm, 10)
    }
  })
  await test('更晚启动的刷新完成后，先前响应无权覆盖', async (s) => {
    const a = deferred(), b = deferred()
    responder = () => calls.length === 1 ? a.promise : b.promise
    const first = s.refreshLatest()
    await flush()
    const second = s.refreshLatest()
    await flush()
    b.resolve([row(30, 30)])
    await second
    a.resolve([row(40, 999)])
    await first
    assert.equal(s.latestMap[1].latest.defo_mm, 30)
  })
  await test('切换项目后旧项目响应不写回', async (s) => {
    const pending = deferred()
    responder = () => pending.promise
    const refresh = s.refreshLatest()
    s.projectId = 2
    s.latestMap = { 2: row(30, 30, 2) }
    pending.resolve([row(40, 999)])
    await refresh
    assert.deepEqual(Object.keys(s.latestMap), ['2'])
  })
  await test('退出或更换登录身份后旧响应不写回', async (s) => {
    const pending = deferred()
    responder = () => pending.promise
    const refresh = s.refreshLatest()
    memory.set('monitor_token', 'test-session-b')
    pending.resolve([row(40, 999)])
    await refresh
    assert.equal(s.latestMap[1].latest.defo_mm, 10)
  })
  await test('批量结果夹带其他项目测点时忽略', async (s) => {
    responder = () => [row(20, 20), row(20, 99, 2)]
    await s.refreshLatest()
    assert.deepEqual(Object.keys(s.latestMap), ['1'])
  })
  await test('网络和 500 错误保留旧值且不扩大为逐点请求', async (s) => {
    const loadedAt = s.loadedAt
    responder = () => { throw Object.assign(new Error('模拟服务故障'), { response: { status: 500 } }) }
    await s.refreshLatest()
    assert.equal(calls.length, 1)
    assert.equal(s.latestMap[1].latest.defo_mm, 10)
    assert.equal(s.loadedAt, loadedAt)
    assert.ok(s.error)
  })
  await test('只有批量接口不存在时才兼容旧单点接口', async (s) => {
    responder = (config) => {
      if (config.url.includes('/projects/')) throw Object.assign(new Error('旧接口'), { response: { status: 404 } })
      return row(20, 20)
    }
    await s.refreshLatest()
    assert.equal(calls.length, 2, `requests=${JSON.stringify(calls)}; error=${s.error}`)
    assert.equal(s.latestMap[1].latest.defo_mm, 20)
    assert.equal(s.error, null)
  })
  await test('响应缺少测点时明确提示，不伪报全量刷新成功', async (s) => {
    responder = () => []
    await s.refreshLatest()
    assert.ok(s.error?.includes('部分测点'))
    assert.equal(s.latestMap[1].latest.defo_mm, 10)
  })
  await test('旧项目档案快照不覆盖新项目上下文', async (s) => {
    const pending = deferred()
    responder = () => pending.promise
    const snapshot = s.loadSnapshot()
    s.projectId = 2
    pending.resolve([])
    await snapshot
    assert.equal(s.projectId, 2)
    assert.equal(s.points.length, 2)
  })
  await test('1000 条乱序批次保留采集时间最大的一条', (s) => {
    const events = Array.from({ length: 1000 }, (_, i) => event(1010 - i, i))
    s.applyMeasurements(events)
    assert.equal(s.latestMap[1].latest.collectTime, time(1010))
    assert.equal(s.latestMap[1].latest.defo_mm, 0)
  })

  // ── 回放取数（清单第 18/19 条）─────────────────────────────────
  const { useReplayStore } = await server.ssrLoadModule('/src/stores/replay.js')
  const { frameFreshCount } = await server.ssrLoadModule('/src/utils/timeline.js')

  await test('回放只拉本项目测点，且每个 series 都带 to（原来拉全部可见点、且窗口右端无界）', async (s) => {
    const replay = useReplayStore()
    replay.rangeHours = 24 // 单段路径：先只看「拉谁、带什么参数」
    responder = () => ({ points: [] })
    await replay.load()
    // 项目 1 只有点 1；点 2 属于项目 2，画面上根本不出现，就不该为它发请求
    assert.deepEqual(calls, ['/v1/points/1/series'], JSON.stringify(calls))
    assert.ok(callConfigs[0].params.to, 'series 必须带 to')
    assert.ok(callConfigs[0].params.from, 'series 必须带 from')
    // P0-3 之后 24 小时窗口不再是 raw：后端对 raw 有 5000 点上限（5 秒采样 ≈ 6 小时），
    // 24 小时 @raw 会被 400 掉、曲线整条消失。这条断言改成钉"降采到 hour"这个结论。
    assert.equal(callConfigs[0].params.granularity, 'hour')
  })

  // P0-3 之后这条用例的口径变了：后端给 raw 加了 5000 点硬上限，7 天窗口即使只有
  // 一个测点也装不下（168h × 720 行 = 12 万行），于是粒度落到 hour、**一次取回**，
  // 逐段拼接那条路径不再被走到（分段函数本身仍在 selfcheck 里单测）。
  // 这条断言钉的正是"不再分段"这个结论——否则下次有人把上限调大、
  // 分段路径重新活过来时，这里会静默变成另一种形状。
  await test('7 天窗口：raw 装不下 → 按小时一次取回（不再逐 24 小时分段）', async (s) => {
    const replay = useReplayStore()
    replay.rangeHours = 168
    responder = () => ({ points: [] })
    await replay.load()
    assert.equal(calls.length, 1, JSON.stringify(calls))
    assert.equal(callConfigs[0].params.granularity, 'hour')
    // 窗口右端仍贴到「现在」，否则时间轴右端会缺一块
    assert.ok(Date.now() - Date.parse(callConfigs[0].params.to) < 5000, '窗口没有贴到当前时刻')
    // 左端是 7 天前（不是被降采缩掉的更短窗口）
    const span = Date.parse(callConfigs[0].params.to) - Date.parse(callConfigs[0].params.from)
    assert.ok(Math.abs(span - 168 * 3600 * 1000) < 5000, `窗口应为 7 天，实得 ${span / 3600000}h`)
  })

  await test('切主测项后旧批次最后返回：不写帧、不改 metricCode、也不关掉新批次的 loading', async (s) => {
    const replay = useReplayStore()
    replay.rangeHours = 24
    const a = deferred()
    const b = deferred()
    responder = () => (calls.length === 1 ? a.promise : b.promise)
    const first = replay.load()
    await flush()
    assert.equal(replay.loading, true)
    s.primaryMetricCode = 'rate_mm_d'
    const second = replay.load()
    await flush()
    b.resolve({ points: [{ t: time(0), v: 7 }] }) // 新测项先回
    await second
    assert.equal(replay.metricCode, 'rate_mm_d')
    assert.equal(replay.loading, false)
    const framesAfterNew = replay.frames.length
    a.resolve({ points: [{ t: time(0), v: 999 }, { t: time(60), v: 888 }] }) // 旧测项后回
    await first
    assert.equal(replay.metricCode, 'rate_mm_d', '旧批次的测项码不许写回')
    assert.equal(replay.frames.length, framesAfterNew, '旧批次的帧不许写回')
    assert.equal(replay.values[1], 7, '旧批次的值不许写回')
    assert.equal(replay.loading, false, '旧批次的 finally 不许关掉新批次的 loading')
  })

  await test('同一份窗口连点两次刷新只发一轮请求', async (s) => {
    const replay = useReplayStore()
    replay.rangeHours = 24
    const pending = deferred()
    responder = () => pending.promise
    const p1 = replay.load()
    const p2 = replay.load()
    await flush()
    assert.equal(calls.length, 1, JSON.stringify(calls))
    pending.resolve({ points: [] })
    await Promise.all([p1, p2])
  })

  await test('小时级采样每帧都算新鲜，且采样时刻不晚于帧时刻（写死 5 分钟会满屏过期）', async (s) => {
    // 两个点错开半小时上报：于是每一帧都落在某个点的两次采样**之间**，
    // 前值保持真的被用上了——只有一个点时帧必然与采样重合，这条断言什么也测不到。
    s.objects = [{ id: 1, sceneId: 1 }]
    s.points = [{ id: 1, code: 'P-1', objectId: 1 }, { id: 2, code: 'P-2', objectId: 1 }]
    const replay = useReplayStore()
    replay.rangeHours = 24
    const hourlyA = Array.from({ length: 6 }, (_, i) => ({ t: time(i * 3600), v: i }))
    const hourlyB = Array.from({ length: 6 }, (_, i) => ({ t: time(i * 3600 + 1800), v: 100 + i }))
    responder = (config) => ({ points: config.url.includes('/points/1/') ? hourlyA : hourlyB })
    await replay.load()
    assert.equal(calls.length, 2, JSON.stringify(calls))
    assert.ok(replay.frames.length >= 11, `frames=${replay.frames.length}`)
    // 第一帧（t=0）只有点 1 上报过：那是「还没有数据」，不是「数据过期」。
    // 这两态在界面上必须是不同的样子（第 19 条的措辞就是「缺测或过期」）。
    assert.equal(frameFreshCount(replay.frames[0]), 1)
    assert.equal(replay.frames[0].values[2], null)
    assert.equal(replay.frames[0].stale[2], undefined, '尚未上报不该被标成过期')
    // 其余每一帧两个点都在时限内——写死 5 分钟的实现在这里满屏过期
    assert.ok(
      replay.frames.slice(1).every((f) => frameFreshCount(f) === 2),
      `有过期的帧：${JSON.stringify(replay.frames.map((f) => f.stale))}`,
    )
    assert.ok(replay.frames.every((f) => Object.keys(f.stale).length === 0), '小时级数据不该有任一帧过期')
    assert.equal(replay.currentFreshCount, 2)
    assert.equal(replay.currentStaleCount, 0)
    for (const f of replay.frames) {
      for (const [id, sampleMs] of Object.entries(f.sampleMs)) {
        if (sampleMs === null) continue
        assert.ok(sampleMs <= f.ms, `点 ${id} 用了未来的采样（${sampleMs} > ${f.ms}）`)
      }
    }
  })

  // ── 源码级接线绊线（只证明「接上了」，不证明「接对了」）──────────
  ok('绊线·第 17 条：PointsView 两个 loader 各有一处守卫，且写回/catch/finally 三处都判了', () => {
    const text = src('views/PointsView.vue')
    assert.ok(text.includes("from '@/utils/requestGuard'"), '没引入守卫')
    for (const name of ['loadAlarms', 'loadDetail']) {
      const body = fnBody(text, name)
      assert.equal(
        (body.match(/Guard\.isCurrent\(/g) || []).length,
        3,
        `${name} 的守卫判了 ${(body.match(/Guard\.isCurrent\(/g) || []).length} 处，应为 3（写回/catch/finally）`,
      )
    }
    assert.ok(text.includes('detailGuard.invalidate()') && text.includes('alarmsGuard.invalidate()'), '卸载时没作废')
    assert.ok(text.includes('const pointId = selectedId.value'), '请求参数没快照')
  })

  ok('绊线·第 17 条：HomeAnalyst 的 pick 也用守卫，且 next() 在那个提前 return 之前', () => {
    const text = src('views/home/HomeAnalyst.vue')
    assert.ok(text.includes("from '@/utils/requestGuard'"), '没引入守卫')
    assert.equal((text.match(/seriesGuard\.isCurrent\(/g) || []).length, 3, '写回/catch/finally 三处都要判')
    assert.ok(text.includes('onBeforeUnmount(seriesGuard.invalidate)'), '卸载时没作废')
    // 选中一条没有 pointId 的设备告警时，上一个点的请求还在飞——不先自增代号就会画在它下面
    assert.ok(
      text.indexOf('seriesGuard.next()') < text.indexOf('if (!row?.pointId)'),
      'next() 必须早于「没有 pointId 就 return」，否则设备告警会挂上上一个点的曲线',
    )
  })

  ok('绊线·第 14 条：7 个设备几何字段已从测点页签搬到设备页签', () => {
    const text = src('views/AdminView.vue')
    const start = text.indexOf("key: 'points'")
    const pointsTab = text.slice(start, text.indexOf("key: 'metrics'", start))
    const devStart = text.indexOf("key: 'devices'")
    const devicesTab = text.slice(devStart, text.indexOf("key: 'alarm-rules'", devStart))
    for (const k of [
      'headingDegrees', 'pitchDegrees', 'detectionRangeM',
      'halfAngleDegrees', 'verticalHalfAngleDegrees', 'antennaHeightM',
    ]) {
      assert.ok(!pointsTab.includes(k), `测点页签里还有设备字段 ${k}（提交给 /v1/points 会被静默丢弃）`)
      assert.ok(devicesTab.includes(k), `设备页签缺 ${k}`)
    }
    assert.equal((pointsTab.match(/key: 'altitude'/g) || []).length, 1, 'altitude 在测点页签只能声明一次')
    assert.ok(devicesTab.includes("key: 'altitude'"), '设备页签缺基座高程')
  })

  ok('绊线·第 16 条：标定对话框有有效期输入，停用按钮走白名单原因下拉', () => {
    const text = src('components/DeviceDrawer.vue')
    assert.ok(/v-model="calibrationForm\.validFrom"/.test(text), '对话框缺 validFrom 输入')
    assert.ok(/v-model="calibrationForm\.validTo"/.test(text), '对话框缺 validTo 输入')
    assert.ok(text.includes('invalidateDevicePointCalibration'), '停用端点没有调用点')
    // 原因必须是后端白名单里的一个（自由文本会被 400），所以断言五个值都在
    for (const r of ['MANUAL', 'DEVICE_RELOCATED', 'TARGET_REMOVED', 'MAINTENANCE', 'SUSPECTED_DRIFT']) {
      assert.ok(text.includes(`'${r}'`), `原因下拉缺白名单值 ${r}`)
    }
    assert.ok(text.includes('CALIBRATION_REASON_LABELS'), '原因标签没有复用已有的那份')
  })

  ok('绊线·第 18/19 条：大屏接上了窗口下拉、粒度标注、过期搬运与图例', () => {
    const text = src('views/ScreenView.vue')
    assert.ok(text.includes('replay.setRange'), '时间轴没有窗口下拉')
    assert.ok(text.includes('replayGranularityText'), '没有粒度标注（用户会以为数据丢了）')
    assert.ok(text.includes('stale: replay.isStale(p.id)'), 'stale 没有搬进 displayPoints')
    assert.ok(text.includes('replay.sampleMsOf(p.id)'), 'collectTime 还在用帧时刻')
    assert.ok(text.includes("key: 'stale'"), '图例没有「数据过期」一行')
    assert.ok(src('cesium/pointLayer.js').includes('（过期）'), '点标签没有过期后缀')
  })

  /**
   * P2-11：菜单与使用说明会漂移——菜单加了新页面，说明页还停在上一版，
   * 而「说明页少一个模块」不会报错、也没人会注意到。这条把两者的源码对起来：
   * `AppLayout` 的每个菜单标题都必须在 `HelpView` 的 MODULES 里有一节。
   *
   * 例外只有「使用说明」自己（它就是那一页），以及右上角头像里的「个人中心」
   * （不是菜单项，但说明页里也有——方向相反，不在这里断言）。
   */
  ok('绊线·P2-11：每个菜单项都在使用说明里有对应小节', () => {
    const layout = src('layout/AppLayout.vue')
    const help = src('views/HelpView.vue')
    const titles = [...layout.matchAll(/\{\s*path:\s*'[^']+',\s*title:\s*'([^']+)'/g)].map((m) => m[1])
    assert.ok(titles.length >= 9, `菜单标题解析失败，只拿到 ${titles.length} 个：${titles.join('/')}`)
    const missing = titles.filter((t) => t !== '使用说明' && !help.includes(`name: '${t}'`))
    assert.deepEqual(missing, [], `这些菜单项在 HelpView 的 MODULES 里没有小节：${missing.join('、')}`)
  })

  /**
   * P1-11：**理论视场**与**已核验目标**必须分开表达。
   *
   * 这件事一旦回退，界面上是"看起来更干净了"——扇形变成实线、三档连线只差颜色深浅，
   * 没有任何报错。所以用源码绊线把四条措辞与两处画法钉住：
   *   · 开关文案叫「雷达理论视场」（不是"雷达视场扇面"）；
   *   · 图例里有已核验 / 待核验 / 被遮挡三行；
   *   · 面板旁注写明"未按地形裁剪"；
   *   · 目标连线三档里，已核验走实线（不是虚线）、理论视场轮廓走虚线。
   */
  ok('绊线·P1-11：理论视场与已核验目标分开表达', () => {
    const screen = src('views/ScreenView.vue')
    const scene = src('cesium/digitalTwinScene.js')
    assert.ok(screen.includes('雷达理论视场'), '开关文案必须写明"理论"')
    for (const label of ['已核验目标', '待核验目标', '被遮挡目标']) {
      assert.ok(screen.includes(label), `图例缺「${label}」`)
    }
    assert.ok(screen.includes('未按地形裁剪'), '面板缺口径说明（扇形不是真实覆盖）')
    assert.ok(scene.includes('verified ?') || scene.includes('verified\n'), '目标连线没有区分已核验')
    assert.ok(/sectorOutline[\s\S]{0,400}PolylineDashMaterialProperty/.test(scene),
      '理论视场轮廓必须是虚线（实线看起来像已经成立的边界）')
  })

  /**
   * 时间展示绊线：后端给的是**带纳秒的 ISO8601**（如 2026-09-17T15:00:38.411855+08:00），
   * 直接渲染会折行、也没人读那六位小数。所有时间字段必须先过 formatTime /
   * formatTimeShort / fromNow。
   *
   * 这条的来源是一次真实的用户反馈（2026-09-20）：审计日志的时间列窄，原样贴出来折成三行。
   * 修的时候顺手发现告警中心、告警队列、设备列表、维护记录时间线都有同样的问题——
   * 所以绊线覆盖的是一组文件，而不只是被投诉的那一列。
   */
  ok('绊线·时间展示：视图不直接渲染后端 ISO（带纳秒）串', () => {
    const files = [
      'views/AuditView.vue', 'views/AlarmView.vue', 'views/DeviceView.vue',
      'components/AlarmQueue.vue', 'components/DeviceDrawer.vue', 'views/PointsView.vue',
    ]
    const fieldRe = /\.(createdAt|updatedAt|triggeredAt|resolvedAt|collectTime|receiveTime|lastReportTime|validFrom|validTo|installedOn)/
    for (const f of files) {
      const text = src(f)
      const tpl = text.slice(text.indexOf('</script>'))
      // 先把三种格式化调用抹掉，剩下的才是"没经过格式化"的引用
      const stripped = tpl.replace(/(formatTime|formatTimeShort|fromNow)\([^)]*\)/g, 'FMT')
      const bad = stripped
        .split('\n')
        // 同一行里出现过 FMT 就算"这个时间字段是格式化过的"——三元表达式
        // （`{{ row.lastReportTime ? FMT : '—' }}`）里字段名会留在条件位，不能算漏。
        // 这一条是绊线不是证明：一行里两个字段、只格式化了一个的情况它看不出来。
        .filter((line) => line.includes('{{') || line.includes(':timestamp='))
        .filter((line) => fieldRe.test(line) && !line.includes('FMT'))
      assert.equal(bad.length, 0, `${f} 里还在直接渲染原始时间串：${bad[0] || ''}`)
    }
  })

  /**
   * 地面热力层的绊线（2026-09-20）。这一层**两次**变成"视觉上的死代码"：
   *   ① 颜色取状态色（测点普遍正常 → 全绿）、半径按绝对量级（±1mm/d → 全贴下限）；
   *   ② 晕圈画在"档案高程 + 0.45m"的水平面上，被 GLB 山体吞掉，勾了开关什么都不出现。
   * 现在颜色走数值色标、形状是有厚度的短柱、图例给出刻度——三条都钉住。
   */
  ok('绊线·地面热力层：数值色标 + 有厚度 + 图例刻度', () => {
    const layer = src('cesium/heatmapLayer.js')
    const screen = src('views/ScreenView.vue')
    assert.ok(!layer.includes("from '@/constants/status'"),
      '热力层不该再用状态色（测点普遍正常时所有晕圈同色，等于看不出来）')
    assert.ok(layer.includes('extrudedHeight'),
      '晕圈必须有厚度：贴地平面会被 GLB 山体三角形吞掉')
    assert.ok(layer.includes('heatExtentOf') && layer.includes('heatColorBucketOf'),
      '颜色与半径要按当前测点集合的标度算')
    assert.ok(screen.includes('heat-legend') && screen.includes('heatExtentOf'),
      '图例里要给出色标刻度（否则"颜色深一点"没有可解释的含义）')
  })

  /**
   * 地形试算的绊线（P1-10）。这一步的意义全在"接上了没有"：
   *   · 后端端点 `/calibration/preview` 存在但界面没入口 -> 现场还是要靠人填数；
   *   · 界面有按钮但传的是自己填的方位角/斜距 -> 变成了"用填的数校验填的数"，
   *     什么也证明不了；所以断言必须钉住"调用时只传两个高度"。
   * 判据本身（净空、逐米步进、遮挡拒收）在后台单测与 22-line-of-sight 套件里验，
   * 这里只保证前端没有把这条通路接错。
   */
  ok('绊线·地形试算：入口接上且只传高度（不把填的方位角再喂回去）', () => {
    const drawer = src('components/DeviceDrawer.vue')
    const api = src('api/monitor.js')
    assert.ok(drawer.includes('按地形试算'), '标定对话框里要有试算按钮')
    assert.ok(drawer.includes('previewDevicePointCalibration'),
      '要调用试算接口，而不是只在界面上写一行说明')
    assert.ok(/previewDevicePointCalibration\([\s\S]{0,240}?antennaHeightM/.test(drawer),
      '试算请求里必须带上天线高（试算的核心就是"换个高度会怎样"）')
    assert.ok(!/previewDevicePointCalibration\([\s\S]{0,240}?azimuthDegrees/.test(drawer),
      '试算请求里不能带方位角/斜距：那是人填的，拿它去校验它自己等于没校验')
    assert.ok(/calibration\/preview/.test(api), 'API 层要指向 /calibration/preview')
    assert.ok(drawer.includes('按试算结果填入'), '试算结果要能一键填进表单（否则人还得手抄）')
  })

  // 执行实际路由守卫和 onError；只替换浏览器 history、页面组件与对话框。
  const { default: router } = await server.ssrLoadModule('/src/router/index.js')
  const { useUserStore } = await server.ssrLoadModule('/src/stores/user.js')
  globalThis.document = { title: '' }
  const user = useUserStore()
  user.token = 'test-token'
  user.user = { role: 'ADMIN' }
  const original = router.getRoutes().find((r) => r.name === 'screen')
  assert.equal(original.path, '/screen')
  assert.equal(typeof original.components.default, 'function')
  let fail = true
  router.addRoute({ path: '/screen', name: 'screen', meta: original.meta, component: () => {
    if (fail) return Promise.reject(new Error('Failed to fetch dynamically imported module: simulated'))
    return Promise.resolve({ render: () => null })
  } })
  const prompts = []
  const redirects = []
  let confirmation
  globalThis.__p0Confirm = (...args) => {
    prompts.push(args)
    confirmation = deferred()
    return confirmation.promise
  }
  globalThis.window = { location: { assign: (target) => redirects.push(target) } }
  await assert.rejects(router.push('/screen?case=p0'))
  assert.equal(prompts.length, 1)
  assert.equal(redirects.length, 0)
  confirmation.reject('cancel')
  await flush()
  assert.equal(redirects.length, 0)
  console.log(`PASS ${++passed} 分包加载失败可见提示，取消不刷新`)
  await assert.rejects(router.push('/screen?case=p0'))
  confirmation.resolve()
  await flush()
  assert.deepEqual(redirects, ['/screen?case=p0'])
  console.log(`PASS ${++passed} 确认重试保留目标路径与查询参数`)
  fail = false
  await router.push('/screen')
  assert.equal(router.currentRoute.value.name, 'screen')
  console.log(`PASS ${++passed} 已登录访问大屏路由可正常完成`)
  user.token = ''
  router.addRoute({ path: '/login', name: 'login', meta: { public: true }, component: { render: () => null } })
  await router.push('/screen?case=logged-out')
  assert.equal(router.currentRoute.value.name, 'login')
  assert.equal(router.currentRoute.value.query.redirect, '/screen?case=logged-out')
  console.log(`PASS ${++passed} 未登录大屏访问仍跳转登录，保留返回地址`)
  delete globalThis.window
  delete globalThis.document
} finally {
  await server.close()
}
console.log(`\nP0 第 1 批：${passed} 项全部通过（模拟网络；未替代现场浏览器与真实后端验收）。`)
