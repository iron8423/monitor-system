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
 * GET /api/v1/alarm-rules → AlarmRuleVO[]（**裸数组**，不分页）。
 * 曲线上的阈值线由它算出来（原来写死 ±3mm），管理端改规则后刷新即可见。
 */
export function listAlarmRules() {
  return http.get('/v1/alarm-rules')
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
 * 订阅实时推送（SSE）。EventSource 带不了请求头，所以 token 走 query——
 * 这是契约 D8 专门为它开的唯一例外。
 * @returns {EventSource} 调用方负责 close()，否则连接会一直挂着
 */
export function openStream(token) {
  return new EventSource(`/api/v1/stream?token=${encodeURIComponent(token)}`)
}

/**
 * POST /api/v1/media（multipart：file、pointId、takenAt、note）→ MediaUploadVO。
 *
 * 注意**不要**手写 `Content-Type: multipart/form-data`：浏览器要自己往里面补
 * boundary，手写了反而会把 boundary 写死成空、后端解析不到文件。
 * 交给 axios 认 FormData 自动设置即可。
 */
export function uploadMedia(file, { pointId, takenAt, note } = {}) {
  const form = new FormData()
  form.append('file', file)
  form.append('pointId', pointId)
  if (takenAt) form.append('takenAt', takenAt)
  if (note) form.append('note', note)
  return http.post('/v1/media', form)
}

/** GET /api/v1/points/{pointId}/media → MediaVO[]（非分页，裸数组） */
export function pointMedia(pointId) {
  return http.get(`/v1/points/${pointId}/media`)
}

/**
 * 影像内容地址。<img>/背景图带不了 Authorization 头，故走 `?token=`
 * —— 与 SSE 同一条例外（契约 §7、`06-media.sh` ⑤ 有断言）。
 */
export function mediaContentUrl(mediaId, token) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return `${base}/v1/media/${encodeURIComponent(mediaId)}/content?token=${encodeURIComponent(token || '')}`
}
