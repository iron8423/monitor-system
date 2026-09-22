# AGENTS.md — 给 AI 编码助手的项目上下文

> 这份文件是给**在这个仓库里干活的 AI** 看的，假设你没有对话历史。
> 人类读者请从 `README.md` 开始；这里只讲"上手必须知道的事实、红线、以及踩过的坑"。
> 最后更新：2026-09-22（main = `596c0bf`）

## 0. 这是什么

通用多传感监测管理系统（V0.1 演示闭环）：现场传感数据 → 3D 数字孪生大屏标出测点 →
实时/历史曲线 → 超限自动告警 → 确认/研判/处置/解除全程留痕。
首批数据源是**毫米波点形变雷达**，落地场景是**清远电厂**库区/边坡形变监测。

分工：**A = 后端**（git 作者 `yanzu`），**B = 前端 / 三维 / 工具链**。

## 1. 仓库与现状

- 仓库：`https://github.com/iron8423/monitor-system`，**private**，默认分支 `main`
- 当前 HEAD：`596c0bf`（2026-09-22）
- 栈：Vue 3 + Element Plus + ECharts + **CesiumJS 1.145** + Vite｜Spring Boot 3.5 / Java 21 + MyBatis-Plus + Flyway｜H2（本地）/ PostgreSQL（部署）
- 最近的工作：三维大屏"远景层"（地块之外铺真实影像，解决转到哪都看到地块边缘）、
  影像与资产工具链、系统审计。**细节见 `docs/给A的移交_20260922.md`（本轮入口）**。
- 仓库里**包含**清远电厂预览资产与远景瓦片（合计约 71.6 MB），因为仓库已转 private；
  这是有前提的，见下面"红线 1"。

## 2. 怎么跑起来

| 方式 | 数据库 | 命令 |
|---|---|---|
| 本地零配置 | H2 内存库（重启即清空） | 后端 `cd backend && ./mvnw spring-boot:run`（Windows：`mvnw.cmd -o spring-boot:run`）<br>前端 `cd frontend && npm install && npm run dev` → <http://localhost:5173> |
| Docker Compose · dev | PostgreSQL 16（具名卷） | `docker compose up -d` → <http://localhost> |
| Docker Compose · 生产 | PG + 严格契约 | `docker compose --env-file .env.production -f docker-compose.production.yml up -d --build` |

- 演示账号：`admin` / `123456`（另有 operator / analyst / maintainer，密码同）
- 健康检查 `/api/v1/health`；接口文档 `/swagger-ui.html`；三维大屏 `/screen`
- **前端环境变量**：`frontend/.env.development` **已入库**（含三维预览默认值：指向清远电厂资产 + 本地远景层）。
  `npm run dev` 会自动加载它，所以拉下来就能看到预览。个人差异（如 Cesium ion token）写 `frontend/.env.local` 覆盖，那个文件不进 git。

## 3. 代码地图

**前端 `frontend/src/`**

| 目录 | 职责 | 备注 |
|---|---|---|
| `router/index.js` | 19 条路由 + 角色门禁 | 角色落地页表 `ROLE_HOMES` 是唯一数据源，派生而非手写 |
| `views/` | 页面（大屏 `ScreenView.vue` 67 KB 最大） | 大屏是顶层路由，不套 `AppLayout` |
| `cesium/` | 三维管线：`createViewer`（viewer/影像/雾）、`digitalTwinScene`（资产/装饰/相机）、`pointLayer`、`heatmapLayer`、`renderProfile` | **改三维先读这四个文件** |
| `stores/` | `user` / `monitor` / `realtime`(SSE) / `replay` / `integrity` | SSE 连接持有者放 store（应用级单例），不放组件 |
| `api/` | `http`(axios 封装) + `auth` / `monitor` / `ops`，47 个导出函数 | 页面不要绕过它直接发请求 |
| `utils/`、`composables/`、`constants/` | 纯函数与复用件（阈值、热力色阶、时间轴、格式化、请求代际守卫…） | 有自检脚本覆盖 |

