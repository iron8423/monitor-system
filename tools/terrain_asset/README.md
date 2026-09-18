# 离线真实地形资产流水线（DEM + 卫星影像 → GLB）

把**公开 DEM** 与**公开卫星影像**烘焙成可离线运行的 GLB 地形资产，供 3D 大屏按现有
GLB + ENU 本地坐标管线直接加载。它和 `tools/mountain_asset/` 的分工是：

| 工具 | 地形来源 | 纹理 | 用途 |
|---|---|---|---|
| `tools/mountain_asset/` | 固定数学函数（虚构） | 顶点色，无纹理 | 不依赖任何外部输入的可复现演示 |
| `tools/terrain_asset/` | **真实 DEM**（30m 级） | **真实卫星影像**（10m 级）+ 程序化细节 | 真实地形演示；后续接无人机航测成果 |

## 数据源与许可

| 数据 | 来源 | 分辨率 | 许可 |
|---|---|---|---|
| 高程 | Mapzen/AWS terrain tiles（terrarium 编码 PNG，SRTM 派生） | 30m 级（z=15，4.4m/px 过采样） | SRTM 为 NASA/USGS 公有领域；瓦片打包 CC BY 4.0 |
| 影像 | EOX「Sentinel-2 cloudless 2023」（WMTS） | 10m（真彩色、全球无云拼接） | CC BY 4.0，允许离线缓存与再分发，**需保留署名** |

两条刻意的取舍：

1. **不用 Google / Esri 瓦片**：那两家的服务条款禁止离线缓存与二次分发，内网部署要另购授权；
   Sentinel-2 是唯一「免费 + 允许离线」的高频选择。Google 中国区影像还带 GCJ-02 偏移，
   直接叠加会与 WGS-84 测点错位。
2. **不直接下 SRTM `.hgt`**：整幅 1°×1° 压缩后 20MB 以上，本机实测十几 KB/s；
   同样覆盖范围用 terrarium 分块只有几百 KB，且 PNG 可直接解码，不需要 GDAL。

## 用法

```bash
# 1) 抓数据（带 SHA-256 缓存，重复执行不会重复下载）
python3 tools/terrain_asset/fetch_sources.py \
    --anchor-lon 113.05133 --anchor-lat 23.75946 \
    --width 320 --depth 240 \
    --out generated/terrain-asset/sources

# 2) 生成资产
python3 tools/terrain_asset/build_terrain_asset.py \
    --sources generated/terrain-asset/sources \
    --out frontend/public/models/qingyuan-hillside
```

依赖：Python 3.10+、`numpy`、`Pillow`（`pip install numpy pillow`）。抓取阶段需要联网，
生成阶段完全离线。

### 主要参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--anchor-lon/--anchor-lat` | 113.05133 / 23.75946 | 场景中心；由 `fetch_sources.py` 写入清单，生成时沿用 |
| `--width/--depth` | 320 / 240 | 场景米数（东西 × 南北） |
| `--cells-x/--cells-y` | 160 / 120 | 网格单元数（2m 间距，38,400 三角形） |
| `--detail-amplitude` | 1.6 | 程序化地形细节基准幅度（米）；**接航测数据时传 0** |
| `--detail-seed` | 20260918 | 细节噪声种子（固定种子 ⇒ 逐字节可复现） |
| `--camera` | 327 -34 520 | 初始相机（方位 / 俯仰 / 距离） |

### 输出（写入 `--out` 目录）

| 文件 | 用途 |
|---|---|
| `<name>.glb` | 资产本体：网格 + 内嵌 JPEG 纹理 + `KHR_materials_unlit` |
| `preview.jpg` | 烘焙后纹理的俯视预览（人工核对用） |
| `scene-config.json` | 与 mountain-demo 同形状的场景配置（雷达、相机、尺寸） |
| `points.json` | 7 个测点的本地坐标与经纬度高程 |
| `coverage.json` | 雷达位姿与每个目标的方位/俯仰/斜距/通视/最小净空 |
| `scene-update.sql` | **Flyway 迁移草稿**（见下） |
| `build-manifest.json` | 资产 SHA-256、纹理 SHA-256、数据源与参数 |
| `ASSET_PROVENANCE.md` | 来源、许可、实测/程序化的边界说明 |

## 哪些是量出来的、哪些是算出来的

这是本流水线最需要被读者知道的一条边界：

- **实测**：宏观地形（30m 级 DEM）、影像色彩与地物分布（10m 级）。
- **程序化**：中高频地形细节（按坡度加权，基准幅度由 `--detail-amplitude` 控制）、
  树冠级纹理噪声、山体阴影（太阳方位 315°、高度角 45°，烘焙进纹理，配合 unlit 材质，
  画面不受场景时钟影响）。

为什么必须补细节：30m DEM 在 320m 场景里只有约 10×8 个采样点，直接插值出来像一块融化的蜡；
而公开可离线的高分辨率影像只有 10m 一档。补细节解决的是**观感**，不是精度——
**本资产不是测绘成果，不得用于工程测量、风险评估或应急决策**。

## 接入无人机航测成果（后续）

同一脚本按「外部 DEM / 正射影像」使用即可，不需要改代码：

1. 把 DSM 导出为 GeoTIFF（或先用 QGIS 采样成与 terrarium 等价的栅格），
   正射影像导出为 PNG/JPEG；
2. `--detail-amplitude 0` 关掉程序化细节（实测数据不需要补）；
3. 用航测成果的实际中心与尺寸（`--width/--depth`）重跑；
4. 用生成的 `scene-update.sql` 出新迁移，并把 `points.json` 的坐标回填到档案。

## 与 Flyway 的关系（重要）

生成器**从不直接写 `db/migration`**：迁移一旦执行就不可再改（Flyway 校验和），
自动覆盖会造成「本地能起、线上校验失败」。正确流程是：

1. 评审 `scene-update.sql`（数值应由同一份数据源解算出来，不是手填）；
2. 复制为新版本号迁移（例如 `V18__real_terrain_asset.sql`），并写清资产版本与 SHA-256；
3. 若之后重新生成资产，**用新版本号**再开一条迁移，不要改已执行的迁移。

## 复现与校验

- 抓取阶段：`manifest.json` 记录每张瓦片的 URL、字节数与 SHA-256；
- 生成阶段：固定种子 + 固定参数 ⇒ **逐字节一致的 GLB**（可用 `sha256sum` 复核）；
- GLB 结构：glTF 2.0、单 buffer、内嵌 JPEG、`KHR_materials_unlit`；
- 视线校验：`coverage.json` 里每个目标都必须 `lineOfSight=true` 且净空 > 0，
  生成器在任一目标不可见时会直接失败，不会产出「雷达看不见自己测点」的资产。

## 当前默认资产

`frontend/public/models/qingyuan-hillside/`（版本 `qingyuan-hillside-1.0.0`）：
清远市区以北约 4km 的一处模拟边坡选址（**非清远电厂真实厂址**），
场景 320m × 240m、真实起伏约 96m、两台模拟雷达 4/3 目标。
来源与边界见该目录下的 `ASSET_PROVENANCE.md`。
