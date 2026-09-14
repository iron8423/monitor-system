# monitor-system · 通用监测管理系统

通用多传感监测管理系统 Demo（V0.1 最小闭环）。数据源 = 毫米波点形变雷达；落地清远电厂灰库 / 库区边坡。

> 需求基线：`docs/通用多传感监测管理系统_需求分析与开发指引_v1.0.md`
> 分工与里程碑：`docs/双人分工实施方案v2.md`（最终版；历史稿见 `docs/archive/`）
> 消息契约：`docs/message-contract.md`（M0 冻结 · 唯一事实源）

## 目录

```
monitor-system/                 ← GitHub 单仓库（iron8423/monitor-system，A/B 共用）
├── .gitattributes / .gitignore / README.md
├── docs/                       需求/方案/接口契约/每日日志/归档
│   ├── daily/                  每日工作日志 YYYY-MM-DD-A/B.md
│   ├── message-contract.md     雷达标准消息契约
│   ├── M0_接口冻结_致B_v1.md     M0 冻结口径与回执
│   └── archive/                需求历史稿（只读）
├── backend/                    Spring Boot 3 + Java 21 + MyBatis-Plus + Flyway
│   └── com.monitor/
│       ├── common·auth·organization·project·asset·audit   A 底座（A0–A4，已验收）
│       ├── telemetry           ingest 接入 / 查询（latest·series）/ 项目概览 / SSE 推流
│       ├── alarm               告警规则·状态机·等级升级·设备离线告警
│       ├── media               影像挂点（上传/读取）
│       └── config·controller   安全·时区·MyBatis 配置与健康检查
├── frontend/                   Vue3 + Vite + Element Plus + ECharts + Cesium（3D 大屏已落地，见其 README）
└── tools/
    ├── radar_csv_replay/       真雷达 CSV 回放适配器（Python，B 侧）
    ├── radar_simulator/        雷达数据模拟器（Python，无外部依赖，验收链第一环）
    └── acceptance/             后端验收套件（A 侧，§9 验收脚本可跑部分）
```

## 启动方式一：Docker Compose（含 PostgreSQL，推荐）

```bash
docker compose up -d          # 起 db + backend + frontend（首次要构建镜像，见下方注意）
docker compose ps             # 三个服务都该是 healthy
docker compose down           # 停；数据留在具名卷里，下次 up 还在
docker compose down -v        # 连数据一起删（只有清干净重来才用）
```

- PostgreSQL 16 持久化在 `monitor-system_pgdata` 卷；上传的影像在 `monitor-system_media` 卷。
- 配置（端口/密码/密钥）：`cp .env.example .env` 再改；`.env` 不入库。
- **首次 `up` 很慢**（本机实测约 20 分钟）：要拉 `maven` 基础镜像，并把 pom 里全部依赖从
  Maven Central 下一遍。这一步缓存在独立的构建层里，**之后只改 Java 代码重构建只要几十秒**。
- 国内网络两个坑（换机器照抄）：官方的 `get.docker.com` 脚本固定去 `download.docker.com`
  取 GPG key，本机被重置，改走清华源装 `docker.io`；拉镜像必须配 `registry-mirrors`。

## 启动方式二：本地 Maven（H2 内存库，零配置）

```bash
cd backend
./mvnw spring-boot:run      # Linux/macOS
# Windows: mvnw.cmd spring-boot:run
```

- 默认 H2 内存库，重启即清空——要持久化就用上面的 Compose。
- 健康检查：`GET http://localhost:8080/api/v1/health`
- 接口文档：`http://localhost:8080/swagger-ui.html`
- 演示账号：admin / operator / analyst / maintainer（密码 123456），外加一个**不进登录页**的
  `outsider`（访客，不属于任何项目）——它是「可见范围为空」这条边界的样本，见验收第 7 条那节。

## 验收（后端）

```bash
tools/acceptance/run-all.sh --fresh    # 另起全新后端（空库，端口 18080）跑完整套，跑完自动停
tools/acceptance/run-all.sh            # 或跑在当前已启动的后端上（8080）
```

