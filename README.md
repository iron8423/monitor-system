# monitor-system · 通用多传感监测管理系统

给客户用的**通用多传感监测管理系统**（V0.1 演示闭环）：现场传感数据统一接进来 → 3D 数字孪生大屏标出监测点 → 实时/历史曲线 → 超限自动告警 → 确认 / 研判 / 处置 / 解除全程留痕。
首批数据源 = **毫米波点形变雷达**；落地清远电厂"天地一体化"监测预警系统，首个真实场景 = 灰库 / 库区边坡点形变监测。

通用性体现在**数据模型、接入契约、模块边界**三处：换场景、加传感器不动主框架，不改前端代码。

> 需求基线：[`docs/通用多传感监测管理系统_需求分析与开发指引_v1.0.md`](docs/通用多传感监测管理系统_需求分析与开发指引_v1.0.md)（唯一开发基线）
> 数据接入契约：[`docs/message-contract.md`](docs/message-contract.md)（M0 冻结 · 唯一事实源）
> 接口契约：[`docs/B侧接口契约_M0.md`](docs/B侧接口契约_M0.md)
> 进展沿革（历史决策、证据与各处「更正」）：[`docs/项目进展沿革_20260917.md`](docs/项目进展沿革_20260917.md)
> 未完成事项：[`docs/monitor-system_系统完善与改进清单.md`](docs/monitor-system_系统完善与改进清单.md)（50 条现状对账）· [`docs/后续阶段工作清单_A_v1.md`](docs/后续阶段工作清单_A_v1.md)

## 快速开始

三种形态按需选一种；三者跑的是同一份代码，差别只在数据库、鉴权严格度和前端由谁托管。

| 形态 | 数据库 | 命令 | 前端入口 |
|---|---|---|---|
| 本地 Maven（零配置） | H2 内存库，重启即清空 | `cd backend && ./mvnw spring-boot:run` | `cd frontend && npm install && npm run dev` → <http://localhost:5173> |
| Docker Compose · dev | PostgreSQL 16（具名卷持久化） | `docker compose up -d` | <http://localhost>（`FRONTEND_PORT` 可改） |
| Docker Compose · 生产 | PostgreSQL 16 + 强制严格契约 | `docker compose --env-file .env.production -f docker-compose.production.yml up -d --build` | `FRONTEND_PORT`（默认 80，示例用 8088） |

```bash
# 1) 本地最省事（H2 内存库）
cd backend && ./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
# 健康检查 http://localhost:8080/api/v1/health · 接口文档 http://localhost:8080/swagger-ui.html

# 2) dev 编排（db + backend + frontend 三个服务，含 PostgreSQL）
cp .env.example .env                          # 端口/密码/密钥都在这里；.env 不入库
docker compose up -d && docker compose ps     # 三个服务都该是 healthy
docker compose down                           # 停；数据留在具名卷里
docker compose down -v                        # 连数据一起删（只有清干净重来才用）

# 3) 生产编排（严格契约 + 真实密钥 + 不建演示账号）
cp .env.production.example .env.production    # 必须替换 PG_PASSWORD / JWT_SECRET / MONITOR_INGEST_KEY
docker compose --env-file .env.production -f docker-compose.production.yml up -d --build
```

两个形态差异要知道：**dev 编排默认开演示账号并默认 `INGEST_STRICT_CONTRACT=true`**（`application.yml` 里 app 默认是 `false`，dev compose 显式打开）；**生产编排不带演示账号**（`MONITOR_DEMO_ACCOUNTS` 默认 `false`），所以生产实例上跑验收套件要先显式打开账号或改用 `--fresh`。
首次 `docker compose up` 很慢（要拉 maven 基础镜像并把依赖下一遍，本机实测约 20 分钟）；之后只改 Java 代码重构建是几十秒。

## 演示账号与角色

四个演示角色（H2 / dev compose 形态由 `DataInitializer` 建，密码统一 `123456`），登录后按角色自动进入各自的工作台：

| 账号 | 显示名 | 角色 | 落地页 | 主要用途 |
|---|---|---|---|---|
| `admin` | 陈立 | 系统管理员 | `/home/admin` | 档案与权限、审计、全量数据 |
| `operator` | 李敏 | 值班员 | `/home/operator` | 待确认警情队列 |
| `analyst` | 王越 | 研判员 | `/home/analyst` | 待研判队列 + 该测点曲线 |
| `maintainer` | 赵安 | 运维员 | `/home/maintainer` | 设备异常表 + 待处置队列 |

