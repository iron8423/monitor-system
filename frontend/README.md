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
  components/   # EChart.vue（通用 ECharts 封装）、SeriesChart.vue（测点时序 → option）
  constants/    # status.js：质量/状态/告警级别枚举 + resolvePointVisual（全局统一取色口径）
  layout/       # AppLayout.vue（顶栏 + 侧边菜单 + 内容区 + 全局 SSE 连接）
  router/       # 路由 + 登录守卫 + 角色门禁
  scripts/      # copy-cesium.mjs：把 Cesium 运行时资源复制到 public/cesium/（postinstall 自动跑）
  stores/       # Pinia：user（登录态）、monitor（3D 大屏用的监测数据单一数据源）
  styles/       # 全局样式与 CSS 变量（含 .mk-panel / .mk-mono 等页面通用件）
  utils/        # token.js（localStorage 读写）、labels.js（枚举 → 中文展示文案）、format.js（时间/数字）
  views/        # 页面：Login / Home（总览）/ Points（测点与曲线）/ Device / Alarm / Admin / Screen（3D 大屏）/ NotFound
```

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
- 生产部署时让 Nginx/后端把 `/api` 反代到后端，前端代码不用动。

## 阶段进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 工程骨架 + 登录（JWT 存储 / 请求头注入 / 路由守卫） | ✅ |
| 2 | 总览 / 测点页（points、latest、series、summary） | ✅ 总览 + 测点与曲线已落地；设备状态页另加 |
| 3 | Cesium 3D 大屏（地形 / 标点着色 / 弹窗 / 时间轴 / 热力图） | **3a ✅**（真实地形 + 卫星影像 + 标点着色 + 点击弹窗 + 三级降级）；3b 时间轴 / 热力图 / SSE 直连待做 |
| 4 | 告警中心 + 管理端（alarms / actions / 规则 CRUD） | 告警中心 ✅（含处置时间线）；管理端**只读**，写操作待做 |
| 5 | 无人机影像挂点（media） | 待做 |

> **2026-09-11 合并说明**：阶段 1 骨架由 B 落地；A 在其基础上并入总览 / 测点与曲线 /
> 设备状态 / 告警中心 / 管理端五个页面（路由沿用本表阶段划分）。合并时 B 的骨架文件
> （`http.js` / `stores/user.js` / `AppLayout.vue` / `LoginView.vue`）**保持原样**，
> A 那版对应的重复实现已丢弃。详见 `docs/daily/2026-09-11-A.md`。

## 已知限制

- **管理端只读**：表格能列出后端各档案资源（证明 CRUD 列表端点已可被前端消费），
  但新建/编辑/删除按钮未接——写操作要 `ADMIN`，且表单字段多（项目/场景/对象/测点四层外键）。
- **设备状态靠轮询**（10s）：SSE 只推 `measurement` / `alarm` 两类事件，不推设备状态。
- **曲线阈值线是写死的 ±3mm**：应当来自 `/alarm-rules`，等规则页做出来再改成拉接口。
- **图表库警告**：`echarts` 单独成 chunk 后 `PointsView` 产物约 1.1MB，
  构建时会有「chunk 大于 500kB」的提示；当前不做 `manualChunks` 拆分，等真在意首屏再处理。
- **3D 大屏目前靠 10s 轮询刷新**：`AppLayout` 里那条全局 SSE 连接只用于顶栏「实时已连接」
  状态灯，页面拿不到它（`stream` 是布局内的局部变量，页面无法订阅）。3b 会把 SSE 收口到
  Pinia store，让大屏改由推送驱动（点位实时跳动 + 告警脉冲）。
- **`utils/labels.js`（A）与 `constants/status.js`（B）有部分重叠**：前者负责枚举 → 中文文案，
  后者是 3D 取色口径 + 质量/状态枚举。建议后续合并到一处，避免同一个枚举两套映射。