10 个套件 / 359 条断言，覆盖 §9 验收脚本里后端可独立验证的部分（详见 `tools/acceptance/README.md`）。
退出码 `0` 全过、`1` 断言失败、`2` 环境问题。
H2 空库（`--fresh`）**实测 359/359，失败 0**（连跑两轮均绿）；compose 的 PostgreSQL 形态复跑了 `04-alarm.sh`（47/47）、
`07-device-alarm.sh`（26/26）与 `02-ingest-idempotency.sh`（34/34）。两者执行计划不同，
有些缺陷只会在其中一个上现形（见下方「取最新一行」那条）。

套件只认 `BASE` 一个地址，所以 **`docker compose up` 之后直接 `run-all.sh` 就是「一键过验收脚本」**
（§9 第 8 条的后半句）——跑的是容器里的后端，不是宿主机的 `./mvnw`。

验收链的第一环（**模拟器**）由 `tools/radar_simulator/` 提供，`08-simulator.sh` 用它造数并断言：
造数 → 落库 → 立即可查 → 超限触发 → 等级升级 → 自动恢复 全链打通。

```bash
python3 tools/radar_simulator/radar_simulator.py --dry-run    # 只看报文
python3 tools/radar_simulator/radar_simulator.py --inject-overlimit --recover-after 6
```

## 协作约定

- 单仓库 monorepo；`main`（保护）+ `feature/<模块>` 分支 + PR；每日工作日志当天提交。
- schema/seed 由 A 统一维护（要改走 V3，不与 B 同改 V1/V2）。
- 行尾 LF（.gitattributes）；UTF-8；相对路径；Linux 用 `./mvnw`、Windows 用 `mvnw.cmd`。
- ingest：请求头 `X-Ingest-Key`（dev 默认 `dev-ingest-key`，env `MONITOR_INGEST_KEY` 覆盖）。
- stream(SSE)：`?token=<JWT>`（EventSource 带不了 Header）。

## 当前进度

- M0 契约已冻结：测项 `defo_mm/rate_mm_d`、幂等 `device_id+message_id`、默认规则 ±3mm 双向、`X-Ingest-Key` / `?token=` 鉴权。
- A 底座 A0–A4 + schema/种子已入库并验证；B1 telemetry ingest 骨架 + CSV 回放已并入。
- **后端闭环已跑通**（A-3 已落地）：ingest 校验/去重 → 落库 → 规则触发（含等级升级）→ 警情生成 → 处置留痕 → 自动恢复，外加设备离线告警。
  端到端可重复验证：`tools/acceptance/run-all.sh --fresh` → **10 套件 / 359 条断言全绿**。
- **验收链第一环（模拟器）已落地**：`tools/radar_simulator/` 按契约连续造数，不依赖真雷达 CSV；
  `radar_csv_replay/` 是回放器不是生成器（要真实数据），两者分工互补，都发同一条契约消息。