另有第 5 个账号 `outsider`（**不进登录页**，不属于任何项目），它是"可见范围为空"这条权限边界的对照样本：六个页面上都是空态，不是白屏、不卡加载中、导航菜单仍在。
角色不只是界面显隐——处置动作的角色边界由后端 `AlarmConstants.ROLE_ACTIONS` 强制（越权 403），前端藏按钮不是权限。

## 架构与技术栈

```
monitor-system/                 ← GitHub 单仓库（iron8423/monitor-system，A/B 共用）
├── docs/                       需求基线 / 契约 / 方案 / 每日日志 / 归档
├── backend/                    Spring Boot 3.5 + Java 21 + MyBatis-Plus + Flyway
│   └── com.monitor/
│       ├── common·auth·organization·project·asset·audit   底座（RBAC、档案、审计）
│       ├── telemetry           ingest 接入 / 查询（latest·series）/ 项目概览 / SSE 推流
│       ├── alarm               告警规则·状态机·等级升级·设备离线/质量/延迟告警
│       ├── media               影像挂点（上传 / 读取 / 逻辑删除）
│       ├── scope               项目数据范围隔离（DataScopeService）
│       └── config·controller   安全·时区·MyBatis 配置与健康检查
├── frontend/                   Vue 3 + Vite + Element Plus + ECharts + CesiumJS（见 frontend/README.md）
└── tools/
    ├── radar_simulator/        雷达数据模拟器（无外部依赖，验收链第一环）
    ├── radar_csv_replay/       真雷达 CSV 回放适配器（改写为 BACKFILL 模式）
    ├── production_simulator/   生产规模造数（10 雷达 / 1000 目标）+ 数据集校验
    ├── mountain_asset/         程序化（虚构）山地 GLB 生成脚本
    ├── terrain_asset/          离线真实地形资产：公开 DEM + 卫星影像 → GLB
    ├── acceptance/             后端验收套件（15 套件）
    ├── backup/                 备份 / 恢复 / 自测（含影像卷）
    └── operations/             PostgreSQL 备份恢复脚本（运维口径）
```

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.5.16 · Java 21 · MyBatis-Plus 3.5.17 · Flyway（V1–V20）· JJWT · springdoc-openapi |
| 数据库 | PostgreSQL 16（部署）/ H2 2.3（本地与验收 `--fresh`） |
| 前端 | Vue 3.5 · Vite 6 · Pinia · Element Plus · ECharts 6 · CesiumJS 1.145 |
| 鉴权 | JWT（`Authorization: Bearer`）；例外两处：ingest 用 `X-Ingest-Key` 头，SSE 与影像内容用 `?token=` |

## 数据接入契约（要点）

完整字段与边界见 [`docs/message-contract.md`](docs/message-contract.md)，落地实现见 `com.monitor.telemetry`。

- **标准单点消息**：`schemaVersion / messageId / deviceId / pointCode / collectTime / receiveTime / sequence / metrics / quality / position / signal / state`；每条消息含 2 个测项（`defo_mm` 累计形变 mm、`rate_mm_d` 速率 mm/d），落库按测项拆行。
- **幂等键 = `device_id + message_id`**，重复整条去重（返回 `DUPLICATE`，不重复写、不重复报警）；一条重复不该拖垮同批其它合法消息。
- **接入模式**：`REALTIME`（默认，落库后更新设备心跳、推 SSE、评估告警）与 `BACKFILL`（历史回补，只落库，不改在线状态、不推流、不触发/解除当前警情）。
- **质量与时序闸门**：`quality` 四态（RAW/VALID/SUSPECT/FAULT）；`collectTime` 超前 5 分钟以上拒收（`COLLECT_TIME_IN_FUTURE`），未来的 `receiveTime` 被钳制而不是拒收。
- **严格契约模式**（`INGEST_STRICT_CONTRACT`）：额外要求 `schemaVersion=1.0`、非空 `sequence`、设备与测点已绑定、标定 `isProductionReady` 且未过期/未遮挡；生产编排强制开，dev compose 默认开，本地 H2 默认关。

## 已落地能力

