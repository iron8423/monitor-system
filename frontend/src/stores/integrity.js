import { defineStore } from 'pinia'

import {
  HASH_ERROR,
  HASH_MISMATCH,
  isHashProblem,
  verifyAssetHash,
} from '@/utils/assetHash'

/**
 * 数字孪生资产的核对结果（复查清单 P0-5）。
 *
 * 为什么要一个 store 而不是各页面各存一份：同一份资产会在两处被看——
 * 大屏加载模型时顺手核对（用户视角：这个模型是不是真的），
 * 运维页手动触发一次全量核对（管理员视角：整套系统里有没有哪份资产被动过）。
 * 两处结果必须同源，否则会出现"大屏报红、运维页说一切正常"。
 *
 * 结果按项目 id 存，**只保存在内存里**：它是"这台浏览器在这次会话里看到的事实"，
 * 刷新页面后重新核对即可。做成持久化反而会让人对着一份几小时前的结论下判断。
 */
export const useIntegrityStore = defineStore('integrity', {
  state: () => ({
    /** projectId -> 核对结果（见 utils/assetHash.js 的形状） */
    byProject: {},
    /** 正在核对的 projectId 集合（大屏 + 运维页可能同时在跑） */
    running: {},
  }),

  getters: {
    /** 已经核对出结论的那些结果，按状态排一下：有问题的一律排在最前。 */
    results(state) {
      const rows = Object.values(state.byProject)
      return rows.sort((a, b) => {
        const ra = isHashProblem(a.status) ? 0 : 1
        const rb = isHashProblem(b.status) ? 0 : 1
        if (ra !== rb) return ra - rb
        return String(a.checkedAt).localeCompare(String(b.checkedAt))
      })
    },

    /** 需要人处理的结果（不一致 / 读不到文件）。两者都不是"资产没问题"。 */
    problems() {
      return this.results.filter((r) => isHashProblem(r.status))
    },

    mismatches() {
      return this.results.filter((r) => r.status === HASH_MISMATCH)
    },

    errors() {
      return this.results.filter((r) => r.status === HASH_ERROR)
    },

    checking: (state) => Object.values(state.running).some(Boolean),
  },

  actions: {
    /**
     * 核对一个场景配置并记下结果。
     *
     * @param {object} config `/v1/projects/{id}/digital-twin` 的响应
     * @returns {Promise<object>} 本次结果（调用方通常直接用返回值，不必再读 state）
     */
    async verify(config) {
      const projectId = config?.projectId
      if (projectId == null) return null
      this.running[projectId] = true
      try {
        const result = await verifyAssetHash(config)
        this.byProject[projectId] = result
        return result
      } finally {
        this.running[projectId] = false
      }
    },

    /** 一批场景（运维页「全部核对」）。串行即可：它跟的是人工点击的节奏，不是首屏。 */
    async verifyAll(configs) {
      for (const cfg of configs || []) {
        // eslint-disable-next-line no-await-in-loop -- 故意串行：一次点全量核对时不要把链路打满
        await this.verify(cfg)
      }
      return this.results
    },

    resultOf(projectId) {
      return this.byProject[projectId] || null
    },

    clear() {
      this.byProject = {}
    },
  },
})
