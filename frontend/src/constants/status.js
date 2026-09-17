/**
 * 枚举展示口径（《B侧接口契约_M0》§8 D5 冻结）。
 * 集中放一处：总览表、曲线页、以及阶段 3 的 Cesium 标点着色都读这里，
 * 避免同一套枚举在三个页面各写一份、改一处漏两处。
 */

/** 测项目标状态 state */
export const POINT_STATE = {
  normal: { label: '正常', type: 'success', color: '#35b37e' },
  suspicious: { label: '可疑', type: 'warning', color: '#e6a23c' },
  disappeared: { label: '失联', type: 'danger', color: '#f56c6c' },
}

/** 数据质量 quality */
export const QUALITY = {
  RAW: { label: '原始', type: 'info', color: '#909399' },
  VALID: { label: '有效', type: 'success', color: '#35b37e' },
  SUSPECT: { label: '可疑', type: 'warning', color: '#e6a23c' },
  FAULT: { label: '异常', type: 'danger', color: '#f56c6c' },
}

/** 告警级别（notice 注意 / warning 预警 / alarm 告警） */
export const ALARM_LEVEL = {
  notice: { label: '注意', type: 'info', color: '#909399' },
  warning: { label: '预警', type: 'warning', color: '#e6a23c' },
  alarm: { label: '告警', type: 'danger', color: '#f56c6c' },
}

/** 警情状态（阶段 4 用） */
export const ALARM_STATUS = {
  PENDING: { label: '待确认', type: 'danger' },
  CONFIRMED: { label: '已确认', type: 'warning' },
  PROCESSING: { label: '处置中', type: 'warning' },
  OBSERVING: { label: '观察中', type: 'info' },
  RESOLVED: { label: '已解除', type: 'success' },
  FALSE_ALARM: { label: '误报', type: 'info' },
}

/**
 * 数据新鲜度（清单第 19 条：回放体现数据中断）。
 *
 * 前四组常量都是「服务端判定的状态」，这一组**是前端按时间自己推的**——阈值怎么来、
 * 为什么是 5 分钟，见 `utils/timeline.js#carryMsOf`。新鲜不是一种颜色：只有过期需要说出来。
 */
export const FRESHNESS = {
  stale: { label: '数据过期', color: '#a3acbb' },
}

export function pointStateOf(state) {
  return POINT_STATE[state] || { label: '未知', type: 'info', color: '#c0c4cc' }
}

export function qualityOf(quality) {
  return QUALITY[quality] || { label: quality || '—', type: 'info', color: '#c0c4cc' }
}

export function alarmLevelOf(level) {
  return ALARM_LEVEL[level] || { label: level || '—', type: 'info', color: '#c0c4cc' }
}

/**
 * 等级强弱序号（notice < warning < alarm），用于同一测点多条警情时挑出最高的那条。
 *
 * 与后端 `AlarmConstants.rankOf` 同一份顺序、同一条兜底：未知/null 返回 -1（最低），
 * 这样一条等级写错的警情不会把同点另一条真警情挤掉。两处顺序一旦不一致，
 * 「这个点该显示成什么等级」在前后端就会给出两个答案。
 */
const ALARM_LEVEL_ORDER = ['notice', 'warning', 'alarm']

export function alarmRankOf(level) {
  if (level == null) return -1
  const i = ALARM_LEVEL_ORDER.indexOf(String(level).trim().toLowerCase())
  return i < 0 ? -1 : i
}

/** 3D 场景与列表共用的"正常"色（绿） */
const NORMAL_COLOR = '#35b37e'

/**
 * 决定一个测点在界面上显示成什么颜色 —— **这是全局唯一的口径**。
 *
 * 为什么要有优先级：一个点可能同时"超限了 + 数据可疑 + 已经失联"，
 * 不定义优先级就会出现 3D 上是红的、列表里是黄的这种自相矛盾。
 *
 * 优先级：未解除告警 > 数据质量(FAULT/SUSPECT) > 目标状态(失联/可疑) > **数据过期** > 数值超限 > 正常
 *
 * 「数据过期」排在这个位置不是随手放的（清单第 19 条）：前四档都是**服务端判定的既成事实**
 * （告警是靠 `visual.key === 'alarm'` 才转的脉冲环、质量与目标状态是后端下的结论），
 * 而「过期」是前端按时间推出来的推断——推断不该盖过事实。排在「数值超限」之前，
 * 是因为一个过期值再去比阈值没有意义（3 小时前的读数超没超限，不说明现在）。
 *
 * **与具体测项无关**：传进来的是「当前主测项的值」（`value`）和它的 `unit`，
 * 不再是写死的 `defoMm`。加一种新测项时，这里一个字都不用改。
 *
 * `threshold` 是超限判据，**必须由调用方给**（来自 `/alarm-rules`，见
 * `utils/thresholds.js#warnThresholdOf`）。这里刻意**没有默认值**：以前自带一个
 * `warnThreshold = 3`，结果规则改成 ±5mm 之后后端按 5mm 报警、界面还按 3mm 变黄，
 * 同一件事两套口径。没有规则就不判超限——宁可不黄，也不要自己发明一个阈值。
 *
 * @param {{quality?:string, state?:string, value?:number|null, unit?:string,
 *          hasAlarm?:boolean, alarmLevel?:string, threshold?:number|null,
 *          stale?:boolean}} input
 *        `stale` 由 `utils/timeline.js#buildFrames` 算好传进来（**不要在这里再算一次**，
 *        否则回放帧与点图层会各判一次，同一个值两个颜色）
 */
export function resolvePointVisual(input = {}) {
  const { quality, state, value, hasAlarm, alarmLevel, threshold } = input

  if (hasAlarm) {
    const level = alarmLevelOf(alarmLevel)
    return { key: 'alarm', label: `${level.label}中`, color: level.color }
  }
  if (quality === 'FAULT') {
    return { key: 'fault', label: '数据异常', color: QUALITY.FAULT.color }
  }
  if (quality === 'SUSPECT') {
    return { key: 'suspect', label: '数据可疑', color: QUALITY.SUSPECT.color }
  }
  if (state === 'disappeared') {
    return { key: 'disappeared', label: '目标失联', color: '#8a94a6' }
  }
  if (state === 'suspicious') {
    return { key: 'suspicious', label: '目标可疑', color: POINT_STATE.suspicious.color }
  }
  if (value === null || value === undefined) {
    return { key: 'no-data', label: '暂无数据', color: '#c0c4cc' }
  }
  const num = Number(value)
  if (!Number.isFinite(num)) {
    return { key: 'no-data', label: '暂无数据', color: '#c0c4cc' }
  }
  // 数据过期（清单第 19 条）。**必须排在 value === null 之后**：退化输入
  // `{stale: true, value: null}`（正常路径不会产生）应当答「暂无数据」而不是「数据过期」
  // ——一个从来没有过读数的点不叫过期。别把这四行往上挪。
  if (input.stale === true) {
    return { key: 'stale', label: FRESHNESS.stale.label, color: FRESHNESS.stale.color }
  }
  const limit = Number(threshold)
  if (threshold !== null && threshold !== undefined && Number.isFinite(limit) && Math.abs(num) >= limit) {
    return { key: 'over-threshold', label: '超限', color: ALARM_LEVEL.warning.color }
  }
  return { key: 'normal', label: '正常', color: NORMAL_COLOR }
}