- **监测预警闭环**：ingest 校验/去重 → 落库 → 规则触发（含等级升级）→ 警情生成 → 确认/研判/派发/处置/解除留痕 → 数值回落自动恢复。并发不变式有专用套件（同测点并发上报只开 1 条警情；并发处置恰好 1 个 200、其余 4xx 且含 409）。
- **设备侧三类告警**：离线、数据质量异常（坏质量占比超阈值）、数据延迟（`receiveTime − collectTime` 超阈值）。三者共用一张 `alarm` 表与同一套状态机，成因落在 `alarm_reason`，彼此独立成条、互不掩盖；样本不足或设备本身 `FAULT`/离线时不做判定，也不解除已有警情。
- **查询与概览**：`latest` / `series`（原始点、小时、天三种粒度）/ 项目概览；"取最新一行"的排序判据收在 `MeasurementMapper#latestRowOf` 单一实现（同测点同 `collect_time` 多行时排序必须带 `id` 兜底，否则取到哪行由执行计划决定）。
- **影像挂点**：上传/列表/内容/删除；删除是**逻辑删除**（盘上文件保留，可审计可恢复），角色限 ADMIN/MAINTAINER 并留审计痕，重复删除返回 404。影像在测点详情、`/media` 总览页、3D 大屏浮窗三处共用同一对组件。
- **项目数据范围隔离**：成员关系走显式 `project_member` 表，可见范围沿 `point → object → scene → project` 归集，设备经 `device_point` 反查；**ADMIN 不受限**。过滤做在列表/分页/详情三条路径上，不可见返回 403、不存在仍返回 404；空集合 fail-closed（`1 = 0`，否则 `in(空集)` 会退化成"不过滤 = 看到全部"）。SSE 推送同样按订阅者过滤——服务端没有"发给所有人"这个入口。
- **审计与留痕**：关键写操作走 `@AuditAction`，`/audit` 页仅 ADMIN 可见（后端类级 `@PreAuthorize`，非管理员接口层就 403）。处置人、维护记录 `operator` 一律由后端取当前登录用户，客户端传什么都不作数。
- **会话与失效**：JWT 带令牌版本号，**每次请求回库核对**账号仍在、`enabled` 仍为真、版本一致，所以停用/降权/改密**立即生效**，不必等 24h 过期。
- **自助注册**（登录页「注册账号」→ `/register`）：账号 / 密码（≥ 8 位）/ 姓名 / 公司 / 岗位 / 联系电话 / 邮箱 / 角色，**注册即登录**。个人信息**由本人填写**；公司名按需自动落成一条组织（同名复用）；**不能注册成管理员**（要管理员权限由既有管理员分配角色）。
- **个人中心**（`/profile`，入口在右上角头像下拉）：**本人维护自己的资料**（姓名 / 公司 / 岗位 / 联系电话 / 邮箱可改，账号与角色只读——那是身份与权限）+ **修改密码**（验原密码、新密码 ≥ 8 位、不得与原密码相同；成功后返回新令牌，当前页继续用，其它端立即 401）。
- **用户管理**（管理端「用户」页签，仅 ADMIN）：**看全部注册账号**（账号 / 姓名 / 角色 / 公司 / 岗位 / 电话 / 邮箱 / 启用）、**改角色与启用状态**、**删除账号**（删除后本人无法登录，需用同一账号名重新注册——后端复用那行墓碑记录，不会因唯一索引卡住）。管理员**改不到**别人的姓名/岗位/电话/邮箱（那是个人信息，归本人）。停用与降权**立刻生效**（鉴权每请求回库核对），接口不外泄 `password`/`tokenVersion`；带三条自锁保护——不能停用自己、不能改自己的角色、不能删自己。
- **角色即身份**：角色写死在账号上，登录接口不接受「以什么角色登录」这种入参；越权一律由后端 `@PreAuthorize` 拦（管理端/审计/用户管理/数字孪生配置 = ADMIN；设备绑定与标定 = ADMIN/MAINTAINER；资源增删改 = ADMIN），前端的菜单与路由只是「别让人白点」。四个角色各有自己的落地页，侧边栏也按角色收敛。
- **测项中立化**：有哪些测项只认 `GET /metrics` 档案，3D 着色、热力图、时间轴回放、测点列表统一按"主测项"表现，大屏顶栏可切换。**加一种测项 = 管理端加一行数据**，前端零改动。
- **3D 大屏**：推送驱动（SSE，断流 15s / 正常 60s 兜底轮询），时间轴回放 + 地面热力图（每个测点一圈径向渐变，不做插值——7 个离散点插出来的面是算出来的、不是量出来的）。

