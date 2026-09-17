import { defineStore } from 'pinia'

import { pointSeries } from '@/api/monitor'
import { useMonitorStore } from '@/stores/monitor'
import { getToken } from '@/utils/token'
import { buildFrames, frameDataCount, frameFreshCount } from '@/utils/timeline'

/**
 * 大屏的时间轴回放（阶段 3b 的呈现部分）。
 *
 * 与实时链路的关系：
 *   - **实时**（`stores/realtime.js` + `stores/monitor.js`）始终在跑，不受回放影响；
 *   - 回放只是在大屏这一层**换个数据源**：`enabled` 时点位显示历史帧的值，
 *     退出回放立刻回到实时——不是「暂停订阅」，这样退出时不会等下一次推送才更新。
 *
 * 数据来自 `/points/{id}/series`（逐点各一份），合并成帧的逻辑在 `utils/timeline.js`，
 * 是纯函数（有断言），这里只负责取数、播放节拍和状态。
 *
 * **取数规模**（清单第 18 条）：`/series` 没有条数上限、`raw` 分支不抽稀，而生产基线是
 * 5 秒采样——单点 7 天就是 12 万行，7 个点合起来 85 万行 ≈ 38MB JSON。原来的写法是
 * 「对**全部可见点**逐个 `Promise.all` 发 raw、不传 `to`、无守卫」，在 1000 点项目上
 * 是 1.2 亿行。现在三道闸：**粒度降采样**（总量）、**并发池**（瞬时压力）、
 * **代际守卫**（切项目/测项时不串数据）。
 */

/** 播放节拍（毫秒）。回放的是历史采样，比采样间隔更快只会闪；这个值肉眼能跟上 */
const FRAME_INTERVAL_MS = 700

/** 定时器不进 state：原生句柄被 Pinia 代理包一层没有意义，也可能出意外 */
let timer = null

// ---------------- 取数规模控制 ----------------

/**
 * 一次回放允许的**估算**行数上限（不是硬约束，是选粒度的预算）。
 * 出处：7 点项目 24 小时 @5 秒 ≈ 12.1 万行 ≈ 5.4MB JSON，是浏览器还能一次吃下、
 * 还能在内存里驻留成帧的量级。超过就换更粗的桶。
 */
const ROW_BUDGET = 200000

/**
 * 最坏的采样密度：生产基线 5 秒/点（`tools/production_simulator/README.md`）。
 * 用来把「窗口 × 点数」换算成行数——**估的就是最坏情况**，估少了就会崩。
 */
const RAW_ROWS_PER_HOUR = 720

/**
 * `raw` 长窗口的请求分段长度（小时）。仅当粒度仍是 raw 且窗口更长时才切段。
 *
 * **诚实记账**：降采样生效后 raw 只剩短窗口（7 点项目 raw 的窗口上界约 40 小时），
 * 所以分段是**兜底**而不是主力——它防的是「实际采样密度高于基线」时同一窗口行数翻数倍
 * （1 秒采样 = 基线的 5 倍）。真正解决规模的是降采样与并发池。
 */
const SEGMENT_HOURS = 24

/** 同时在途的 series 请求上限。浏览器对同域并发本来就有上限，显式限流是为了让首屏可预期 */
const MAX_INFLIGHT = 4

/**
 * 选粒度：**先用最坏采样密度估行数，超预算就换更粗的桶**。
 *
 * 为什么必须把点数一起算进来：只用 `rangeHours` 决定粒度，7 点项目能接受的窗口
 * 1000 点项目接受不了——那一定会在最大的那个项目上崩一次。
 *
 * 实测：`(24,7)→raw`、`(168,7)→hour`、`(24,1000)→hour`、`(720,1000)→day`。
 */
export function pickGranularity(rangeHours, pointCount) {
  const points = Math.max(1, Number(pointCount) || 1)
  const hours = Math.max(0, Number(rangeHours) || 0)
  if (points * hours * RAW_ROWS_PER_HOUR <= ROW_BUDGET) return 'raw'
  if (points * hours <= ROW_BUDGET) return 'hour'
  return 'day'
}

/**
 * 有上限的并发：一次只放 `limit` 个在途任务，谁先完成谁接着领下一个。
 *
 * 为什么不是「每 K 个一组 `Promise.all`」：分组会在每组末尾形成**波峰等待**
 * ——组里最慢的那个拖住整组，下一组才发车。池子是流水线，没有这道墙。
 *
 * 单个任务抛错只让它自己变成 `undefined`，不拖垮整批（调用方按 `undefined` 处理）。
 */
