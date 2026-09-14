import { defineStore } from 'pinia'

import { openStream } from '@/api/monitor'
import { useMonitorStore } from '@/stores/monitor'
import { useUserStore } from '@/stores/user'

/**
 * 实时推送（SSE）的唯一持有者。
 *
 * 为什么必须放 store 而不是布局组件里：
 * `/screen`（3D 大屏）是**顶层路由**，不套 `AppLayout`。连接若由 `AppLayout.onMounted`
 * 发起，用户直接进大屏时那条连接根本没人建；反过来把发起放进大屏，从大屏退回工作台
 * 又会断一次（断开期间的事件没有重放，直接丢）。**Pinia store 的实例活在应用级**，
 * 比布局和大屏都活得久，这是唯一能同时覆盖两者的位置。
 *
 * 职责边界：
 *   - 本 store 只管**连接**（建立/断开/重连/状态）；
 *   - 数据不在这里落，一律转交 `stores/monitor.js` 的单一数据源，
 *     否则又会出现「同一个时刻、两个页面两份数据」。
 */

/*
 * 下面几个是**模块级**变量，刻意不放进 state：
 * EventSource 是原生对象，塞进 Pinia 的响应式 state 会被 Proxy 包一层，
 * `close()` 之类的原生方法经代理调用既慢又容易出意外；而且「每个浏览器一条连接」
 * 本来就是模块级的资源，不是可响应的数据。
 */
let source = null
let retryTimer = null
let retryDelay = 1000
let retryAttempts = 0
let lifecycleBound = false

/** 重连退避上限：再久也该让用户看到「一直在重试」而不是无限静默 */
const RETRY_MAX_MS = 30000
/** 连续失败到这个次数就停手，交回轮询兜底（避免 token 失效时无限重连刷日志） */
const RETRY_GIVE_UP = 8

const nowIso = () => new Date().toISOString()

function parse(event) {
  try {
    return JSON.parse(event.data)
  } catch {
    return null
  }
}

export const useRealtimeStore = defineStore('realtime', {
  state: () => ({
    /** idle | connecting | live | reconnecting | closed */
    status: 'idle',
    connectedAt: '',
    lastEventAt: '',
    /** 收到过的业务事件数（比轮询更能说明「数据真的在流」） */
    measurementCount: 0,
    alarmCount: 0,
    /** 最近一次失败原因（给界面提示用，不弹窗） */
    error: '',
  }),

  getters: {
    isLive: (state) => state.status === 'live',
    statusText: (state) => {
      if (state.status === 'live') return '实时推送'
      if (state.status === 'connecting') return '推送连接中'
      if (state.status === 'reconnecting') return '推送重连中'
      if (state.status === 'closed') return '推送已断开'
      return '轮询兜底'
    },
  },

  actions: {
    /**
     * 建立连接。**幂等**：重复调用（布局挂载 + 大屏挂载都会调）只会有一条连接。
     * 无 token（未登录）时什么也不做。
     */
    start() {
      const user = useUserStore()
      if (!user.token) return
      if (source) return

      this.bindLifecycle()
      this.status = 'connecting'
      this.error = ''

      const monitor = useMonitorStore()
      const es = openStream(user.token)
      source = es

      // 后端建好订阅会推一条 `connected`；浏览器的 `open` 更早。
      // 两个都认，避免只有一个到达时状态灯不变绿。
      const onOpen = () => {
        this.status = 'live'
        this.connectedAt = nowIso()
        this.error = ''
        retryDelay = 1000
        retryAttempts = 0
      }
      es.addEventListener('open', onOpen)
      es.addEventListener('connected', onOpen)

      es.addEventListener('measurement', (event) => {
        const data = parse(event)
        if (!data) return
        this.lastEventAt = nowIso()
        // 认领成功才计数：SSE 推的是全库，不属于当前项目的点会被数据源丢掉，
        // 那是正常的，不该算进「本页收到了多少条」
        if (monitor.applyMeasurement(data)) {
          this.measurementCount += 1
        }
      })

      es.addEventListener('alarm', (event) => {
        const data = parse(event)
        if (!data) return
        this.lastEventAt = nowIso()
        if (monitor.applyAlarm(data)) {
          this.alarmCount += 1
        }
      })

      es.addEventListener('error', () => {
        // readyState=CLOSED 表示浏览器放弃重连（401/404、或服务端主动关闭），
        // CONNECTING 表示它自己在退避重连（服务重启这类瞬断）——两条路都要给界面一个说法
        const closed = es.readyState === EventSource.CLOSED
        this.status = 'reconnecting'
        if (closed) {
          this.error = '推送连接已断开'
          this.scheduleReconnect()
        }
      })
    },

    /** 主动断开（登出、整页卸载）。会让 EventSource 停止自动重连。 */
    stop() {
      if (retryTimer !== null) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
      if (source) {
        // 显式 close：整页卸载时（pagehide）浏览器不会替我们回收这条长连接，
        // 而 nginx 的非缓冲反代要等**第二次**心跳写失败才察觉对端已走——
        // 这段时间那条半关闭的 socket 占着浏览器「单源 6 连接」的名额，
        // 连刷几次之后所有请求排队，界面看起来就是死了几十秒。
        source.close()
        source = null
      }
      if (this.status !== 'idle') this.status = 'idle'
    },

    /** 登出：断连接 + 清计数（数据侧的告警态由调用方一并用 clearAlarms 清） */
    reset() {
      this.stop()
      this.connectedAt = ''
      this.lastEventAt = ''
      this.measurementCount = 0
      this.alarmCount = 0
      this.error = ''
      retryDelay = 1000
      retryAttempts = 0
    },

    /** 显式重连（大屏「刷新」、或从 bfcache 回来时用） */
    reconnect() {
      this.reset()
      this.start()
    },

    /**
     * 退避重连。浏览器自己会重连 CONNECTING 那条路，
     * 这条只管「浏览器已经放弃」的情况（CLOSED）。
     */
    scheduleReconnect() {
      if (retryTimer !== null) return
      retryAttempts += 1
      if (retryAttempts > RETRY_GIVE_UP) {
        this.status = 'closed'
        this.error = '推送重连失败，已切回轮询'
        return
      }
      const delay = retryDelay
      retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS)
      retryTimer = setTimeout(() => {
        retryTimer = null
        // 浏览器放弃时 source 还在（readyState=CLOSED），必须先清干净再重来
        source = null
        this.start()
      }, delay)
    },

    /**
     * 整页卸载 / bfcache 往返。**只绑一次**，与组件生命周期无关——
     * 这正是把连接放在 store 的另一个好处：不用每个页面各写一遍。
     */
    bindLifecycle() {
      if (lifecycleBound || typeof window === 'undefined') return
      lifecycleBound = true
      window.addEventListener('pagehide', () => this.stop())
      window.addEventListener('pageshow', (event) => {
        // 从 bfcache 回来时 pagehide 已经关过连接了，必须补上，
        // 否则按一次「后退」SSE 就静默失效——页面看着正常，数据永远停在那一刻
        if (event.persisted) {
          source = null
          this.start()
        }
      })
    },
  },
})
