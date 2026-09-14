import { defineStore } from 'pinia'

import { listAlarms, listObjects, listPoints, listProjects, listScenes, pointLatest, projectSummary } from '@/api/monitor'
import { TERMINAL_STATUSES } from '@/utils/labels'

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

    projects: [],
    projectId: null,
    scenes: [],
    objects: [],
    points: [],
    summary: null,

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

    sceneNameOf: (state) => (point) => {
      const object = state.objects.find((o) => o.id === point?.objectId)
      if (!object) return '—'
      return state.scenes.find((s) => s.id === object.sceneId)?.name || '—'
    },

    /** 测点 + 最新值 + 场景名，拍平成视图直接能用的结构 */
    enrichedPoints(state) {
      return state.points.map((point) => {
        const vo = state.latestMap[point.id]
        const latest = vo?.latest || null
        return {
          ...point,
          sceneName: this.sceneNameOf(point),
          hasData: Boolean(latest),
          collectTime: latest?.collectTime || null,
          defoMm: latest?.defo_mm ?? null,
          rateMmD: latest?.rate_mm_d ?? null,
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
      try {
        if (!this.projects.length) {
          this.projects = await listProjects()
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
      const results = await Promise.allSettled(this.points.map((p) => pointLatest(p.id)))
      const map = { ...this.latestMap }
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          map[this.points[index].id] = result.value
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
  },
})
