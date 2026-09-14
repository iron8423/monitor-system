# B 侧接口契约（角色 B · 已按 A 冻结口径 D1–D10 更新）

> 用途：角色 B 负责的接口。本文已按 A 的《M0 接口冻结_致B_v1》**冻结口径**更新：设备状态归 A、id/code 分离、幂等 `device_id+message_id`、`X-Ingest-Key`、`?token=`、D5 枚举、D6 规则字段、D9 `BizException`。
> 基线：`/api/v1`；信封 `{code,message,data}`（code=0 成功）；时间 ISO8601 带时区；位移 mm、速率 mm/d。
> 依赖 A：档案 CRUD（project/scene/object/point/device）、`common.Result<T>`、`BizException`、`JwtAuthFilter`、`SecurityConfig`。B 只读 `pointId/pointCode/deviceId/deviceCode`。

---

## 0. 通用约定（冻结）

| 项 | 口径 |
|---|---|
| 基础路径 | `/api/v1` |
| id vs code | **DB id = 数值 BIGINT**（A identity 自增 1000 起）；**字符串 = code（业务键）**。路径用数值 id；上报消息用 code；响应同时给数值 id 与字符串 code（如 `pointId:1000, pointCode:"P-HK01"`） |
| 幂等 | **`device_id + message_id`**（整条消息去重；重复不重复写、不重复报警） |
| 落库 | `measurement` **按测项一行**：一条含 N 个测项 → 拆 N 行，共用同一 `message_id` |
| 附加字段 | `position/signal/state` 等存入 `measurement.attributes`(JSON ≤1024)，不加列 |
| 时间 | ISO8601 带时区；`collectTime`(设备) 与 `receiveTime`(平台) 分开 |
| 时间输出 | **全仓统一**由 Jackson 序列化器补 `+08:00`（`JacksonTimeConfig`），档案 CRUD 直接返回的实体时间（`createdAt/updatedAt`）也在内。不要依赖 `spring.jackson.date-format`——它只作用于 `java.util.Date`，对 JSR-310 无效 |
| 单位 | 位移 mm / 速率 mm/d |
| 异常 | 统一用 A 的 **`BizException`**，不要自建 `BusinessException` |
| 分页 | 列表默认 `page=1, size=20`，返回 `total` |

## 1. 接口清单（B 负责，已按冻结调整）

| 方法 | 路径 | 说明 | 归属 |
|---|---|---|---|
| POST | `/api/v1/ingest/measurements` | 接入测量值（单条/批量，见 §2） | B |
| GET | `/api/v1/points/{pointId}/latest` | 测点最新值 | B |
| GET | `/api/v1/points/{pointId}/series` | 测点时序（曲线，按 metricCode/time/粒度） | B |
| GET | `/api/v1/projects/{projectId}/summary` | 项目概览 | B |
| GET | `/api/v1/alarms` | 警情列表 | B |
| GET | `/api/v1/alarms/{id}` | 警情详情（含处置时间线） | B |
| POST | `/api/v1/alarms/{id}/actions` | 处置动作 | B |
| GET/POST/PUT/DELETE | `/api/v1/alarm-rules` | 告警规则 CRUD（字段见 §4-D6） | B |
| GET | `/api/v1/stream` | SSE 实时推送（`?token=`） | B |
| POST | `/api/v1/media` | 上传图片（关联测点） | B |
| GET | `/api/v1/points/{pointId}/media` | 测点影像列表 | B |

> ⚠️ **`GET /api/v1/devices/{deviceId}/status` 已归 A**（A 已实现），**B 不再提供**。B 如需含 `DATA_ABNORMAL` 的 `health`，基于 A 的 status + 数据质量**另出**，不重复“在线判定”。
> 档案 CRUD（project/scene/object/point/device/metrics）、维护记录、审计 = A。

## 2. 接入：POST /api/v1/ingest/measurements

