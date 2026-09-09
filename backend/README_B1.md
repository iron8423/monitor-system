# B1 · ingest 接入模块（Java 骨架）

> 已按 A 冻结口径写好。放到 **A 的 backend 工程**对应包下即可跑；以下三处需与 A 对齐后确认。

## 已包含

```text
com.monitor.telemetry
├── dto   IngestMessage / IngestRequest / IngestResult   （单条+批量+响应）
├── entity Measurement                                       （按测项一行；共享 message_id）
├── mapper MeasurementMapper                                （BaseMapper + countByMessageId 幂等）
├── service IngestService                                   （校验/幂等/拆行/质量/attributes）
└── web    IngestController                                 （POST /api/v1/ingest/measurements）
```

## 逻辑（对齐冻结口径）

- **幂等**：`device_id + message_id`，重复整条去重（返回 DUPLICATE，不重复写、不重复报警）。
- **落库**：一条含 N 测项（defo_mm/rate_mm_d）→ 拆 N 行 `measurement`，共用 `message_id`。
- **attributes**：`position/signal/state` 序列化进 `measurement.attributes`（JSON，超 1024 截断）。
- **质量**：`quality` 缺省按 信号<0.3→SUSPECT / state=suspicious→SUSPECT / 数值非法→FAULT / 否则 VALID。
- **响应**：`accepted`（=落库行数）、`rejected`、`duplicates`、`results[{pointCode,collectTime,status,quality}]`。

## 放进工程后要做/确认（3 项）

1. **Measurement 列名**：与 A 实际 `measurement` 表对齐（本骨架列：id/message_id/device_id/point_code/metric_code/collect_time/receive_time/value/quality/attributes/raw_ref）。若 A 已建表，按 A 的列名改 entity 或加 `@TableField`。
2. **point/device 校验**：`IngestService.valid()` 现只做非空校验；如需“pointCode/deviceId 必须存在于档案”，注入 A 的 point/device 查询（TODO，`com.monitor.project`/`asset` 的读接口）。
3. **包名 / Result / BizException**：`com.monitor` 占位若与 A 不同，改包名；`Result` / `BizException` 用 A `common` 的（本骨架直接用 `com.monitor.common.Result`）。

## 依赖假设

- Spring Boot 3 + MyBatis-Plus + Lombok + Jackson + PostgreSQL/H2。
- 鉴权由 A 的 `SecurityConfig` 放行 `/api/v1/ingest/**` 并校验 `X-Ingest-Key`（dev=`dev-ingest-key`）；本控制器不再重复鉴权。

## 联调（阶段 1 M1）

```powershell
py radar_csv_replay.py --date 20260827 --send --speed 5 --max-events 200 --inject-overlimit
```
（`--ingest-key` 默认 `dev-ingest-key` 已匹配；`--inject-overlimit` 会把 defo 凑到 ±4mm 触发 ±3 告警。）
