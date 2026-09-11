/**
 * 枚举 → 中文展示文案。
 *
 * 契约 §4 明确：后端返回的 `lastAction`、`status`、`level` 都是**原始枚举串**，
 * 「不是中文标签——前端自行映射展示文案」。所以映射表只能在前端，
 * 集中放这里而不是散在各个组件里，否则同一个 `OBSERVING` 在两处会译成两个词。
 */

export const LEVEL_LABELS = {
  notice: '提示',
  warning: '预警',
  alarm: '报警',
}

/** Element Plus 的 tag type —— 等级越高越红，一眼能扫出来 */
export const LEVEL_TAG = {
  notice: 'info',
  warning: 'warning',
  alarm: 'danger',
}

export const STATUS_LABELS = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  OBSERVING: '研判中',
  PROCESSING: '处置中',
  RESOLVED: '已解除',
  FALSE_ALARM: '误报',
}

export const STATUS_TAG = {
  PENDING: 'danger',
  CONFIRMED: 'warning',
  OBSERVING: 'warning',
  PROCESSING: 'primary',
  RESOLVED: 'success',
  FALSE_ALARM: 'info',
}

/** 终态：不再受理处置动作（后端会回 400，前端据此把按钮藏掉） */
export const TERMINAL_STATUSES = ['RESOLVED', 'FALSE_ALARM']

export const ACTION_LABELS = {
  trigger: '触发',
  recover: '自动恢复',
  escalate: '等级升级',
  confirm: '确认',
  research: '研判',
  dispatch: '派单',
  handle: '处置',
  resolve: '解除',
  misreport: '标记误报',
}

export const ALARM_TYPE_LABELS = {
  POINT: '测点',
  DEVICE: '设备',
}

/**
 * 可执行的处置动作，按角色显隐。
 * 这是演示口径：需求 §5 里四类角色各司其职（值班员确认 / 研判员研判 /
 * 运维员处置），但后端 `@PreAuthorize` 目前**不限制动作与角色的对应**，
 * 所以这只是界面上的引导，不是权限边界——真正的边界要做需在后端补。
 */
export const ACTIONS_BY_ROLE = {
  ADMIN: ['confirm', 'research', 'dispatch', 'handle', 'resolve', 'misreport'],
  OPERATOR: ['confirm', 'dispatch', 'handle', 'resolve', 'misreport'],
  ANALYST: ['research', 'resolve', 'misreport'],
  MAINTAINER: ['handle', 'resolve', 'misreport'],
}

export function label(map, key) {
  if (!key) return '—'
  return map[key] || key
}
