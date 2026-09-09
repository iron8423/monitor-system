# 雷达标准消息契约（message-contract）

> 依据：以“点形变雷达”为基准，每点 **2 个测项**：`defo_mm`（累计形变 mm）+ `rate_mm_d`（速率 mm/d，由历史推导）。本文件为 M0 唯一事实源；接入/落库/鉴权规则（D2 拆分、D3 attributes、D7/D8 鉴权）由 A 同步本文件。
> 单位：位移 mm、速率 mm/d；时间 ISO8601 带时区（+08:00）。

---

## 1. 单点标准消息（POST /api/v1/ingest/measurements）

```json
{
  "schemaVersion": "1.0",
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceId": "radar-001",
  "pointCode": "P-HK01",
  "collectTime": "2026-08-27T09:14:33.256+08:00",
  "receiveTime": "2026-09-09T14:24:49.702+08:00",
  "sequence": 1,
  "metrics": {
    "defo_mm": 0.7348,
    "rate_mm_d": 0.05
  },
  "quality": "VALID",
  "position": { "angleDeg": 49.0, "distanceM": 20.1 },
  "signal": 0.9,
  "state": "normal"
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `schemaVersion` | 消息结构版本，固定 `1.0` |
| `messageId` | 全局唯一 ID |
| `deviceId` | 设备唯一标识（须在设备档案存在） |
| `pointCode` | **测点业务编码**（如 `P-HK01`；须在点位档案存在） |
| `collectTime` | 采集时间（设备端） |
| `receiveTime` | 平台接收时间 |
| `sequence` | 设备侧序号（信息性，不作幂等键） |
| `metrics` | 测项值；本点形变雷达仅 `defo_mm`、`rate_mm_d` |
| `quality` | `RAW/VALID/SUSPECT/FAULT` |
| `position` | `angleDeg` 角度 / `distanceM` 距离 |
| `signal` | 信号强度 0~1 |
| `state` | `normal` / `disappeared` / `suspicious` |

## 2. 幂等

- 幂等键 = **`device_id + message_id`**（冻结口径：整条消息去重；重复不重复写、不重复报警）。
- `messageId` 需稳定（同一条数据重新上报时保持一致，系统据此去重）。

## 3. 质量标记

| `quality` | 含义 |
|---|---|
| `RAW` | 原始值 |
| `VALID` | 有效、可采信 |
| `SUSPECT` | 可疑（信号过低、跳变、state=suspicious、晚到） |
| `FAULT` | 异常/越界，不采信 |

> 异常数据默认不参与告警判定；不覆盖原始值。

## 4. 测项编码

| metricCode | 说明 | 单位 |
|---|---|---|
| `defo_mm` | 累计形变（点形变主指标） | mm |
| `rate_mm_d` | 形变速率 | mm/d |

> 无 X/Y/Z、无合位移（点形变雷达是单点累计形变）。其它多轴位移测项后置。

## 5. 落库与鉴权（A 同步进本文件）

- **D2**：一条含 2 测项的消息 → 拆成 2 行 `measurement`，共用 `message_id`。
- **D3**：`position/signal/state` 存入 `measurement.attributes`(JSON ≤1024)。
- **D7**：`POST /api/v1/ingest/**` 免 JWT，校验请求头 `X-Ingest-Key: <key>`（key 读 `MONITOR_INGEST_KEY`，dev 默认 `dev-ingest-key`）。
- **D8**：`GET /api/v1/stream?token=<JWT>`（SSE）。
