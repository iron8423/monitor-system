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

> **更新（2026-09-10 晚）**：B 转前端后其 M1/M2 后端部分由 A 就地补齐，**后端闭环已在 A 侧跑通**——验收套件扩到 **7 套件 / 156 条断言**且全绿（`--fresh` 空库 + 同库连跑三轮）。本轮定案并落地了 §2 的 **B-8～B-12** 五项（档案时间全局带时区、`latest` 次排序键、设备告警、等级升级、collectTime 非法拒收）。**验收第 5 条（设备告警）与第 3 条（等级升级）自此不再是缺口**；仍未覆盖的是第 8 条（Docker/PG，B-3/B-4）与各条的前端呈现。
>
> **更新（2026-09-10 夜）**：**验收链的第一环补上了**（A-8）。此前 01–07 全部用 curl 合成报文，等于绕过链子的起点——后半段验得很扎实，「模拟器」这一环却从没被跑过（`radar_csv_replay` 是回放器不是生成器）。新增 `tools/radar_simulator/` + `tools/acceptance/08-simulator.sh`，套件升至 **8 套件 / 173 条断言**（空库连跑两次 173/173）。**里程碑 §3 里 M1 的判据「模拟器 → 落库 → `latest/series`」至此才算真的成立**（此前只是「等价构造过」）。仍在缺口里的：~~② M0 契约的 B 正式签字~~（**已取消，见下条更新**）；③ 低电量/数据中断告警（**用户定案：暂不做**，见下）。
>
> **更新（2026-09-10 深夜）**：本机装好 Docker，**B-4（PG 迁移验证）已验完**——`postgres:16` 上 V1–V4 全部迁移成功、**173 条断言 173/173**、重启后端数据不丢。「本机无 Docker/PG」这条老卡点至此注销。**M0 签字流程已取消（用户定案）**：项目已用**单仓库（monorepo）单一事实源**——双方读同一份文档、同一份代码，git 历史本身就是「谁在何时同意了什么」。签字这个动作的全部价值是「在没有共享事实源时留一条双方同意的记录」，单仓之后它成了没有信息量的一层仪式。<br>**M0 的收口方式因此改为：仓库文档与代码保持一致**，不再走签字。落地三件事：① `M0_接口冻结_致B_v1.md` 就地作废（编号 D1–D10 仍以它为定义处，但表内数值以现行文档/代码为准，三处不符已逐条标注）；② `message-contract.md` 补上缺失的 `collectTime` 拒收规则；③ 现行事实源 = `B侧接口契约_M0.md` + `message-contract.md`。
>
> **更新（2026-09-10 深夜·二）**：**B-3 已完成**——`docker-compose.yml` + `backend/Dockerfile` + `.env.example` 落仓，`docker compose up -d` 起 db + backend，双双 healthy；**对容器跑验收 173/173**（`run-all.sh` 只认 `BASE`，一行未改）；`down` 再 `up` 后容器确系重建而数据不丢（计数 `49\|9\|16`、schema `v4` 不变，Flyway `Migrating` 0 次）。**§9 第 8 条的两句「一键」与「数据持久」都已实测通过**。至此 A5 收口，阶段 1/2 里 A 侧**只剩「前端呈现」这一半**（`frontend/` 仍是空的，等阶段 2 动工）。
>
> **更新（2026-09-10 深夜·三）**：**B-7 已完成**。它本来就该紧跟 B-3——跑 `postgres` profile 的不再是我临时起的进程，而是 `docker compose up` 出来的**部署产物**，洞随 artifact 一起交付了。修法：`application-postgres.yml` 显式关掉 H2 console，且 `SecurityConfig` 的放行**改为跟着这个开关走**（漏洞形态正是「开关与放行各写一份」）；另把 `frameOptions` 从 `disable()` 收成 `sameOrigin()`。实测 `/h2-console/*` 三个路径全部 **200 → 401**，对照面（health/swagger/未鉴权接口）行为不变，dev profile 下控制台仍可用，两个 profile 各 173/173。**A 侧阶段 1/2 的待办至此清空**，只剩前端。

