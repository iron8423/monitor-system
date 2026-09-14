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
  views/        # 页面：Login / Points（测点与曲线）/ Device / Alarm / Admin / Media（影像挂点）/ Screen（3D 大屏）/ NotFound
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

## 实时推送（阶段 3b）

SSE 的**连接归 `stores/realtime.js`**，数据落进 `stores/monitor.js`（唯一数据源）。

### 为什么连接必须放 store，而不是布局或页面

`/screen` 是**顶层路由**，不套 `AppLayout`：

- 发起写在 `AppLayout.onMounted` → 用户直接进大屏时**没人建连接**；
- 发起写在大屏里 → 从大屏退回工作台又断一次，而事件**没有重放机制**，断开期间全丢。

Pinia store 的实例活在应用级，比布局和大屏都活得久，是唯一能同时覆盖两者的位置。
`start()` 是幂等的，布局与大屏都调也只会有**一条**连接。整页卸载（F5 / 直接输地址）
不触发 `onBeforeUnmount`，所以 `pagehide` 的显式 `close()` 也由 store 绑一次——
不显式关的话，那条半关闭的 socket 会占着浏览器「单源 6 连接」的名额，
连刷几次之后所有请求排队，界面像死了几十秒（2026-09-11 实测 47s）。

### 事件怎么落到界面

| 事件 | 落点 | 界面效果 |
|---|---|---|
| `measurement` | `monitor.applyMeasurement()` 并进 `latestMap` | 大屏点位数值/颜色、侧栏、弹窗**同时**变（同一份数据） |
| `alarm` | `monitor.applyAlarm()` 维护 `activeAlarms` + `recentAlarms` | 该点常亮扩散环（`hasAlarm` → 着色第一优先级）；刚推来的一闪 6s + 顶部横幅 12s |

两个刻意的口径：

- **不认识的 `pointId` 直接忽略**——SSE 推的是全库，不属于当前项目的点不该污染快照，
  也不计入「本页收到多少条」；
- **解除/误报要把点从 `activeAlarms` 摘掉**，否则数值回落之后颜色一直是红的。
  同一条警情就地升级（A 定案：不另开平行警情）会**再推一条**，所以同一个点会再闪一次
  ——「它又恶化了」这件事得看得见。

### 兜底与重连

浏览器自己会重连（服务重启这类瞬断）；若服务端明确关闭（401 / 服务端主动关），
`EventSource` 会进入 `CLOSED` 不再自愈，此时按 1s→30s 退避重连，连续失败 8 次后
落到 `closed`，界面显示「推送已断开」。轮询只是兜底：**断流时 15s 一次补齐**，
流正常时 **60s 才对一次账**（兜住新建测点、漏事件这类推送覆盖不到的变化）。

## 影像挂点（阶段 5）

页面 `/media`：左边选测点，右边上传 + 照片墙（点击可放大）。大屏里点开测点，弹窗里
会给出该点最多 3 张现场照片，并有「现场照片」按钮跳到本页（`/media?pointId=3` 深链，
测点页的「现场照片」也走同一条路）。

契约 §7 的三个口径，前端都得照做：

| 项 | 后端 | 前端注意 |
|---|---|---|
| 上传 | `POST /api/v1/media`，multipart：`file` / `pointId` / `takenAt` / `note` | `Content-Type` **不要手写** —— boundary 要由浏览器补，手写会让后端解析不到文件 |
| 列表 | `GET /api/v1/points/{id}/media` 返回**裸数组** | `mediaId` 当作**不透明 id**，不解析 `M1000` 里的数字 |
| 看图 | `GET /api/v1/media/{mediaId}/content`（需鉴权） | `<img>` 带不了请求头 → 走 `?token=`（与 SSE 同一类例外） |

`takenAt` 不填则后端取当前时间；填了就必须可解析（`Times.parse` 对非法串是 400），
所以「没填」要**不传该字段**、不能传空串 —— `api/media` 的 `uploadMedia()` 就是这么写的。

演示路径（既是验收第 6 条，也是最好讲的一段）：选测点 → 选择图片 → 填拍摄时间/备注 →
上传并挂点 → 照片墙出现缩略图 → 点开放大；再到 3D 大屏点该测点，弹窗里出现同一张照片。

## 大屏的时间轴回放与地面热力图（阶段 3b 呈现）

底部那条时间轴负责回放，左下角图例下面那个勾选框负责地面热力图。

**回放不是「暂停订阅」**：实时链路（`stores/realtime.js` + `stores/monitor.js`）始终在跑，
回放只是在大屏这一层换一个数据源 —— `ScreenView` 里那个 `displayPoints` 决定「屏幕上显示的是
实时快照还是历史帧」，点位、列表、弹窗、热力图全读它（只切一处，就不会出现「3D 是历史值、
列表还是实时值」这种自相矛盾的画面）。退出回放立即回到实时，不需要等下一次推送。

数据是逐点拉的 `/points/{id}/series`，再合并成帧（`utils/timeline.js`，纯函数、有断言）：

- 各点采样时刻并不对齐（补传、分批上报都会错开），所以先并到同一条时间轴上；
- 某点在某一帧的值 = **该时刻之前最近的一次采样**（前值保持）。这条不是可选口径：
  屏幕上任何时刻看到的值，都必须是那时**已经量到**的值，不能用未来的采样倒推；
- 帧的时间串沿用后端原文（带 `+08:00`），不自己转 UTC，免得界面显示差 8 小时。

