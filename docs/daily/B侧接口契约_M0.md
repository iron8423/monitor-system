# B 侧接口契约（角色 B · M0 对表用）

> 用途：角色 B 负责的接口清单，发给 A 一起冻结（M0）。A 提供 **统一响应体 `Result<T>`**、**认证 `JWT`**、**项目/测点/设备档案**；B 提供以下**数据与告警**接口。字段、单位、时间格式与《雷达标准消息契约_v1》一致。
> 约定：所有响应都用 A 的统一信封 `{ "code": 0, "message": "ok", "data": ... }`（code=0 成功，非 0 失败）。下文只写 `data` 内容。

---

## 0. 通用约定

| 项 | 约定 |
|---|---|
| 基础路径 | `/api/v1` |
| 认证 | 除 `/ingest`（网关/预共享密钥）外，均需 `Authorization: Bearer <JWT>`（A 发），并带角色权限 |
| 时间 | ISO8601 带时区，如 `2026-08-27T09:14:33.256+08:00` |
| 单位 | 位移 mm、速率 mm/d |
| 幂等 | ingest 以 `deviceId+messageId+sequence+collectTime` 去重 |
| 分页 | 列表默认 `page=1, size=20`，返回 `total` |
| 枚举 | 见 §8 |

## 1. 接口清单

| 方法 | 路径 | 说明 | 归属 |
|---|---|---|---|
| POST | `/api/v1/ingest/measurements` | 接入测量值（单条/批量） | B |
| GET | `/api/v1/points/{pointId}/latest` | 测点最新值 | B |
| GET | `/api/v1/points/{pointId}/series` | 测点时序（曲线） | B |
| GET | `/api/v1/projects/{projectId}/summary` | 项目概览 | B |
| GET | `/api/v1/alarms` | 警情列表 | B |
| GET | `/api/v1/alarms/{id}` | 警情详情（含处置时间线） | B |
| POST | `/api/v1/alarms/{id}/actions` | 处置动作（确认/研判/派发/处置/解除/误报） | B |
| GET/POST/PUT/DELETE | `/api/v1/alarm-rules` | 告警规则 CRUD | B |
| GET | `/api/v1/devices/{deviceId}/status` | 设备状态/健康 | B |
| GET | `/api/v1/stream` | SSE 实时推送 | B |
| POST | `/api/v1/media` | 上传图片（关联测点） | B |
| GET | `/api/v1/points/{pointId}/media` | 测点影像列表 | B |

> 说明：`points/devices/projects` 的**档案 CRUD** 归 A；B 只读 `pointId/pointCode/deviceId`，并依赖 A 这些档案已存在。`GET /api/v1/maintenance-records`（运维记录）由 A 负责。

---

## 2. 接入：POST /api/v1/ingest/measurements

> 无需 JWT（网关/共享密钥）。body = 单条标准消息，或批量 `{ "items": [ ... ] }`（见消息契约）。

**请求（单条）**
```json
{
  "schemaVersion": "1.0", "messageId": "...", "deviceId": "radar-001",
  "pointCode": "RT1", "collectTime": "2026-08-27T09:14:33.256+08:00",
  "sequence": 1, "metrics": { "defo_mm": 0.0, "rate_mm_d": 0.0 },
  "quality": "VALID", "position": { "angleDeg": 49.0, "distanceM": 20.1 },
  "signal": 0.9, "state": "normal"
}
```

**响应 data**
```json
{
  "accepted": 1, "rejected": 0, "duplicates": 0,
  "results": [
    { "pointCode": "RT1", "collectTime": "2026-08-27T09:14:33.256+08:00",
      "status": "OK",          // OK / DUPLICATE / REJECTED
      "quality": "VALID" }
  ]
}
```

边界：`deviceId/pointCode` 不存在 → REJECTED；幂等命中 → DUPLICATE（不重复写、不重复报警）；质量规则见消息契约 §3。

---

## 3. 数据查询

### GET /api/v1/points/{pointId}/latest
```json
{
  "pointId": "P1", "pointCode": "RT1",
  "latest": { "collectTime": "2026-08-27T09:14:33.256+08:00",
    "defo_mm": 0.0, "rate_mm_d": 0.0, "quality": "VALID",
    "signal": 0.9, "position": { "angleDeg": 49.0, "distanceM": 20.1 } },
  "state": "normal"
}
```

### GET /api/v1/points/{pointId}/series
> query：`metricCode`（defo_mm | rate_mm_d，默认 defo_mm）、`from`、`to`、`granularity`（raw | hour | day，默认 raw）
```json
{
  "pointId": "P1", "pointCode": "RT1", "metricCode": "defo_mm", "unit": "mm",
  "points": [ { "t": "2026-08-27T09:14:33.256+08:00", "v": 0.0 },
              { "t": "2026-08-27T09:20:10.000+08:00", "v": 0.19 } ]
}
```

### GET /api/v1/projects/{projectId}/summary
```json
{
  "projectId": "PRJ-01", "pointCount": 5, "alertCount": 1,
  "onlineDeviceCount": 4, "maxDeformationMm": 0.73
}
```

---

## 4. 告警