> 免 JWT；需请求头 **`X-Ingest-Key: <key>`**（key 来自环境变量 `MONITOR_INGEST_KEY`，dev 默认在 A 的 application.yml）。密钥不符 → 401。body = 单条标准消息或 `{ "items": [...] }`。
>
> **两种形态都真实支持（2026-09-14 修）**：直接给数组 `[ {...} ]` 也按 items 处理。
> 既不像消息、又没有 `items`（如 `{}`），或 `items` 为空 / 含 `null` / 不是数组，一律 **400**
> —— 此前单条消息会被静默丢成空批量（HTTP 200 + `accepted=0`），调用方看不出错。
> 消息字段类型写错（如 `metrics` 传字符串）也是 400，错误信息只给一行原因，不甩 Jackson 内部引用链。

**请求头**：`X-Ingest-Key: <MONITOR_INGEST_KEY 的 dev 值>`

**请求体（单条）**
```json
{
  "schemaVersion": "1.0", "messageId": "uuid", "deviceId": "radar-001",
  "pointCode": "P-HK01", "collectTime": "2026-08-27T09:14:33.256+08:00",
  "sequence": 1, "metrics": { "defo_mm": 0.0, "rate_mm_d": 0.0 },
  "quality": "VALID", "position": { "angleDeg": 49.0, "distanceM": 20.1 },
  "signal": 0.9, "state": "normal"
}
```

**落库规则（D2/D3）**：A 收到一条含 2 测项（defo_mm/rate_mm_d）→ 拆 **2 行** `measurement`，共用 `message_id`；`position/signal/state` 写 `attributes`(JSON)。幂等 `device_id+message_id`，重复整条去重。

**响应 data**
```json
{ "accepted": 2, "rejected": 0, "duplicates": 0,
  "results": [ { "pointCode": "P-HK01", "collectTime": "2026-08-27T09:14:33.256+08:00", "status": "OK", "quality": "VALID" } ] }
```
> **量纲不同，注意**：`accepted` 按**落库行数**计（一条 2 测项消息 → `accepted: 2`，因拆 2 行）；
> `duplicates`/`rejected` 按**消息条数**计。故一条 2 测项消息被去重命中时是 `duplicates: 1` 而非 2。
> `results` 则**每条消息一项**。

> 边界：`deviceId/pointCode` 不存在 → REJECTED；幂等命中 → DUPLICATE；质量规则见消息契约 §3。
> **`collectTime` 是必填且必须可解析**：缺失或格式非法 → 整条 REJECTED（计入 `rejected`），
> 不会替你取当前时间兜底。理由：兜底取 `now()` 会让设备的坏时间戳被盖上「刚刚收到」的章，
> 而设备在线判定（`DeviceStatusPolicy`，5 分钟窗口）只认最后上报时间——**设备一直在报垃圾却永远显示在线**，
> 离线告警永远不会响。`receiveTime`（平台侧时间）缺省才取当前时间。
> 宽容度：ISO8601 带偏移（自动换算到 `+08:00`）、ISO8601 本地、`yyyy-MM-dd HH:mm:ss` 三种写法都接受。

## 3. 数据查询（id 用数值，code 用字符串）

### GET /api/v1/points/{pointId}/latest
```json
{ "pointId": 1000, "pointCode": "P-HK01",
  "latest": { "collectTime": "2026-08-27T09:14:33.256+08:00", "defo_mm": 0.0, "rate_mm_d": 0.0,
    "quality": "VALID", "signal": 0.9, "position": { "angleDeg": 49.0, "distanceM": 20.1 } },
  "state": "normal" }
```

### GET /api/v1/points/{pointId}/series
> query：`metricCode`(defo_mm|rate_mm_d，默认 defo_mm)、`from`、`to`、`granularity`(raw|hour|day)
```json
{ "pointId": 1000, "pointCode": "P-HK01", "metricCode": "defo_mm", "unit": "mm",
  "points": [ { "t": "2026-08-27T09:14:33.256+08:00", "v": 0.0 }, { "t": "2026-08-27T09:20:10.000+08:00", "v": 0.19 } ] }
```

### GET /api/v1/projects/{projectId}/summary
```json
{ "projectId": 1000, "pointCount": 7, "alertCount": 1, "onlineDeviceCount": 4, "maxDeformationMm": 0.73 }
```

