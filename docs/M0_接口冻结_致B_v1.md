# M0 接口冻结与衔接（A → B）

> 日期：2026-09-09 · 发起：A · 接收：B
> 背景：基于两份输入——你的《B侧接口契约_M0》+ 我（A）已落地的底座实现——对账后按 A 建议冻结 D1~D10（详见 `docs/daily/M0对齐_差异清单_A视角.md`，此处是发给你的最终口径，以本文为准）。
> 状态：**大部分已冻结**，文末 §4 有 3 项等你今天答复后即可全部闭环。
> 更新（同日）：已收到 B Q1–Q3 答复 + ingest 密钥口径，回执见文末 §6；D1 收敛为 2 项，落地待《雷达标准消息契约_v1》原文进仓核验。

---

> ## ⚠️ 本文已停止作为开发依据（2026-09-10 定）
>
> **现行事实源**：`docs/B侧接口契约_M0.md`（接口/字段/枚举）+ `docs/message-contract.md`（消息契约），二者均已同步到代码现状。
>
> **为什么留在这里不删**：**D1–D10 的编号定义只在本文件**，`message-contract.md`（「D2/D3/D7/D8」）与 `B侧接口契约_M0.md`（「见 §4-D6」）都在引用。整体移走会让这些引用悬空。所以就地作废：**编号仍以本文为准，表内数值一律以现行文档与代码为准**。
>
> **签字流程已取消**：本文原为「发给 B 签字的冻结口径」。项目已改为**单仓库（monorepo）单一事实源**——双方直接读同一份文档、同一份代码，git 历史本身就是「谁在何时同意了什么」。签字这个动作的全部价值是「在没有共享事实源时留一条双方同意的记录」，单仓之后它成了没有信息量的一层仪式，故取消。M0 的收口方式改为：**仓库文档与代码保持一致**，不再走签字。
>
> **本文与现状不符之处**（照本文实现会出错的地方，逐条列出）：
>
> | 位置 | 本文写的 | 实际（以现行文档/代码为准） |
> |---|---|---|
> | §2-D5 | 规则类型 `THRESHOLD/RATE/CHANGE` 均为合法取值 | **只收 `THRESHOLD`**；传 `RATE`/`CHANGE` → 400（`AlarmRuleService`）。速率类告警请对 `rate_mm_d` 建 `THRESHOLD` 规则 |
> | §2-D6 | 默认规则 `defo_mm THRESHOLD gte 10 / 恢复 5` | **三条**：`gte +3.0/恢复+1.0`、`lte −3.0/恢复−1.0`（均 warning）、`gte +5.0/恢复+2.0`（alarm，V4 补）。原 `gte 10` 因真实数据范围仅约 −3.6~+3.3mm 而**永不触发** |
> | §2-D2 | 「**不需要改表**」 | 已到 **V14**：V3（`alarm` 加 `alarm_type`/`device_id`、放开 `point_id NOT NULL`）、V4（升级档规则）、V12（`measurement.ingest_mode`，补报与实时分离）、**V14（`alarm.metric_code` 副本 + `open_key` 可空唯一索引，见下）**。表结构一律以 `backend/src/main/resources/db/migration/` 为准 |
> | §1 归属行 | 数据/告警/实时/媒体 = B | B 已转前端，这些模块**由 A 就地补齐**并验收（1–8 套件） |
>
> 另有两处**本文未覆盖**、后续才定的口径：`collectTime` 必填且必须可解析、非法即整条 `REJECTED`（见 `B侧接口契约_M0.md §2`）；全仓时间输出统一带 `+08:00`（`JacksonTimeConfig`）。

## 1. 双方已经一致的基线（无需再议）

