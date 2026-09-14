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
  projectSummary,
} from '@/api/monitor'
import { TERMINAL_STATUSES } from '@/utils/labels'
import { warnThresholdOf } from '@/utils/thresholds'

/** 主测项的默认选择：形变是本项目的第一个场景，档案里没有它就退回排序最靠前的测项 */
const DEFAULT_PRIMARY_METRIC = 'defo_mm'

/** latest 里这几项不是测项值，不能当测项读（contract §3） */
const NON_METRIC_KEYS = new Set(['collectTime', 'receiveTime', 'quality', 'signal', 'position', 'state'])

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

/** 把警情列表折成「测点 → 未解除警情」的映射；终态与设备告警不进这张表。 */
function toAlarmMap(records) {
  const map = {}
  for (const a of records || []) {
    if (!a?.pointId) continue
    if (TERMINAL_STATUSES.includes(a.status)) continue
    map[a.pointId] = {
      alarmId: a.id,
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
     * 用途只有一个：SSE 目前**不按订阅者过滤**（A 的 09-14 日志 §120 记为已知未修），
     * 推来的是全库事件。大屏那条告警横幅如果不判可见性，就会出现「别的项目的告警」
     * 挂在屏幕上——项目隔离在界面上被 SSE 绕过去了。
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

    /** 测点 + 最新值 + 场景名，拍平成视图直接能用的结构 */
    enrichedPoints(state) {
      const code = state.primaryMetricCode
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
          // 3D 那圈脉冲环就是靠这两个字段转起来的
          hasAlarm: Boolean(state.activeAlarms[point.id]),
          alarmLevel: state.activeAlarms[point.id]?.level || null,
          alarmStatus: state.activeAlarms[point.id]?.status || null,
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
      this.loading = true
      this.error = null
      this.noProjectReason = ''
      try {
        if (!this.projects.length) {
          this.projects = await listProjects()
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
        }

        const [scenes, objects, points, summary, alarms] = await Promise.all([
          listScenes(),
          listObjects(),
          listPoints(),
          this.projectId ? projectSummary(this.projectId) : Promise.resolve(null),
          // 警情是「非首屏必需」的：失败不该把整个快照判成失败，故单独 catch
          listAlarms({ pageNum: 1, pageSize: 200 }).catch(() => null),
        ])
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
        this.metrics = metrics || []
        this.rules = rules || []
        this.deviceIds = (devices || []).map((d) => d.id)
        this.primaryMetricCode = pickPrimaryMetric(this.metrics, this.primaryMetricCode)

        await this.refreshLatest()
        this.loadedAt = new Date().toISOString()
      } catch (e) {
        // 必须把失败**记下来**，不能只置 loading=false 就走。
        // loadedAt 仍然是空串，而视图那条 `!loadedAt → '加载中…'` 会**永远**成立——
        // 于是「请求失败」被显示成「还在加载」，一个不会自己结束的假象。
        this.error = e?.message || '数据加载失败'
      } finally {
        this.loading = false
      }
    },

    /** 只刷最新值（大屏定时刷新、或 SSE 断线后补齐时用） */
    async refreshLatest() {
      // 只刷**当前项目可见**的测点：别的项目的点既不在画面上，也没有理由占请求
      const visible = this.pointsOfProject
      const results = await Promise.allSettled(visible.map((p) => pointLatest(p.id)))
      const map = { ...this.latestMap }
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          map[visible[index].id] = result.value
        }
      })
      this.latestMap = map
      this.loadedAt = new Date().toISOString()
    },

    /**
     * SSE `measurement` 事件 → 并进对应测点的 latest（单位与字段口径见契约 §6）。
     *
     * 为什么要落回这一份：大屏、侧栏、弹窗、曲线都从 `latestMap` 取数，
     * 推送若各页面各存一份，同一个时刻会出现「3D 上已经跳了、列表里还是旧值」。
     *
     * 不认识的 pointId 直接忽略——SSE 推的是全库，本项目之外的测点不该进快照。
     * @returns {boolean} 是否真的并进了一份数据（调用方据此计数）
     */
    applyMeasurement(evt) {
      const id = evt?.pointId
      if (!id || !this.latestMap[id]) return false
      const { pointId, pointCode, ...rest } = evt
      const prev = this.latestMap[id]
      this.latestMap = {
        ...this.latestMap,
        [id]: {
          ...prev,
          pointId: prev.pointId ?? pointId,
          pointCode: prev.pointCode ?? pointCode,
          // 保留 prev.latest 里的 signal/position 等推送里没有的字段
          latest: { ...(prev.latest || {}), ...rest },
        },
      }
      this.loadedAt = new Date().toISOString()
      return true
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
       * 项目隔离：SSE 不按订阅者过滤（A 的日志 §120），所以这里必须自己把「看不到的」
       * 挡在门外，否则别的项目的告警会以横幅形式挂在大屏上。
       *   - 测点告警：只在**已加载的可见测点**里认（latestMap 就是可见测点的集合）；
       *   - 设备告警：只认**可见设备**（deviceIds 来自 /devices，A 已按成员项目过滤）。
       * 服务端按订阅者过滤才是根治，那是 A 侧的待办；前端这一层是当下不再外溢的保证。
       */
      if (evt.alarmType === 'POINT') {
        if (evt.pointId == null || !this.latestMap[evt.pointId]) return false
      } else if (evt.alarmType === 'DEVICE') {
        if (evt.deviceId == null || !this.deviceIds.includes(evt.deviceId)) return false
      }

      const id = evt.pointId
      const closed = TERMINAL_STATUSES.includes(evt.status)
      if (id) {
        const next = { ...this.activeAlarms }
        if (closed) {
          delete next[id]
        } else {
          next[id] = {
            alarmId: evt.id,
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
      this.summary = null
      // 清掉上一个项目的最新值：它同时是「SSE 事件可见性」的判据（见 applyAlarm），
      // 留着就会出现「切走之后还认得出旧项目的点」。
      this.latestMap = {}
      await this.loadSnapshot()
    },
  },
})
