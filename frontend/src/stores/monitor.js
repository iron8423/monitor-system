import { defineStore } from 'pinia'

import {
  listAlarmRules,
  listAlarms,
  listDevices,
  listMetrics,
  listObjects,
  listPoints,
  listProjects,
  listScenes,
  pointLatest,
  projectPointsLatest,
  projectSummary,
} from '@/api/monitor'
import { alarmRankOf } from '@/constants/status'
import { TERMINAL_STATUSES } from '@/utils/labels'
import { warnThresholdOf } from '@/utils/thresholds'
import { getToken } from '@/utils/token'

/** 主测项的默认选择：形变是本项目的第一个场景，档案里没有它就退回排序最靠前的测项 */
const DEFAULT_PRIMARY_METRIC = 'defo_mm'

/** latest 里这几项不是测项值，不能当测项读（contract §3） */
const NON_METRIC_KEYS = new Set(['collectTime', 'receiveTime', 'quality', 'signal', 'position', 'state', 'deviceId', 'messageId', 'sequence', 'schemaVersion'])

// 请求序号属于当前 store 实例；不在多个页面/测试实例之间共用。
const requests = new WeakMap()
function requestState(store) {
  if (!requests.has(store)) requests.set(store, { snapshot: 0, latest: 0 })
  return requests.get(store)
}

function collectMillis(latest) {
  if (typeof latest?.collectTime !== 'string' || !latest.collectTime.trim()) return NaN
  return Date.parse(latest.collectTime)
}

/**
 * 最新值是一条观测，而不是不同时间测项的拼盘。
 * 旧时间永不覆盖；无版本号的同时间 SSE 由先到值暂时占位，再由服务器快照对账。
 * 同时间快照仅在请求期间本地未更新时采用，避免在途 HTTP 覆盖刚到的 SSE。
 */
function preferSnapshot(current, incoming, atRequestStart) {
  const incomingTime = collectMillis(incoming?.latest)
  const currentTime = collectMillis(current?.latest)
  if (!Number.isFinite(incomingTime)) return current || { ...incoming, latest: null, state: null }
  if (!Number.isFinite(currentTime) || incomingTime > currentTime) return incoming
  if (incomingTime === currentTime && current === atRequestStart) return incoming
  return current
}

/** 从测项档案里挑一个可用的主测项；当前值仍有效就保持不变 */
function pickPrimaryMetric(rows, current) {
  const codes = [...new Set((rows || []).map((r) => r?.code).filter(Boolean))]
  if (current && codes.includes(current)) return current
  if (codes.includes(DEFAULT_PRIMARY_METRIC)) return DEFAULT_PRIMARY_METRIC
  return codes[0] || DEFAULT_PRIMARY_METRIC
}

/** 把 latest 里的测项值挑出来：数值字段、且不是时间/质量这些元信息 */
function metricValuesOf(latest) {
  const out = {}
  for (const [key, value] of Object.entries(latest || {})) {
    if (NON_METRIC_KEYS.has(key)) continue
    if (typeof value === 'number') out[key] = value
  }
  return out
}

/**
 * 把警情列表折成「未解除警情表」，**按 `alarmId` 键**。
 *
 * 为什么不能按 `pointId` 键：一个测点可以同时有多条未解除警情（不同测项、不同规则）。
 * 按 pointId 只存一条时，后到的那条覆盖先到的，而**解除其中任意一条**都会把这个点整个删掉
 * ——另一条还没解除的警情颜色跟着消失。「这个点显示成什么颜色」由 `openAlarmsByPoint`
 * 汇总（取最高等级），不在写入侧做这种有损压缩。
 *
 * 终态与设备告警不进这张表：终态已经结束；设备告警没有 pointId，不参与测点着色，
 * 它只进 `recentAlarms`（大屏横幅要说得出「哪台设备离线了」）。
 */
function toAlarmMap(records) {
  const map = {}
  for (const a of records || []) {
    if (!a?.id || !a.pointId) continue
    if (TERMINAL_STATUSES.includes(a.status)) continue
    map[a.id] = {
      alarmId: a.id,
      pointId: a.pointId,
      level: a.level,
      status: a.status,
      at: a.triggeredAt,
    }
  }
  return map
}

/**
 * 监测数据仓库（单一数据源）。
 *
 * 为什么要有这个 store：3D 大屏、侧栏列表、弹窗、曲线如果各自去请求接口，
 * 拿到的「同一时刻」其实不是同一份数据 —— 会出现 3D 上标红、列表里还是绿的情况。
 * 所有视图统一从这里取数，后续 SSE 实时增量也只 patch 这一份。
 */
