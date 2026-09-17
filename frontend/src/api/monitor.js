import http from './http'
import { getToken } from '@/utils/token'

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

/**
 * GET /api/v1/projects/{projectId}/points/latest → PointLatestVO[]。
 * 大屏按项目一次取齐，避免 1000 个测点产生 1000 个 HTTP 请求。
 */
export function projectPointsLatest(projectId) {
  return http.get(`/v1/projects/${projectId}/points/latest`)
}

/** 每个项目独立的数字孪生资产、地理配准、性能预算与雷达姿态。 */
export function projectDigitalTwin(projectId) {
  return http.get(`/v1/projects/${projectId}/digital-twin`)
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

/**
 * GET /api/v1/alarm-rules → AlarmRuleVO[]（裸数组，不分页）
 *
 * 读接口开放（`AlarmRuleController` 只在写上加 `@PreAuthorize`），所以画曲线的页面
 * 任何角色都能拉到阈值。字段名是**对外口径**：`type`/`value`/`level`，
 * 不是库里的 `rule_type`/`threshold_value`/`alarm_level`（`AlarmRuleService#toVO` 转过一道）。
 */
export function listAlarmRules() {
  return http.get('/v1/alarm-rules')
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
 * GET /api/v1/metrics → Metric[]（裸数组）：`{ id, pointId, code, name, unit, sortOrder }`。
 *
 * **「有哪些测项」的唯一来源。** 业务页面原先把 defo_mm / rate_mm_d 写死在代码里，
 * 于是加一种测项要改一堆前端文件；测项的**名称与单位**也从这里取，
 * 界面里不要再自己拼「累计形变(mm)」这种字符串。
 */
export function listMetrics() {
  return http.get('/v1/metrics')
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
 * GET /api/v1/devices/{id}/points → DevicePoint[]（裸数组，每项 `{ id, deviceId, pointId }`）
 *
 * ⚠️ 返回的是**绑定关系行**，不是测点对象——只有 `pointId`，没有点号/点名的。
 * 界面上要显示「P-HK01 边坡位移」得自己拿 `listPoints()` 的结果去 join。
 * 后端没做联表返回（`DevicePoint` 是纯关系表），所以这一步在前端。
 */
export function listDevicePoints(deviceId) {
  return http.get(`/v1/devices/${deviceId}/points`)
}

/** POST /api/v1/devices/{id}/points/{pointId} → 空。已绑定则 400（后端显式判重）。角色 ADMIN/MAINTAINER */
export function bindDevicePoint(deviceId, pointId) {
  return http.post(`/v1/devices/${deviceId}/points/${pointId}`)
}

/** PUT 标定关系；只有通过量程/水平/垂直视场校验后才会成为 ACTIVE。 */
export function calibrateDevicePoint(deviceId, pointId, payload) {
  return http.put(`/v1/devices/${deviceId}/points/${pointId}/calibration`, payload)
}

/** DELETE /api/v1/devices/{id}/points/{pointId} → 空。**幂等**：没绑过也返回 200，不是 404。角色 ADMIN/MAINTAINER */
export function unbindDevicePoint(deviceId, pointId) {
  return http.delete(`/v1/devices/${deviceId}/points/${pointId}`)
}

/**
 * DELETE /api/v1/devices/{id}/points/{pointId}/calibration → DevicePoint。
 * **停用标定**：保留绑定关系与标定参数，只把状态置为 INVALID。角色 ADMIN/MAINTAINER，幂等。
 *
 * 注意它与上面 `unbindDevicePoint` 只差一段路径、破坏性却完全相反——那个是**硬删行**
 * （device_point 无软删列），azimuth/slantRange/lineOfSight 一起丢。别按错。
 *
 * `reason` 走白名单（MANUAL/DEVICE_RELOCATED/TARGET_REMOVED/MAINTENANCE/SUSPECTED_DRIFT），
 * 传别的会 400；不传即 MANUAL。
 */
export function invalidateDevicePointCalibration(deviceId, pointId, reason) {
  return http.delete(`/v1/devices/${deviceId}/points/${pointId}/calibration`, {
    params: reason ? { reason } : {},
  })
}

/**
 * GET /api/v1/maintenance-records?deviceId= → MaintenanceRecord[]（裸数组）
 * 每项 `{ id, deviceId, type, description, operator, createdAt }`。
 * 不传 `deviceId` 就是全量（按 `createdAt` 倒序）。
 */
export function listMaintenanceRecords(deviceId) {
  return http.get('/v1/maintenance-records', { params: deviceId ? { deviceId } : {} })
}

/**
 * POST /api/v1/maintenance-records → 创建后的记录。角色 ADMIN/MAINTAINER。
 *
 * **不要传 `operator`**：后端一律用当前登录用户覆盖（`MaintenanceRecordController.create`），
 * 传了也会被丢掉。这是有意的——「谁写的维护记录」不该由客户端说了算。
 */
export function createMaintenanceRecord(payload) {
  return http.post('/v1/maintenance-records', payload)
}

/**
 * GET /api/v1/audit-logs → PageResult<AuditLog>（**分页**，同 `/alarms`）
 * @param {object} p
 * @param {number} [p.pageNum] 从 1 起
 * @param {number} [p.pageSize]
 * @param {string} [p.username] 精确匹配，不是模糊
 * @param {string} [p.targetType] 精确匹配
 *
 * 整个控制器挂了 `@PreAuthorize("hasRole('ADMIN')")`（类级），所以非管理员一律 403——
 * 界面上菜单只对 ADMIN 显示，但**真正的边界在后端**。
 * 两个筛选参数都是 `eq` 不是 `like`：输错一个字就是空列表，不会「差不多匹配」。
 */
export function listAuditLogs(params = {}) {
  return http.get('/v1/audit-logs', { params })
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
 * GET /api/v1/points/{pointId}/media → MediaVO[]（裸数组，同 `/points`）
 * 每项 `{ mediaId, url, takenAt, note }`。`url` 是**根相对路径**
 * （`/api/v1/media/M001/content`），不是绝对地址；`<img>` 同源可直接用，但要带 token，
 * 见 {@link mediaContentUrl}。
 */
export function listPointMedia(pointId) {
  return http.get(`/v1/points/${pointId}/media`)
}

/**
 * POST /api/v1/media（multipart：file / pointId / takenAt / note）→ MediaUploadVO
 *
 * **不要手工设 `Content-Type: multipart/form-data`**：boundary 是浏览器拼 FormData 时生成的，
 * 手写一个没有 boundary 的头会让后端解析不出任何 part，报「file 缺失」——看起来像后端坏了，
 * 其实是前端把头写死了。交给 axios 自己判断即可。
 */
export function uploadMedia(file, pointId, { takenAt, note } = {}) {
  const form = new FormData()
  form.append('file', file)
  form.append('pointId', pointId)
  if (takenAt) form.append('takenAt', takenAt)
  if (note) form.append('note', note)
  return http.post('/v1/media', form)
}

/**
 * DELETE /api/v1/media/{mediaId} → 无返回体
 *
 * **逻辑删除**：后端只把库里的行标成 `deleted=1`，**盘上的文件保留**（契约 §7、迁移 V6）。
 * 所以「删掉」在界面上是不可见的，误删时可以从服务器上找回。
 *
 * 角色限 ADMIN / MAINTAINER（与设备-测点绑定同一个口径）——值班/研判是**读**影像的角色，
 * 调这个接口会得 403。界面上由 `MediaGallery` 的 `deletable` 控制入口是否出现，
 * 但**真正的边界在后端 `@PreAuthorize`**，前端藏按钮不是权限。
 */
export function deleteMedia(mediaId) {
  return http.delete(`/v1/media/${mediaId}`)
}

/**
 * 把 MediaVO 变成 `<img src>` 能直接用的地址——**必须带 `?token=`**。
 *
 * 这是契约里除 SSE 之外**唯一**的第二处 query 鉴权（理由见 `MediaController` 的类注释与
 * `JwtAuthFilter`）：`<img>` 发不出 Authorization 头，与 EventSource 是同一个约束。
 * 忘了它就是满屏碎图——而且**每张图各自一个 401**，图片加载失败又不走 axios 拦截器，
 * 控制台里只有一串裸 401、没有任何提示。这是这块最容易踩、也最难看出原因的点。
 *
 * 与 `openStream(token)` 的差别：那个由调用方把 token 递进来（调用方本来就持有登录态），
 * 这里直接读 `utils/token`，因为用到它的三处（测点详情、影像总览、大屏浮窗）都不持有 token，
 * 让它们各自去读一遍反而更散。
 */
export function mediaContentUrl(media) {
  if (!media) return ''
  const path = media.url || `/api/v1/media/${media.mediaId}/content`
  const token = getToken()
  if (!token) return path
  return `${path}${path.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
}
