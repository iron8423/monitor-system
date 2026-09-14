import { computed, ref, toValue, watch } from 'vue'

import * as api from '@/api/monitor'
import { LEVEL_LABELS } from '@/utils/labels'

/**
 * 曲线阈值线：把 `/alarm-rules` 里生效的规则翻译成 ECharts markLine 的 data。
 *
 * **为什么要有这个 composable**：这段口径原先在 `PointsView.vue` 与
 * `HomeAnalyst.vue` 里各抄了一份，两份都写死 ±3mm 并标成「告警 +3mm」。
 * V4 补了 `defo_mm gte +5.0 / level=alarm` 之后，真正的「报警」档是 +5，
 * 于是两张图**同时**变成了错的——既把 warning 档（+3）标成了「告警」，
 * 又整个漏掉了 +5 那条线。抄两份的代价就是这样：改一处、漏一处，且没人会发现。
 * 现在只有这一份，两个页面共用。
 *
 * 三条过滤规则（少一条都会画出误导性的线）：
 *   - `enabled`：停用的规则不该出现在图上
 *   - `metricCode` 相符：**把 mm 的阈值画到 mm/d 的图上是最典型的误读**，
 *     两个测项量纲不同，3mm 和 3mm/d 完全不是一回事
 *   - `pointId` 为空（全局）或正等于本测点：别人的**测点级**规则不该影响本图。
 *     没选测点时 `pid` 为 null，此时只画全局规则，测点级的一律滤掉
 */

/**
 * 等级 → 线色。用等级定色而不是每条规则自己挑色，
 * 是为了让「同一张图上的线谁更严重」不依赖读文字。
 * 取值与 Element Plus 的 warning/danger 同色系，和告警中心的标签对得上。
 */
const LEVEL_COLORS = {
  notice: '#909399',
  warning: '#e6a23c',
  alarm: '#f56c6c',
}

/**
 * 规则列表的共享缓存。
 *
 * 两个页面会各自调一次；规则是管理员偶尔才改的低频数据，没有理由每开一个页面就拉一遍。
 * 但**不能永久缓存**——管理员刚改完阈值，用户切回曲线页应当能看到新线。
 * 所以给一个短 TTL，而不是 module 级常量缓存。
 */
const CACHE_TTL_MS = 30_000
let cache = null
let cachedAt = 0
let inflight = null

function fetchRules() {
  if (cache && Date.now() - cachedAt < CACHE_TTL_MS) {
    return Promise.resolve(cache)
  }
  // 同一时刻两个组件都在拉时共用一个请求，否则会打两次（切页面时很常见）
  if (inflight) return inflight

  inflight = api
    .listAlarmRules()
    .then((rules) => {
      cache = rules || []
      cachedAt = Date.now()
      return cache
    })
    .finally(() => {
      inflight = null
    })
  return inflight
}

/** 仅供测试/退出登录时清缓存用；正常渲染路径不需要调 */
export function clearThresholdCache() {
  cache = null
  cachedAt = 0
}

/**
 * @param {string|import('vue').Ref<string>} metricCode 当前展示的测项
 * @param {number|import('vue').Ref<number|null>} pointId 当前测点（无则不画测点级规则）
 * @returns {{thresholds: import('vue').ComputedRef<Array>, failed: import('vue').Ref<boolean>}}
 */
export function useThresholds(metricCode, pointId) {
  const rules = ref([])
  const failed = ref(false)

  async function load() {
    try {
      rules.value = await fetchRules()
      failed.value = false
    } catch {
      // 拉不到就不画线：**画错的线比没有线更糟**，用户会据此判断超没超限。
      // 置 failed 让调用方能提示一句，而不是静默变成「没有阈值」。
      rules.value = []
      failed.value = true
    }
  }

  watch(() => toValue(metricCode), load, { immediate: true })

  const thresholds = computed(() => {
    const mc = toValue(metricCode)
    if (!mc) return []
    const pid = toValue(pointId)

    return rules.value
      .filter((r) => r.enabled !== false)
      .filter((r) => (r.type || 'THRESHOLD') === 'THRESHOLD')
      .filter((r) => r.metricCode === mc)
      .filter((r) => r.pointId == null || r.pointId === pid)
      .map((r) => {
        const value = Number(r.value)
        return {
          // 用契约自己的等级枚举（labels.js 的 LEVEL_LABELS）而不是另起一个名字——
          // 之前那句写死的「告警 +3mm」正是问题所在：+3 是 warning，报警档在 +5。
          // 规则全名在管理端「告警规则」页签里，图上留短标签免得吃掉绘图区。
          label: `${LEVEL_LABELS[r.level] || r.level} ${value > 0 ? '+' : ''}${value}`,
          value,
          color: LEVEL_COLORS[r.level] || LEVEL_COLORS.warning,
        }
      })
      // 同值多条规则（比如全局与本测点各配了一条 +3）只画一条，否则线会叠在一起变粗
      .filter((t, i, arr) => arr.findIndex((x) => x.value === t.value) === i)
  })

  return { thresholds, failed }
}