---

## 1. 阶段 1 内 · A 剩余清单（本周）

| # | 事项 | 阻塞 | 说明 |
|---|---|---|---|
| A-1 | **首个 git commit**（A 交付基线） | ✅ 已完成 | `221d96b feat(repo): 初始化通用监测管理系统（A0-A4 + M0 契约）` |
| A-2 | **M0 冻结收口**：发 B《M0_接口冻结_致B_v1》，收 Q1–Q5 答复 | ~~商定共同分支/remote~~ → 等 B 加 collaborator（yanzu1024）+ SSH 公钥注册 | 已发并收到 B 全部提问答复（2026-09-09，含 Q4/Q5 闭合）；剩 Q1 契约原文文件进仓：B 已作为 `message-contract.md` 提交共同分支，本仓无 remote → **已定（2026-09-09）：B 的 GitHub `iron8423/monitor-system` 单一事实源**，等 collaborator + SSH 后 A 执行 merge+push；demo 默认规则阈值决策已定（±3mm 双向，随 A-3 落） |
| A-3 | **D1/D2 收尾**（V2 测项种子/默认规则/契约对齐） | 无（内容就绪，由 A 排期） | **口径已定且原文已进仓**（message-contract.md）：每点 2 测项 `defo_mm`/`rate_mm_d`（无 X/Y/Z）、点号用 A 档案码、默认规则 metric_code→`defo_mm` 且**阈值双向 \|abs\| ±3mm**（gte +3.0/恢复1.0 + lte −3.0/恢复−1.0，均 warning）。message-contract §2 已由 B 契约原文覆盖。**用户决策：本轮不落 metric 重构，后置执行**（届时改 V2 metric 行 + alarm_rule 默认规则 + Metric 注释） |
| A-4 | schema/seed **A 唯一维护**：B 加表/加列需求走 A 统一出 V3 | 无 | 防两人同仓改迁移脚本冲突（§10 风险 1）；M0 冻结后 schema 视为冻结，只进 V3 不回头改 V1/V2（**已在执行**：V3 加设备告警列、V4 补升级档规则，均未回头改 V1/V2） |
| A-5 | **契约接口人**：配合 B 的 ingest 落库拆行、latest/series 返回、alarm_rule 字段答疑 | 随 B 开发节奏 | 只答疑/出 V3，不改已冻结 schema |
| A-6 | （可选）CesiumJS 可行性 spike | 无 | §10 风险 4：B 的 3D 大屏攻坚前置，A 有空档先验「真实地形 + 标点变色 + 点击弹窗」可行性，非必须 |
| A-7 | **验收套件落仓** | ✅ 已完成 | `tools/acceptance/`：**8 套件 / 173 条断言** + `run-all.sh --fresh`（另起空库后端）+ README（脚本↔§9 验收条映射）。覆盖 §9 里后端可独立验证的部分。空库连跑两次 173/173。对应 M5 判据「一键过验收脚本」的后端部分。 |
| A-8 | **雷达数据模拟器**（验收链第一环） | ✅ 已完成（2026-09-10） | `tools/radar_simulator/radar_simulator.py`（Python 3 标准库，无第三方依赖）+ README。按 `message-contract.md` **连续造数**，不依赖任何外部数据——`tools/radar_csv_replay/` 是**回放器不是生成器**（要真雷达 CSV，默认还指向 B 的 Windows 桌面），拿不到数据就一步也跑不了，于是链子最上游一直没人跑过、套件全用 curl 合成报文。模拟器补上起点：`--step-minutes` 模拟时钟（形变按速率积分，避免墙钟反推出几千 mm/d 的速率）、`--inject-overlimit/duplicate/suspect/outage` 四种注入。新增 `tools/acceptance/08-simulator.sh`（17 条断言）把它纳入一键验收。 |