| 项 | 口径 |
|---|---|
| 基础路径 | `/api/v1` |
| 统一信封 | `{ "code": 0, "message": "success", "data": ... }`，`code=0` 成功，非 0 失败；成功消息文案 A 用 `message` 字段 |
| 时间 | ISO8601 带时区（如 `2026-09-09T09:14:33.256+08:00`） |
| 单位 | 位移 mm、速率 mm/d |
| DB id | **一律数值 BIGINT**（A 的 identity，自增 1000 起，种子占用 1~N）；**字符串是 code（业务键）不是 id** |
| 档案归属 | project/scene/object/point/device 档案 CRUD = A；你只读 `pointId/pointCode/deviceId/deviceCode` |
| 数据/告警/实时/媒体 | ingest、latest/series/summary、alarm、stream、media = ~~B~~ → **A**（2026-09-10：B 转前端，这些模块由 A 就地补齐并通过 8 套件验收） |
| 错误 | 你抛错用 A 的 `BizException`（同一 backend 工程可直接 import），不要自建 `BusinessException` |

## 2. M0 冻结口径（请 B 按此调整你的实现/文档）

| # | 主题 | 冻结结论 | 你需要做什么 |
|---|---|---|---|
| D1 | 测项编码 | 默认每点 **2 项**：`defo_mm`（位移 mm）/ `rate_mm_d`（速率 mm/d）。**（B 已答复：真实雷达每点仅 defo_mm+rate_mm_d、无三分量 → 按 2 项重写，见 §6）** | 消息/规则里的 metricCode 用 `defo_mm`、`rate_mm_d` |
| D2 | 消息→落库 | V1 `measurement` 是**按测项一行**存储。你 ingest 收到一条含 N 个测项的消息 → **拆 N 行写入**，共用同一 `message_id`（幂等键 `device_id+message_id`，重复消息整条去重、不重复写不重复报警）。~~**不需要改表**~~ <br>⚠️ **2026-09-10 更正**：`measurement` 表本身确实没改，但 `alarm` 表改了（V3 加 `alarm_type`/`device_id` 并放开 `point_id NOT NULL`，V4 补升级档规则）——照「不需要改表」理解会漏掉这两个迁移 | 你的 ingest 按拆分规则落库；拆分规则我会同步进 `message-contract.md` |
| D3 | 附加字段 | 消息里的 `position/signal/state` 等存入 `measurement.attributes`（JSON，≤1024），无需加列 | 读取 latest 时从 attributes 还原 |
| D4 | 设备状态归属 | `GET /api/v1/devices/{id}/status` **归 A**（已实现，返回 `deviceId/numeric`、`code`、`status(ONLINE/OFFLINE/FAULT)`、`online`、`battery`、`lowBattery`、`lastReportTime`）。**从你的清单里删除该接口**；你要的 `health`(含 DATA_ABNORMAL) 若要保留，由你基于 A 的 status + 数据质量另出，不重复"在线判定" | 删接口；需要 health 再单独对 |
| D5 | 枚举 | 告警级别 `notice/warning/alarm`（小写）；警情状态 `PENDING/CONFIRMED/PROCESSING/OBSERVING/RESOLVED/FALSE_ALARM`；处置动作 `confirm/research/dispatch/handle/resolve/misreport`；~~规则类型 `THRESHOLD/RATE/CHANGE`~~；质量 `RAW/VALID/SUSPECT/FAULT`。A 的 V1 注释与种子将统一成这套 <br>⚠️ **2026-09-10 更正**：规则类型**只收 `THRESHOLD`**，传 `RATE`/`CHANGE` → 400。其余枚举不变 | 你的枚举别再用 `INFO/WARN/CRITICAL` 等其它取值 |
| D6 | alarm_rule 结构 | A 将扩展列以承载你的规则对象：`point_id`(可空=全局)、`metric_code`、`rule_type(THRESHOLD/RATE/CHANGE)`、`operator(gte/lte)`、`threshold_value`、`window_minutes`、`recovery_value`、`level(notice/warning/alarm)`、`repeat_suppress_seconds`、`enabled`。~~默认规则种子改为 `defo_mm THRESHOLD gte 10 / 恢复 5`~~ <br>⚠️ **2026-09-10 更正**：字段集不变，但默认规则是**三条**——`gte +3.0/恢复+1.0`、`lte −3.0/恢复−1.0`（均 warning）、`gte +5.0/恢复+2.0`（alarm，V4 补）。原 `gte 10` 因真实数据范围仅约 −3.6~+3.3mm 而永不触发（详见 §6 末）| 你的规则 CRUD 按此字段集实现，等 A 的 schema |
| D7 | ingest 鉴权 | Demo 无网关 → A 放行 `/api/v1/ingest/**` 并校验共享密钥：请求头 `X-Ingest-Key: <key>`，key 从环境变量 `MONITOR_INGEST_KEY` 读取（缺省用 `application.yml` 的 dev 值）。密钥不符 → 401 | 你的模拟器/上报端带 `X-Ingest-Key` 头 |
| D8 | SSE 鉴权 | `EventSource` 带不了 Header → A 的 `JwtAuthFilter` 支持 query token：`GET /api/v1/stream?token=<JWT>`。仅该路径走 query，其余仍走 `Authorization` 头 | 你前端用 `EventSource('/api/v1/stream?token='+token)` |
| D9 | 异常 | 统一用 A 的 `BizException`，不要自建 | —（见 §1 错误行） |
| D10 | id vs code | 响应中同时给数值 id 与字符串 code（如 `pointId: 1000, pointCode: "P-HK01"`）；路径参数用数值 id；上报消息里用 code | 修正你样例中的 `"pointId":"P1"` 这类字符串 id |