## 3D 数字孪生（V2 · 双雷达）

- **自持模型，不依赖在线服务**：仓库内自带两份地形资产，运行期都不请求 Cesium ion 或在线底图；设 `VITE_SCENE_MODE=globe` 可切回 ion 真实地形 + 卫星影像模式。
  - **默认（V19 起）= `qingyuan-hillside-2.0.0`**：离线「真实地形」资产。几何骨架来自公开 DEM（SRTM 派生，30m 级）、色彩底来自公开卫星影像（Sentinel-2 cloudless，10m 级、CC BY 4.0），中高频细节由生成器程序化补充并**逐条写在资产的 `ASSET_PROVENANCE.md` 里**；1000m × 750m、94,000 三角面、真实起伏约 237m、4096×3072 内嵌纹理。两台雷达放在**监测区之外的稳定地面**（量程 620m，分别看守 4/3 个目标）。数据来源、复现方式与「实测 vs 程序化」的边界见 [`tools/terrain_asset/README.md`](tools/terrain_asset/README.md) 与 [`docs/3D数字孪生_离线真实地形资产_20260918.md`](docs/3D数字孪生_离线真实地形资产_20260918.md)。
  - **保留（回退）= `qingyuan-hillside-1.0.0`（320×240m 首版）与 `mountain-demo-2.0.0`（纯程序化虚构山体）**：只切换默认项目的 `asset_url`，旧资产文件与历史迁移都不删。
  - 后续接入无人机航测时，用同一条流水线（`--detail-amplitude 0`）替换输入即可，前端零改动。
- **双雷达标定**：北/南两台雷达分别覆盖 4/3 个可见目标，一条 `device_point` 关系带目标号、方位、俯仰、斜距、反射器高度、LOS、净空、标定状态与有效期；大屏可切换当前雷达并显示其三维视场、目标 LOS 与浮窗标定信息。
- **标定失效闭环**：设备位姿或测点几何一变，旧标定自动转 `INVALID` 并留痕（成因码 `DEVICE_POSE_CHANGED` / `POINT_MOVED`），可重新标定回 `ACTIVE`。管理端已能改雷达位姿、绑定测点、激活/人工停用标定（含有效期）。
- **按项目加载场景**：大屏读当前项目的数字孪生资产；项目没配场景时**明说**「未配置数字孪生场景，只显示离线底色」并给一键切到已配置场景的项目（默认项目由后端固定为 id 升序返回，不再随数据库执行计划漂移——此前实测过一次：默认落到没配场景的项目上，屏幕只剩底色，看起来就是"大屏打不开"）。
- **多场景（V20 起）**：一个项目 = 一处场址 = 一份资产，大屏顶栏的**项目下拉就是场景切换**（前端零改动）。除默认的清远山地边坡外，另有三个演示场景：**野外桥梁**（跨谷桥，桥面/桥墩/护栏）、**山区铁路**（路基/道砟/钢轨/轨枕）、**郊外工厂**（地坪/厂房/储罐/烟囱），各 6 个测点 + 1 台雷达，结构与地形拼在同一份 GLB 里（程序化示意级，见 `tools/terrain_asset/features.py`）。切换后测点、曲线与模拟数据都是该场景自己的：`tools/radar_simulator/seed_all_scenes.sh` 一次给四个场景各灌 12 小时历史 + 当前实时值。
- **图层与符号**：雷达视场扇面**每台一个颜色**（按声明顺序固定配色，两台雷达的覆盖面交叠时能看出归属）、可单独开关（图例里的「雷达视场扇面」）、**选中那台提亮**（面 alpha 0.26 / 描边加粗，其余压暗）；主测项不只换数字——**累积形变 = 实心圆 + 实线立柱**，**形变速率 = 空心环 + 虚线立柱**，图例里写明当前主测项用的是哪种编码。
- **生产数据集**：`generated/production-baseline-20260916/`（10 台雷达 / 1000 个目标 / 141,625 条消息 + 地面真值 + catalog.sql，约 6.7MB gz），标定参数由 **mountain-demo-2.0.0（程序化地形）**实际采样——它服务于独立测试项目 900，与默认项目的默认资产不是同一份，换地形后如需同步重采样是后续动作；校验：`python3 tools/production_simulator/validate_dataset.py generated/production-baseline-20260916`。