> B 侧阶段 1 任务：B0–B5（telemetry/模拟器/quality/alarm/realtime/media）→ 在 M1/M2 联调点与 A 汇合。

## 2. 阶段 2 · A 留后端收尾（§7.1 + A5）

| # | 事项 | 依赖 | 说明 |
|---|---|---|---|
| B-1 | 设备/质量闭环补全 | 阶段 1 与 B 的 health 边界（D4）定稿 | A 保留 `/devices/{id}/status`；`health`(DATA_ABNORMAL) 归属按对表结果 |
| B-2 | 媒体存储对接 | B 实现 `POST /media` | A 提供 `upload.dir`（application.yml 已配）与静态资源访问约定；`/media` 代码归 B |
| B-3 | **A5 · Docker Compose 一键启动** | ~~Docker 环境~~ → ✅ **已完成（2026-09-10）** | backend + postgres +（含前端构建产物）；env 注入 `JWT_SECRET` / `MONITOR_INGEST_KEY` / `PG_*`。<br>**落地**：`docker-compose.yml`（根目录）+ `backend/Dockerfile` + `backend/.dockerignore` + `.env.example`（`.gitignore` 加 `!.env.example`，否则被 `.env.*` 规则吃掉）。<br>**实测**：`docker compose up -d` → db/backend 双双 healthy（后端 45s 内，`start_period: 60s` 之内）；对容器跑 `run-all.sh`（默认就打 8080，未改一行脚本）→ **173/173**；`docker compose down` 再 `up` → 容器 ID 变了（确系重建）、计数 `49\|9\|16` 与 schema `v4` 前后一致、Flyway `Migrating` **0 次**、影像落在 `monitor-system_media` 卷且 owner=uid 10001。<br>**两个刻意取舍**：① `depends_on: db: condition: service_healthy` 而不是 `depends_on: [db]`——PG 容器起来后还有一段 initdb，只等「容器起了」会让后端抢跑、Flyway 直接连不上；② 不用 `container_name`——它是反模式（和项目名冗余、挡住扩容），顺带避开了与 B-4 遗留的 `monitor-pg` 容器重名冲突。<br>**仍未达**：验收第 8 条的「浏览器完整可访问」需要前端构建产物，而 `frontend/` 目前只有 README + `.gitkeep`（阶段 2 才动工），故**不放半成品 nginx 占位**，`docker-compose.yml` 末尾以注释块写明它该长什么样（node 构建 → nginx 托管 + `/api` 反代到 `backend:8080`）。<br>**顺带修**：`README.md` 写的 `http://localhost:8080/swagger-ui` 是错的（实测 404，springdoc 配的是 `/swagger-ui.html`）；`frontend/README.md` 是 **GBK 字节**（`file` 报 ISO-8859，在 UTF-8 仓库里显示为乱码，违反 `.gitattributes` 约定），已转 UTF-8 且内容不变。 |
| B-4 | **PostgreSQL 迁移验证** | ~~A5/B-3~~ → ✅ **已验证（2026-09-10，手工 docker run）** | 切 `application-postgres.yml` 跑通；H2 内存库重启即清 → PG 持久化（对应验收脚本第 8 条「重启数据不丢」）。<br>**结果**：`postgres:16` 上 V1–V4 四个迁移全部 success；**173 条验收断言在 PG 上 173/173 全绿**；重启后端后 `measurement/alarm/monitor_point` 计数原样（49\|10\|17），迁移不重复执行。<br>**顺带扫掉的方言风险**：类型只有 `VARCHAR/BOOLEAN/NUMERIC`；`GENERATED BY DEFAULT AS IDENTITY` 是 SQL:2003（PG 10+）；分页方言是**运行时探测**（`new PaginationInnerInterceptor()` 未写死 `DbType`）；`series` 的 hour/day 分桶在 **Java 里**做而非 SQL 函数；全仓只有一条原生 `@Select`（`COUNT(*)`）。<br>**仍未做的**：`docker compose` 编排本身（B-3）——本次是手工 `docker run` 起的库，compose 文件还没有。 |
| B-5 | README 完善 | 阶段 1 演示可用 | 启动步骤 + 演示账号（admin/operator/analyst/maintainer，123456）+ 触发告警/断连/重复的演示脚本 |
| B-6 | OpenAPI 契约维护 + 联调响应 | 全程 | A 是「契约接口人」，联调优先级高于收尾（§10 风险 2） |
| B-7 | **生产 profile 收窄调试面**（H2 console / swagger） | ~~B-4 PostgreSQL 迁移~~ → ✅ **已完成（2026-09-10）** | `SecurityConfig` 有意放行 `/h2-console/**`、`/swagger-ui/**`、`/v3/api-docs/**`；`spring.h2.console.enabled: true` 写在**基础** `application.yml`。<br>已核实三点，威胁是现实的而非假设：① `H2ConsoleAutoConfiguration` 的生效条件只有 servlet 应用 + classpath 有 `JakartaWebServlet` + 该开关为 true（`javap` 查 3.5.16 的注解），**没有一条与数据源类型有关**；② H2 是 `runtime` scope → 各 profile 的 runtime classpath 上都有；③ `application-postgres.yml` 只覆盖了 datasource，**未**关该开关。<br>后果：切 `postgres` profile 后 console 仍注册，且**无需任何凭证**即可访问、可自行指定任意 JDBC URL（含 PG 库）。<br>落地要求：PG/生产 profile 显式 `spring.h2.console.enabled: false`，console 放行按 profile 收窄到 dev（swagger 视联调需要可保留）。<br>发现于 2026-09-10：借该 console 直写库验证告警引擎兜底时**无凭证即进入**。<br>**2026-09-10 夜补实测**（当时有 postgres profile 的后端在跑）：`GET /h2-console/` → **200**，直接给出 H2 Console 登录页；`GET /h2-console` → 302。此时 `org.postgresql.Driver` 就在 classpath 上（该后端正连着 PG），所以登录页那个「任意 JDBC URL」输入框能填 `jdbc:postgresql://…`。**威胁不是推理，是实测。**<br>**修复（同日）**：① `application-postgres.yml` 显式 `spring.h2.console.enabled: false`；② `SecurityConfig` 的 `/h2-console/**` 放行**改为跟着这个开关走**（`@Value("${spring.h2.console.enabled:false}")`）——B-7 的漏洞形态就是「开关与放行各写一份」，只关一处的话，哪天有人把开关拨回去调试、忘了改回来，就恢复成无凭证可达；③ 顺带把 `headers.frameOptions` 从 `disable()` 改为 `sameOrigin()`——`disable()` 是**全局**关掉点击劫持防护，而它存在的唯一理由（H2 控制台的框架页）只需要同源放行。<br>**修复后实测**（Docker Compose 部署形态，postgres profile）：`/h2-console/`、`/h2-console`、`/h2-console/login.jsp` 全部 **401**（原为 200）；对照面 `health` 200、`swagger-ui.html` 200、未鉴权 `/alarms` 401 不变；dev(H2) profile 下控制台仍 200 可用。两个 profile 各跑一遍验收均 **173/173**。<br>**swagger 有意保留放行**（原口径：联调期前端要读 OpenAPI，它只暴露接口形状）。 |
| B-8 | **档案 CRUD 时间统一带时区** | ✅ 已完成（2026-09-10） | 加 `config/JacksonTimeConfig`：注册 `LocalDateTime` 序列化器统一走 `Times.iso`，把被 `BaseCrudController` 原样返回的实体时间一次修净（8 个 `BaseEntity` 子类及其后新增字段全部覆盖）。同时删掉 `application.yml` 里**误导性**的 `spring.jackson.date-format`——它只作用于 `java.util.Date`，对 JSR-310 无效，正是这条配置让人以为时间格式已经配好了。01 套件加 3 条断言锁住（测点 / 项目 / 设备各一）。 |
| B-9 | `latest` 加次排序键 | ✅ 已完成（2026-09-10） | `orderByDesc(collectTime)` 后补 `orderByDesc(id)`。选 `id` 不选 `receive_time`：主键单调递增、等价于「后写库的覆盖先写的」，且没有 NULL 排序的方言差异（PG 的 DESC 把 NULL 排最前、H2 排最后）。02 套件加断言：同一 `collect_time` 两条时取后写的那条。 |
| B-10 | 验收第 5 条「生成设备告警」 | ✅ 已完成（2026-09-10，用户定案） | **形态：V3 给 `alarm` 加 `alarm_type`/`device_id` 并放开 `point_id NOT NULL`，复用同一张表**——设备告警因此直接走现成的状态机、处置动作、时间线留痕与 SSE，不必复制第二份警情域。**归属：A 做**（`DeviceAlarmMonitor`，`@Scheduled` 扫描，判据复用 `DeviceStatusPolicy.OFFLINE_MINUTES`；从不报数的设备不告警；等级取 `notice`）。07 套件 21 条断言覆盖。 |
| B-11 | 验收第 3 条「等级升高能升级」 | ✅ 已完成（2026-09-10，用户定案） | **语义：同一测点 + 测项未解除的警情至多一条，命中更高等级规则时就地升级**（`level` 抬高 + 时间线追加 `escalate`），不另开平行警情；升级只改等级、不改所属规则，恢复仍按最初触发那条规则判定。另发现**演示前提缺失**：种子原本两条规则都是 `warning`，没有更高等级可升——V4 补 `defo_mm gte +5.0/恢复 +2.0`（alarm）。04 套件 ⑨ 覆盖。 |
| B-12 | `collectTime` 非法兜底取 `now()` | ✅ 已完成（2026-09-10，用户定案） | `IngestService#parseTime` 解析失败静默取 `now()`：既污染数据，又**掩盖设备离线**——坏时间戳被盖上「刚刚收到」的章，而在线判定只认 `last_report_time`，设备一直在报垃圾却永远显示在线。改为 `collectTime` 非法即整条 `REJECTED`；`receiveTime`（平台侧时间）缺省才取当前时间。契约 §2 已注明知会 B。 |