**后端 `backend/src/main/java/com/monitor/`**：`auth` · `asset` · `project` · `telemetry` · `alarm` · `media` · `scope` · `audit` · `ops` · `twin` · `common`（含 `BaseCrudController`）· `config`。
26 个 Controller（61 个显式端点）+ 17 Service + 20 Entity + 25 个 Flyway 迁移 / 19 张表。
**注意**：`BaseCrudController` 为每个资源提供通用 CRUD（GET 列表/分页/详情、POST、PUT、DELETE），
所以"某个资源的写端点"往往不在子类里。

**工具链 `tools/`**：`acceptance/`（22 套验收）· `imagery_fetch/`（多源影像 + 远景金字塔）·
`terrain_asset/`（地形资产流水线：build / composite / replace_texture / validate_glb / prune）·
`twin_check/`（控制点校核）· `radar_simulator/` `radar_csv_replay/` `production_simulator/`（数据链路）·
`backup/` `operations/`（运维）。

## 4. 硬约束（红线）

1. **仓库必须保持 private。** 清远电厂 GLB 与远景瓦片的纹理来自 **Google 影像**，
   其服务条款禁止离线缓存与二次分发。它们能待在仓库里的唯一理由是"私有、内部共享"。
   **一旦把仓库改回 public，必须先移除它们（含 git 历史）。** 对外交付/公开演示必须换成
   天地图授权影像或采购影像。
2. **Flyway 迁移一旦执行过就不能改**，也不能删除旧资产；资产变更走**新版本号迁移 + 更新 `asset_sha256`**。
   本地 `VITE_ASSET_OVERRIDE` 只是开发开关，不是交付形态。
3. **不要提交**：`frontend/.env.local`、`node_modules/`、`target/`、`data/`、`backups/`。
   新增 >50 MB 的资产前先跟人确认，并考虑 Git LFS。
4. **不要越层**：Controller 里不写 SQL；组件里不直连数据库式地拼接口（应走 `api/` 层）；
   三维逻辑集中在 `cesium/`。
5. **契约必须同步**：`docs/message-contract.md`（设备上报）与 `docs/B侧接口契约_M0.md`（前后端）是事实源，
   改了接口就改文档，反之亦然。
6. **演示向功能不要擅自删**：时间轴回放、地面热力图、多场景切换、`/ops`、`/help` 的去留
   属于"面向客户最小集"的决策，见 `docs/系统审计_冗余与死代码清单_20260922.md`。
7. **不要伪造数据可信度**：结构体/热力/立柱等示意内容必须标注"示意，非实测"；
   资产来源与限制写在 `ASSET_PROVENANCE.md` 与文档里。

## 5. 已知陷阱（都踩过，别重犯）

| # | 陷阱 | 正确做法 |
|---|---|---|
| 1 | **Cesium 1.145 已移除 `model.readyPromise`**，访问它会抛异常，把建场景函数后半段（取景/边界/诊断）整段带崩——表现为"模型能看见，但只有点按钮才取景" | 用 `readyEvent`；整段包 `try` |
| 2 | **不要每帧 `camera.setView` 修相机**：会闪、会黑（"点列表闪烁变黑"就是这么来的） | 超出阈值才修，并加节流（超出 2% + 300 ms） |
| 3 | 取景不要用 `flyToBoundingSphere` 叠 `HeadingPitchRange`（距离会被算两次） | 让相机留在**以场址为原点的 lookAt 变换**里；取景与"回到全局视角"共用一个函数 |
| 4 | **雾只调 `density` 几乎没效果**：`fog = 1 - exp(-((k·s+k)·s·(1+k)))`，`s = 距离 × density`，`k = fog.visualDensityScalar` | 两个参数一起给；另外切主题会重置 density（已在代码里修） |
| 5 | **远景瓦片模板必须与盘上命名一致**：盘上是 `{z}/{x}_{y}.jpg`，Cesium 默认 `{z}/{x}/{y}.jpg` → 全部 404，**而页面看着"还行"**（其实一直用兜底图层在渲染） | 模板写 `/farfield/{z}/{x}_{y}.jpg` |
| 6 | **GLB 改纹理/合并后必须校验**：曾因长度字段算错产出坏文件，浏览器表现是"一直转圈不出图" | `python tools/terrain_asset/validate_glb.py <file.glb>` |
| 7 | **Bing 在本场址是建设期旧图**（冷却塔还是脚手架）、色调偏黄（饱和度 62.8 vs Google 27.7） | 地块与远景都用 Google；别把地块换成 Bing |
| 8 | **Vite HMR 不会重建已创建的 Cesium 场景**：改了三维代码刷新可能不生效 | 强制刷新（Ctrl+Shift+R）或关标签重开 |
| 9 | `renderProfile.js` 的 `lit` 档曾把 `globe.show` 关掉，远景层一开就"虚空回归" | 已修；改渲染档时不要再关椭球/大气 |