## 验证与验收

所有数字都是**本轮实测**（2026-09-17），不是估算；逐套件明细见 [`tools/acceptance/README.md`](tools/acceptance/README.md)。

| 验证 | 命令 | 本轮实测 |
|---|---|---|
| 后端验收（15 套件） | `tools/acceptance/run-all.sh --fresh` | **527 条断言 / 0 失败** |
| 后端单测 | `cd backend && ./mvnw test` | **89 个测试 / 0 失败** |
| 前端自检（store 与纯函数真跑） | `cd frontend && npm run selfcheck`（需在后端运行时执行） | **73 条 / 0 失败** |
| 前端 P0 脚本（模拟网络 + 源码绊线） | `cd frontend && node scripts/check-p0-stage1.mjs` | **35 项全通过** |
| 前端构建 | `cd frontend && npm run build` | 通过（仅有 Cesium/ECharts 大 chunk 提示） |

```bash
tools/acceptance/run-all.sh --fresh   # 另起空库后端（18080）跑完整套，跑完自动停
tools/acceptance/run-all.sh           # 或跑在当前已启动的后端上（8080）
# 退出码：0=全过 · 1=断言失败 · 2=环境问题（后端起不来/登录不上，CI 可据此区分）
```

注意两个前置：套件要**演示账号开着**、要**关掉严格契约**（`INGEST_STRICT_CONTRACT=false`），否则十二个精简报文套件会成片红——看起来像功能坏了，其实是套件跑在更严的模式下；`--fresh` 不受影响。另有 13 条**浏览器内人工验证**步骤（脚本层测不到的组件内竞态与 1000 点规模），见 [`docs/手动验证步骤_第14-16-17-18-19条_20260917.md`](docs/手动验证步骤_第14-16-17-18-19条_20260917.md)。

`14-password-change.sh` 有个**顺序与计数**上的讲究：新密码强制 ≥8 位，而演示口令是 6 位（`123456`），所以「轮换之后改回去」在 API 上做不到——它排在套件数组**最末**，且只在 `--fresh`（H2 随进程消失）上做真实轮换；对着 compose 那种持久库跑时自动跳过，该套件显示 6 条（总数 486）。

## 运维与部署

```bash
tools/backup/pg-backup.sh --with-media        # -> backups/monitor-<时间>.sql + media-<时间>.tgz
tools/backup/pg-restore.sh --latest --with-media backups/media-<时间>.tgz
tools/backup/selftest.sh                      # 实测一遍整条链（需要 compose 起着，实测 17/17）
```

- **备份必须带媒体卷**：附件是卷里的文件，不在库里；只备库的话恢复后 `media` 行回来了、点开图却是碎的。
- **恢复是破坏性操作**（先 `DROP SCHEMA public CASCADE` 再灌），默认要交互敲 `yes`，只有 `--yes` 才跳过；它会先停后端、恢复后等后端真的健康才收工。
- 库名/用户/项目名一律从 `.env` 读（读不到才退回 compose 默认值）——写死的话，`.env` 一改就会去备份/清空**另一个库**。
- **迁移**：Flyway V1–V20，容器启动时自动执行，升级不要删数据卷。切库只需 profile：`./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres`（`PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD`）。
- 生产上线流程与验收步骤见 [`docs/交付说明_双雷达精细山体_V2_20260916.md`](docs/交付说明_双雷达精细山体_V2_20260916.md) 与 [`docs/3D数字孪生_生产候选部署与验收.md`](docs/3D数字孪生_生产候选部署与验收.md)。

## 已知限制与后续

按"明确没做"列，不粉饰；完整对账与逐条证据见 [`docs/monitor-system_系统完善与改进清单.md`](docs/monitor-system_系统完善与改进清单.md)。

