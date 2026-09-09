# 标准单点消息契约（M0 · 对齐基线 §7）

> 依据：基线《需求分析与开发指引 v1.0》§7 数据接入契约 + §6 领域数据模型。
> 原则：真实雷达协议未拿到前一律走「模拟器 + 标准消息」，拿到后只换协议适配器（telemetry 层 DTO 不变）。
> M0 对表（2026-09-09）：与《B侧接口契约_M0》核对后的补充见 §5（鉴权/拆分/扩展字段）；
> 测项编码是否改为 `defo_mm/rate_mm_d` 见 §2 的 D1 待定标注，以《雷达标准消息契约_v1》为准。

## 1. 标准单点消息（基线 §7 原文口径）

`POST /api/v1/ingest/measurements`，一条消息 = 一个设备的一个测项（metric）的一次读数：

```json
{
  "schemaVersion": "1.0",
  "messageId": "unique-id",
  "deviceId": "radar-001",
  "metricCode": "cumulative_deformation",
  "collectTime": "2026-09-09T10:00:00+08:00",
  "receiveTime": "2026-09-09T10:00:01.2+08:00",
  "sequence": 12345,
  "value": 2.36,
  "unit": "mm",
  "quality": "RAW",
  "attributes": { "signal": 0.91, "state": "normal", "angleDeg": 15.2, "distanceM": 87.4 }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | string | 是 | 消息版本号，字段版本化 |
| `messageId` | string | 是 | 消息唯一 ID，幂等键之一 |
| `deviceId` | string | 是 | 设备编码，与 `device.code` 对应（不靠展示名） |
| `metricCode` | string | 是 | 测项编码，与 `metric.code` 对应（如 X/Y/Z/DISP/VEL） |
| `collectTime` | string(ISO8601) | 是 | 采集时间 |
| `receiveTime` | string(ISO8601) | 否 | 接收时间（采集/接收分开） |
| `sequence` | long | 否 | 递增序列号，幂等/排序辅助 |
| `value` | number | 是 | 测值 |
| `unit` | string | 否 | 单位（mm / mm/d） |
| `quality` | string | 否 | 质量标记（M0-D5 冻结：RAW / VALID / SUSPECT / FAULT） |
| `attributes` | object | 否 | 扩展属性（signal/state/angleDeg/distanceM…），原样存 `attributes` |

## 2. 测项 → 测量值（基线 §6）

时序按「项目/设备/测点/测项 + 时间 + 值/单位/质量标记/原始引用」存储，不塞无约束 JSON。

- 一个测点下有 5 个测项（`metric.code`）：`X`/`Y`/`Z`（累计形变分量）、`DISP`（合位移）、`VEL`（速率）。
  - **M0-D1 待定**：测项编码拟与 B 对齐为 `defo_mm`(位移)/`rate_mm_d`(速率)，取决于《雷达标准消息契约_v1》是否含三分量；B 答复后本文与 V2 种子同步更新。
- 每条消息按 `metricCode` 落为 `measurement` 一行（`value/unit/quality/attributes/raw_ref/collect_time/receive_time`）。
  - 注：JSON 字段 `value` 对应 DB 列 `measure_value`（`value` 为 H2 保留字，故列名规避）。
- 原始报文引用存 `raw_ref`，可追溯。

## 3. 幂等去重（基线 §7 + 分工方案 B1）

- 唯一键 `(deviceId, messageId)`，落库层 `UNIQUE` 兜底 + 业务层预查。
- 同一（设备 + 消息 ID / 序号 + 采集时间）不重复落库、不重复告警（验收第 3 条）。

## 4. 断连 / 超限注入（模拟器 B2）

- 模拟器持续上报；可注入：超限值（触发告警）、断连（停止上报→设备离线）、重复消息（验幂等）。

## 5. 鉴权与接入补充（M0 冻结，2026-09-09）

### 5.1 消息拆分落库（D2）
- V1 `measurement` 按**测项一行**存储（`metric_code` + `measure_value`），不塞宽表。
- 若一条上报消息含多个测项（如 `metrics: { defo_mm: ..., rate_mm_d: ... }`），由 ingest 拆成 N 条标准单值记录写入，**共用同一 `messageId`**。
- 幂等键 `(deviceId, messageId)`：整条消息去重——重复到达不重复落库、不重复触发告警。

### 5.2 扩展字段落位（D3）
- `position`(angleDeg/distanceM)、`signal`、`state` 等扩展字段统一放 `attributes`（JSON，≤1024 字符），原样存储，不新增列；latest 查询时原样还原。

### 5.3 ingest 鉴权（D7）
- `POST /api/v1/ingest/measurements` **免 JWT**，改验请求头 `X-Ingest-Key: <key>`（Demo 无网关）。
- key 读取 `MONITOR_INGEST_KEY` 环境变量，未设置用 application.yml 默认值；缺失/不符 → 401。

### 5.4 SSE 订阅（D8）
- `GET /api/v1/stream?token=<JWT>` —— `EventSource` 不能带 Header，故仅该路径支持 query token。
- 事件：`measurement`（新测量值）/ `alarm`（新警情），`data` 为 JSON。

## 6. 其余契约（OpenAPI 骨架）

- 档案 CRUD：`/api/v1/{organizations,projects,scenes,objects,points,metrics,devices}`（A）
- 接入：`/api/v1/ingest/measurements`（B）
- 数据查询：`/api/v1/points/{id}/latest`、`/series`、`/projects/{id}/summary`（B）
- 告警：`/api/v1/alarms`、`/alarm-rules`（B）
- 实时：`/api/v1/stream`（SSE，B）
- 媒体：`/api/v1/media`（B）
- 审计/运行：`/api/v1/audit-logs`、`/api/v1/health`（A）

> 完整字段以 OpenAPI（springdoc 自动生成）为准，M0 双方逐条确认后冻结。
