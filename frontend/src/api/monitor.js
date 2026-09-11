import http from './http'

/**
 * 监测数据接口（《B侧接口契约_M0》§3）。
 *
 * 注意返回形状的两处不一致，都是契约有意为之，别当成 bug：
 *   - `GET /points` 是**裸数组**（非分页列表风格，同 `GET /projects`）
 *   - `GET /alarms` 是**分页** `{total, pageNum, pageSize, records}`
 * 前端因此只需要认这两种形状，不必每种列表都写一套。
 */

/** GET /api/v1/points → MonitorPoint[]（裸数组） */
export function listPoints() {
  return http.get('/v1/points')
}

/** GET /api/v1/points/{id}/latest → { pointId, pointCode, latest, state } */
export function pointLatest(pointId) {
  return http.get(`/v1/points/${pointId}/latest`)
}

/**
 * GET /api/v1/points/{id}/series → { pointId, pointCode, metricCode, unit, points:[{t,v}] }
 * @param {object} p
 * @param {string} [p.metricCode] defo_mm | rate_mm_d
 * @param {string} [p.from] ISO8601
 * @param {string} [p.to]   ISO8601
 * @param {string} [p.granularity] raw | hour | day
 */
export function pointSeries(pointId, params = {}) {
  return http.get(`/v1/points/${pointId}/series`, { params })
}

/** GET /api/v1/projects/{projectId}/summary → { pointCount, alertCount, onlineDeviceCount, maxDeformationMm } */
export function projectSummary(projectId) {
  return http.get(`/v1/projects/${projectId}/summary`)
}

/** GET /api/v1/projects → Project[]（裸数组） */
export function listProjects() {
  return http.get('/v1/projects')
}

/** GET /api/v1/scenes → Scene[]（裸数组）。3D 大屏按场景给测点分组用 */
export function listScenes() {
  return http.get('/v1/scenes')
}

/** GET /api/v1/objects → MonitorObject[]（裸数组）。测点 → 对象 → 场景 的归属链 */
export function listObjects() {
  return http.get('/v1/objects')
}

/** GET /api/v1/alarms → PageResult<Alarm> */
export function listAlarms(params = {}) {
  return http.get('/v1/alarms', { params })
}

/** GET /api/v1/alarms/{id} → { ...快照, timeline: [{ time, action, operator, comment }] } */
export function alarmDetail(id) {
  return http.get(`/v1/alarms/${id}`)
}

/**
 * POST /api/v1/alarms/{id}/actions
 * @param {string} action confirm | research | dispatch | handle | resolve | misreport
 */
export function alarmAction(id, action, comment) {
  return http.post(`/v1/alarms/${id}/actions`, { action, comment })
}

/** GET /api/v1/devices → Device[]（裸数组） */
export function listDevices() {
  return http.get('/v1/devices')
}

/**
 * GET /api/v1/devices/{id}/status → { status, online, lowBattery, lastReportTime }
 *
 * 这里曾经写着「列表端点返回的是档案表里存的 status，与这个是两回事」——**那句已经不是真的了**。
 * `DeviceController` 覆盖了列表与详情，两个端点都会用 `DeviceStatusPolicy.statusOf` 现算后
 * 覆盖 status，口径已经统一。所以取一台设备的状态**不必**调这个端点，`listDevices()` 里就有。
 *
 * 它比列表多给 `online` / `lowBattery` 两个布尔（列表只有推导后的 status 串）。
 * **当前全前端没有调用点**——留着是因为后端端点确实存在（契约里有），
 * 需要单台设备的布尔量时可直接用；不需要的话下次清理时删掉。
 */
export function deviceStatus(id) {
  return http.get(`/v1/devices/${id}/status`)
}

/**
 * 订阅实时推送（SSE）。EventSource 带不了请求头，所以 token 走 query——
 * 这是契约 D8 专门为它开的唯一例外。
 * @returns {EventSource} 调用方负责 close()，否则连接会一直挂着
 */
export function openStream(token) {
  return new EventSource(`/api/v1/stream?token=${encodeURIComponent(token)}`)
}
