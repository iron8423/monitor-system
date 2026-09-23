# 资产来源与使用说明 · wh-plant-hires（航拍级参考档）

## 用途

回答公司那个问题：**"同时有航拍正射和高精度地形时，这套三维方案能做到什么样子"**。

与现状档（`wh-plant`）的**唯一差别是数据精度与材质档位**——场地、锚点、分区、测点、相机全部不变，
所以两档的截图是**同机位可比**的。它是"公开免费数据的上限"那一档：
再往上一级只有无人机航测/倾斜摄影，或者甲方总平面图 / CAD / BIM。

## 数据来源

| 数据 | 来源 | 规格 |
|---|---|---|
| 正射影像 | swisstopo **SWISSIMAGE z20** | **0.10 m/px**（原生），1692 张瓦片 |
| 高程 | swissALTI3D 0.5 m → terrarium **z17** | 0.83 m/px |
| 建筑真几何 | **swissBUILDINGS3D 3.0 LoD2**（CityGML，含屋顶） | 与现状档同一份几何 |

**许可**：swisstopo 开放数据（OGD），允许离线缓存与再分发，**需署名 swisstopo**。

## 生成方式（可复现）

```text
影像 ← tools/imagery_fetch/fetch_imagery.py --source swisstopo（z20）
高程 ← tools/terrain_asset/geotiff_to_sources.py
地形 ← tools/terrain_asset/build_terrain_asset.py（2 m 网格 + 8192×6144 纹理）
建筑 ← tools/terrain_asset/citygml_to_glb.py（**屋面贴正射影像 + 墙面 PBR 受光**，屋面/墙面分流成两个 primitive）
合并 ← tools/terrain_asset/merge_glb.py
```

## 规格

| 项 | 值 | 对比：现状档 |
|---|---|---|
| 文件 | `wh-plant-hires.glb`，38.86 MB | 13.20 MB |
| 三角面 | 597,753（地形 540,000 + 屋面 19,535 + 墙面 38,218） | 192,753 |
| 地形网格 | **2 m** | 4 m |
| 纹理 | 8192 × 6144 JPEG 18.2 MB（**0.15 m/px**） | 4096 × 3072，0.29 m/px |
| 材质 | 地形 `SatelliteTerrainUnlit` + **屋面 `RoofOrtho`（贴正射影像）** + 墙面 `BuildingPBR`（受光） | 建筑为白模顶点色 |
| 渲染档位 | `lit` + HDR；**AO 关、实时阴影关**（理由见下） | `flat`（烘焙明暗） |
| 预览方式 | `npx vite --mode hires --port 5174`（对应 `frontend/.env.hires`） | `npm run dev` |
| SHA-256 | `8ae5a292e51a4b90aaa7c2571fab5cfdb8b1f9d240178362b5c7365294d036f1` | — |

**为什么关掉 AO 与实时阴影**：

- AO（环境光遮蔽）在 2 m 网格 + 0.1 m 正射下会**自遮蔽出规则细斜纹**（实测：关掉即完全干净）；
- 正射影像里本来就有真实日照与建筑投影，再叠实时阴影属于**重复计算**，而且阴影贴图自遮蔽会在地面糊出细网格。

## 使用边界（红线）

1. 演示与内部预览用途，**不得用于工程测量、风险评估或应急决策**；
2. 屋面贴的是**正射投影**影像，所以斜看/俯看像真的，**贴到地平面平视时立面仍是素色**（LoD2 无门窗、设备、管廊）；
3. **"看得清"不等于"尺寸准"**：真尺寸仍然只能靠甲方图纸/CAD/BIM 或控制点测量；
4. 场址是苏黎世样板（锚点 8.506°E, 47.3935°N），**不是清远现场**。