export const useMonitorStore = defineStore('monitor', {
  state: () => ({
    loading: false,
    loadedAt: '',
    /** 首屏加载失败的原因；非空时视图要显示「失败」，不能一直显示「加载中」 */
    error: null,

    /**
     * 「一个项目都没加入」的说明（项目隔离落地后新增的账号就是这种状态）。
     * 与 `error` 分开：这不是失败，是**权限范围的正常结果**——显示「加载失败」
     * 会让人去查后端，而真正要做的是找管理员加项目成员。
     */
    noProjectReason: '',

    projects: [],
    projectId: null,
    scenes: [],
    objects: [],
    points: [],
    summary: null,

    /**
     * 测项档案（GET /metrics，每点一份：`{pointId, code, name, unit, sortOrder}`）。
     * 名称与单位都以它为准，界面里不再自己拼「累计形变(mm)」这种字符串。
     */
    metrics: [],

    /**
     * 当前**主测项**：3D 着色、热力图、时间轴回放、测点列表都跟着它走。
     * 大屏上可以切换（见 ScreenView 顶栏的「主测项」下拉）。
     */
    primaryMetricCode: DEFAULT_PRIMARY_METRIC,

    /**
     * 告警规则（GET /alarm-rules）。放这里是为了让 3D 的「超限变色」与曲线上的
     * 阈值线同源——以前 3D 那边自带一个写死的 3mm，规则改了它不会跟着变。
     */
    rules: [],

    /**
     * 当前用户**可见**的设备 id（GET /devices，A 的项目隔离已把范围限到成员项目）。
     *
     * 后端已按订阅者过滤 SSE，前端仍用可见设备列表约束界面状态。
     */
    deviceIds: [],

    /** pointId -> PointLatestVO（{ pointId, pointCode, latest, state }） */
    latestMap: {},

    /**
     * pointId -> 未解除警情 { alarmId, level, status, at }。
     * 首屏由 `listAlarms` 播种，之后由 SSE 的 `alarm` 事件增量维护——
     * 这样「进页面时已有告警的点」和「刚推送来的告警」着色口径一致。
     */
    activeAlarms: {},

    /** 最近的告警事件（新到旧，有上限）。大屏的告警横幅读它 */
    recentAlarms: [],
  }),

  getters: {
    currentProject: (state) => state.projects.find((p) => p.id === state.projectId) || null,

    /** 去重后的测项代码（按档案出现顺序） */
    metricCodes: (state) => [...new Set(state.metrics.map((m) => m.code))],

    /** 某个测点的测项档案（按 sortOrder） */
    metricsOf: (state) => (pointId) =>
      state.metrics
        .filter((m) => m.pointId === pointId)
        .sort((a, b) => (a.sortOrder ?? 99) - (b.sortOrder ?? 99)),

    /** 测项的展示元信息（名称/单位）；档案里没有就退回代码本身 */
    metricMeta: (state) => (code) =>
      state.metrics.find((m) => m.code === code) || { code, name: code, unit: '' },

    /** 当前主测项的元信息 */
    primaryMetric() {
      return this.metricMeta(this.primaryMetricCode)
    },

    /**
     * **当前项目下的测点**：按档案的归属链过滤 测点 → 对象 → 场景 → 项目。
     *
     * 为什么要在前端过滤：`/points` 只按「成员项目」限范围（A 的 09-14 隔离），
     * 不接 `projectId` 参数。而这条链（scenes 里每个场景挂哪个 projectId）本来就在
     * 已加载的档案里，所以切换项目时不必再打接口，也不会把别的项目的点画到大屏上。
     */
    pointsOfProject(state) {
      if (!state.projectId) return state.points
      const sceneIds = new Set(
        state.scenes.filter((s) => s.projectId === state.projectId).map((s) => s.id),
      )
      const objectIds = new Set(
        state.objects.filter((o) => sceneIds.has(o.sceneId)).map((o) => o.id),
      )
      return state.points.filter((p) => objectIds.has(p.objectId))
    },

    sceneNameOf: (state) => (point) => {
      const object = state.objects.find((o) => o.id === point?.objectId)
      if (!object) return '—'
      return state.scenes.find((s) => s.id === object.sceneId)?.name || '—'
    },

    /**
     * 「测点 → 该点等级最高的未解除警情」，由 `activeAlarms` 汇总而来。
     *
     * `activeAlarms` 是**按 alarmId** 存的全集（同一点可以有多条），这里做一次聚合，
     * 于是着色只需要问「这个点有没有警情、其中最高是什么等级」。
     * 等级相同时取触发时间较晚的那条，只是为了让结果确定——否则同一份数据可能因为
     * 遍历顺序不同给出两个答案。
     */
    openAlarmsByPoint(state) {
      const byPoint = {}
      for (const alarm of Object.values(state.activeAlarms)) {
        if (alarm.pointId == null) continue
        const current = byPoint[alarm.pointId]
        if (!current) {
          byPoint[alarm.pointId] = alarm
          continue
        }
        const rank = alarmRankOf(alarm.level)
        const currentRank = alarmRankOf(current.level)
        if (rank > currentRank
          || (rank === currentRank && String(alarm.at || '') > String(current.at || ''))) {
          byPoint[alarm.pointId] = alarm
        }
      }
      return byPoint
    },

    /** 测点 + 最新值 + 场景名，拍平成视图直接能用的结构 */
    enrichedPoints(state) {
      const code = state.primaryMetricCode
      const alarms = this.openAlarmsByPoint
      return this.pointsOfProject.map((point) => {
        const vo = state.latestMap[point.id]
        const latest = vo?.latest || null
        const metrics = metricValuesOf(latest)
        const value = metrics[code] ?? null
        const meta = state.metrics.find((m) => m.pointId === point.id && m.code === code)
          || state.metrics.find((m) => m.code === code)
        return {
          ...point,
          sceneName: this.sceneNameOf(point),

          // —— 与测项无关的那份：谁需要就自己查，界面不再写死字段名 ——
          metrics,
          metricCode: code,
          metricName: meta?.name || code,
          unit: meta?.unit || '',
          /** 当前主测项的值：3D 标签/热力图/列表都用它 */
          value,
          hasData: value !== null && value !== undefined,
          /**
           * 超限判据（来自规则，取绝对值最小的那条）。没有规则就是 null，
           * 此时不判超限——宁可不黄，也不要自己发明一个阈值。
           */
          threshold: warnThresholdOf(state.rules, { pointId: point.id, metricCode: code }),

          collectTime: latest?.collectTime || null,
          quality: latest?.quality || null,
          signal: latest?.signal ?? null,
          state: vo?.state || null,
          // 告警优先级最高（resolvePointVisual 的第一档），
          // 3D 那圈脉冲环就是靠这两个字段转起来的。
          // 同一测点多条未解除警情时，这里给的是**等级最高**的那条。
          hasAlarm: Boolean(alarms[point.id]),
          alarmLevel: alarms[point.id]?.level || null,
          alarmStatus: alarms[point.id]?.status || null,
        }
      })
    },

    dataPointCount() {
      return this.enrichedPoints.filter((p) => p.hasData).length
    },
  },

  actions: {
    /** 首屏快照：档案 + 概览 + 每个测点的最新值（并发取，个别失败不影响整体） */
    async loadSnapshot() {
      const state = requestState(this)
      const requestId = ++state.snapshot
      const token = getToken()
      let projectId = this.projectId
      const active = () => state.snapshot === requestId
        && this.projectId === projectId && getToken() === token
      this.loading = true
      this.error = null
      this.noProjectReason = ''
      try {
        if (!this.projects.length) {
          const projects = await listProjects()
          if (!active()) return
          this.projects = projects
        }
        /*
         * 项目隔离（A 的 09-14 批）：`/projects` 只返回「我是成员」的项目。
         * 一个都没加入时**直接收工**——继续拉 points/latest 只会得到空数组，
         * 界面却要显示成"暂无数据"，让人以为系统坏了。
         */
        if (!this.projects.length) {
          this.noProjectReason = '你还没有加入任何项目'
          this.points = []
          this.latestMap = {}
          this.loadedAt = ''
          return
        }
        if (!this.projectId) {
          this.projectId = this.projects[0]?.id ?? null
          projectId = this.projectId
        }

        const [scenes, objects, points, summary, alarms] = await Promise.all([
          listScenes(),
          listObjects(),
          listPoints(),
          projectId ? projectSummary(projectId) : Promise.resolve(null),
          // 警情是「非首屏必需」的：失败不该把整个快照判成失败，故单独 catch
          listAlarms({ pageNum: 1, pageSize: 200 }).catch(() => null),
        ])
        if (!active()) return
        this.scenes = scenes
        this.objects = objects
        this.points = points
        this.summary = summary
        if (alarms?.records) {
          this.activeAlarms = toAlarmMap(alarms.records)
        }

        /*
         * 测项档案与规则同理：它们决定「显示哪个测项、超限从多少算起」，
         * 取不到就退回默认（defo_mm + 不判超限），不该让整个首屏失败。
         */
        const [metrics, rules, devices] = await Promise.all([
          listMetrics().catch(() => []),
          listAlarmRules().catch(() => []),
          listDevices().catch(() => []),
        ])
        if (!active()) return
        this.metrics = metrics || []
        this.rules = rules || []
        this.deviceIds = (devices || []).map((d) => d.id)
        this.primaryMetricCode = pickPrimaryMetric(this.metrics, this.primaryMetricCode)

        await this.refreshLatest()
      } catch (e) {
        // 必须把失败**记下来**，不能只置 loading=false 就走。
        // loadedAt 仍然是空串，而视图那条 `!loadedAt → '加载中…'` 会**永远**成立——
        // 于是「请求失败」被显示成「还在加载」，一个不会自己结束的假象。
        if (active()) this.error = e?.message || '数据加载失败'
      } finally {
        if (active()) this.loading = false
      }
    },

    /** 只刷最新值（大屏定时刷新、或 SSE 断线后补齐时用） */
    async refreshLatest() {
      const state = requestState(this)
      const requestId = ++state.latest
      const projectId = this.projectId
      const token = getToken()
      const active = () => state.latest === requestId
        && this.projectId === projectId && getToken() === token
      const visible = [...this.pointsOfProject]
      const atRequestStart = { ...this.latestMap }
      if (!visible.length) {
        this.latestMap = {}
        this.loadedAt = new Date().toISOString()
        return
      }
      let rows
      let partial = false
      try {
        rows = await projectPointsLatest(projectId)
        if (!Array.isArray(rows)) throw new Error('最新值接口返回格式错误')
      } catch (error) {
        if (!active()) return
        // 仅旧服务确实没有批量接口时兼容单点；网络/鉴权/500 错误不能放大成 N 个请求。
        if (![404, 405].includes(error?.response?.status ?? error?.status)) {
          this.error = error?.message || '最新值刷新失败'
          return
        }
        const results = await Promise.allSettled(visible.map((p) => pointLatest(p.id)))
        rows = results.flatMap((result) => result.status === 'fulfilled' ? [result.value] : [])
        partial = results.some((result) => result.status === 'rejected')
        console.warn('[monitor] 批量最新值接口不可用，已退回单点查询：', error?.message || error)
      }
      if (!active()) return
      // 必须在 await 之后读取当前状态：请求期间可能已经收到更新的 SSE。
      const visibleIds = new Set(visible.map((p) => String(p.id)))
      const map = Object.fromEntries(Object.entries(this.latestMap)
        .filter(([id]) => visibleIds.has(String(id))))
      const received = new Set()
      for (const row of rows || []) {
        if (!row || !visibleIds.has(String(row.pointId))) continue
        const id = row.pointId
        received.add(String(id))
        map[id] = preferSnapshot(map[id], row, atRequestStart[id])
      }
      partial ||= received.size < visibleIds.size
      this.latestMap = map
      this.error = partial ? '部分测点刷新失败，已保留现有数据，请稍后重试' : null
      if (!partial) this.loadedAt = new Date().toISOString()
    },

    /**
     * SSE `measurement` 事件 → 并进对应测点的 latest（单位与字段口径见契约 §6）。
     *
     * 为什么要落回这一份：大屏、侧栏、弹窗、曲线都从 `latestMap` 取数，
     * 推送若各页面各存一份，同一个时刻会出现「3D 上已经跳了、列表里还是旧值」。
     *
     * 只接受当前项目档案中的测点；首屏 HTTP 尚未返回时也可接收首条有效 SSE。
     * @returns {boolean} 是否真的并进了一份数据（调用方据此计数）
     */
    applyMeasurement(evt) {
      return this.applyMeasurements([evt]) === 1
    },

    /**
     * 批量并入 SSE 测值。1000 点同刻到达时只复制一次 latestMap，只触发一次视图重算。
     * @returns {number} 真正属于当前快照的事件数
     */
    applyMeasurements(events) {
      let next = null
      let accepted = 0
      const visible = new Map(this.pointsOfProject.map((p) => [String(p.id), p]))
      for (const evt of events || []) {
        const point = visible.get(String(evt?.pointId))
        if (!point) continue
        const id = point.id
        const current = (next || this.latestMap)[id]
        const incomingTime = collectMillis(evt)
        const currentTime = collectMillis(current?.latest)
        if (!Number.isFinite(incomingTime)) continue
        if (Number.isFinite(currentTime) && incomingTime <= currentTime) continue
        const { pointId, pointCode, ...rest } = evt
        if (next === null) next = { ...this.latestMap }
        next[id] = {
          ...current,
          pointId: id,
          pointCode: point.code ?? pointCode,
          // 当前后端没有推送全部附加字段，缺失值先显示未知，随后由 HTTP 对账补齐。
          // 不沿用上一时刻的速率、质量或目标状态冒充本次观测。
          latest: { quality: null, signal: null, position: null, ...rest },
          state: evt.state ?? null,
        }
        accepted += 1
      }
      if (next !== null) {
        this.latestMap = next
        this.loadedAt = new Date().toISOString()
      }
      return accepted
    },

    /**
     * SSE `alarm` 事件 → 维护未解除警情表 + 事件流。
     *
     * 解除/误报要把点从表里摘掉，否则「箭头回落后颜色一直是红的」。
     * 设备告警没有 pointId，仍进事件流（大屏横幅要说得出「哪台设备离线了」），
     * 但不参与测点着色。
     * @returns {boolean} 是否是一次有效的告警事件
     */
    applyAlarm(evt) {
      if (!evt) return false

      /*
       * 后端按订阅者过滤，前端再按当前画面约束告警状态。
       *   - 测点告警：只在**已加载的可见测点**里认（latestMap 就是可见测点的集合）；
       *   - 设备告警：只认**可见设备**（deviceIds 来自 /devices，A 已按成员项目过滤）。
       */
      if (evt.alarmType === 'POINT') {
        if (evt.pointId == null || !this.latestMap[evt.pointId]) return false
      } else if (evt.alarmType === 'DEVICE') {
        if (evt.deviceId == null || !this.deviceIds.includes(evt.deviceId)) return false
      }

      // 按 **alarmId** 记账。此前按 pointId 存单条，于是同一点的另一条未解除警情
      // 会被后到的那条覆盖，而解除其中任意一条又把这个点整条删掉——另一条还没解除，
      // 颜色却没了。着色要的「最高等级」由 openAlarmsByPoint 汇总，不在这里压缩。
      // 没有 id 的事件无法记账，但仍照常进 recentAlarms。
      const closed = TERMINAL_STATUSES.includes(evt.status)
      if (evt.alarmType === 'POINT' && evt.id != null) {
        const next = { ...this.activeAlarms }
        if (closed) {
          delete next[evt.id]
        } else {
          next[evt.id] = {
            alarmId: evt.id,
            pointId: evt.pointId,
            level: evt.level,
            status: evt.status,
            at: evt.triggeredAt,
          }
        }
        this.activeAlarms = next
      }
      this.recentAlarms = [
        { ...evt, receivedAt: new Date().toISOString() },
        ...this.recentAlarms,
      ].slice(0, 20)
      return true
    },

    /** 登出：清掉告警态，避免换账号后还留着上一个人的红点 */
    clearAlarms() {
      this.activeAlarms = {}
      this.recentAlarms = []
    },

    /**
     * 切换主测项（大屏顶栏的下拉）。
     * 只认档案里存在的代码——手输一个不存在的测项，界面会全是「暂无数据」，
     * 那种空白最难解释。
     */
    setPrimaryMetric(code) {
      if (this.metricCodes.includes(code)) {
        this.primaryMetricCode = code
      }
    },

    /**
     * 切换当前项目（大屏顶栏的「项目」下拉）。
     *
     * 只影响**读这份 store 的页面**（当前就是 3D 大屏）：其余页面（测点/设备/告警）
     * 各有自己的取数口径，但都被后端按「成员项目」限过范围。要做成全局项目上下文，
     * 得让那些页面也走这份 store——那是后续的事，别在这里假装已经生效。
     */
    async setProject(id) {
      if (!id || id === this.projectId) return
      if (!this.projects.some((p) => p.id === id)) return
      this.projectId = id
      requestState(this).latest += 1
      this.summary = null
      // 清掉上一个项目的最新值：它同时是「SSE 事件可见性」的判据（见 applyAlarm），
      // 留着就会出现「切走之后还认得出旧项目的点」。
      this.latestMap = {}
      await this.loadSnapshot()
    },
  },
})