> ~~⚠️ 本机无 Docker/PG（此前确认）→ A5（B-3/B-4）需要一台能跑 Docker 的环境验证，落地时先补环境。~~
> ✅ **已解除（2026-09-10 夜）**：本机装好 Docker（`docker.io 29.1.3` + `docker-compose-v2 2.40.3`），并在 `postgres:16` 上把 B-4 验完（173/173 + 持久化）。
> 装机两个坑记在这里，换机器时照抄：① **别用官方 `get.docker.com` 脚本**——它固定去 `download.docker.com` 取 GPG key，本机被重置（`curl: (35) Recv failure`）；改走已配好的**清华 TUNA** 源装 `docker.io`。② **拉镜像必须配加速器**——`registry-1.docker.io` 直连同样被重置，可用镜像写在 `/etc/docker/daemon.json` 的 `registry-mirrors`。

## 3. 里程碑对表（M0–M5，标 A 的动作与判据）

| 里程碑 | 计划节点 | 内容 | A 的动作 | A 侧判据 |
|---|---|---|---|---|
| M0 契约冻结 | 阶段 1 · 第 0 天 | schema/种子/消息契约/OpenAPI/git 约定 | ✅ 已完成（D3–D8 落地，B 提问 Q1–Q6 全答） | ~~收 B Q1–Q3 → 两人签字~~ → **判据改为「仓库文档与代码一致」**（2026-09-10：单仓单一事实源，签字取消） |
| M1 后端骨架 | 阶段 1 · 第 2 天 | A：登录+档案 CRUD（✅ 已完成）；B：ingest+模拟器+quality（✅ 由 A 就地补齐） | 参与联调 | **模拟器 → 落库 → `latest/series`** ✅ 已达成（A-8；`08-simulator.sh` 断言） |
| M2 后端闭环 | 阶段 1 · 第 5 天 | A：asset/audit（✅ 已完成）+schema 稳定；B：alarm 状态机/realtime/media（✅ 由 A 就地补齐） | 支持 schema/契约 | **超限 → 告警 → 处置 → 解除全链** ✅ 已达成（04/08 套件断言） |
| M3 前端门面 | 阶段 2 · 第 8 天 | B：登录+3D 壳+位移分析页 | 供曲线接口联调 | 前端曲线拉到真实数据 |
| M4 前端闭环 | 阶段 2 · 第 11 天 | B：告警中心+管理端；A：设备闭环+部署 | 开始 A5 | 告警处置 + 新建测点/规则生效 |
| M5 演示就绪 | 阶段 2 · 第 14 天 | B：影像+实时；A：Docker Compose | ✅ A5 已完成（B-3，2026-09-10） | ✅ `docker compose up` + `run-all.sh` 一键过验收脚本（173/173）；**仅剩前端的浏览器可访问面**（`frontend/` 未动工） |