- **PostgreSQL 已验证**（B-4）：`postgres:16` 上 V1–V4 迁移全部成功，验收在 PG 上全绿
  （2026-09-11 复跑全绿），重启后端数据不丢。切库只需 profile：
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres`
  （`PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD`，默认 `localhost:5432/monitor`、`monitor/monitor`）。
- **Docker Compose 一键启动已落地**（B-3 / 验收第 8 条）：`docker compose up -d` 起
  **db + backend + frontend** 三个服务，前端构建进镜像、由 nginx 托管并反代 `/api`。
  已实测 —— 容器重建后数据仍在（`measurement/alarm/monitor_point` 计数与 schema 版本前后一致、
  Flyway 不重跑）、影像落卷、对容器跑验收全绿。
- **备份恢复已落地**（2026-09-14，验收第 8 条后半）：
  ```bash
  tools/backup/pg-backup.sh --with-media        # -> backups/monitor-<时间>.sql + media-<时间>.tgz
  tools/backup/pg-restore.sh --latest --with-media backups/media-<时间>.tgz
  tools/backup/selftest.sh                      # 实测一遍整条链（需要 compose 起着）
  ```
  库名/用户/项目名一律从 `.env` 读，读不到才退回 compose 默认值——写死的话，
  `.env` 一改就会去备份/清空**另一个库**（备份时是拿到错的东西，恢复时是删错东西）。
  恢复是**破坏性**的（先 `DROP SCHEMA public CASCADE` 再灌），所以默认要交互敲 `yes`，
  只有 `--yes` 才跳过；它还会先停后端（`DROP SCHEMA` 要拿排他锁，和后端的长连接撞上就是卡死）、
  恢复后等后端**真的健康**再收工（后端起来时 Flyway 会核对迁移版本，备份与代码不一致在这里就会失败）。
  `--with-media` 那半份不是可有可无：附件是**卷里的文件**，不在库里，只备库的话
  恢复后 `media` 行回来了、点开图却是碎的。自测脚本按验收第 8 条的原话走完整条链——
  挂真附件 → 备份 → **破坏**（灌假项目 + 删真告警 + 清空附件卷）→ 恢复 →
  逐样查回（假项目没了 / 告警回来了 / 历史条数回到基线 / 附件读出真 PNG），**实测 17/17**。
- **前端已可访问**（Vue3 + Vite + Element Plus + ECharts，阶段 3a 起含 Cesium）：
  开发态 `cd frontend && npm install && npm run dev` → <http://localhost:5173>；
  部署态就是上面的 `docker compose up -d` → <http://localhost>（`FRONTEND_PORT` 可改）。
  已落地工作台页面：**四个角色各有自己的落地页**（管理工作台 / 值班工作台 / 研判工作台 /
  运维工作台，登录后按角色自动进入；另有一个「未分配角色」兜底页）、测点与曲线、设备状态、
  告警中心含处置时间线、**管理端读写**、**影像挂点 `/media`**，以及独立整屏的
  **3D 大屏 `/screen`**（真实地形 + 卫星影像 + 测点按状态着色 + 三级降级）。
  测点详情共五个页签（资料 / 曲线 / 数据表 / 影像 / 告警），影像在详情页、`/media` 总览页、
  大屏点击浮窗三处共用同一对组件（`MediaGallery` / `MediaUploader`）。
  浏览器端到端已在 **compose 形态**实测（下条）。
  ⚠️ 仍未完成：顶栏的 SSE 实时连接**尚无页面消费事件**（只有连接状态标签，
  页面级消费由 B 的 3b 接手）。
- **浏览器端到端已在 compose 形态实测**（2026-09-11）：未登录访问 `/home` 被守卫拦下并
  回跳 `/login?redirect=/home`、登录后回到原目标；**四个角色各自落到自己的工作台**
  （`/home/admin` `/home/operator` `/home/analyst` `/home/maintainer`，四条 URL 与四个页面
  主区块互不相同，顶栏显示「真人名 + 角色」）；越权直达被弹回且**不形成重定向环**
  （生产构建下 vue-router 的导航次数保护会被摇掉，环会卡死标签页，故刻意在生产产物上验）；
  各页与 `/screen` 均有真实数据；跨页口径一致（设备页「在线」行数 == 总览「在线设备」）；
  管理端**新建 → 接口核对 → 删除**全通（即验收第 7 条「平台管理端新建测点 → 业务端无需改代码
  立即可见」）；全程零 4xx/5xx、零控制台报错。**四角色矩阵 45 断言 / 0 失败**。
- **审计日志页与设备详情抽屉**（2026-09-14，验收第 7 条后半）：新增 `/audit`（仅 ADMIN，
  后端控制器是**类级** `@PreAuthorize`，非管理员接口层就 403）与 `DeviceDrawer`
  （基本信息 / 绑定测点 / 维护记录，设备页与运维台**共用同一个组件**）。浏览器实测 26/26，
  含一次真解绑→真重绑的往返与一次真写维护记录。**维护记录的 `operator` 由后端从当前登录用户
  覆盖**，前端传什么都不作数——「谁写的」不该由客户端说了算。
  顺带把这批接口补进契约：`/devices/{id}/points`、`/maintenance-records`、`/audit-logs`
  此前**被引用却从无出处**（与 `/media/{id}/content` 同一类问题）。
- **「取最新一行」的口径已收成单一实现**：`measurement` 同测点同 `collect_time` 可合法落多行
  （幂等键是 `device+message`，不含 collect_time），此时排序必须带 `id` 兜底，否则取到哪行由
  执行计划决定。此前 `MeasurementQueryService#latest` 有兜底、`ProjectSummaryService#maxDeformation`
  没有，两个端点会给同一测点两个值；现统一走 `MeasurementMapper#latestRowOf`，
  `03-query.sh` ⑨ 有回归断言。