## 3. A 侧可用/将改动清单（让你知道时间线）

**已可依赖（已实现并验证）**：
- `POST /api/v1/auth/login`、`GET /auth/me`（账号 admin/operator/analyst/maintainer，密码统一 123456，A 的 DataInitializer 幂等创建）
- 档案读：`GET /api/v1/{organizations,projects,scenes,objects,points,metrics,devices}`（list 直接返回数组）
- `GET /api/v1/devices/{id}/status`、`/devices/{id}/points`、维护记录、审计日志
- Swagger：`/swagger-ui/index.html`、OpenAPI：`/v3/api-docs`

**今天将改（不影响你已开工的纯 B 代码，只影响你等 schema/鉴权）**：
1. `SecurityConfig`：放行 `/api/v1/ingest/**`（+`X-Ingest-Key` 校验）；`/api/v1/stream` 允许 query token
2. `JwtAuthFilter`：仅 `/stream` 支持从 `?token=` 取 JWT
3. V1：`alarm_rule` 扩展列（D6）+ 枚举注释统一（D5）
4. V2：测项种子与默认告警规则按 D1 重写（**等 §4.Q2 答复后**）
5. `message-contract.md`：写入 D2 拆分规则、D3 attributes、D7/D8 鉴权约定

> 以上 schema 改动发生在 **M0 冻结前**（尚未提交/尚未给你联调用），改动安全。

## 4. 需要你今天提供/确认（3 项，全部闭环就绪）

> 已收到 B Q1–Q3 答复（2026-09-09），逐条见 §6 回执。

- **Q1（最高优先）**：《雷达标准消息契约_v1》原文，放到 `docs/`。它是 D1/D2 唯一事实源。
- **Q2**：真实雷达每点实际输出几个量？只有 `位移 defo_mm + 速率 rate_mm_d` 两项，还是含三分量 X/Y/Z？（决定 V2 测项种子 & 后续规则可选项）
- **Q3**：`pointCode` 体系用谁的？默认采用 A 档案点号 `P-HK01…P-BP04`（上报即此码）；若雷达自带点编号请明示，A 会同步改 V2 种子。

## 5. 请你同步更新你那份《B侧接口契约_M0》的地方

1. 删去 `/api/v1/devices/{deviceId}/status`（归 A，见 D4）
2. 消息/规则示例里的测项与点号按 D1/D10 对齐（`defo_mm/rate_mm_d`、`P-HK01…` 而非 `RT1`、id 用数值）
3. ingest 请求补充 `X-Ingest-Key` 头（D7）；stream 订阅改 `?token=`（D8）
4. 枚举统一为 §2-D5 表；异常统一用 `BizException`（D9）

---

## 6. B 答复回执与收尾状态（2026-09-09，A 记）

> 依据：B 对 Q1–Q3 的书面答复 + ingest 密钥口径（同日）。逐条记录，作为 M0 冻结收口依据。

