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
 * 设备状态。**与上面的告警状态是两套枚举，别混**。
 *
 * 四个值都不来自 `device.status` 那一列的存量值——列表与详情端点会用
 * `DeviceStatusPolicy.statusOf` 现算后覆盖（`DeviceController` 里有详尽注释）。
 * 其中 `FAULT` 是**人工在档案里显式标注**的，优先于按上报时间推出来的离线，
 * 而且被标故障的设备**不发离线告警**（`DeviceAlarmMonitor.isOffline` 里 FAULT 直接
 * 返回 false），所以它在告警流里是哑的——只有设备页和运维台会把它露出来。
 * 三者互斥且守恒：`ONLINE + OFFLINE + FAULT = 设备总数`。
 */
export const DEVICE_STATUS_LABELS = {
  ONLINE: '在线',
  OFFLINE: '离线',
  FAULT: '故障',
  UNKNOWN: '未知',
}

export const DEVICE_STATUS_TAG = {
  ONLINE: 'success',
  // 离线用灰不用红：它是常见的「暂时没数据」，真正的硬件故障由 FAULT 标红
  OFFLINE: 'info',
  FAULT: 'danger',
  UNKNOWN: 'warning',
}

/**
 * 可执行的处置动作，按角色显隐。
 * 需求 §5 里四类角色各司其职（值班员确认 / 研判员研判 / 运维员处置）。
 *
 * ⚠️ **这张表只决定按钮显不显示，不是权限边界。** 真正的强制在
 * 后端 `AlarmConstants.ROLE_ACTIONS`（`AlarmService.act` 里校验，越权 403）。
 * 两处是同一张表的副本，改一处必须同步改另一处，否则会出现
 * 「按钮在但点了报 403」或「按钮没了但接口仍放行」。
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