- 低电量告警未做（用户定案：暂不做）；只读角色、报表、视频接入、巡查模型未做（属 P1，未定案前不动）。
- **忘记密码目前没有自助找回路径**（2026-09-17 记录）：改密码要求先验原口令，所以口令忘了只能由本人重新注册（同名可注册）或由管理员删除后重注册。真正的「找回」需要**邮件/短信通道**，属于要接外部服务的活，先记在案。
- 注册目前**不区分邮箱/短信验证**、也没有审批流：任何人填个账号名就能用（这也是「删除后自己重新注册」能成立的前提）。要收紧就得引入邀请码或管理员审批。
- 公司名是自由文本、按需自动建组织：没有改名/合并/删除组织的界面，也没有「加入已有组织」的选择器，时间长了可能攒出重名或错拼的组织（演示规模下无碍，登记为待办）。
- **严格契约模式没有自动化覆盖**——13 个套件都不发 `schemaVersion`/`sequence`，那条路径目前真跑时才被执行，补 `14-strict-contract.sh` 是待办。
- **1000 点回放仍是逐点请求**（已用粒度降采样 + 并发池 4 + 按天分段压住，但请求个数没变）；根治需要后端批量 series 端点，取数已收口在一处，将来只改那一处。
- **nginx 访问日志未脱敏**：`?token=` 会原样进默认 combined 日志格式（后端自身请求日志不记 query string）。
- 主工作页（大屏、告警、测点、设备、管理端、审计、影像）**没有窄屏适配**；管理端表列是后端字段名；健康检查目前只返回 `UP`，未做数据库就绪检查。
- 3D 视场图形未按地形裁剪、无自动通视计算、模型版本与历史标定未关联（清单第 35–38、40 条）。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/通用多传感监测管理系统_需求分析与开发指引_v1.0.md`](docs/通用多传感监测管理系统_需求分析与开发指引_v1.0.md) | 唯一开发基线：范围、角色、领域模型、验收口径 |
| [`docs/message-contract.md`](docs/message-contract.md) | 雷达标准消息契约（M0 冻结 · 唯一事实源） |
| [`docs/B侧接口契约_M0.md`](docs/B侧接口契约_M0.md) | 接口 / 字段 / 枚举现行事实源 |
| [`docs/项目进展沿革_20260917.md`](docs/项目进展沿革_20260917.md) | 2026-09-09 → 09-17 的进展、决策与证据（原 README「当前进度」全文） |
| [`docs/monitor-system_系统完善与改进清单.md`](docs/monitor-system_系统完善与改进清单.md) | 50 条现状对账（已修复 / 部分完成 / 未动），每条带文件行号证据 |
| [`docs/手动验证步骤_第14-16-17-18-19条_20260917.md`](docs/手动验证步骤_第14-16-17-18-19条_20260917.md) | 脚本层测不到的人工验证步骤 |
| [`docs/3D数字孪生_V2双雷达标定与验收.md`](docs/3D数字孪生_V2双雷达标定与验收.md) | 双雷达标定的接口与验收口径 |
| [`docs/3D数字孪生_离线真实地形资产_20260918.md`](docs/3D数字孪生_离线真实地形资产_20260918.md) | 离线真实地形资产（DEM + 卫星影像 → GLB）的来源、设计与验证记录 |
| [`docs/真实数据接入与三维建模全流程_雷达与无人机航测_20260918.md`](docs/真实数据接入与三维建模全流程_雷达与无人机航测_20260918.md) | 真雷达 + 无人机航测：原始数据、转换原理、建模流程与交付/验收清单 |
| [`tools/terrain_asset/README.md`](tools/terrain_asset/README.md) | 地形资产流水线用法：抓取数据、生成 GLB、出迁移草稿、接航测成果 |
| [`docs/双人分工实施方案v2.md`](docs/双人分工实施方案v2.md) | 分工与里程碑（历史稿见 `docs/archive/`） |
| [`docs/README_文档管理说明.md`](docs/README_文档管理说明.md) | 文档版本约定与归档规则 |

## 协作约定

- 单仓库 monorepo；`main`（保护）+ `feature/<模块>` 分支 + PR；每日工作日志当天提交到 `docs/daily/`。
- schema/seed 由 A 统一维护（要改走新版本号迁移，不与他人同改既有迁移文件）。
- 行尾 LF（`.gitattributes`）；UTF-8；相对路径；Linux 用 `./mvnw`、Windows 用 `mvnw.cmd`。
- ingest 鉴权：请求头 `X-Ingest-Key`（dev 默认 `dev-ingest-key`，env `MONITOR_INGEST_KEY` 覆盖）；SSE 与影像内容用 `?token=<JWT>`（这两处浏览器发不出自定义头，是浏览器限制不是选择）。