## 4. 验收脚本 8 条 · A 的参与面

- **第 1/2/3/5 条**（上报→刷新、幂等、超限告警、断连离线）：主体 B（模拟器/规则/quality），A 供 schema、种子、`/devices/{id}/status` 与消息契约。~~B 转前端后此处全部由 A 就地补齐~~ → **已补齐**：模拟器见 A-8（`tools/radar_simulator/`），第 2/3/5 条分别由 02/04/07/08 套件断言。
- **第 4 条**（确认→研判→处置→解除留痕）：B 的状态机 + A 的审计切面已支持动作留痕。
- **第 6 条**（无人机照片挂测点）：B（media），A 供存储目录约定。
- **第 7 条**（新建测点/规则 → 业务端可见）：A 档案 CRUD + 规则表结构（D6）已就绪。
- **第 8 条**（docker compose 一键 + 数据持久）：**A（A5）** → ✅ **已落地（B-3）**。「一键」与「数据持久」两句都实测过了；未达的只有「浏览器完整可访问」里的前端那一半——`frontend/` 尚未动工。

## 5. 当前卡点 / 风险

| 卡点 | 影响 | 对策 |
|---|---|---|
| ~~git 无首个提交~~ | ✅ 已提交 | `221d96b`，B 可拉分支协同 |
| ~~M0 未签字~~ ✅ **已解除** | — | 签字流程**取消**（单仓单一事实源，git 历史即记录）；A-3 已完成；remote 已定并持续推送。收口方式改为「仓库文档与代码一致」，落地项见上一行 |
| B 转前端后 A 是后端唯一人 | 联调没人接 | A-5 契约接口人职责优先（§10 风险 2） |
| ~~本机无 Docker/PG~~ | ✅ **已解除** | Docker 已装（走清华源 + 镜像加速器）；B-4 已在 `postgres:16` 上验完（173/173 + 重启不丢数据）；B-3 的 compose 编排也已完成（`docker compose up` → 173/173 + down/up 不丢数据） |
| ~~`M0_接口冻结_致B_v1.md` 与现状不符~~ | ✅ **已处理（2026-09-10 深夜）** | 该文停在 09-09，D5/D6/D2 与现状不符（详见下方「已处理」）。<br>**处理方式：就地作废，不删不移。** 理由：**D1–D10 的编号定义只在这一份文件**，`message-contract.md`（「D2/D3/D7/D8」）与 `B侧接口契约_M0.md`（「见 §4-D6」）都在引用它，整体挪走会让引用悬空。故保留编号定义，在文首加作废横幅 + 在三处不符的行上就地标注。<br>**另行补**：`message-contract.md` 缺的 `collectTime` 非法即 REJECTED 一条已补进 §2「字段有效性」；`docs/README_文档管理说明.md` 已在索引里注明本文为历史参考。 |
