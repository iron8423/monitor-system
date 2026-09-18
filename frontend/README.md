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

## 5. 前端自检（不需要浏览器）

```powershell
npm run selfcheck   # 需要后端在跑（默认 http://127.0.0.1:8080，可用 MONITOR_BASE 覆盖）
```

`scripts/selfcheck.mjs` 用 Vite 的 SSR 加载器把 store 与纯函数**真跑一遍**（不是读代码），
覆盖六块：① 实时推送（连接归属/幂等、measurement 并表、alarm 升降级与**同一测点多条未解除警情的
汇总**、**SSE 可见性收口**）、② 时间轴回放（多序列合帧、前值保持、**采样时刻与过期限时**、滑块往返）、
③ 测项中立化与项目过滤（含「编一个档案里本来没有的第三种测项」那种端到端断言）、
④ **请求代际守卫**（`createRequestGuard` 的语义，含**乱序完成**：A 先发后回、B 后发先回 → A 失效 B 有效）、
⑤ **回放取数规模**（清单第 18 条：`pickGranularity` 粒度选择、`mapWithLimit` 并发池、`splitWindow` 分段边界）、
⑥ **曲线窗口建议**（`suggestRange`：命中最小可用档、跨越到近 7 天、边界算覆盖、超出最大档、无数据/非法时间为 null、未来时间不抛）。
当前 **73 条**，失败会以非零码退出。

> **2026-09-17 晚更正**：本条上一版写的是「覆盖五块 …… 当前 **67 条**」——当晚新增 §⑥
> 「曲线窗口建议」一组 6 条（67 → 73），用于钉「曲线为空时说清原因并给一键切窗口」那个改动。
> 旧数不删。

> **2026-09-17 更正**：上一版这里写的是「覆盖三块 …… 当前 **35 条**」。当日清单第 17/18/19 条落地时
> 新增了 §④ 与 §⑤ 两节（35 → 67），四块/五块的划分也随之上移。**旧数不删**，因为 35 是那一天之前
> 的实测值，是判断「这轮改动有没有真的加进覆盖」的基线。

## 目录结构