export async function mapWithLimit(items, limit, fn) {
  const out = new Array(items.length)
  let cursor = 0
  const worker = async () => {
    while (cursor < items.length) {
      const i = cursor
      cursor += 1
      try {
        out[i] = await fn(items[i], i)
      } catch {
        out[i] = undefined
      }
    }
  }
  const size = Math.max(1, Math.min(limit, items.length))
  await Promise.all(Array.from({ length: size }, worker))
  return out
}

/**
 * 把 `[from, to]` 按 `hours` 切段，**由近及远**返回（第一段贴着 `to`）。
 *
 * 边界用 `to = 下一段的 from - 1ms`：后端 `from`/`to` 是**双闭区间**
 * （`MeasurementQueryService.series` 的 `ge`/`le`），不减这 1ms，边界那一行会同时
 * 落在相邻两段里，合帧时多出重复的采样时刻。
 */
export function splitWindow(from, to, hours) {
  const span = hours * 3600 * 1000
  const segments = []
  let end = to
  while (end > from) {
    const start = Math.max(from, end - span)
    segments.push({ from: start, to: end })
    end = start - 1
  }
  return segments
}

/** 回放窗口的可选值。放在 store 里是为了让界面与错误文案用同一份口径 */
export const REPLAY_RANGES = [
  { value: 6, label: '近 6 小时' },
  { value: 24, label: '近 24 小时' },
  { value: 72, label: '近 3 天' },
  { value: 168, label: '近 7 天' },
]

/** 粒度 → 界面上要说的话。「按小时聚合」必须显示出来，否则「7 天只有 168 个点」像是丢了数据 */
export const GRANULARITY_TEXT = { raw: '原始点', hour: '小时', day: '天' }

/**
 * 请求序号属于当前 store 实例；不在多个页面/测试实例之间共用（同 `stores/monitor.js`）。
 */
const requests = new WeakMap()
function requestState(store) {
  if (!requests.has(store)) requests.set(store, { load: 0 })
  return requests.get(store)
}

/** 同一份窗口已经在飞时不再发第二遍（手动刷新连点、主测项 watch 与手动刷新撞车） */
let inflightKey = null

