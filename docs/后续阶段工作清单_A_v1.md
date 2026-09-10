# A 后续阶段工作清单（阶段 1 收尾 → 阶段 2）

> 日期：2026-09-09 · 作者：A
> 依据：《双人分工实施方案v2》§6/§7/§8（A0–A5、阶段 1/阶段 2、里程碑 M0–M5）
> **更新（当日·协作模式定案）**：以 **B 的 GitHub 仓库 `iron8423/monitor-system`（私有，main）为唯一共享事实源**，A 侧全部历史/内容并入并上传；后续两人同步一律看仓库状态，不再 md 寄件。A 上传账号 = **`yanzu1024`**（待 B 加 collaborator），本机 SSH ed25519 密钥已生成待注册；合入方向 = 保留双方 commit、`git merge origin/main --allow-unrelated-histories`。阻塞：collaborator + 公钥注册（详见当日 A 日志晚段 6）。
> 范围：只列 **A（底座）** 的后续动作；B 侧仅在与 A 的衔接点标注。勾选状态随进展更新。

---

## 0. 一句话状态

A 侧**阶段 1 交付已完成并通过端到端验证**（A0–A4 + M0 的 schema/种子/契约起草 + M0 对表 D3–D8 落地），
git 已含首个提交 `221d96b`（A 交付基线，B 可拉分支协同）。
**尚未到「阶段 1 整体完成」**：M0 契约差 B 签字——B 已答**全部提问**，且《雷达标准消息契约_v1》原文**已进仓**（`docs/message-contract.md`，2026-09-09 随本轮提交）→ **A-3（V2 每点 2 测项 + 默认规则 ±3mm 双向）内容就绪、待 A 执行**（用户决策：本轮只提交文档，metric 重构后置）；~~共同分支/remote 线上推送路径仍待与 B 商定~~ → **已定（2026-09-09）：B 的 GitHub `iron8423/monitor-system` 单一事实源，A 并入上传；阻塞 = collaborator（yanzu1024）+ SSH 公钥注册**；且后端闭环验收（M2：超限→告警→处置→解除）依赖 B 的 ingest/模拟器/告警模块。

---

## 1. 阶段 1 内 · A 剩余清单（本周）

| # | 事项 | 阻塞 | 说明 |
|---|---|---|---|
| A-1 | **首个 git commit**（A 交付基线） | ✅ 已完成 | `221d96b feat(repo): 初始化通用监测管理系统（A0-A4 + M0 契约）` |
| A-2 | **M0 冻结收口**：发 B《M0_接口冻结_致B_v1》，收 Q1–Q5 答复 | ~~商定共同分支/remote~~ → 等 B 加 collaborator（yanzu1024）+ SSH 公钥注册 | 已发并收到 B 全部提问答复（2026-09-09，含 Q4/Q5 闭合）；剩 Q1 契约原文文件进仓：B 已作为 `message-contract.md` 提交共同分支，本仓无 remote → **已定（2026-09-09）：B 的 GitHub `iron8423/monitor-system` 单一事实源**，等 collaborator + SSH 后 A 执行 merge+push；demo 默认规则阈值决策已定（±3mm 双向，随 A-3 落） |
| A-3 | **D1/D2 收尾**（V2 测项种子/默认规则/契约对齐） | 无（内容就绪，由 A 排期） | **口径已定且原文已进仓**（message-contract.md）：每点 2 测项 `defo_mm`/`rate_mm_d`（无 X/Y/Z）、点号用 A 档案码、默认规则 metric_code→`defo_mm` 且**阈值双向 \|abs\| ±3mm**（gte +3.0/恢复1.0 + lte −3.0/恢复−1.0，均 warning）。message-contract §2 已由 B 契约原文覆盖。**用户决策：本轮不落 metric 重构，后置执行**（届时改 V2 metric 行 + alarm_rule 默认规则 + Metric 注释） |
| A-4 | schema/seed **A 唯一维护**：B 加表/加列需求走 A 统一出 V3 | 无 | 防两人同仓改迁移脚本冲突（§10 风险 1）；M0 签字后 schema 视为冻结，只进 V3 不回头改 V1/V2 |
| A-5 | **契约接口人**：配合 B 的 ingest 落库拆行、latest/series 返回、alarm_rule 字段答疑 | 随 B 开发节奏 | 只答疑/出 V3，不改已冻结 schema |
| A-6 | （可选）CesiumJS 可行性 spike | 无 | §10 风险 4：B 的 3D 大屏攻坚前置，A 有空档先验「真实地形 + 标点变色 + 点击弹窗」可行性，非必须 |
| A-7 | **验收套件落仓** | ✅ 已完成 | `tools/acceptance/`：6 套件 / 117 条断言 + `run-all.sh --fresh`（另起空库后端）+ README（脚本↔§9 验收条映射）。覆盖 §9 里后端可独立验证的部分。同库连跑三轮 117/117。对应 M5 判据「一键过验收脚本」的后端部分。 |