- **项目概览的「未解除警情」必须同时算上设备告警**（2026-09-11 修）：告警有两条来源，挂的字段不同——
  `POINT` 类型只写 `point_id`、`DEVICE` 类型只写 `device_id`（另一侧为 NULL）。`ProjectSummaryService`
  原先只判 `point_id IN (...)`，而 SQL 里 `NULL IN (...)` 不成立，**设备告警一条也数不进来**（B-14）。
  现改为 `point_id IN (...) OR device_id IN (...)`，设备侧由 `device_point` 反查。注意空集合：
  MyBatis-Plus 的 `in(空集合)` 会丢掉整条条件，所以 `or` 那一支只在设备非空时才挂，否则会反向退化成
  「所有设备告警都算」。`07-device-alarm.sh` ①-b 有回归断言（**改回旧写法实测会红**）。
- **SSE 长连接必须在整页卸载时显式 `close()`**（2026-09-11 实测定位）：`AppLayout` 里那条全局
  SSE 原先只在 `onBeforeUnmount` 里关，而**整页卸载（F5 / 直接输地址）不会触发它**——文档是被
  丢弃的。此时浏览器发的 FIN 仍要等对端收尾，可 nginx 的非缓冲反代只能靠「向客户端写失败」
  察觉，而**第一次写进半关闭的 socket 会成功**，于是要等第二次心跳（后端 `HEARTBEAT_MS` 30s × 2）
  才收口。这段时间那条半关闭 socket 占着浏览器「单源 6 连接」的名额，**连刷 6 次之后所有请求
  全部排队，界面像死了几十秒**。对照实验：拦掉 `/api/v1/stream` → 9 次导航全 4–8ms；不拦 →
  第 6 次冻 47 秒。修法是在 `pagehide` 里显式 `close()`（浏览器当场回收名额，不必等对端），
  并在 `pageshow`（`persisted`）里补重连——否则从 bfcache 后退回来 SSE 会**静默失效**。
  后端侧无此问题：直连 8080 时断开能被立即回收，只有过 nginx 才滞后。
- **调试面已按 profile 收窄**（B-7）：H2 控制台只在基础 profile 开着，`postgres` profile 显式关闭，
  且 `SecurityConfig` 的放行跟着这个开关走；`frameOptions` 由 `disable()` 收成 `sameOrigin()`。
  swagger 保留放行（联调期前端要读 OpenAPI）。
- **契约不再走「签字」**：项目用单仓库单一事实源，双方读同一份文档与代码，git 历史即记录。
  现行事实源 = `docs/message-contract.md`（消息契约）+ `docs/B侧接口契约_M0.md`（接口/字段/枚举）；
  `M0_接口冻结_致B_v1.md` 已就地作废（D1–D10 编号仍由它定义，数值以现行文档/代码为准）。
- **`<img>` 取影像是契约里第二处（也是最后一处）query 鉴权**（2026-09-14）：`<img>` 发不出
  `Authorization` 头，与 `EventSource` 是同一个约束，所以 `/media/{id}/content` 走 `?token=`。
  忘了它就是满屏碎图——而且**每张图各自一个 401**，图片加载失败又不走 axios 拦截器，
  控制台里只有一串裸 401、界面上没有任何提示。前端统一走 `api/monitor.js` 的 `mediaContentUrl()`，
  三处调用点都不自己拼地址。**证伪过**：把 token 去掉后 18 条浏览器断言红 8 条，
  错误明细就是 `取图 HTTP 401`。