export const useReplayStore = defineStore('replay', {
  state: () => ({
    enabled: false,
    loading: false,
    error: '',
    /** 回放窗口（小时），默认近 7 天。界面上可改（ScreenView 底部时间轴） */
    rangeHours: 24 * 7,
    /** 本批帧的实际粒度（raw/hour/day），由 {@link pickGranularity} 决定，界面要说明 */
    granularity: 'raw',
    /** [{ t, ms, values, sampleMs, stale }] */
    frames: [],
    index: 0,
    playing: false,
    /** 这批帧是哪个测项的（大屏上要写明，切换主测项后帧会重新拉） */
    metricCode: '',
  }),

  getters: {
    current: (state) => state.frames[state.index] || null,
    time: (state) => state.frames[state.index]?.t || '',
    values: (state) => state.frames[state.index]?.values || {},
    total: (state) => state.frames.length,
    /** 当前帧**没过期**的点数（第 19 条：陈旧值不算「有数据」） */
    currentFreshCount() {
      return frameFreshCount(this.current)
    },
    /** 当前帧有值但已过期的点数 */
    currentStaleCount() {
      const frame = this.current
      if (!frame) return 0
      return frameDataCount(frame) - frameFreshCount(frame)
    },
    // 原先这里还有一个 `valueOf(pointId)` getter。它**没有任何调用点**，而 Pinia 每次
    // 实例化这个 store 都会告警（getter 与 state 重名检查），更要紧的是它盖掉了
    // `Object.prototype.valueOf`——store 一旦参与隐式类型转换（模板插值、`+`、排序比较）
    // 就会调到它并拿到一个函数，症状极难追。取值用 `values[pointId]` 或下面的 `sampleMsOf`。
    /**
     * 该点当前帧的值**来自哪一次采样**（毫秒）。前值保持时它早于帧时刻——
     * 界面上的「采集时间」要显示这个，写帧时刻等于把陈旧读数伪装成当前读数。
     */
    sampleMsOf: (state) => (pointId) => state.frames[state.index]?.sampleMs?.[pointId] ?? null,
    /** 该点当前帧是不是过期值 */
    isStale: (state) => (pointId) => state.frames[state.index]?.stale?.[pointId] === true,
  },

  actions: {
    /** 拉历史序列并合帧。失败只记错误，不影响实时链路。 */
    async load() {
      const monitor = useMonitorStore()
      // 与大屏**显示的是同一份**（displayPoints → enrichedPoints → pointsOfProject）。
      // 原来用 monitor.points（全部可见点），会拉一堆本项目根本不画的点——免费的多余请求
      const points = monitor.pointsOfProject
      if (!points.length) {
        this.error = '当前项目没有测点，无法加载回放数据'
        this.frames = []
        return
      }

      // 回放看的就是**当前主测项**——它由测项档案决定，不再写死 defo_mm
      const metricCode = monitor.primaryMetricCode
      const projectId = monitor.projectId
      const token = getToken()
      const granularity = pickGranularity(this.rangeHours, points.length)
      const key = [projectId, metricCode, this.rangeHours, granularity, points.length].join('|')
      if (inflightKey === key) return

      const state = requestState(this)
      const requestId = ++state.load
      const active = () =>
        state.load === requestId &&
        monitor.projectId === projectId &&
        monitor.primaryMetricCode === metricCode &&
        getToken() === token

      inflightKey = key
      this.loading = true
      this.error = ''

      // 按测点累积（同一测点的多段拼起来就是它的完整序列，段本身由近及远，
      // 所以拼完仍是时间升序的，不必再排一次）
      const byPoint = new Map(points.map((p) => [p.id, []]))
      const applyFrames = () => {
        this.frames = buildFrames(points.map((p) => ({ pointId: p.id, points: byPoint.get(p.id) })))
        this.metricCode = metricCode
        this.granularity = granularity
        // 落在**最后一帧**：进来先看「最近的历史」，与实时画面相差最小
        this.index = Math.max(0, this.frames.length - 1)
      }

      try {
        const to = Date.now()
        const from = to - this.rangeHours * 3600 * 1000
        const segments =
          granularity === 'raw' && this.rangeHours > SEGMENT_HOURS
            ? splitWindow(from, to, SEGMENT_HOURS)
            : [{ from, to }]
        const tasks = points.flatMap((p) =>
          segments.map((seg, segIndex) => ({ pointId: p.id, segIndex, ...seg })),
        )

        // 多段时**最近一段到齐先出一版帧**：时间轴先显示最近一段，其余段在后台补齐。
        // 由近及远保证右边缘的「前值保持 / 过期」判定天然正确——更老的段不可能让
        // 某个点的最后一次采样变新，所以先出的这一版不会出现「刚进来满屏数据过期」。
        // 单段路径（含所有降采样路径）不触发，只成一次帧。
        const needFirst = segments.length > 1 ? points.length : 0
        let doneFirst = 0
        let staged = false

        await mapWithLimit(tasks, MAX_INFLIGHT, async (t) => {
          const res = await pointSeries(t.pointId, {
            metricCode,
            granularity,
            from: new Date(t.from).toISOString(),
            to: new Date(t.to).toISOString(),
          }).catch(() => null)
          if (!active()) return null
          const bucket = byPoint.get(t.pointId)
          if (bucket) bucket.push(...(res?.points || []))
          if (needFirst && t.segIndex === 0) {
            doneFirst += 1
            if (doneFirst === needFirst && !staged) {
              staged = true
              applyFrames()
            }
          }
          return res
        })

        if (!active()) return // 迟到的整批结果：一个字都不写
        applyFrames()
        if (!this.frames.length) {
          this.error = `近 ${this.rangeHours} 小时内没有历史数据`
        }
      } catch (e) {
        if (!active()) return
        this.error = e?.message || '回放数据加载失败'
        this.frames = []
      } finally {
        // 只清自己那一份 key，别把后来者的清掉
        if (inflightKey === key) inflightKey = null
        // 旧请求的 finally 不能把**在途**新请求的 loading 关掉
        if (active()) this.loading = false
      }
    },

    /** 改回放窗口：正在回放就立刻重拉（与切主测项同一形） */
    setRange(hours) {
      const h = Number(hours)
      if (!Number.isFinite(h) || h <= 0 || h === this.rangeHours) return
      this.rangeHours = h
      if (this.enabled) this.load()
    },

    /** 进入回放（首次进入顺带取数） */
    async enter() {
      this.enabled = true
      if (!this.frames.length) await this.load()
    },

    /** 退出回放：停节拍、回到实时（实时链路一直在跑，无需补数） */
    exit() {
      this.pause()
      this.enabled = false
    },

    async toggle() {
      if (this.enabled) this.exit()
      else await this.enter()
    },

    play() {
      if (!this.frames.length) return
      // 播到末尾再按播放 → 从头开始，否则按了没反应会让人以为坏了
      if (this.index >= this.frames.length - 1) this.index = 0
      this.playing = true
      if (timer !== null) clearInterval(timer)
      timer = setInterval(() => this.step(), FRAME_INTERVAL_MS)
    },

    pause() {
      this.playing = false
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
    },

    step() {
      if (this.index >= this.frames.length - 1) {
        this.pause()
        return
      }
      this.index += 1
    },

    seek(index) {
      if (!this.frames.length) return
      this.index = Math.min(this.frames.length - 1, Math.max(0, index))
    },

    /** 组件卸载时收尾：定时器不随组件销毁会一直跑 */
    dispose() {
      this.pause()
    },
  },
})