> B 侧阶段 1 任务：B0–B5（telemetry/模拟器/quality/alarm/realtime/media）→ 在 M1/M2 联调点与 A 汇合。

## 2. 阶段 2 · A 留后端收尾（§7.1 + A5）

| # | 事项 | 依赖 | 说明 |
|---|---|---|---|
| B-1 | 设备/质量闭环补全 | 阶段 1 与 B 的 health 边界（D4）定稿 | A 保留 `/devices/{id}/status`；`health`(DATA_ABNORMAL) 归属按对表结果 |
| B-2 | 媒体存储对接 | B 实现 `POST /media` | A 提供 `upload.dir`（application.yml 已配）与静态资源访问约定；`/media` 代码归 B |
| B-3 | **A5 · Docker Compose 一键启动** | Docker 环境 | backend + postgres +（含前端构建产物）；env 注入 `JWT_SECRET` / `MONITOR_INGEST_KEY` / `PG_*` |
| B-4 | **PostgreSQL 迁移验证** | A5/B-3 | 切 `application-postgres.yml` 跑通；H2 内存库重启即清 → PG 持久化（对应验收脚本第 8 条「重启数据不丢」） |
| B-5 | README 完善 | 阶段 1 演示可用 | 启动步骤 + 演示账号（admin/operator/analyst/maintainer，123456）+ 触发告警/断连/重复的演示脚本 |
| B-6 | OpenAPI 契约维护 + 联调响应 | 全程 | A 是「契约接口人」，联调优先级高于收尾（§10 风险 2） |
| B-8 | **档案 CRUD 时间统一带时区** | 无（影响面大，需定夺） | `BaseEntity.createdAt/updatedAt` 是裸 `LocalDateTime`，被 `BaseCrudController` 原样返回 → `GET /projects`、`/points` 等**所有**档案端点的时间**无偏移**，违反契约 §0「时间 ISO8601 带时区」（`alarms.triggeredAt` 那些走 `Times.iso` 是带 `+08:00` 的）。<br>同日已单独修掉 `DeviceStatusVO.lastReportTime`（同类缺陷、且是契约点名的字段），但档案时间属**全局响应契约**，宜注册 Jackson 序列化器统一走 `Times.iso` 一次修净。<br>**前端尚未开始写，是改的窗口期**；越晚改波及越大。 |
| B-9 | `latest` 加次排序键 | 无 | `MeasurementQueryService#latest` 用 `orderByDesc(collectTime).last("LIMIT 1")`，**无第二排序键**：同一测点同一 `collect_time` 有两条（不同 messageId）时，「最新值」取哪一行随数据库返回顺序而变，兄弟测项也跟着那一行的 messageId 走。建议按 `receive_time` 或 `id` 兜底排序。发现于 2026-09-10（验收套件双跑时暴露，非套件本身问题）。 |
| B-10 | 验收第 5 条「生成设备告警」归属与形态 | 与 B 对表 | 全仓唯一 `@Scheduled` 是 SSE 心跳，`OFFLINE` 只在 `DeviceStatusPolicy`（读时算、拉）。`/devices/{id}/status` 已实现且离线/恢复正确，但**没有任何东西生成设备告警**；而本清单 §4 写「第 1/2/3/5 条主体 B」——**接口两侧都假定对方生成**。<br>待定：归谁做、是否进警情列表（`alarm.point_id` 为 `NOT NULL`，设备告警无点可挂，要么走 V3 加表、要么借 `device_point` 挂到关联测点）、什么 level。 |
| B-11 | 验收第 3 条「等级升高能升级」 | 需先定语义 | `AlarmEngine` 在同规则已有未解除警情时只判恢复（`if (open != null) { shouldRecover ? 恢复 : 什么都不做 }`），**无升级路径**；不同 level 是不同规则，会各自成警情。「持续超限不刷屏」那半句已实现并有覆盖（04 套件 ⑦）。 |
| B-7 | **生产 profile 收窄调试面**（H2 console / swagger） | B-4 PostgreSQL 迁移 | `SecurityConfig` 有意放行 `/h2-console/**`、`/swagger-ui/**`、`/v3/api-docs/**`；`spring.h2.console.enabled: true` 写在**基础** `application.yml`。<br>已核实三点，威胁是现实的而非假设：① `H2ConsoleAutoConfiguration` 的生效条件只有 servlet 应用 + classpath 有 `JakartaWebServlet` + 该开关为 true（`javap` 查 3.5.16 的注解），**没有一条与数据源类型有关**；② H2 是 `runtime` scope → 各 profile 的 runtime classpath 上都有；③ `application-postgres.yml` 只覆盖了 datasource，**未**关该开关。<br>后果：切 `postgres` profile 后 console 仍注册，且**无需任何凭证**即可访问、可自行指定任意 JDBC URL（含 PG 库）。<br>落地要求：PG/生产 profile 显式 `spring.h2.console.enabled: false`，console 放行按 profile 收窄到 dev（swagger 视联调需要可保留）。<br>发现于 2026-09-10：借该 console 直写库验证告警引擎兜底时**无凭证即进入**。 |

