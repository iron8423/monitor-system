import { defineStore } from 'pinia'

import { pointSeries } from '@/api/monitor'
import { useMonitorStore } from '@/stores/monitor'
import { buildFrames, frameDataCount } from '@/utils/timeline'

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
 */

/** 播放节拍（毫秒）。回放的是历史采样，比采样间隔更快只会闪；这个值肉眼能跟上 */
const FRAME_INTERVAL_MS = 700

/** 定时器不进 state：原生句柄被 Pinia 代理包一层没有意义，也可能出意外 */
let timer = null

export const useReplayStore = defineStore('replay', {
  state: () => ({
    enabled: false,
    loading: false,
    error: '',
    /** 回放窗口（小时），默认近 7 天 */
    rangeHours: 24 * 7,
    /** [{ t, ms, values: { pointId: value } }] */
    frames: [],
    index: 0,
    playing: false,
  }),

  getters: {
    current: (state) => state.frames[state.index] || null,
    time: (state) => state.frames[state.index]?.t || '',
    values: (state) => state.frames[state.index]?.values || {},
    total: (state) => state.frames.length,
    currentHasData() {
      return frameDataCount(this.current)
    },
    /** 单个测点在当前帧的值；没有该点或该帧为空时为 null */
    valueOf: (state) => (pointId) => {
      const v = state.frames[state.index]?.values?.[pointId]
      return v === undefined ? null : v
    },
  },

  actions: {
    /** 拉历史序列并合帧。失败只记错误，不影响实时链路。 */
    async load() {
      const monitor = useMonitorStore()
      if (!monitor.points.length) {
        this.error = '没有测点，无法加载回放数据'
        this.frames = []
        return
      }
      this.loading = true
      this.error = ''
      try {
        const from = new Date(Date.now() - this.rangeHours * 3600 * 1000).toISOString()
        const list = await Promise.all(
          monitor.points.map((p) =>
            pointSeries(p.id, { metricCode: 'defo_mm', granularity: 'raw', from }).catch(() => null),
          ),
        )
        const seriesByPoint = monitor.points.map((p, i) => ({
          pointId: p.id,
          points: list[i]?.points || [],
        }))
        this.frames = buildFrames(seriesByPoint)
        // 落在**最后一帧**：进来先看「最近的历史」，与实时画面相差最小
        this.index = Math.max(0, this.frames.length - 1)
        if (!this.frames.length) {
          this.error = `近 ${this.rangeHours} 小时内没有历史数据`
        }
      } catch (e) {
        this.error = e?.message || '回放数据加载失败'
        this.frames = []
      } finally {
        this.loading = false
      }
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