## 4. 告警（枚举按 D5，规则字段按 D6）

### GET /api/v1/alarms（筛选：level/status/pointId/deviceId/alarmType/from/to/page/size）
> 分页字段用 A 的公共信封 `common.PageResult`（`total/pageNum/pageSize/records`）——A 的全部 CRUD 列表端点都是这个形状，前端只需认一种。
> `alarmType` = `POINT`（测点形变警情）/ `DEVICE`（设备状态告警），大小写不敏感；
> 不传则两类混排。`POINT` 的记录给 `pointId/pointCode`，`DEVICE` 的记录给 `deviceId/deviceCode`，
> 另一侧为 `null`——前端按 `alarmType` 决定渲染哪一个。
> `lastAction` / `lastActionAt` 是**系统或人工最后一次动作**，取值是原始动作串
> （`trigger` 触发 / `recover` 自动解除 / `escalate` 升级 / `confirm` / `research` / `dispatch` / `handle` / `resolve` / `misreport`），
> 不是中文标签——前端自行映射展示文案。
```json
{ "total": 2, "pageNum": 1, "pageSize": 20, "records": [
  { "id": 1, "alarmType": "POINT", "pointId": 1000, "pointCode": "P-HK01",
    "deviceId": null, "deviceCode": null, "level": "alarm", "status": "PENDING",
    "triggeredAt": "2026-08-27T09:30:00+08:00", "lastAction": "trigger", "lastActionAt": "2026-08-27T09:30:00+08:00" },
  { "id": 2, "alarmType": "DEVICE", "pointId": null, "pointCode": null,
    "deviceId": 1000, "deviceCode": "radar-001", "level": "notice", "status": "PENDING",
    "triggeredAt": "2026-08-27T09:35:00+08:00", "lastAction": "trigger", "lastActionAt": "2026-08-27T09:35:00+08:00" } ] }
```

### GET /api/v1/alarms/{id}
> 字段同列表项 + `resolvedAt`；`snapshot` 随 `alarmType` 变化（见下）。
```json
{ "id": 1, "alarmType": "POINT", "pointId": 1000, "pointCode": "P-HK01", "level": "alarm", "status": "PENDING",
  "triggeredAt": "2026-08-27T09:30:00+08:00",
  "snapshot": { "defo_mm": 0.73, "threshold_mm": 10.0 },
  "timeline": [ { "time": "2026-08-27T09:30:00+08:00", "action": "trigger", "operator": "system", "comment": "累计形变 0.73mm 超阈值 10mm" } ] }
```
```json
{ "id": 2, "alarmType": "DEVICE", "pointId": null, "pointCode": null,
  "deviceId": 1000, "deviceCode": "radar-001", "level": "notice", "status": "PENDING",
  "triggeredAt": "2026-08-27T09:35:00+08:00",
  "snapshot": { "deviceCode": "radar-001", "deviceName": "毫米波点形变雷达 1 号",
                "lastReportTime": "2026-08-27T09:29:10+08:00", "offlineMinutes": 5 },
  "timeline": [ { "time": "2026-08-27T09:35:00+08:00", "action": "trigger", "operator": "system",
                  "comment": "设备 radar-001（毫米波点形变雷达 1 号）已超过 5 分钟未上报数据，判定为离线" } ] }
```

### 警情唯一性与等级升级（A 于 2026-09-10 定案）
- 同一**测点 + 测项**未解除的警情**至多一条**。值继续恶化、命中更高等级规则时**就地升级**
  （`level` 抬高 + 时间线追加 `escalate`），**不另开平行警情**——运维看到的是「这个点在恶化」，
  而不是两条都要处置的警情。
- 升级只改 `alarm_level`，**不改所属规则**；因此**恢复仍按最初触发那条规则**的恢复阈值判定。
- 作用域带「测项」：不同测项是不同的物理量，一条速率警情不该把同点的形变警情挡住。
- 这半句是验收脚本第 3 条要求的；`RATE`/`CHANGE` 仍未实现（见下）。
- 种子规则因此有三条（V4 补）：`defo_mm gte +3.0/恢复 +1.0`（warning）、`lte −3.0/恢复 −1.0`（warning）、
  `gte +5.0/恢复 +2.0`（alarm）。**只注入 ~4mm 只会看到 warning**，要演示升级需冲到 ≥5mm。

