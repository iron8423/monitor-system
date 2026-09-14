# 前端（阶段 2）· 通用监测管理系统

Vue 3 + Vite + Element Plus + ECharts + CesiumJS（阶段 3a 已引入，3D 大屏在 `/screen`）。

后端接口契约见仓内 `docs/message-contract.md`、`docs/B侧接口契约_M0.md`；后端代码在 `../backend`。

## 0. 环境要求

- Node.js ≥ 20（20/22/24 都可），自带 npm。
- 后端已启动在 `http://localhost:8080`（H2 内存库）。

### 装 Node（Windows）

方式一（推荐，可多版本切换）：

```powershell
winget install CoreyButler.NVMforWindows
# 重开终端后
nvm install 22
nvm use 22
node -v
```

方式二（最省事）：到 <https://nodejs.org/zh-cn/download> 下载 **LTS 的 Windows 安装包（.msi）**，
一路下一步；装完**重开终端**，`node -v` / `npm -v` 能打印版本即可。

> 自检：新开 PowerShell 输入 `node -v`。报「无法识别」= PATH 没生效，重开终端或重装。

## 1. 装依赖

```powershell
cd C:\Users\ASUS\Desktop\实习\监测系统\frontend
npm install
```

国内网络卡住就先换镜像：

```powershell
npm config set registry https://registry.npmmirror.com
npm install
```

### ⚠️ 如果报「无法加载文件 …npm.ps1，因为在此系统上禁止运行脚本」

这不是 Node 装错，是 **Windows PowerShell 5.1 的默认执行策略是 Restricted**（禁止运行任何 .ps1）。
三种解法，任选其一：

```powershell
# 解法 A（推荐，无需管理员）：只给「当前用户」放开签名脚本
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

# 解法 B（最快，不改变任何系统设置）：显式用 npm.cmd，绕过 .ps1 包装脚本
npm.cmd install
npm.cmd run dev

# 解法 C：临时给当前这个终端窗口放开，关掉窗口即失效
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

验证：`Get-ExecutionPolicy` 打印出 `RemoteSigned` 或 `Unrestricted` 即可。
注意解法 A/B/C 只在**新版 PowerShell 与 Windows PowerShell 5.1 各自生效**——
两个终端（`powershell.exe` 和 `pwsh.exe`）的策略是分开存的。

## 2. 起后端（另开一个终端，保持常驻）

```powershell
cd C:\Users\ASUS\Desktop\实习\监测系统\backend
.\mvnw.cmd spring-boot:run
```

看到 `Started MonitorApplication` 即可。

## 3. 起前端

```powershell
cd C:\Users\ASUS\Desktop\实习\监测系统\frontend
npm run dev
```

浏览器打开 <http://localhost:5173>，用 `admin / 123456` 登录。

## 4. 构建

```powershell
npm run build      # 产物在 dist/
npm run preview    # 本地预览 dist
```

## 目录结构

```
src/
  api/          # 接口层：http.js（axios 实例 + 信封拆解 + 401 处理）、auth.js、monitor.js（监测数据 + SSE）
  cesium/       # createViewer.js（viewer + 地形/影像 + 相机）、pointLayer.js（测点实体与状态着色）
  components/   # EChart.vue（通用 ECharts 封装）、SeriesChart.vue（测点时序 → option）、
                # StatTiles.vue（KPI 磁贴行）、AlarmQueue.vue（紧凑警情队列）
  composables/  # usePolling.js：挂载即取一次 + 定时轮询 + 卸载清理
  constants/    # status.js：质量/状态/告警级别枚举 + resolvePointVisual（全局统一取色口径）
  layout/       # AppLayout.vue（顶栏 + 侧边菜单 + 内容区 + 全局 SSE 连接）
  router/       # 路由 + 登录守卫 + 角色门禁（ROLE_HOMES 是落地页的唯一数据源）
  scripts/      # copy-cesium.mjs：把 Cesium 运行时资源复制到 public/cesium/（postinstall 自动跑）
  stores/       # Pinia：user（登录态）、monitor（3D 大屏用的监测数据单一数据源）
  styles/       # 全局样式与 CSS 变量（含 .mk-panel / .mk-mono 等页面通用件）
  utils/        # token.js（localStorage 读写）、labels.js（枚举 → 中文展示文案）、format.js（时间/数字）
  views/        # 页面：Login / Points（测点与曲线）/ Device / Alarm / Admin / Screen（3D 大屏）/ NotFound
  views/home/   # 四个角色各自的落地页（HomeAdmin / HomeOperator / HomeAnalyst / HomeMaintainer）
                # + HomeNone（角色认不出来时的兜底页）
