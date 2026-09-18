/**
 * series 取数的粒度口径（P0-3 的前端侧）。
 *
 * 后端从 2026-09-18 起对 `raw` 有**硬上限**：单次最多 5000 点，超过直接 400，
 * 并且**不截断**（截断过的曲线看起来和真的一样，这是形变监测里最不能出的错）。
 * 而本仓的生产采样基线是 5 秒一个点 = 每小时 720 行，于是 raw 只装得下约 6 小时。
 *
 * 这条约束必须在前端也成立一次，理由很实际：前端若继续"24 小时以内都取 raw"，
 * 生产节奏下接口必然 400，而各页的 catch 一律把曲线置空——用户看到的是
 * 「这个点没有数据」，真正的原因却是「你问错了粒度」。那不是数据问题，是口径问题。
 *
 * 所以三个取数点（测点详情页 / 研判工作台 / 大屏回放）都从这里拿结论：
 * 窗口 ≤ 6 小时用 raw，更长的一律走 hour/day 聚合。
 */

/** 与后端 `SeriesWindowPolicy.MAX_RAW_POINTS` 对齐；改一处必须改两处（有 selfcheck 绊线）。 */
export const MAX_RAW_POINTS = 5000

/** 最坏的采样密度：生产基线 5 秒/点。**估的就是最坏情况**——估少了就会崩。 */
export const RAW_ROWS_PER_HOUR = 720

/** raw 能安全覆盖的最大窗口（小时）。6h × 720 = 4320 ≤ 5000，7h 就超了。 */
export const RAW_SAFE_WINDOW_HOURS = Math.floor(MAX_RAW_POINTS / RAW_ROWS_PER_HOUR)

/** 这个窗口在最坏采样密度下装得进 raw 吗。 */
export function rawFitsInLimit(rangeHours) {
  const hours = Math.max(0, Number(rangeHours) || 0)
  return hours * RAW_ROWS_PER_HOUR <= MAX_RAW_POINTS
}

/** 按窗口自动选粒度：装得下用 raw，装不下退到 hour（更长的窗口由调用方再退到 day）。 */
export function autoGranularity(rangeHours, fallback = 'hour') {
  return rawFitsInLimit(rangeHours) ? 'raw' : fallback
}