### POST /api/v1/alarms/{id}/actions
> body：`action`(confirm/research/dispatch/handle/resolve/misreport)、`comment`、`operator`
```json
{ "id": 1, "status": "CONFIRMED",
  "timeline": [ { "time": "...", "action": "confirm", "operator": "值班员-张", "comment": "值班确认" } ] }
```

**角色 → 动作：后端强制（2026-09-11 起）。** 权威表在 `AlarmConstants.ROLE_ACTIONS`，
`AlarmService.act` 里校验，越权返回 **403**（`Result.code=403`，message 形如
`角色 ANALYST 无权执行处置动作「confirm」（该角色可用 [resolve, misreport, research]）`）。

| 角色 | 允许的动作 |
|---|---|
| `ADMIN` | confirm / research / dispatch / handle / resolve / misreport |
| `OPERATOR` | confirm / dispatch / handle / resolve / misreport |
| `ANALYST` | research / resolve / misreport |
| `MAINTAINER` | handle / resolve / misreport |

> 角色为 null / 空 / 未登记时**一律拒绝**（失败即拒绝，没有「按管理员放行」的回退）。
> 前端 `frontend/src/utils/labels.js` 的 `ACTIONS_BY_ROLE` 是同一张表的**展示副本**，
> 只决定按钮显不显示——**改这张表必须同步改那边**，否则会出现「按钮在但点了报 403」。
> 错误码优先级：**动作名非法 → 400**（先认动作名）、**角色无权 → 403**、**警情不存在 → 404**、
> **已终态 → 400**。所以打错动作名得到的是 400 而不是 403。

### 告警规则 CRUD（D6 字段集；A 列名下划线）
```json
{ "id": 1, "pointId": 1000, "pointCode": "P-HK01", "metricCode": "defo_mm",
  "type": "THRESHOLD", "operator": "gte", "value": 3.0, "windowMinutes": 30,
  "level": "warning", "recoveryValue": 1.0, "repeatSuppressSeconds": 300, "enabled": true }
```
> A 字段：`point_id`、`metric_code`、`rule_type`、`operator`、`threshold_value`、`window_minutes`、`recovery_value`、`level`、`repeat_suppress_seconds`、`enabled`。
> **`type` 目前只接受 `THRESHOLD`**（传 `RATE`/`CHANGE` → 400）。速率类告警请对雷达已上报的 `rate_mm_d` 测项建 `THRESHOLD` 规则；`CHANGE`（窗口内变化量）引擎不评估，待定语义后再实现。`windowMinutes` 字段保留（D6 字段集）但当前不参与判定。
> **默认规则（A 已冻结，双向 + 一条升级档）**：`defo_mm` 规则① `THRESHOLD gte +3.0 / 恢复 +1.0`（warning）；规则② `THRESHOLD lte -3.0 / 恢复 -1.0`（warning）；规则③ `THRESHOLD gte +5.0 / 恢复 +2.0`（alarm，见「警情唯一性与等级升级」）。
> Demo 用 `--inject-overlimit` 把 defo 凑到 ~4mm（或 ~-4mm）即触发 warning；**要继续看到升级到 alarm，得冲到 ≥ +5mm**（比如 6mm）。

## 5. 设备状态——归 A（B 不再提供）

`GET /api/v1/devices/{id}/status` 由 A 返回 `deviceId`(数值)、`code`、`status(ONLINE/OFFLINE/FAULT)`、`online`、`battery`、`lowBattery`、`lastReportTime`。

**B 如需 `health`(含 `DATA_ABNORMAL`)**：基于 A 的 status + B 的数据质量判定单独产出，**不与 A 的在线判定重复**（接口待定，需时再对）。

### 设备离线告警（A 于 2026-09-10 补齐，对应验收第 5 条）