热力图的实现取向：`ellipse` + `CLAMP_TO_GROUND`（贴地形）+ 一张径向渐变贴图（按颜色缓存），
半径随 |形变| 放大、颜色沿用 `resolvePointVisual` 的全局口径。**故意不做插值** ——
7 个离散点插出来的面是算出来的、不是量出来的，图上却看不出这层区别；晕圈按值大小叠出来
既诚实又够看。有面状采样（或测点上百）后再换真插值。

两个容易踩的点：热力晕圈半径可达上千米，所以**它不能被拾取**（`pointLayer.pickId` 只认
`kind === 'point'` 的那颗点），否则整片地面都变成「点了就弹窗、想关还关不掉」。

## 测项中立化（骨架）

**「有哪些测项」只有一个来源：`GET /api/v1/metrics`**（每点一份：`{pointId, code, name, unit, sortOrder}`）。
界面里不再出现 `defo_mm` / `rate_mm_d` 这种写死的字段名，测项的**名称与单位也从档案取**。

### 「主测项」是什么

一份测点数据里可能有多个测项（形变、速率、温度…），但 3D 着色、地面热力图、点位标签、
时间轴回放**同一时刻只能按一个测项来表现**——这个就是主测项（`monitor.primaryMetricCode`）：

- 默认取档案里的 `defo_mm`（形变是本项目的第一场景）；档案里没有它就取排序最靠前的那个；
- 大屏顶栏有「主测项」下拉，切换后 3D、热力图、弹窗、时间轴一起切；
- 切到档案里不存在的代码会被拒绝（`setPrimaryMetric`）——界面全空是最难解释的一种故障。

每个测点拍平后的结构（`enrichedPoints`）与测项无关：

```js
{ id, code, metrics: { defo_mm: 1.8, temp_c: 41.5 },   // 所有测项的原值
  metricCode: 'defo_mm', value: 1.8, unit: 'mm', metricName: '累计形变',
  threshold: 3,                                         // 来自告警规则，见下
  quality, signal, state, hasAlarm, alarmLevel, ... }
```

3D 图层只认 `value / unit / metricName` 这三样，所以换测项时 `cesium/*` 一个字都不用改。

### 超限判据来自规则，不再自带默认值

`resolvePointVisual` 原来有个写死的 `warnThreshold = 3`——规则改成 ±5mm 之后，后端按 5mm 报警、
界面还按 3mm 变黄，同一件事两套口径。现在判据由调用方从 `/alarm-rules` 算出来：
`utils/thresholds.js#warnThresholdOf()` 取**绝对值最小**的那条阈值线（最容易触发的那一档）。
**没有规则就返回 null，界面不判超限**——宁可不黄，也不要自己发明一个阈值。
曲线上的虚线也是这几个数，两处同源。

### 加一种测项要做什么

1. 管理端「测项」页给该测点加一行（`code` / `name` / `unit`）——**前端零改动**；
2. 想让它有阈值告警，再加一条 `/alarm-rules` 规则（同一个 `metricCode`）；
3. 设备按 `message-contract` 上报这个测项的值即可。

> 唯一还需要手改的地方：`cesium/heatmapLayer.js` 里的 `RADIUS_PER_UNIT`（每单位放大多少米）
> 是**呈现标度**，因为档案里暂时没有量程字段。mm 与 ℃ 的数值范围差几个量级，
> 用同一个系数会让某类测项要么糊满全屏、要么看不见。将来 metric 档案若加 `range`，改成读档案即可。

> 另一处同类遗留**不在本仓前端**：`views/AdminView.vue` 的告警规则表单里，测项下拉仍是写死的
> `['defo_mm', 'rate_mm_d']`（A 的文件），加第三种测项时那里会选不到——已记入交接清单。

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
| 3 | Cesium 3D 大屏（地形 / 标点着色 / 弹窗 / 时间轴 / 热力图） | **3a ✅**（真实地形 + 卫星影像 + 标点着色 + 点击弹窗 + 三级降级）；**3b ✅**（SSE 推送驱动 + 告警脉冲 + 时间轴回放 + 地面热力图，见下两节） |
| 4 | 告警中心 + 管理端（alarms / actions / 规则 CRUD） | 告警中心 ✅（含处置时间线）；管理端 ✅ **读写**（项目/场景/对象/测点/测项/设备/告警规则的增删改） |
| 5 | 无人机影像挂点（media） | ✅ 上传 / 列表 / 看图全通（见「影像挂点」一节）；大屏测点弹窗里也能看现场照片 |

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
- **曲线阈值线已改为拉 `/alarm-rules`**（原来写死 ±3mm）：换算口径见 `utils/thresholds.js`
  —— 只取 THRESHOLD、测项精确匹配、全局规则对所有点生效而测点规则只对该点生效、
  同值同向只留更具体的一条。管理端改规则后，曲线页点「刷新」即跟着变。
- **图表库警告**：`echarts` 单独成 chunk 后 `PointsView` 产物约 1.1MB，
  构建时会有「chunk 大于 500kB」的提示；当前不做 `manualChunks` 拆分，等真在意首屏再处理。
- **地面热力图的形态是「每个测点一圈径向渐变」**，不是插值出来的连续场：数据源只有 7 个
  离散测点，插值面是算出来的、不是量出来的（详见 `cesium/heatmapLayer.js` 顶部注释）。
  有面状采样数据后可以换成真正的热力面。
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
- 影像挂点 `/media` 详情页看图（阶段 5）；运维台的「设备维护记录」后端已有接口
  （`/api/v1/maintenance-records`）但前端没页面。
- 审计日志后端有接口（仅 ADMIN 可调），前端 `api/monitor.js` 未封装、无页面。
