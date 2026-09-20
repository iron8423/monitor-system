/**
 * 地面热力层的标度（2026-09-20 重做）。
 *
 * ## 先说清它是什么
 *
 * 平台上每个测点只有一个数（`defo_mm` 或 `rate_mm_d`），没有面状采样。所以这一层
 * **不是**由数据插值出来的连续场，而是「每个测点一片半透明晕圈」的叠加——晕圈的
 * 颜色表示测值大小、半径表示相对大小的示意。图上必须让人分得出这两者：
 * 颜色是**读数**（有量纲、有刻度），半径是**视觉强调**（没有物理含义，不是影响半径）。
 *
 * ## 上一版为什么"看不出来"
 *
 * 旧实现有两处让差异消失：
 *   ① 颜色取的是**状态色**（正常=绿）。演示场景里测点普遍正常，于是所有圈同一个绿；
 *   ② 半径 = 12m + |值| × 每单位米数，而形变速率量级只有 ±1mm/d → 全部贴在下限 12m 上，
 *      大小几乎一样，在山体（几百米尺度）上就是几个小点。
 *
 * ## 现在的口径
 *
 *   · 颜色：**发散色标**（负值冷色 → 0 中性 → 正值暖色），刻度 = 当前测点集合的最大绝对值，
 *     所以"哪一片形变大"看颜色就能读，与告警状态无关（状态仍由测点符号表达）；
 *   · 半径：把 |值| 归一化到 [0,1] 再映射到 [24m, 90m]，**相对大小一定看得见**；
 *     值为 0 不画（"没有形变"不该有热力）；
 *   · 图例里给出刻度两端与单位，并写明"半径是示意、不是实测影响范围"。
 *
 * 这两个函数是纯函数，selfcheck 里有断言（颜色单调、半径为 0 的边界、归一化后的相对关系）。
 */

/** 半径范围（米）。下限保证在小比例尺下也看得见，上限防止一个大值糊满屏。 */
export const HEAT_RADIUS_MIN = 24
export const HEAT_RADIUS_MAX = 90

/**
 * 发散色标锚点：值 / 最大绝对值 ∈ [-1, 1]。
 * 负值走冷色（蓝）、正值走暖色（橙红）、0 附近接近中性灰——形变的正负是物理含义，不能丢。
 */
export const HEAT_RAMP_STOPS = [
  { at: -1, color: '#2b6cb0' },
  { at: -0.5, color: '#63b3ed' },
  { at: 0, color: '#e8eef5' },
  { at: 0.5, color: '#f6ad55' },
  { at: 1, color: '#e53e3e' },
]

function toHex(rgb) {
  return `#${rgb.map((n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0')).join('')}`
}

function parseHex(hex) {
  const s = hex.replace('#', '')
  return [parseInt(s.slice(0, 2), 16), parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16)]
}

/**
 * 当前测点集合的最大绝对值（热力标度的上界）。
 * 只认有限数值：`null`/`NaN` 是"没有读数"，不能当成 0 参与比较（否则会把刻度压扁）。
 */
export function heatExtentOf(items) {
  let max = 0
  for (const item of items || []) {
    const v = Math.abs(Number(item?.value))
    if (Number.isFinite(v) && v > max) max = v
  }
  return max
}

/**
 * 值 → 颜色。`max <= 0`（没有任何有效读数）时返回中性色——调用方本来也不会画。
 */
export function heatColorOf(value, max) {
  const v = Number(value)
  const m = Math.abs(Number(max))
  if (!Number.isFinite(v) || !Number.isFinite(m) || m <= 0) return HEAT_RAMP_STOPS[2].color
  const ratio = Math.max(-1, Math.min(1, v / m))
  for (let i = 1; i < HEAT_RAMP_STOPS.length; i += 1) {
    const prev = HEAT_RAMP_STOPS[i - 1]
    const next = HEAT_RAMP_STOPS[i]
    if (ratio <= next.at) {
      const t = (ratio - prev.at) / (next.at - prev.at)
      const a = parseHex(prev.color)
      const b = parseHex(next.color)
      return toHex(a.map((n, k) => n + (b[k] - n) * t))
    }
  }
  return HEAT_RAMP_STOPS[HEAT_RAMP_STOPS.length - 1].color
}

/**
 * 值 → 晕圈半径（米）。
 *   · 无值 / 非有限 / 恰为 0 → 0（调用方据此不画：没有形变就没有热力）；
 *   · 否则按 |值| / 集合最大绝对值 归一化后映射到 [MIN, MAX]。
 */
export function heatRadiusOf(value, max) {
  const v = Math.abs(Number(value))
  const m = Math.abs(Number(max))
  if (!Number.isFinite(v) || v === 0) return 0
  if (!Number.isFinite(m) || m <= 0) return 0
  const ratio = Math.min(1, v / m)
  return HEAT_RADIUS_MIN + ratio * (HEAT_RADIUS_MAX - HEAT_RADIUS_MIN)
}

/**
 * 值 → **量化后**的颜色：贴图按颜色缓存，而连续色标会为每个测点生成一张 128×128 的画布
 * （1000 点项目 = 1000 张小画布，纯属浪费）。量化成 `steps` 档后缓存长度有上界，
 * 而 12 档在屏幕上已经看不出台阶。图例仍用 {@link heatColorOf}（不分档）。
 */
export function heatColorBucketOf(value, max, steps = 12) {
  const v = Number(value)
  const m = Math.abs(Number(max))
  if (!Number.isFinite(v) || !Number.isFinite(m) || m <= 0) return HEAT_RAMP_STOPS[2].color
  const ratio = Math.max(-1, Math.min(1, v / m))
  const bucketed = Math.round(ratio * steps) / steps
  return heatColorOf(bucketed * m, m)
}