> ⚠️ 本机无 Docker/PG（此前确认）→ A5（B-3/B-4）需要一台能跑 Docker 的环境验证，落地时先补环境。

## 3. 里程碑对表（M0–M5，标 A 的动作与判据）

| 里程碑 | 计划节点 | 内容 | A 的动作 | A 侧判据 |
|---|---|---|---|---|
| M0 契约冻结 | 阶段 1 · 第 0 天 | schema/种子/消息契约/OpenAPI/git 约定 | ✅ 已完成 A 侧草案 + D3–D8 | 收 B Q1–Q3 → 签字 |
| M1 后端骨架 | 阶段 1 · 第 2 天 | A：登录+档案 CRUD（✅ 已完成）；B：ingest+模拟器+quality | 参与联调 | 模拟器→落库→`latest/series` |
| M2 后端闭环 | 阶段 1 · 第 5 天 | A：asset/audit（✅ 已完成）+schema 稳定；B：alarm 状态机/realtime/media | 支持 schema/契约 | 超限→告警→处置→解除全链 |
| M3 前端门面 | 阶段 2 · 第 8 天 | B：登录+3D 壳+位移分析页 | 供曲线接口联调 | 前端曲线拉到真实数据 |
| M4 前端闭环 | 阶段 2 · 第 11 天 | B：告警中心+管理端；A：设备闭环+部署 | 开始 A5 | 告警处置 + 新建测点/规则生效 |
| M5 演示就绪 | 阶段 2 · 第 14 天 | B：影像+实时；A：Docker Compose | A5 完成 | `docker compose up` 一键过验收脚本 |

## 4. 验收脚本 8 条 · A 的参与面

- **第 1/2/3/5 条**（上报→刷新、幂等、超限告警、断连离线）：主体 B（模拟器/规则/quality），A 供 schema、种子、`/devices/{id}/status` 与消息契约。
- **第 4 条**（确认→研判→处置→解除留痕）：B 的状态机 + A 的审计切面已支持动作留痕。
- **第 6 条**（无人机照片挂测点）：B（media），A 供存储目录约定。
- **第 7 条**（新建测点/规则 → 业务端可见）：A 档案 CRUD + 规则表结构（D6）已就绪。
- **第 8 条**（docker compose 一键 + 数据持久）：**A（A5）**。

## 5. 当前卡点 / 风险

| 卡点 | 影响 | 对策 |
|---|---|---|
| ~~git 无首个提交~~ | ✅ 已提交 | `221d96b`，B 可拉分支协同 |
| M0 未签字：契约原文已进仓（`message-contract.md`）、口径已齐；**A-3（metric 重构）待 A 执行**；~~共同分支/remote 线上推送路径待与 B 定~~ → **已定（B GitHub 单一事实源）** | A-3 未执行前 V2 别大动 | A 排期执行 A-3（V2 metric 2 项 + 默认规则 ±3mm 双向）；线上路径已定，等 collaborator（yanzu1024）+ SSH 后 A 执行 merge+push |
| B 转前端后 A 是后端唯一人 | 联调没人接 | A-5 契约接口人职责优先（§10 风险 2） |
| 本机无 Docker/PG | A5 无法在本机验证 | B-3/B-4 需 Docker 环境，先补环境再动 |
