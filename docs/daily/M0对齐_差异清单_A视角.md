# M0 对表：A 侧实现 vs B 侧接口契约（A 视角差异清单）

> 日期：2026-09-09 · 作者：A
> 触发：收到 `docs/daily/B侧接口契约_M0.md`（14:38）。该文档引用了仓库内不存在的《雷达标准消息契约_v1》。
> 关键词交叉核对结论：`defo_mm / rate_mm_d / RT1 / pointCode / 雷达标准消息契约` 在 A 手上的《需求分析与开发指引》与 `docs/message-contract.md` 中均 **0 处** → 双方契约不同源，必须先冻结消息契约，再谈字段。
> **更新（同日傍晚）**：收到 B 对 Q1–Q3 的书面答复 + ingest 密钥口径 → ① Q2 确认真实雷达每点仅 `defo_mm`(累计形变)+`rate_mm_d`(速率)，**无 X/Y/Z** → **D1 收敛为 2 项已定案**；② Q3 确认点号用 A 档案码 `P-HK01…P-BP04` → 编码体系不动；③ Q1 的《雷达标准消息契约_v1》原文 B 称已放 `docs/`，但**仓库内暂未同步到** → D1/D2 落地等该原文进仓核验（A-3 顺延）；④ ingest dev 密钥**统一为 `dev-ingest-key`**（A 侧 `application.yml` 默认已同步）。B 答复全文与逐条回执见 §4。
> **更新 2（当晚）**：收到 B 第二轮答复 → ⑤ Q4 `/devices/{id}/status` **确认归 A 保留**（B 不重复在线判定）；⑥ Q5 级别语义**确认完全对齐**；⑦ B 正式给出契约核心（每点 2 测项 `defo_mm/rate_mm_d`、幂等 `device_id+message_id`、quality `RAW/VALID/SUSPECT/FAULT`、点号 `P-HK01…P-BP04`，均与 A 现状/规划一致），称已把原文作为 `docs/message-contract.md` 推到共同分支（本仓无 remote，分支/推送路径**待定**）→ **A-3 仍等原文文件进仓后落地**；⑧ B 提示真实数据 defo 范围 -3.6~+3.3mm、原 ≥10 几乎不触发 → **demo 默认规则阈值决策：双向 |abs| ±3mm**（随 A-3 一起写 V2 seed）。逐条见 §4。
> **更新 3（当晚）**：《雷达标准消息契约_v1》原文文件**已进仓**——`docs/message-contract.md` 被替换为 B 的雷达契约原文并随本轮提交；内容与对话答复一致 → **A-3 条件满足**。用户决策：**本轮只提交文档 + 设备码对齐，metric 重构（每点 2 测项/默认规则 ±3mm）后置执行**。另：A 侧 `device.code` 由 `RADAR-001` **对齐契约为 `radar-001`**（V2 seed 已改）。

---

## 0. 术语对照（当前双方口径）

| 概念 | A（已落地实现） | B（契约文档） | 冲突 |
|---|---|---|---|
| 统一信封 | `Result{code,message,data}`，code=0 成功 | `{code,message,data}` 一致 | ✅ 一致 |
| DB 主键 | `id` BIGINT identity（自 1000 起，种子 1~N） | 未声明（响应样例写 `"P1"`/`"PRJ-01"` 字符串） | ⚠️ 需钉死 |
| 业务键 | `code` 唯一：点 `P-HK01..P-BP04`、设备 `radar-001`（A 侧已对齐契约小写） | 样例点 `RT1`、设备 `radar-001` | ✅ 已对齐（2026-09-09） |
| 测项编码 | `metric.code`：每点 5 项 `X/Y/Z/DISP/VEL` | `defo_mm` / `rate_mm_d` | ❌ 不同源 |
| 单位 | DISP→mm、VEL→mm/d（V2 seed） | 位移 mm、速率 mm/d | ⚠️ 语义同、编码不同 |
| 消息形态 | 单点**单测项单值** + `metricCode`（对齐基线 §7） | 单点一消息带 `metrics{}` 多测项 + position/signal/state | ❌ 结构不同 |
| 级别枚举 | `INFO/WARN/CRITICAL`（V1 设计）+ 告警规则种子 `DISP ACCUMULATE ≥10 恢复5` | 告警 `notice/warning/alarm` | ❌ 值不同 |
| 警情动作 | V1 设计 `CONFIRM/ANALYZE/PROCESS/RESOLVE/REJECT/ESCALATE` | `confirm/research/dispatch/handle/resolve/misreport` | ❌ 值不同 |
| 设备状态 | A3 已实现 `GET /devices/{id}/status`（派生 online/battery/lowBattery） | B 又列为己方，返回 `health NORMAL/LOW_BATTERY/OFFLINE/DATA_ABNORMAL` | ❌ 双归属 |