```

`views/home/` 单开一层：那是路由 `/home` 的派发目标，与 `/points` `/devices` 这些
「按内容分」的页面不同，它们是**按角色分**的，放在一起才好一起改。

`utils/labels.js` 单独存在是有原因的：契约 §4 规定后端返回的 `status`/`level`/`lastAction`
是**原始枚举串**、中文文案由前端映射。映射表集中放一处，否则同一个 `OBSERVING`
在两个组件里会译成两个词。

## 3D 大屏（Cesium，阶段 3a）

路由 `/screen` 是**独立于工作台布局**的整屏页面（自带 HUD，不套侧边菜单），入口在侧边栏「3D 大屏」。

### 依赖与静态资源

- `cesium`（当前 1.145.0）是普通依赖。
- Cesium 运行时要去 `/cesium/` 找 Workers / Assets / Widgets / ThirdParty —— 这些**不是 JS 模块**，
  打包器不会带上。项目用 `scripts/copy-cesium.mjs` 从 node_modules 复制到 `public/cesium/`，
  由 `npm install` 的 `postinstall` **自动执行**（也可手动 `npm run cesium:assets`）。
  `public/cesium/` 是构建产物，已由 `frontend/.gitignore` 忽略。
- `vite.config.js` 里 `define: { CESIUM_BASE_URL: '"/cesium/"' }` 告诉 Cesium 去哪儿取这些文件。

### 访问令牌与地形模式

配置放 `frontend/.env.local`（**不进 git**，模板见 `.env.example`）：

```
VITE_CESIUM_ION_TOKEN=<ion 控制台的 Access Token>
VITE_TERRAIN_MODE=ion     # ion = Cesium 官方真实地形；none = 不用地形（断网兜底）
```

令牌在 <https://ion.cesium.com/tokens> 生成。**前端里的 token 会随构建产物公开**，
正式环境请在 ion 后台限制来源域名与用量。

### 降级策略

大屏是「先保证出画面，再逐步升级」：同步建好 viewer（无地形、深色球体）→ 页面立刻有画面；
再异步加载影像（ion 卫星影像失败则退 Carto 深色底图）与地形（失败则标记「地形降级」，
立柱改用档案高程）。顶栏三个状态灯（数据 / 地形 / 影像）实时反映当前用的是哪一层，
**断网也不会白屏**。

### 坐标口径（重要）

档案里的 `altitude`（如 P-HK01 = 30m）与该点**真实地形高度**（实测约 7m）相差二十多米。
大屏在真实地形就绪后用 `sampleTerrainMostDetailed` 采样地形高度，把立柱「种」在地面上；
档案高程仍显示在测点页。立柱高度是视觉示意，图例里已标注「立柱高度为示意，非实测量值」。

## 关于跨域（重要）

后端 `SecurityConfig` **没有配置 CORS**，所以前端统一走 **Vite 代理**：

```
浏览器 → http://localhost:5173/api/** → (dev server 转发) → http://localhost:8080/api/**
```

配置在 `vite.config.js` 的 `server.proxy`。因此：

- 前端请求一律以 `/api` 开头，**不要**写死 `http://localhost:8080`；
- 换后端地址只改 `vite.config.js` 的 `target`；
- 生产部署由 `Dockerfile` + `nginx.conf` 落地（`docker compose up -d` 即含前端），
  前端代码不用动。该配置里有三处是**必须**的，改动时别丢：
  `try_files $uri $uri/ /index.html`（history 模式，否则直达路由刷新 404）、
  SSE 单独一个 location 且 `proxy_buffering off`（否则事件被缓冲，要等缓冲满才出）、
  Cesium token 走 Docker **build arg**（`VITE_*` 是构建期注入，不是运行期环境变量）。

