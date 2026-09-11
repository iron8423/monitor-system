import { defineStore } from 'pinia'

import { listObjects, listPoints, listProjects, listScenes, pointLatest, projectSummary } from '@/api/monitor'

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

    projects: [],
    projectId: null,
    scenes: [],
    objects: [],
    points: [],
    summary: null,

    /** pointId -> PointLatestVO（{ pointId, pointCode, latest, state }） */
    latestMap: {},
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
      try {
        if (!this.projects.length) {
          this.projects = await listProjects()
        }
        if (!this.projectId) {
          this.projectId = this.projects[0]?.id ?? null
        }

        const [scenes, objects, points, summary] = await Promise.all([
          listScenes(),
          listObjects(),
          listPoints(),
          this.projectId ? projectSummary(this.projectId) : Promise.resolve(null),
        ])
        this.scenes = scenes
        this.objects = objects
        this.points = points
        this.summary = summary

        await this.refreshLatest()
        this.loadedAt = new Date().toISOString()
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
  },
})
