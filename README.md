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
- 演示账号：admin / operator / analyst / maintainer（密码 123456）

## 验收（后端）

```bash
tools/acceptance/run-all.sh --fresh    # 另起全新后端（空库，端口 18080）跑完整套，跑完自动停
tools/acceptance/run-all.sh            # 或跑在当前已启动的后端上（8080）
```

8 个套件 / 188 条断言，覆盖 §9 验收脚本里后端可独立验证的部分（详见 `tools/acceptance/README.md`）。
退出码 `0` 全过、`1` 断言失败、`2` 环境问题。
H2 空库（`--fresh`）**实测 188/188**；compose 的 PostgreSQL 形态复跑了 `04-alarm.sh`（46/46）。两者执行计划不同，
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
  端到端可重复验证：`tools/acceptance/run-all.sh --fresh` → **8 套件 / 188 条断言全绿**。
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
- **前端已可访问**（Vue3 + Vite + Element Plus + ECharts，阶段 3a 起含 Cesium）：
  开发态 `cd frontend && npm install && npm run dev` → <http://localhost:5173>；
  部署态就是上面的 `docker compose up -d` → <http://localhost>（`FRONTEND_PORT` 可改）。
  已落地工作台五个页面（总览 / 测点与曲线 / 设备状态 / 告警中心含处置时间线 / **管理端读写**）
  与独立整屏的 **3D 大屏 `/screen`**（真实地形 + 卫星影像 + 测点按状态着色 + 三级降级）；
  **影像挂点仍是待做**（`frontend/README.md` 有阶段表与已知限制）。
  浏览器端到端已在 **compose 形态**实测（下条）。
  ⚠️ 仍未完成：顶栏的 SSE 实时连接**尚无页面消费事件**（只有连接状态标签，
  页面级消费由 B 的 3b 接手）。
- **浏览器端到端已在 compose 形态实测**（2026-09-11）：未登录访问 `/home` 被守卫拦下并
  回跳 `/login?redirect=/home`、登录后回到原目标；五个工作台页与 `/screen` 均有真实数据；
  跨页口径一致（设备页「在线」行数 == 总览「在线设备」）；管理端**新建 → 接口核对 → 删除**
  全通（即验收第 7 条「平台管理端新建测点 → 业务端无需改代码立即可见」）；
  全程零 4xx/5xx、零控制台报错。
- **「取最新一行」的口径已收成单一实现**：`measurement` 同测点同 `collect_time` 可合法落多行
  （幂等键是 `device+message`，不含 collect_time），此时排序必须带 `id` 兜底，否则取到哪行由
  执行计划决定。此前 `MeasurementQueryService#latest` 有兜底、`ProjectSummaryService#maxDeformation`
  没有，两个端点会给同一测点两个值；现统一走 `MeasurementMapper#latestRowOf`，
  `03-query.sh` ⑨ 有回归断言。
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
- 尚缺（详见 `docs/后续阶段工作清单_A_v1.md`）：① 低电量/数据中断告警未做——**用户定案暂不做**；
  ② 影像挂点 `/media`（阶段 5）未做；
  ③ SSE 的页面级消费未做（连接已在，事件没人订阅，归 B 的 3b）。