`/devices/{id}/status` 是**读时计算**（拉）——不去查状态就什么都不会发生。
验收第 5 条要求「断连 → 生成设备告警」，所以 A 补了产出侧：`DeviceAlarmMonitor` 定时扫描
（`monitor.device-offline.sweep-ms`，默认 10s），掉线的开警情、恢复的自动解除。

- 判据复用 `DeviceStatusPolicy.OFFLINE_MINUTES`（5 分钟），与状态接口同一口径，不另立阈值；
- **从不报数的设备不告警**（`last_report_time` 为空）——刚建档还没上线不是事件；
- 等级取最低档 `notice`——离线是可恢复的运行状态，不该跟真正的超限告警抢注意力；
- 复用同一张 `alarm` 表、同一套状态机 / 处置动作 / 时间线 / SSE（`alarmType = DEVICE`），
  B 不需要新开警情接口；`POST /alarms/{id}/actions` 对设备告警同样可用。

> 展示口径待 B 定：设备告警是否进「警情中心」默认视图（后端可按 `alarmType` 筛，见 §4）。

## 6. 实时推送：GET /api/v1/stream（SSE）

> `EventSource` 带不了 Header → 用 **query token**：`GET /api/v1/stream?token=<JWT>`。仅该路径走 query，其余仍走 `Authorization` 头。
```text
event: measurement
data: {"pointCode":"P-HK01","collectTime":"...","defo_mm":0.19,"quality":"VALID"}

event: alarm
data: {"id":1,"pointCode":"P-HK01","level":"alarm","status":"PENDING","triggeredAt":"..."}
```

## 7. 媒体

### POST /api/v1/media（multipart：`file`、`pointId`、`takenAt`、`note`）
> `mediaId` 是**不透明字符串编码**：`M` + 主键（左补零至 3 位）。主键起点 1000（与其余表一致），故首个编码为 `M1000`。前端不要解析数字部分。
```json
{ "mediaId": "M1000", "objectKey": "media/P-HK01/xxx.jpg", "url": "/api/v1/media/M1000/content", "pointId": 1000, "takenAt": "2026-08-27T10:00:00+08:00" }
```

### GET /api/v1/points/{pointId}/media
> 非分页列表，直接返回数组（与 A 的 `GET /projects` 等一致）。
```json
[ { "mediaId": "M1000", "url": "/api/v1/media/M1000/content", "takenAt": "2026-08-27T10:00:00+08:00", "note": "" } ]
```

## 8. 枚举（D5，冻结）

| 枚举 | 取值 |
|---|---|
| `quality` | `RAW` / `VALID` / `SUSPECT` / `FAULT` |
| 告警级别 | `notice` 注意 / `warning` 预警 / `alarm` 告警 |
| 警情状态 | `PENDING` / `CONFIRMED` / `PROCESSING` / `OBSERVING` / `RESOLVED` / `FALSE_ALARM` |
| 处置动作（`actions` 的 body 可取值） | `confirm` / `research` / `dispatch` / `handle` / `resolve` / `misreport` |
| 系统自动动作（只出现在 `lastAction` / 时间线里，不可作为处置动作提交） | `trigger` / `recover` / `escalate` |
| 警情来源 `alarmType` | `POINT` / `DEVICE` |
| 设备健康 | `NORMAL` / `LOW_BATTERY` / `OFFLINE` / `DATA_ABNORMAL` |
| 规则类型 | `THRESHOLD` / `RATE` / `CHANGE`（当前只实现 `THRESHOLD`，见 §4） |

## 9. 给 A 的依赖（已冻结）

1. B 的所有接口套 A 的 `common.Result<T>` 信封；错误抛 `BizException`。
2. A 提供档案读接口（organizations/projects/scenes/objects/points/metrics/devices）+ auth/login + auth/me；B 只读，`pointId/pointCode/deviceId/deviceCode` 必须存在。
3. A 的 `SecurityConfig` 放行 `/api/v1/ingest/**`（校验 `X-Ingest-Key`）；`JwtAuthFilter` 对 `/api/v1/stream` 支持 `?token=`。
4. 幂等：A 按 `device_id+message_id` 去重。
5. 错误码：业务错误走 A 统一，401/403 由 A 的鉴权返回。