```
src/
  api/          # 接口层：http.js（axios 实例 + 信封拆解 + 401 处理）、auth.js、monitor.js（监测数据 + SSE）
  cesium/       # createViewer.js（viewer + 地形/影像 + 相机）、pointLayer.js（测点实体与状态着色）
  components/   # EChart.vue（通用 ECharts 封装）、SeriesChart.vue（测点时序 → option）、
                # StatTiles.vue（KPI 磁贴行）、AlarmQueue.vue（紧凑警情队列）
                # MediaGallery.vue / MediaUploader.vue（影像，三处共用）
                # DeviceDrawer.vue（设备详情三页签，设备页与运维台共用）
  composables/  # usePolling.js：挂载即取一次 + 定时轮询 + 卸载清理
                # useThresholds.js：告警规则 → 阈值线（两处画曲线的页面共用一份口径）
  constants/    # status.js：质量/状态/告警级别枚举 + resolvePointVisual（全局统一取色口径）
  layout/       # AppLayout.vue（顶栏 + 侧边菜单 + 内容区 + 全局 SSE 连接）
  router/       # 路由 + 登录守卫 + 角色门禁（ROLE_HOMES 是落地页的唯一数据源）
  scripts/      # copy-cesium.mjs：把 Cesium 运行时资源复制到 public/cesium/（postinstall 自动跑）
  stores/       # Pinia：user（登录态）、monitor（3D 大屏用的监测数据单一数据源）
  styles/       # 全局样式与 CSS 变量（含 .mk-panel / .mk-mono 等页面通用件）
  utils/        # token.js（localStorage 读写）、labels.js（枚举 → 中文展示文案）、format.js（时间/数字）
  views/        # 页面：Login / Points（测点与曲线）/ Device / Alarm / Admin / Audit / Media /
                # Screen（3D 大屏）/ Profile（个人中心）/ NotFound
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
- `public/models/` 下有几份自持资产，运行期都不请求在线服务：
  - `qingyuan-hillside-v2/`（V19 起默认，1000×750m）与 `qingyuan-hillside/`（V18 首版，
    320×240m）真实地形资产：公开 DEM + 卫星影像烘焙成 GLB；
    用 `tools/terrain_asset/` 的两个脚本生成，来源与「实测 vs 程序化」的边界见该目录的
    `ASSET_PROVENANCE.md`；
  - `mountain-demo/` 纯程序化生成的虚构山体（无外部输入、无纹理），
    用 `tools/mountain_asset/generate_mountain_glb.py` 重新生成，保留作回退与对照。
  大屏**不读**这两份 `scene-config.json`——它读的是后端 `/projects/{id}/digital-twin`
  （数据库配置），静态配置只作为资产生成时的记录与人工核对材料。

### 场景模式、访问令牌与地形

配置放 `frontend/.env.local`（**不进 git**，模板见 `.env.example`）：

```
VITE_SCENE_MODE=mountain     # 默认：本地低多边形山地，完全离线
VITE_CESIUM_ION_TOKEN=<ion 控制台的 Access Token>
VITE_TERRAIN_MODE=ion        # 仅 globe 模式：ion = 官方地形；none = 椭球
```

`mountain` 模式不使用 ion token，也不请求在线影像或在线地形。若要切回原来的全球底图，设置
`VITE_SCENE_MODE=globe`；此时 ion token 与地形设置才生效。

令牌在 <https://ion.cesium.com/tokens> 生成。**前端里的 token 会随构建产物公开**，
正式环境请在 ion 后台限制来源域名与用量。

#### 图层开关与主测项符号（2026-09-17）

图例面板（左下、**贴在底部时间轴之上**）里有两个图层开关，外加「当前主测项用哪种符号」的说明：

- **雷达视场扇面**：每台雷达按声明顺序固定配色（`digitalTwinScene.js` 的 `RADAR_COLORS`），
  选中那台的面提亮、描边加粗，其余压暗；可整体关掉（关掉后雷达头与目标视线仍在）。
  此前所有雷达共用同一个状态色，两片半透明面叠在一起分不出归属，这正是用户报的问题。
- **地面热力图**：每个测点一圈径向渐变（半径按量纲由 `heatmapLayer.js` 的 `RADIUS_PER_UNIT`
  标度），同样可单独关掉。
- **主测项符号**：累积形变 = 实心圆 + 实线立柱；形变速率 = **空心环 + 虚线立柱**
  （判据是 `metricCode` 是否含 `rate`，见 `pointLayer.js` 的 `paint()`）。
  毫米级的两个量数字长得几乎一样，只换数字看不出来，所以符号也换。

> ⚠ 图例与底部时间轴**曾经都锚在 `bottom: 16px`**，时间轴（`left/right` 各 16px 的整条）
> 把图例整个盖住了——连「地面热力图」那个开关都点不到。现在图例抬到 `bottom: 84px`，两者错开。

### 降级策略

默认 `mountain` 模式同步创建深色椭球，再从同源静态目录加载本地 GLB；山体加载失败会保留
HUD 与数据列表并明确显示「山体加载失败」。`globe` 模式仍按原逻辑异步加载 ion 卫星影像和
地形，失败时退到 Carto 深色底图/椭球。顶栏状态灯会明确显示当前层级。

### 主题：白天 / 黑夜（2026-09-18）

顶栏最右侧（登录页在表单面板右上角、大屏在顶栏右侧）有一个太阳/月亮图标，一键切换；
选择记在 `localStorage.monitor_theme`，**首次访问跟随操作系统的 `prefers-color-scheme`**。

实现收在 `src/composables/useTheme.js`，只有一条通路——在 `<html>` 上打两个标记：

| 标记 | 谁认它 |
|---|---|
| `data-theme="light\|dark"` | 我们自己写的 CSS 变量（外壳、页面、3D 大屏 HUD） |
| `class="dark"` | Element Plus 官方暗色变量表（`main.js` 里 import 的那份） |

两个标记**必须同时切**：只切一个会出现「我们的面板暗了、el-table 还是白的」这种半截状态。

**新增组件时按下面这张表取色**，不要再写死颜色——写死的那一处会在另一套主题下"发亮或发黑"：

| 用途 | 变量 |
|---|---|
| 页面底色 / 面板 / 边框 / 主文字 / 次要文字 | `--mk-bg` `--mk-panel` `--mk-border` `--mk-text` `--mk-text-sub` |
| 外壳顶栏（背景 / 文字 / 次要文字 / 分隔线） | `--mk-header-bg` `--mk-header-text` `--mk-header-muted` `--mk-header-border` |
| 3D 大屏 HUD（面板底 / 描边 / 主文字 / 次要文字） | `--mk-hud-bg` `--mk-hud-border` `--mk-hud-text` `--mk-hud-muted` |
| 场景底色（Cesium 椭球底色）与页面底色 | `--mk-globe-bg` `--mk-screen-bg` |

**3D 里什么跟着主题变、什么不变**：椭球底色与雾密度跟着变（`createViewer.js` 的
`applyViewerTheme`），**影像与地形本身不动**——那是数据，不是装饰。

### 坐标口径（重要）

本地模型以**项目配置里的锚点**（`digital_twin_scene.anchor_longitude/latitude/height`）
建立 ENU（东-北-上）局部坐标系；GLB 的 `+X/+Y/+Z` 分别对应东/北/上。当前两份资产各自的
锚点：`qingyuan-hillside-v2` 为 `113.05133°E, 23.75946°N, 43m`（默认），
`qingyuan-hillside` 为同一锚点的 107m 基准（V18 首版），
`mountain-demo` 为 `113.0508°E, 23.7208°N, 2m`（纯程序化，回退）。测点与雷达的经纬度/高程由生成器
从同一份高程场采样并写进迁移，所以立柱直接落在 GLB 坡面上。
Cesium 的 `CLAMP_TO_GROUND` 只能贴椭球/terrain，不能贴独立 GLB，所以山地模式不使用地形钳制。
切到 `globe` 模式且真实地形就绪后，仍会采样在线地形高度。

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

- **不认识的 `pointId` 直接忽略**——不依赖"服务端一定只推该推的"，不属于当前项目的点不许污染快照；
- **解除/误报要把点从 `activeAlarms` 摘掉**，否则数值回落之后颜色一直是红的。同一条警情
  就地升级（A 定案：不另开平行警情）会**再推一条**，所以同一个点会再闪一次。

### 项目隔离：推送侧已按订阅者过滤，前端这一层是防御

> **2026-09-17 更正**：本节此前写的是「SSE 不按订阅者过滤，前端必须自己收口」。那条**在写成当天就被服务端修掉了**
> （2026-09-14，B 侧同步单提出后当天修）：服务端现在**没有「发给所有人」这个入口**——无过滤的 `broadcast`
> 已删除，8 处调用点全部改为 `broadcastScoped`（`backend/.../common/sse/SseBroadcaster.java:109`），
> 可见性判据**复用 `DataScopeService` 同一份实现**，事件归属是**集合**（一台设备可绑多个项目的测点），
> **空集 = 无归属 → 只发 ADMIN**（fail-closed）。回归锚点是 `tools/acceptance/10-scope.sh` ⑪：开三条真实订阅做正反两侧断言；
> 把判定改成恒真时 6 条负向断言全红、正向对照全绿。

所以 `monitor.applyAlarm()` 里那段可见性判断**还在，但性质变了**：从"唯一屏障"降为**防御层**——
服务端过滤若因回归失效，界面也不至于把别的项目的点或告警横幅亮出来。判据不变：

- 测点告警：`pointId` 必须在本页已加载的测点里（`latestMap` 就是可见测点集合）；
- 设备告警：`deviceId` 必须在 `deviceIds`（来自 `/devices`，后端已按成员项目过滤）里；
- 不认识的 `pointId` / `deviceId` 一律忽略。

`measurement` 事件本来就只并进已存在的测点，无需额外判断。

### 兜底与重连

浏览器自己会重连（服务重启这类瞬断）；若服务端明确关闭（401 / 主动关闭），`EventSource`
进入 `CLOSED` 不再自愈，此时按 1s→30s 退避重连，连续失败 8 次后落到 `closed`，
界面显示「推送已断开」。轮询只是兜底：**断流时 15s 一次补齐**，流正常时 **60s 才对一次账**。

## 大屏的时间轴回放与地面热力图（阶段 3b 呈现）

底部那条时间轴负责回放，左下角图例下面那个勾选框负责地面热力图。

**回放不是「暂停订阅」**：实时链路始终在跑，回放只是在大屏这一层换数据源——`ScreenView`
里的 `displayPoints` 决定「屏幕上显示的是实时快照还是历史帧」，点位、列表、弹窗、热力图
全读它（只切一处，就不会出现「3D 是历史值、列表还是实时值」）。退出回放立即回到实时。

数据逐点拉 `/points/{id}/series` 再合并成帧（`utils/timeline.js`，纯函数、有断言）：

- 各点采样时刻并不对齐（补传、分批上报都会错开），所以先并到同一条时间轴上；
- 某点在某一帧的值 = **该时刻之前最近的一次采样**（前值保持）。这条不是可选口径：
  屏幕上任何时刻看到的值，都必须是那时**已经量到**的值，不能用未来采样倒推；
- 帧的时间串沿用后端原文（带 `+08:00`），不自己转 UTC，免得界面显示差 8 小时。

热力图：`ellipse` 使用测点档案高程铺在 GLB 表面上方 + 径向渐变贴图（按颜色缓存），半径随
|值| 放大、颜色沿用 `resolvePointVisual` 的全局口径。**故意不做插值**——7 个离散点插出来的面是
算出来的、不是量出来的，图上却看不出这层区别。热力晕圈**不可拾取**
（`pointLayer.pickId` 只认 `kind === 'point'` 的那颗点），否则整片地面都变成
「点了就弹窗、想关还关不掉」。

## 测项中立化（骨架）

**「有哪些测项」只有一个来源：`GET /api/v1/metrics`**（每点一份 `{pointId, code, name, unit, sortOrder}`）。
业务页面里不再出现 `defo_mm` / `rate_mm_d` 这种写死的字段名，**名称与单位也从档案取**。

### 「主测项」

一份测点数据可能有多个测项，但 3D 着色、地面热力图、点位标签、时间轴回放**同一时刻只能按
一个测项表现**——这就是主测项（`monitor.primaryMetricCode`）：默认取档案里的 `defo_mm`，
没有就取排序最靠前的；大屏顶栏可切换；切到档案里不存在的代码会被拒绝（界面全空最难解释）。

每个测点拍平后的结构（`enrichedPoints`）与测项无关：

```js
{ id, code, metrics: { defo_mm: 1.8, temp_c: 41.5 },   // 所有测项的原值
  metricCode: 'defo_mm', value: 1.8, unit: 'mm', metricName: '累计形变',
  threshold: 3,                                         // 见下
  quality, signal, state, hasAlarm, alarmLevel, ... }
