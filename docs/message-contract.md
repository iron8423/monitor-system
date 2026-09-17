# 雷达标准消息契约（message-contract）

> 依据：以“点形变雷达”为基准，每点 **2 个测项**：`defo_mm`（累计形变 mm）+ `rate_mm_d`（速率 mm/d，由历史推导）。本文件为 M0 唯一事实源；接入/落库/鉴权规则（D2 拆分、D3 attributes、D7/D8 鉴权）由 A 同步本文件。
> 单位：位移 mm、速率 mm/d；时间 ISO8601 带时区（+08:00）。

---

## 1. 单点标准消息（POST /api/v1/ingest/measurements）

实时单条消息可以直接提交；批量或历史补报使用请求信封：

```json
{
  "ingestMode": "BACKFILL",
  "items": [ /* 标准消息 */ ]
}
```

`ingestMode` 只允许 `REALTIME`、`BACKFILL`，缺省为 `REALTIME`。同一批消息只能使用一种模式。
`REALTIME` 落库后会更新设备最近上报时间、发送 SSE 并评估当前告警；`BACKFILL` 仅落库供历史查询，
不得改变在线状态、实时画面或触发/升级/解除当前告警。CSV 历史回放必须明确使用 `BACKFILL`。

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

### 雷达与测点归属

`deviceId` 与 `pointCode` 不是两个独立的合法值：二者必须命中一条当前有效的雷达目标标定关系。
生产严格模式要求该关系为 `ACTIVE`、视线无遮挡（LOS），且处于有效期内。仅在设备页执行“绑定测点”
会得到 `PENDING` 关系，完成目标号、方位角、俯仰角、斜距和视线标定后才能接收生产测值。

- 没有绑定：`REJECTED / DEVICE_POINT_NOT_BOUND`
- 已绑定但未激活、被遮挡或过期：`REJECTED / DEVICE_POINT_NOT_CALIBRATED`
- 报文角度/距离明显偏离标定：`REJECTED / POSITION_CALIBRATION_MISMATCH`

其中 `position.angleDeg` 定义为目标相对雷达航向中心线的水平角（左负右正，度），
`position.distanceM` 为目标斜距（米）。生产校验容差为角度 2°、距离 `max(2m, 3%)`；厂商坐标含义
不同时，转换器必须先完成坐标转换，不能原样套字段名。

## 2. 幂等

- 幂等键 = **`device_id + message_id`**（冻结口径：整条消息去重；重复不重复写、不重复报警）。
- `messageId` 需稳定（同一条数据重新上报时保持一致，系统据此去重）。

### 字段有效性（2026-09-10 补）

- **`collectTime` 必填且必须可解析**（ISO8601 带时区）。缺失或格式非法 → **整条消息 `REJECTED`**，计入 `rejected`，不落库。
  早期实现解析失败时静默取 `now()`，后果是**掩盖设备离线**：坏时间戳被盖上「刚刚收到」的章，而在线判定只认 `last_report_time`，于是一台一直报垃圾的设备永远显示在线。故改为拒收。
- `receiveTime`（平台侧时间）**是唯一允许缺省取当前时间**的字段——它是平台自己盖的章。

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
- **D9**：`measurement.ingest_mode` 保留接入用途；数据质量“最近数据”只统计 `REALTIME`。