## 阶段进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 工程骨架 + 登录（JWT 存储 / 请求头注入 / 路由守卫） | ✅ |
| 2 | 总览 / 测点页（points、latest、series、summary） | ✅ 总览 + 测点与曲线已落地；设备状态页另加 |
| 3 | Cesium 3D 大屏（地形 / 标点着色 / 弹窗 / 时间轴 / 热力图） | **3a ✅**（真实地形 + 卫星影像 + 标点着色 + 点击弹窗 + 三级降级）；3b 时间轴 / 热力图 / SSE 直连待做 |
| 4 | 告警中心 + 管理端（alarms / actions / 规则 CRUD） | 告警中心 ✅（含处置时间线）；管理端 ✅ **读写**（项目/场景/对象/测点/测项/设备/告警规则的增删改） |
| 5 | 无人机影像挂点（media） | ✅ 2026-09-14：`MediaGallery` / `MediaUploader` 两个共用组件，落在测点详情「影像」页签、`/media` 总览页、3D 大屏点击浮窗三处（浏览器实测 18/18） |

> **2026-09-11 合并说明**：阶段 1 骨架由 B 落地；A 在其基础上并入总览 / 测点与曲线 /
> 设备状态 / 告警中心 / 管理端五个页面（路由沿用本表阶段划分）。合并时 B 的骨架文件
> （`http.js` / `stores/user.js` / `AppLayout.vue` / `LoginView.vue`）**保持原样**，
> A 那版对应的重复实现已丢弃。详见 `docs/daily/2026-09-11-A.md`。

## 已知限制

- **管理端写操作限于「增删改」**，不做批量导入/导出，也不做外键的级联删除提示
  （删除是逻辑删除，父子关系不会一并置灰）。
- **管理端的表列是运行时从响应数据推导的**，所以列名就是后端字段名（`objectId` 而非「所属对象」）。
  要中文列名就得为每个资源写一份列映射——当前优先级不高，没做。
- **设备状态靠轮询**（10s）：SSE 只推 `measurement` / `alarm` 两类事件，不推设备状态。
- **曲线阈值线是写死的 ±3mm**：应当来自 `/alarm-rules`，等规则页做出来再改成拉接口。
- **图表库警告**：`echarts` 单独成 chunk 后 `PointsView` 产物约 1.1MB，
  构建时会有「chunk 大于 500kB」的提示；当前不做 `manualChunks` 拆分，等真在意首屏再处理。
- **3D 大屏目前靠 10s 轮询刷新**：`AppLayout` 里那条全局 SSE 连接只用于顶栏「实时已连接」
  状态灯，页面拿不到它（`stream` 是布局内的局部变量，页面无法订阅）。3b 会把 SSE 收口到
  Pinia store，让大屏改由推送驱动（点位实时跳动 + 告警脉冲）。
- **`utils/labels.js`（A）与 `constants/status.js`（B）有部分重叠**：前者负责枚举 → 中文文案，
  后者是 3D 取色口径 + 质量/状态枚举。建议后续合并到一处，避免同一个枚举两套映射。

## 角色差异化（2026-09-11 落地）

四个演示角色各有一个**自己的工作台**，登录后按角色自动进入：

| 账号 | 角色 | 落地页 | 主区块 | 队列口径 |
|---|---|---|---|---|
| `admin` | 管理员 | `/home/admin` 管理工作台 | 测点 chips + 最近警情 + 管理入口 | 全量（不筛状态） |
| `operator` | 值班员 | `/home/operator` 值班工作台 | 待确认警情队列 | `status=PENDING` |
| `analyst` | 研判员 | `/home/analyst` 研判工作台 | 待研判队列 + **该测点形变曲线** | `status=CONFIRMED` |
| `maintainer` | 运维员 | `/home/maintainer` 运维工作台 | 设备异常表 + 待处置队列 | `status=PROCESSING` |

外加 `/home/none` 兜底页（角色认不出来时用，见下）。

