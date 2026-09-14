/**
 * 阈值判据（纯函数）。**只服务 3D 着色**：某测点、某测项的「超限」从多少算起。
 *
 * 与曲线上的阈值线是什么关系：
 *   - 曲线上的虚线由 `composables/useThresholds.js` 负责（它要跟组件的 ref/watch 绑在一起，
 *     还要 30s 缓存与「拉不到就不画」的处理）；
 *   - 3D 标点的颜色计算发生在 Pinia store 里，用不了 composable，所以这里留一个**纯函数**。
 *   两边都从 `/alarm-rules` 取数，过滤口径一致：`enabled` / `metricCode` 相符 /
 *   `pointId` 为空（全局）或正是本测点。改动口径时两处都要看。
 *
 * 为什么需要它：3D 那边原来自带一个写死的 `warnThreshold = 3`，与规则是两套口径——
 * 管理员把规则改成 ±5mm 之后，后端按 5mm 报警、界面还按 3mm 变黄，且没人会发现。
 */

/** 某条规则是否适用于这个测点 + 测项 */
function applies(rule, pointId, metricCode) {
  if (!rule || rule.enabled === false) return false
  if ((rule.type || 'THRESHOLD') !== 'THRESHOLD') return false   // RATE/CHANGE 后端也不评估
  if (!rule.metricCode || rule.metricCode !== metricCode) return false
  return rule.pointId == null || rule.pointId === pointId
}

/**
 * 该测点、该测项的**超限判据值**（取绝对值最小的那条阈值）。
 *
 * 取绝对值最小：它是最容易被触发的那一档，也就是「到这个数就该变色了」。
 * 没有任何规则时返回 null —— 此时界面不判超限（宁可不黄，也不要自己发明一个阈值）。
 *
 * @param {Array} rules GET /api/v1/alarm-rules 的裸数组
 * @param {{pointId?: number|null, metricCode?: string}} ctx
 * @returns {number|null}
 */
export function warnThresholdOf(rules, { pointId = null, metricCode = '' } = {}) {
  let min = Number.POSITIVE_INFINITY
  for (const rule of rules || []) {
    if (!applies(rule, pointId, metricCode)) continue
    const value = Math.abs(Number(rule.value))
    if (Number.isFinite(value) && value < min) min = value
  }
  return Number.isFinite(min) ? min : null
}