## 1. 必须先冻结的两份契约（其余全靠这两份派生）

1. **《雷达标准消息契约_v1》**：**已进仓（2026-09-09）**——`docs/message-contract.md` 被替换为 B 的雷达契约原文并随本轮提交。契约核心（每点 2 测项 `defo_mm/rate_mm_d`、幂等 `device_id+message_id`、quality `RAW/VALID/SUSPECT/FAULT`、点号 `P-HK01…P-BP04`）与对话答复一致。仍是 D1/D2 落地的唯一事实源 → **A-3 落地条件已满足**；metric 重构执行时点由 A 安排（用户决策本轮后置）。共同分支/remote 线上推送仍待与 B 商定（不影响内容）。
2. **测点/测项/设备编码规范**：雷达上报的 `pointCode` 体系 —— **已定（B Q3 答复）**：用 A 档案点号 `P-HK01…P-BP04`（上报即此码）；B 的 CSV 回放适配器负责把雷达目标映射到这些码（可配置）。若雷达自带点编号，B 会另行告知 → V2 点号**无需改**。

## 2. 差异点与建议处理（A 视角，M0 会议逐条勾）

| # | 差异 | 建议结论 |
|---|---|---|
| D1 | 测项编码 `X/Y/Z/DISP/VEL` vs `defo_mm/rate_mm_d` | **已闭合·待落地**（B Q2 答复 2026-09-09）：真实点形变雷达每点仅输出 2 项——`defo_mm`(累计形变) + 由历史推导的 `rate_mm_d`(速率)，**无 X/Y/Z 三分量** → A 收敛为 2 项，`DISP≈defo_mm`、`VEL≈rate_mm_d`。A 改 V2 seed + message-contract，B 同步；**契约原文已进仓核验（message-contract.md）→ 落地待 A 执行（A-3，用户决策本轮后置）** |
| D2 | 消息结构：单测项 vs 一消息多测项 | V1 `measurement` 已是**按测项一行**（`metric_code` + `measure_value`）。一消息 N 个测项 → 拆 N 行写，`message_id` 相同，天然幂等键可用。B 的 ingest 落库按此表写即可，**不需要改表**，但要在消息契约里写明"拆分规则" |
| D3 | position/signal/state 无处放 | `measurement.attributes`(JSON, 1024) 承接：`{"position":{...},"signal":0.9,"state":"normal"}`。够用则不加列 |
| D4 | `/devices/{id}/status` 双归属 | **保持 A 归属**（A3 已实现 + 档案域内聚）。B 的 `health` 枚举是另一层语义，建议 B 改读 A 的 status 接口做派生，或明确让 B 单独做 `health`，A 不重复。二选一，禁止两套并存 |
| D5 | alarm 级别/动作/状态枚举 | 统一一套。倾向 **B 契约的语义**（notice/warning/alarm；confirm/research/dispatch/handle/resolve/misreport；状态含 FALSE_ALARM）更贴近真实处置闭环；A 改 V1 `alarm_rule/alarm/alarm_action` 的枚举注释与种子，未冻结前可改 |
| D6 | `alarm_rule` 表结构 vs B 规则对象 | B 规则对象含 `pointCode / metricCode / operator(gte/lte) / value / windowMinutes / recoveryValue / repeatSuppressSeconds / enabled`。A 现有表只有 `metric_type/threshold_type/threshold_value/recovery_value/alarm_level/enabled`，**缺 per-point、缺 operator、缺 window、缺 repeatSuppress**。A 改 V1 补齐（A 统一维护 schema，B 等表） |
| D7 | `/ingest` 免 JWT | A 的 `SecurityConfig` 目前未放行 `/api/v1/ingest/**` → 会 401。Demo 无网关 → A 加放行 + 共享密钥校验（Header，如 `X-Ingest-Key`），B 上报时携带。M0 需定密钥放哪（环境变量） |
| D8 | `/stream`(SSE) 鉴权 | `EventSource` 无法带 Header → JWT 走 query 参数（`/stream?token=`）。需 A 的 `JwtAuthFilter` 支持从 query 取 token。B 前端按此订阅 |
| D9 | 异常类命名 | B 写 `BusinessException`，A 是 `BizException`。纯叫法，统一即可（用 A 的，B 抛错时用 A 的类） |
| D10 | 响应样例里的字符串 id | B 样例 `"pointId":"P1"`、`"deviceId":"radar-001"`。钉死：**id 一律数值 BIGINT，code 才是字符串业务键**；响应里同时给 `pointId(数值)+pointCode(串)` 避免歧义 |

