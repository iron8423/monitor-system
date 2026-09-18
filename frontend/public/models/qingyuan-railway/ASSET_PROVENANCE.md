# 真实地形资产来源与使用说明

本目录的 `qingyuan-railway.glb` 是「真实地形演示资产」：几何骨架来自公开 DEM，
色彩底来自公开卫星影像，中高频细节为程序化生成。**不是**现场实测成果，
不得用于工程测量、风险评估或应急决策。

## 数据来源

- 高程：Mapzen/AWS terrain tiles（terrarium 编码，SRTM 派生），zoom=15，1 张瓦片。高程数据 SRTM：NASA/USGS 公有领域；瓦片打包：Mapzen Terrain Tiles（CC BY 4.0）。引用：https://registry.opendata.aws/terrain-tiles/
- 影像：Sentinel-2 cloudless 2023（EOX IT Services GmbH），zoom=16，4 张瓦片。CC BY 4.0。引用：Sentinel-2 cloudless – https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2023)。允许离线缓存与再分发，需保留署名。
- 演示锚点：23.72050N 113.02190E（清远市区以北约 4km 的模拟选址，非清远电厂真实厂址）
- 场景尺寸：600m × 450m，网格 240 × 180 单元（2m）
- 纹理：2048×1536 JPEG，230 KiB，内嵌于 GLB

## 哪些是量出来的、哪些是算出来的

- **实测**：宏观地形（30m 级 DEM）、影像色彩与地物分布（10m 级）。
- **程序化**：中高频地形细节（幅度 ±1.2m 级，按坡度加权）、
  树冠级纹理噪声、山体阴影（太阳方位 315°、高度角 45°，已烘焙进纹理）。
  公开 DEM 在 320m 场景里只有约 10×8 个采样点，不补细节会呈蜡状；
  这条边界必须让读者知道，避免把观感当成测绘精度。
- **模拟**：雷达位姿、测点位置与标定参数。测点仍在原有本地坐标上，
  但高程、雷达瞄准角与视线净空是按本资产地形重新解算的（见 coverage.json）。

## 与现有系统的关系

- 资产走既有 GLB + ENU 本地坐标管线，前端渲染代码无需改动；
- 材质使用 `KHR_materials_unlit`，山体阴影已烘焙，画面不受场景时钟影响；
- 同步 SQL 草稿在 `scene-update.sql`，经人工评审后进入 Flyway 迁移。

## 可追溯性

- 资产版本：`qingyuan-railway-1.0.0`
- GLB SHA-256：`c72e0bcf83012e3085f88966508f2183c7d1a55526e8443fa84c57ece7ee3862`
- 数据获取时间：2026-09-18T02:22:10+00:00
- 包围盒：{"west": 113.01719274821014, "south": 23.716843253820684, "east": 113.02660725178987, "north": 23.724156746179318}

重新生成：先跑 `tools/terrain_asset/fetch_sources.py`（瓦片带 SHA-256 缓存），
再跑 `tools/terrain_asset/build_terrain_asset.py`。同一份数据源与参数会得到
逐字节一致的 GLB。

## 使用边界

资产可随本项目离线部署，需保留上文的 Sentinel-2 cloudless 署名。
正式交付时应替换为项目方提供的无人机航测 DSM + 正射影像；
届时关掉程序化细节（`--detail-amplitude 0`）即为实测资产。