### 三个队列为什么是按状态而不是「分给我的」

后端 `Alarm` **没有负责人字段**，也没有任何「按当前用户过滤」的读端点，做不出「我的警情」。
可动作→目标状态的映射是确定的（`AlarmConstants`：`confirm`→`CONFIRMED`、
`research`→`OBSERVING`、`dispatch`/`handle`→`PROCESSING`），所以**岗位的待办正好是状态的一段**。
这是四个页面能各不相同、且站得住的唯一依据。各页脚注都把这点写给用户看了。

### 路由：`ROLE_HOMES` 是唯一数据源

`router/index.js` 顶部的 `ROLE_HOMES` 数组派生四个落地页路由的 `name` 与 `meta.roles`。
**不要手写第二条**：守卫在角色不匹配时会把人送到 `homeOf(role)`，一旦这张表与某条路由的
`meta.roles` 失配，就会形成**守卫级无限重定向**——而 vue-router 的「30 次导航」保护整段包在
`process.env.NODE_ENV !== 'production'` 里，**生产构建下被摇掉**，症状是微任务无限递归、标签页卡死。
守卫里另有一条逃生舱（`if (to.name === target) → home-none`）兜住万一。

`/home` 这条路由用 **`beforeEnter` 而不是 `redirect`** 派发：route-level redirect 在全局
`beforeEach` **之前**求值，写成 redirect 的话未登录访问 `/` 会被先按空角色弹到兜底页，
再拼出 `login?redirect=/home/none`，**登录后就永远落在兜底页**。

### `/home/none` 为什么不能换成 `/screen`

`/screen` 是顶层路由、**不套 `AppLayout`**，进去布局整个卸载（没有菜单），而大屏上的
「退出大屏」又 `push('/home')` 跳回来——用户困在大屏里出不来。所以兜底页必须是
`AppLayout` 内的一个子路由，且**不带 `meta.roles`**（带了就被守卫当成受限页又弹回去）。

> ⚠️ **`utils/labels.js` 的 `ACTIONS_BY_ROLE` 只管按钮显不显示，不是权限边界。**
> 强制在**后端** `AlarmConstants.ROLE_ACTIONS`（`AlarmService.act` 里校验，越权 403）。
> 两边是同一张表的副本，**改一处必须同步改另一处**，否则会出现「按钮在但点了报 403」
> 或「按钮没了但接口仍放行」。判定未知角色时**两边都是拒绝**（前端空集、后端 false）——
> 这里原先前端写的是 `|| ACTIONS_BY_ROLE.ADMIN`，那是开放回退：角色字段一丢反而亮出管理员全套动作。

### 顶栏角色标签（此前是死代码）

`AppLayout.vue` 那个 `<el-tag>` 的条件是 `roleLabel !== displayName`，而四个种子账号的
显示名恰好就是角色名（`admin` → "管理员"…），**条件恒假、从未渲染过**。改法是
`DataInitializer` 把显示名换成真人名（陈立 / 李敏 / 王越 / 赵安），**标签代码一字未动**，
死代码自然变活。顶栏现在是「真人名 + 角色」两段。

> 种子账号显示名是**启动时校正**的（存在但不同则更新），不是只在创建时写——
> 否则已经跑起来的库（compose 的 PG 卷）根本不会变。副作用是每次启动拉回这四个账号的显示名，
> 可接受：它们密码固定、界面上无法编辑，本来就不是可改数据。

已知未做（不是缺陷，是没排期）：
- 运维台的「设备维护记录」与「设备-测点绑定」后端已有接口
  （`/api/v1/maintenance-records`、`/devices/{id}/points`）但前端没页面（排期周三 9/16）。
- 审计日志后端有接口（仅 ADMIN 可调），前端 `api/monitor.js` 未封装、无页面（排期周三 9/16）。
- **影像没有删除入口**——不是前端偷懒，是契约 §7 没给 DELETE 端点（只有上传 / 列表 / 内容）。
  前端因此没有做「删除」按钮，误传的照片只能从库里手工清。加端点属契约变更，**已上报待定案**。