## 3. M0 冻结前，A 侧可立刻做的小改动（待 B 确认后执行）

- [x] `SecurityConfig`：放行 `/api/v1/ingest/**`（+共享密钥）；支持 `/stream` query token
- [x] `JwtAuthFilter`：支持从 `?token=` 解析 JWT（仅限 SSE 场景）
- [x] V1 `alarm_rule` 扩展列（point_id/metric/operator/value/window/repeat_suppress）；级别/动作/状态枚举值按 B 语义统一
- [ ] V2 测点/测项编码、默认告警规则与真实雷达量纲对齐 —— **口径已定**（D1：每点 2 测项 `defo_mm`/`rate_mm_d`；默认规则 metric_code→`defo_mm`），**落地顺延**：等《雷达标准消息契约_v1》原文进仓核验后改 V2 + message-contract §2
- [x] `/devices/{id}/status`：与 B 定唯一归属后，决定保留 or 移交（**A 侧已定保留并实现**，见 M0-D4；**B 已确认**——见 §4-Q4）

## 4. 请 B 提供 / 确认（A→B 提问清单 · 附 B 回执 2026-09-09）

1. 《雷达标准消息契约_v1》原文（放 `docs/`）——**最高优先**
   → **B（第一/二轮）**：称已放 `docs/`、作为 `docs/message-contract.md` 提交到共同分支（等你给分支名后推送）。
   → **已进仓（2026-09-09）**：`docs/message-contract.md` 被替换为 B 雷达契约原文并随本轮提交；内容与对话答复一致 → **A-3 条件满足**，metric 重构由 A 安排（用户决策：本轮只提交文档，后置执行）。共同分支/remote 线上推送仍待与 B 商定（不影响内容）。
2. 雷达实际输出的测项：是否只有形变+速率？有没有三分量 X/Y/Z？
   → **B：真实点形变雷达每点仅 2 项**：`defo_mm`(累计形变) + 由历史推导的 `rate_mm_d`(速率)，**无 X/Y/Z 三分量** → D1 收敛为 2 项已定。
3. `pointCode` 体系：雷达自带编号 还是 用我方档案点号？
   → **B：用 A 档案点号 `P-HK01…P-BP04`**（上报即此码）；B 的 CSV 回放适配器把雷达目标映射到这些码（可配置）。若雷达自带点编号，B 另行告知 → 编码体系**不用改**。
4. `/devices/status`：确认让 A 保留（B 只读派生）还是 B 接管？
   → **B：确认归 A 保留**，B 不重复「在线判定」；如需含 `DATA_ABNORMAL` 的 `health`，基于 A 的 status + B 自身数据质量单独另出 → **Q4 闭合**。
5. 告警级别语义：`notice/warning/alarm` 与 A 现在 seed 的默认规则(累计位移阈值)如何对应？
   → **B：完全对齐**——A 默认 `warning` = B 的 warning(预警)；notice 注意 / warning 预警 / alarm 告警 → **Q5 闭合**。
6. `/ingest` 共享密钥的协商方式（env `MONITOR_INGEST_KEY`？）
   → **B：A 侧校验请求头 `X-Ingest-Key`**；B 上报脚本默认占位 `dev-ingest-key`，用 `--ingest-key <值>` 或 env `MONITOR_INGEST_KEY` 覆盖。**已定：dev 值统一为 `dev-ingest-key`**（A 侧 `application.yml` 默认已同步，两侧默认一致）。
7. （B 建议，原清单外）真实 20260827 defo 范围约 -3.6~+3.3mm，原 `defo_mm ≥10` 几乎不触发 → **A demo 默认规则阈值决策：双向 |abs| ±3mm**——`defo_mm` gte +3.0/恢复1.0 + lte −3.0/恢复−1.0（均 warning，A-3 落地时写入 V2 seed）；已同步 B，`--inject-overlimit` 以 ~4mm 为参考对齐。