## 6. 改完必须验证

```bash
cd frontend && npm run selfcheck        # 94 条（store 与纯函数真跑；需后端在跑），实测 0 失败
cd frontend && npm run build            # 生产构建必须通过
cd backend  && ./mvnw test              # 后端单测（89 个）
bash tools/acceptance/run-all.sh        # 22 套后端验收（改后端必跑）
python tools/terrain_asset/validate_glb.py <file.glb>   # 动过三维资产必跑
```

三维大屏的观感改动，按 `docs/大屏验收清单_20260921.md`（40 条）逐条复验，
指标口径见 `docs/大屏视觉与体验验收指标_20260922.md`（12 项）。

## 7. 约定

- 提交信息：`<type>(<scope>): 中文说明`，type ∈ feat / fix / docs / chore / refactor / test
- `main` 是集成分支（当前无分支保护，但**实验请开分支**）；不要 `--force` 推 main
- 资产目录命名：`frontend/public/models/<资产名>/<资产名>.glb`，与迁移里的 `asset_url` 一致
- 三维代码注释里写清"为什么这么写"：这个项目里**每个反直觉的写法背后都对应一次线上/演示事故**

## 8. 待办与待决策

**技术侧待办**（详见 `docs/系统审计_冗余与死代码清单_20260922.md`）

- 批 2（口径）：`api/` 层 2 个零调用函数、`AdminView` 绕过 api 层、取最新值 3 条路径统一、
  `BaseCrudController` 通用写端点按资源收紧、`ScreenView.vue`/`digitalTwinScene.js` 拆分
- 批 3（结构）：把"新场址接入"固化成一条规范；合并两份重叠的大清单
- 数据链路：雷达 **CSV 只读适配器**（`tools/radar_csv_replay` 是现成起点）——系统真正的闭环

**待 A / 甲方拍板**

1. 总平面图 / CAD / BIM（决定构筑物能否从"示意级"变成真尺寸）
2. 演示向功能的最小集（时间轴回放 / 热力图 / 多场景开关去留）
3. 资产正式化（新版本号迁移 + `asset_sha256`）

## 9. 文档索引（谁是事实源）

| 文档 | 作用 |
|---|---|
| `README.md` | 人类入口：怎么跑、模块概览 |
| `docs/给A的移交_20260922.md` | **本轮变更的单一入口**（做了什么、要 review 什么、待办） |
| `docs/预览环境搭建_给A_20260922.md` | 三维预览怎么跑、许可见解 |
| `docs/系统审计_结构与依赖全景_20260922.md` | 分层、页面→接口→表矩阵、工具链 I/O |
| `docs/系统审计_冗余与死代码清单_20260922.md` | 冗余/死代码（三档判定，待划勾） |
| `docs/系统审计_可复用资产清单_20260922.md` | 哪些东西"下次还能用" |
| `docs/大屏视觉与体验验收指标_20260922.md` · `docs/大屏验收清单_20260921.md` | 观感的可测口径与逐条验收 |
| `docs/B侧接口契约_M0.md` · `docs/message-contract.md` | 前后端 / 设备上报契约（事实源） |
| `docs/真实数据接入与三维建模全流程_雷达与无人机航测_20260918.md` | 雷达接入七步 + 航测到 3D 全链 |
| `docs/3D数字孪生_生产数据准备与上线实施指南.md` | 资产上线流程 |
| `docs/monitor-system_系统完善与改进清单.md` · `docs/全项目功能与工具复查清单_20260918.md` | 两份历史清单（待合并，见批 3） |