- **影像删除是「逻辑删除」**（2026-09-14 新增 `DELETE /api/v1/media/{mediaId}`，契约 §7）：
  只把库里的行标成 `deleted=1`，**盘上的文件保留**——这是用户定案的口径（与 `monitor_point`/`device`
  一致，可审计可恢复），代价是卷只增不减，回收磁盘要另配离线策略。删除后该影像从列表与内容端点
  一并消失，重复删除得 404（不是静默成功），角色限 ADMIN/MAINTAINER，且走 `@AuditAction` 留痕
  （软删意味着「删了还在库里」，不留痕就无从查证）。前端入口由 `MediaGallery` 的 `deletable` 控制，
  **3D 大屏浮窗刻意不给**（那是「看」的场合）；但真正的边界在后端 `@PreAuthorize`，藏按钮不是权限。
  此前「没有删除端点」导致的两个后果都已消解：套件现在自己回收传的图（`06-media.sh` ⑧），
  误传的照片运维在界面上就能撤。
- **项目数据范围隔离已落地**（2026-09-14，验收第 7 条前半）。成员关系走显式的 `project_member`
  表（硬删，`UNIQUE(user_id, project_id)`），**不用 `sys_user.organization_id`**——组织级隔离在
  「1 组织 1 项目」的种子下演示不出来。可见范围沿 `point → object → scene → project` 归集，
  设备经 `device_point` 反查；**ADMIN 不受限**，其余看 membership。
  三处值得记住的地方：
  - **过滤做在 `BaseCrudController` 的三个钩子上**（`list` / `page` / `get`），七个 CRUD 子类各自实现。
    只给列表加过滤是不够的——`/points/page` 与按 id 直读是**两条独立的泄漏路径**。
  - **不可见返回 403，不存在仍返回 404**，两者不合并：「你没权限」和「这东西没了」在验收时要说的话不同。
  - **空集合要短路**：MyBatis-Plus 的 `in(空集合)` 会**丢掉整条条件**，于是「一个项目都没有」退化成
    「不过滤 = 看到全部」——**一个在做过滤、实际在放大权限的默认值**。收口在 `DataScopeService.inIds()`
    （空集产出 `1 = 0`），`10-scope.sh` 里 `outsider` 那 15 条 fail-closed 断言就是钉这个的。
  警情按 **point 与 device 两侧**都滤（B-14 的镜像），处置动作同样过范围。套件 `10-scope.sh` **93 条**，
  `--fresh` 全量 **359/359**；**证伪过**：把 `unrestricted()` 注入 `return true;` → 46 条转红，
  且红的正是**差值型**断言，控制型（admin 直达 200）仍绿——说明承重的是差值那一半。
  ⚠️ **已知未修**：`SseBroadcaster` 广播时不看订阅者是谁，非管理员订阅 `/stream` 仍收得到项目 2 的
  事件。这是**推送**不是查询，属契约的推送语义变更，见契约 §11 与工作清单 B-20。
- 尚缺（详见 `docs/后续阶段工作清单_A_v1.md`）：① 低电量告警未做（用户定案：暂不做）；
  ② 验收第 7 条里的**只读角色、报表、视频接入、标定/巡查模型**未做（属 P1，未定案前不动）；
  ③ SSE 的页面级消费未做（连接已在，事件没人订阅，归 B 的 3b）。
- **设备侧三类告警已成体系**（2026-09-14，验收第 5 条）：离线的同时补上了
  **数据质量异常**（窗口内坏质量占比超阈值）与**数据延迟**（`receiveTime − collectTime` 超阈值）。
  三者共用一张 `alarm` 表、一套状态机与处置留痕，**不新增 `alarm_type`**——成因落在
  `alarm_reason` 列（V7）+ `snapshot.reason` 上。两个判据的边界都刻意收过：
  回补的历史数据**不算**延迟上报（那是导入不是迟到）；样本不足 4 条时两个判据都不成立，
  也**不解除**已有警情（「数据变好了」在没有样本时是个没有依据的结论）；
  设备处于 `FAULT`/离线时暂不判定，闸门放开后照常判定。
  同一台设备上三种成因**各自独立成条、互不掩盖**——`openAlarmOf` 必须按成因过滤，
  否则离线扫描会把数据质量警情当成自己的那条、在设备恢复在线时把它「解除」掉
  （与 B-14 同一类缺陷，`09-data-quality.sh` ④ 是它的回归锚点）。
