# 资产来源与使用说明 · wh-plant（现状档）

## 用途

三维大屏的**现状档**演示资产：苏黎世 **Werdhölzli 污水处理厂**周边 1200 × 900 m。
选这个场址是为了对标我们的"电厂厂区"监测场景（厂房、水池、管廊、住宅成片）。
它也是"免费公开数据能做成什么样"的基准档，与 `wh-plant-hires`（航拍级参考档）**同机位可比**。

## 数据来源

| 数据 | 来源 | 规格 |
|---|---|---|
| 正射影像 | swisstopo **SWISSIMAGE z19** | 0.20 m/px，456 张瓦片 |
| 高程 | swissALTI3D 0.5 m → terrarium **z17** | 0.83 m/px，30 张瓦片 |
| 建筑真几何 | **swissBUILDINGS3D 3.0 LoD2**（CityGML，含屋顶） | 体块级真几何（无门窗/设备/管廊） |

**许可**：swisstopo 开放数据（OGD）。允许离线缓存与再分发，**使用时必须署名 swisstopo**。

## 生成方式（可复现）

```text
影像 ← tools/imagery_fetch/fetch_imagery.py（源 swisstopo）
高程 ← tools/terrain_asset/geotiff_to_sources.py（GeoTIFF → terrarium）
地形 ← tools/terrain_asset/build_terrain_asset.py
建筑 ← tools/terrain_asset/citygml_to_glb.py（LoD2 → 与地形同坐标系的 GLB）
合并 ← tools/terrain_asset/merge_glb.py
```

## 规格

| 项 | 值 |
|---|---|
| 文件 | `wh-plant.glb`，13.20 MB |
| 三角面 | 192,753（地形 135,000 + 建筑 57,753） |
| 地形网格 | 4 m（6000 × 4500 m 级场址范围内 1200 × 900 m 核心区） |
| 纹理 | 4096 × 3072 JPEG 4.9 MB（**0.29 m/px**） |
| 材质 | 两个 primitive：地形 `SatelliteTerrainUnlit`（贴正射）、建筑 `BuildingUnlit`（顶点色，**白模**） |
| 演示锚点 | 8.506°E, 47.3935°N（**苏黎世**；清远真实厂址尚未落数据） |
| SHA-256 | `c1f7fc2c0ca2c7f62b8ee3879667fca1915f9044b81906a3002599905ca8abbe` |

## 使用边界（红线）

1. **演示与内部预览用途**，不得用于工程测量、风险评估或应急决策；
2. 建筑是 **LoD2 体块级**真几何，**没有门窗、设备、管廊**，也没有立面纹理；
3. 场址是苏黎世样板，**不代表清远现场**；清远那条项目记录的锚点仍是模拟选址；
4. 对外发布/交付前须确认影像授权（本资产为 OGD，署名即可；若替换为商业影像按商业许可执行）。
