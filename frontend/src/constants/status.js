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

/** 测项 */
export const METRIC = {
  defo_mm: { label: '累计形变', unit: 'mm' },
  rate_mm_d: { label: '形变速率', unit: 'mm/d' },
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

/** 3D 场景与列表共用的"正常"色（绿） */
const NORMAL_COLOR = '#35b37e'

/**
 * 决定一个测点在界面上显示成什么颜色 —— **这是全局唯一的口径**。
 *
 * 为什么要有优先级：一个点可能同时"超限了 + 数据可疑 + 已经失联"，
 * 不定义优先级就会出现 3D 上是红的、列表里是黄的这种自相矛盾。
 *
 * 优先级：未解除告警 > 数据质量(FAULT/SUSPECT) > 目标状态(失联/可疑) > 数值超限 > 正常
 *
 * @param {{quality?:string, state?:string, defoMm?:number, hasAlarm?:boolean, alarmLevel?:string, warnThreshold?:number}} input
 */
export function resolvePointVisual(input = {}) {
  const { quality, state, defoMm, hasAlarm, alarmLevel, warnThreshold = 3 } = input

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
  const value = Number(defoMm)
  if (Number.isFinite(value) && Math.abs(value) >= warnThreshold) {
    return { key: 'over-threshold', label: '超限', color: ALARM_LEVEL.warning.color }
  }
  if (defoMm === null || defoMm === undefined) {
    return { key: 'no-data', label: '暂无数据', color: '#c0c4cc' }
  }
  return { key: 'normal', label: '正常', color: NORMAL_COLOR }
}