```

`cesium/pointLayer.js`、`cesium/heatmapLayer.js` 只认 `value / unit / metricName`，
所以换测项时这两个文件零改动。

### 阈值有两个入口，口径必须一致

- **曲线上的虚线**：`composables/useThresholds.js`（要跟组件 ref/watch 绑、带 30s 缓存）；
- **3D 的「超限变色」**：`utils/thresholds.js` 的 `warnThresholdOf()`（纯函数，store 里用不了 composable）。

两边都从 `/alarm-rules` 取，过滤口径一致：`enabled` / `metricCode` 相符 /
`pointId` 为空（全局）或正是本测点。3D 那边原来是写死的 `warnThreshold = 3`，
规则改成 ±5mm 后会出现「后端按 5mm 报警、界面按 3mm 变黄」，现在同源。
**没有规则就不判超限**——宁可不黄，也不要自己发明一个阈值。

### 加一种测项要做什么

1. 管理端「测项」页给该测点加一行（`code` / `name` / `unit`）——**前端零改动**；
2. 想让它有阈值告警，再加一条 `/alarm-rules` 规则（同一个 `metricCode`）；
3. 设备按 `message-contract` 上报这个测项的值即可。

管理端「告警规则」表单里的**测项下拉**也已数据驱动：`AdminView` 的 `REFS` 里多了一条
`metricCodes`（取 `/v1/metrics`，按 `code` 去重、值取 `code`），所以第 2 步选新测项时
下拉里自然会有它——此前那里写死 `['defo_mm','rate_mm_d']`。

## 项目维度（大屏）

项目隔离（A 的 09-14 批）把 `/projects`、`/points`、`/devices` 都限到「我是成员的项目」。
在此之上，大屏顶栏多了一个**项目下拉**（只在成员项目 >1 时才出现）：

- `/points` 本身**不接 `projectId`**，所以按档案的归属链在本地过滤：
  测点 → 对象 → 场景 → 项目（`monitor.pointsOfProject`）。切项目**不再打接口**，
  也不会把别的项目的点画到大屏上；
- 切换时会清掉 `latestMap` 并重载快照——那张表同时是 SSE 事件的可见性判据（见上节）；
- **只影响读这份 store 的页面**（当前就是 3D 大屏）。测点/设备/告警页各有自己的取数口径，
  但都被后端按成员项目限过范围；要做成全局项目上下文，得让那些页面也走这份 store，
  那是后续的事——别在这里假装已经生效。

一个项目都没加入时（项目隔离后新建的账号就是这样）：`loadSnapshot()` 直接收工，
`noProjectReason` 置为「你还没有加入任何项目」，大屏顶栏显示这句话。
它和 `error` 分开——这不是失败，是权限范围的正常结果，显示「加载失败」会让人去查后端。

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
| 3 | Cesium 3D 大屏（山地 / 标点着色 / 弹窗 / 时间轴 / 热力图） | **3a ✅**（自有离线山地 GLB + 雷达扫描扇区 + 贴坡测点；可切回真实地形/卫星影像）；**3b ✅**（SSE 收口到 store、推送驱动 + 告警脉冲、时间轴回放、地面热力图，见下两节） |
| 4 | 告警中心 + 管理端（alarms / actions / 规则 CRUD） | 告警中心 ✅（含处置时间线）；管理端 ✅ **读写**（项目/场景/对象/测点/测项/设备/告警规则的增删改） |
| 5 | 无人机影像挂点（media） | ✅ 2026-09-14：`MediaGallery` / `MediaUploader` 两个共用组件，落在测点详情「影像」页签、`/media` 总览页、3D 大屏点击浮窗三处（浏览器实测 18/18） |
| 6 | 审计日志 + 设备详情（验收第 7 条） | ✅ 2026-09-14：`/audit` 审计日志页（仅 ADMIN）、`DeviceDrawer` 三页签（基本信息 / 绑定测点 / 维护记录），被设备页与运维台共用（浏览器实测 26/26） |
| 7 | 项目数据范围隔离（验收第 7 条前半） | ✅ 2026-09-14：**过滤全在后端**，前端一行没改。但补了一轮**零项目用户**的实测——这是本块最容易出真缺陷的地方（浏览器实测 27/27） |
| 8 | 3D 数字孪生 V2：双雷达标定 + 精细山体（38,400 面） | ✅ 2026-09-16：大屏可切换当前雷达、显示三维视场与目标 LOS、浮窗标定信息（见 `docs/交付说明_双雷达精细山体_V2_20260916.md`） |
| 9 | 管理端几何维护 + 标定界面 | ✅ 2026-09-17：`AdminView` 设备页签补齐 7 个几何字段，`DeviceDrawer` 补人工停用（原因下拉 + 有效期），设备位姿改动**浏览器可达**（清单第 14、16 条） |
| 10 | 回放加载优化 + 请求代际守卫 | ✅ 2026-09-17：粒度降采样 / 并发池 4 / 按天分段（清单第 18 条），`utils/requestGuard.js` 收口乱序响应（第 17 条），回放过期限时（第 19 条） |
| 11 | 前端回归脚本 | ✅ 2026-09-17：`npm run selfcheck` **67 条 / 0 失败**、`node scripts/check-p0-stage1.mjs` **35 项全通过**；脚本层测不到的 13 条走人工验（`docs/手动验证步骤_第14-16-17-18-19条_20260917.md`） |
| 12 | 个人中心（`/profile`） | ✅ 2026-09-17 晚：顶栏头像下拉进入；**本人可改自己的资料**（姓名/公司/岗位/电话/邮箱；账号与角色只读）+ 修改密码（验原密码、新密码 ≥8 位、两次一致）。同轮 `selfcheck` 67 → **73 条** |
| 13 | 自助注册 + 管理端「用户」页签 | ✅ 2026-09-17 晚（按用户口径二次重做）：**登录页新增「注册账号」**（`/register`，个人信息本人填，注册即登录，不能选管理员角色）；管理端「用户」页签改为**只看 + 改角色/启用 + 删除**（不再建号、也改不到别人的资料）；**侧边栏按角色收敛**（设备状态=ADMIN/MAINTAINER/OPERATOR，影像挂点=ADMIN/MAINTAINER/ANALYST，管理端/审计=ADMIN，其余四角色共有），路由 `meta.roles` 与菜单同口径。同轮修掉一个**页签切换的竞态**：`reload()` 原先读的是「响应到达那一刻」的 `currentTab`，切页签后会把上一个页签的数据套在新列上渲染（实测表现为「用户页 2 行、每格都是 —」），现改为**先快照页签 + 代际守卫**，旧响应一律丢弃 |

> **2026-09-11 合并说明**：阶段 1 骨架由 B 落地；A 在其基础上并入总览 / 测点与曲线 /
> 设备状态 / 告警中心 / 管理端五个页面（路由沿用本表阶段划分）。合并时 B 的骨架文件
> （`http.js` / `stores/user.js` / `AppLayout.vue` / `LoginView.vue`）**保持原样**，
> A 那版对应的重复实现已丢弃。详见 `docs/daily/2026-09-11-A.md`。

## 已知限制

- **个人信息归本人、权限归管理员**（2026-09-17 口径）：个人中心可改姓名/公司/岗位/电话/邮箱
  （`PUT /auth/me`），管理员改不到这些（`PUT /users/{id}` 只收 role/enabled）。
  仍未做：**忘记密码的自助找回**（要接邮件/短信通道）、**注册审批**、**组织管理**
  （公司名自由文本自动建组织，没有改名/合并/删除入口）——都记在改进清单第 13 条与根 README 的「已知限制」里。
- **管理端写操作限于「增删改」**，不做批量导入/导出，也不做外键的级联删除提示
  （删除是逻辑删除，父子关系不会一并置灰）。
- **管理端的表列是运行时从响应数据推导的**，所以列名就是后端字段名（`objectId` 而非「所属对象」）。
  要中文列名就得为每个资源写一份列映射——当前优先级不高，没做。
- **设备状态靠轮询**（10s）：SSE 只推 `measurement` / `alarm` 两类事件，不推设备状态。
- ~~曲线阈值线是写死的 ±3mm~~ **已于 2026-09-14 修正**：改由 `composables/useThresholds.js`
  拉 `/alarm-rules`（两处调用点共用一份，此前是「同一口径抄两份」）。当时发现的问题比
  「写死」更重——**两张图都标错了档位**（把 +3 的 warning 标成「告警」，而真正的 alarm 档 +5 一条没画）。
- **图表库警告**：`echarts` 单独成 chunk 后 `PointsView` 产物约 1.1MB，
  构建时会有「chunk 大于 500kB」的提示；当前不做 `manualChunks` 拆分，等真在意首屏再处理。
- **回放取数仍是「逐点请求」**：1000 点 = 1000 个请求（250 波往返）。已用粒度降采样、并发池
  （`MAX_INFLIGHT = 4`）与按天分段把峰值压住，但**请求个数没变**；根治需要后端批量 series 端点，
  取数已收口在 `mapWithLimit` 的 fn 里，将来换端点只改那一处（清单第 18 条）。
- **回放的窗口档位与曲线的窗口档位是两份定义**（`REPLAY_RANGES` 与 `PointsView` 的 `RANGES`）：
  当前取值恰好重合，改一处忘另一处不会报错（清单第 18 条 ⑤）。
- **主工作页没有窄屏适配**：`@media` 只出现在 `LoginView`、四个 `home/*` 工作台与 `StatTiles`；
  大屏、告警、测点、设备、管理端、审计、影像这七页没有媒体查询（清单第 23 条 ④）。
- ~~3D 大屏靠 10s 轮询刷新~~ **已于 2026-09-14 改为推送驱动**：连接归 `stores/realtime.js`
  （应用级单例，见「实时推送（阶段 3b）」一节），轮询降级为兜底（断流 15s / 正常 60s 对账）。
- **地面热力图的形态是「每个测点一圈径向渐变」**，不是插值出来的连续场：数据源只有 7 个离散
  测点，插值面是算出来的、不是量出来的（详见 `cesium/heatmapLayer.js` 顶部注释）。
  有面状采样数据后可以换成真正的热力面。
- **每单位放大多少米是呈现标度**（`cesium/heatmapLayer.js` 的 `RADIUS_PER_UNIT`）：
  mm 与 ℃ 的数值范围差几个量级，用同一个系数会让某类测项要么糊满全屏、要么看不见。
  档案将来若加 `range` 字段，改成读档案即可。
- **`utils/labels.js`（A）与 `constants/status.js`（B）有部分重叠**：前者负责枚举 → 中文文案，
  后者是 3D 取色口径 + 质量/状态枚举。建议后续合并到一处，避免同一个枚举两套映射。
- **「可见范围为空」在新前端上不再是一种角色**（2026-09-14）：项目隔离落地后，
  `outsider`（不属于任何项目的访客账号）会在**六个页面上都看到空态**——已实测：不是白屏、
  不卡在「加载中」、导航菜单仍在（顶栏明写「访客（未加入任何项目）」），零 console error、零 4xx/5xx。
  前端能扛住是因为三处判据本来就有守卫（`projects[0]?.id ?? null`、`summary.` 全 `?.`、
  `projects?.[0]?.id` + `.catch(() => null)`）——**这次是去证实它，不是碰巧没崩**。
- **隔离产生的 403 显示的是后端原文，不是「当前角色没有该操作权限」**：`http.js` 的响应拦截
  在 403 时**优先用 `body.message`**，静态那句只是响应体没有 message 时的兜底。
  所以李敏直达项目 2 看到的是「无权访问该项目的数据: 2」——比笼统的角色提示有用得多。
  这不是缺陷，是拦截器的既有优先级正好契合；写在这里是因为**很容易被误判成拦截器把后端文案吃掉了**。

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
- ~~运维台的「设备维护记录」与「设备-测点绑定」前端没页面~~、~~审计日志无页面~~
  **均已于 2026-09-14 补上**（见下）。~~**大屏 3b**（时间轴动态实体、风险/形变热力图）仍未做~~
  → **同日由 B 完成**（SSE 收口到 store、推送驱动 + 告警脉冲、时间轴回放、地面热力图，
  见「实时推送（阶段 3b）」与「大屏的时间轴回放与地面热力图（阶段 3b）」两节）。
- **设备详情抽屉**（`components/DeviceDrawer.vue`，三页签：基本信息 / 绑定测点 / 维护记录）
  被设备页与运维台共用。绑定与维护的写入口按角色显隐（ADMIN/MAINTAINER），
  值班/研判只读——**同样只是不显示按钮，后端 `@PreAuthorize` 才是边界**。
  维护记录的 `operator` 由后端从当前登录用户填，前端传什么都不作数。
- **审计日志页**（`views/AuditView.vue`，路由 `/audit`，仅 ADMIN）。
  动作名是中文、`targetType` 是自由文本，两者都随 `@AuditAction` 走，不是枚举——
  所以页面上给的是「当前代码能产生的值」的候选下拉 + `allow-create`，**筛不到不等于没记录**。
- ~~影像没有删除入口~~ **已于 2026-09-14 补上**：`DELETE /api/v1/media/{mediaId}`（逻辑删除，
  盘上文件保留）。入口由 `MediaGallery` 的 `deletable` 控制，只在**测点详情**与 **`/media` 总览页**
  打开；3D 大屏浮窗**刻意不给**（那是「看」的场合，演示现场挂个删除按钮只会误触）。
  角色限 ADMIN/MAINTAINER，由 `canDelete` 挡显示、后端 `@PreAuthorize` 挡调用——**藏按钮不是权限**。