**Q1《雷达标准消息契约_v1》原文（最高优先）**
→ B：已放到 `docs/`。⚠️ **A 仓库暂未同步到该文件**（本仓 docs/ 下无此文、git 亦无未提交新增）→ 请 B 确认提交分支/路径，或直接把原文贴给 A；到手后 A 落地 D1/D2（V2 测项种子 + message-contract §2）。

**Q2 雷达每点输出量 → D1 定案**
→ B：真实点形变雷达每点仅 **2 项**——`defo_mm`(累计形变) + 由历史推导的 `rate_mm_d`(速率)，**无 X/Y/Z 三分量**。
→ 本文件 §2-D1「若含三分量则保留」分支关闭：A 按 **2 项** 重写 V2 测项种子，默认规则 metric_code→`defo_mm`。状态：**已闭合 · 待落地**（等 Q1 原文进仓核验，避免改两遍——A 侧决策）。

**Q3 pointCode 体系 → V2 点号不变**
→ B：用 A 档案点号 `P-HK01…P-BP04`（上报即此码）；B 的 CSV 回放适配器负责把雷达目标映射到这些码（可配置）。若雷达自带点编号，B 另行告知。
→ A 侧 `monitor_point.code` 体系不动。

**ingest 共享密钥（§2-D7 细化）**
→ B：A 侧校验请求头 `X-Ingest-Key`；B 上报脚本默认占位 `dev-ingest-key`，用 `--ingest-key <值>` 或 env `MONITOR_INGEST_KEY` 覆盖。
→ 双方 dev 值**统一为 `dev-ingest-key`**：A 侧 `application.yml` 默认已同步（`${MONITOR_INGEST_KEY:dev-ingest-key}`），两侧默认一致；生产/联调用 env 覆盖。

**B 第二轮答复（2026-09-09 当晚）**
- **Q4** `/api/v1/devices/{id}/status` 归属：**B 确认归 A 保留**，不重复「在线判定」；B 如需含 `DATA_ABNORMAL` 的 `health`，基于 A 的 status + 自身数据质量单独另出。
- **Q5** 告警级别：**B 确认完全对齐**——A 默认 `warning` = B 的 warning(预警)；notice 注意 / warning 预警 / alarm 告警 语义一致。
- **Q1 契约原文**：**已进仓（2026-09-09）**——`docs/message-contract.md` 被替换为 B 的雷达契约原文并随本轮提交；契约核心（每点 2 测项 `defo_mm/rate_mm_d`、幂等 `device_id+message_id`、quality `RAW/VALID/SUSPECT/FAULT`、点号 `P-HK01…P-BP04`）与答复一致 → **A-3 落地条件已满足**。⚠️ 共同分支/remote 线上推送仍待与 B 商定（不影响内容）。
- **设备码大小写（A 决策）**：A 侧 `device.code` 由 `RADAR-001` **对齐契约为 `radar-001`**（V2 seed 已改），与 message-contract 上报口径一致。
- **demo 阈值（B 建议，A 决策）**：真实 20260827 defo 范围约 **-3.6 ~ +3.3mm**，原 `defo_mm ≥10` 几乎不触发 → A 决策默认规则用**双向 |abs| ±3mm**：`defo_mm` gte +3.0/恢复1.0 与 lte −3.0/恢复−1.0（均 warning），随 A-3 写入 V2 seed；请 B 以 ~4mm 为参考对齐 `--inject-overlimit`。

**收尾状态**
- 提问（契约口径 + Q1–Q6）均已答复；D1 口径已定；Q4/Q5 已闭合；设备码已对齐 `radar-001`。
- ~~M0 签字剩余：① A-3 执行 ② 共同分支/remote 线上协作路径待与 B 定。无待答问题。~~
  - **① A-3 已完成**（2026-09-10）：V2 测项种子为每点 2 项（`defo_mm`/`rate_mm_d`），默认规则 ±3.0 双向。
  - **② remote 已定并已在使用**：B 的 GitHub `iron8423/monitor-system` 为单一事实源，A 持续推送。
  - **签字流程本身已取消**（2026-09-10 定，理由见文首横幅）：单仓之后双方读同一份文档、同一份代码，git 历史即「谁在何时同意了什么」，签字成了没有信息量的一层仪式。M0 的收口方式改为**仓库文档与代码保持一致**。