### GET /api/v1/alarms
> query：`level`、`status`、`pointId`、`from`、`to`、`page=1`、`size=20`
```json
{
  "total": 2,
  "items": [
    { "id": 1, "pointId": "P1", "pointCode": "RT1", "level": "alarm",
      "status": "PENDING", "triggeredAt": "2026-08-27T09:30:00+08:00",
      "lastAction": "触发", "lastActionAt": "2026-08-27T09:30:00+08:00" }
  ]
}
```

### GET /api/v1/alarms/{id}
```json
{
  "id": 1, "pointId": "P1", "pointCode": "RT1", "level": "alarm", "status": "PENDING",
  "triggeredAt": "2026-08-27T09:30:00+08:00",
  "snapshot": { "defo_mm": 0.73, "threshold_mm": 0.5 },
  "timeline": [ { "time": "2026-08-27T09:30:00+08:00", "action": "trigger", "operator": "system", "comment": "累计形变 0.73mm 超阈值 0.50mm" } ]
}
```

### POST /api/v1/alarms/{id}/actions
> body：`action`（confirm/research/dispatch/handle/resolve/misreport）、`comment`、`operator`
```json
{ "action": "confirm", "comment": "值班确认，已通知现场", "operator": "值班员-张" }
```
```json
{ "id": 1, "status": "CONFIRMED",
  "timeline": [ { "time": "...", "action": "confirm", "operator": "值班员-张", "comment": "值班确认" } ] }
```

### 告警规则 CRUD（`/api/v1/alarm-rules`）
规则对象：
```json
{
  "id": 1, "pointCode": "RT1", "metricCode": "defo_mm",
  "type": "THRESHOLD",        // THRESHOLD(累计) | RATE(速率) | CHANGE(变化量)
  "operator": "gte",          // gte / lte
  "value": 0.5, "windowMinutes": 30,
  "level": "alarm",           // notice / warning / alarm
  "recoveryValue": 0.3,       // 恢复阈值（解除条件）
  "repeatSuppressSeconds": 300, // 同规则同点防刷屏
  "enabled": true
}
```

---

## 5. 设备/质量：GET /api/v1/devices/{deviceId}/status
```json
{
  "deviceId": "radar-001", "online": true, "battery": 92,
  "lastReportAt": "2026-08-27T09:14:33+08:00",
  "health": "NORMAL"          // NORMAL / LOW_BATTERY / OFFLINE / DATA_ABNORMAL
}
```

> 判定：B 根据最近上报时间算在线/离线，电量/数据异常生成设备告警。A 只维护设备档案，不重复判定。

---

## 6. 实时推送：GET /api/v1/stream（SSE）

`Content-Type: text/event-stream`。事件：
```text
event: measurement
data: {"pointCode":"RT1","collectTime":"...","defo_mm":0.19,"quality":"VALID"}

event: alarm
data: {"id":1,"pointCode":"RT1","level":"alarm","status":"PENDING","triggeredAt":"..."}
```

> 前端用 `EventSource` 订阅；给 3D 大屏/曲线做实时刷新。

---

## 7. 媒体

### POST /api/v1/media（multipart/form-data）
> 字段：`file`（图片）、`pointId`、`takenAt`（可选）、`note`（可选）
```json
{ "mediaId": "M001", "objectKey": "media/PRJ-01/P1/xxx.jpg",
  "url": "/api/v1/media/M001/content", "pointId": "P1", "takenAt": "2026-08-27T10:00:00+08:00" }
```

### GET /api/v1/points/{pointId}/media
```json
{ "items": [ { "mediaId": "M001", "url": "/api/v1/media/M001/content",
  "takenAt": "2026-08-27T10:00:00+08:00", "note": "" } ] }
```

---

## 8. 枚举

| 枚举 | 取值 |
|---|---|
| `quality` | `RAW` 原始 / `VALID` 有效 / `SUSPECT` 可疑 / `FAULT` 异常 |
| 告警级别 | `notice` 注意 / `warning` 预警 / `alarm` 告警 |
| 警情状态 | `PENDING` 待确认 / `CONFIRMED` 已确认 / `PROCESSING` 处置中 / `OBSERVING` 观察中 / `RESOLVED` 已解除 / `FALSE_ALARM` 误报 |
| 处置动作 | `confirm` 确认 / `research` 研判 / `dispatch` 派发处置 / `handle` 处置 / `resolve` 解除 / `misreport` 误报 |
| 设备健康 | `NORMAL` 正常 / `LOW_BATTERY` 低电量 / `OFFLINE` 离线 / `DATA_ABNORMAL` 数据异常 |
| 规则类型 | `THRESHOLD` 累计 / `RATE` 速率 / `CHANGE` 变化量 |

---

## 9. 与 A 的衔接点（对表确认）

1. A 提供 `common.Result<T>` 信封、`JWT` 鉴权、`@PreAuthorize` 角色权限；B 的 `/api` 都套这个信封。
2. A 提供 `project/point/device` 档案的读写与唯一编码；B 只读，`pointId/deviceId` 必须存在。
3. `pointCode` ↔ `pointId` 映射、`metrics` 编码（`defo_mm/rate_mm_d`）、时间格式、单位，B 与 A 共用本契约 + 消息契约。
4. `/ingest` 走网关/共享密钥（免 JWT）；`/stream`(SSE)、`/alarms` 等管理/查询接口走 JWT。
5. 错误码：业务错误 `Message` 语义由 A 的 `BusinessException` 统一；B 按 `code` 非 0 返回。
