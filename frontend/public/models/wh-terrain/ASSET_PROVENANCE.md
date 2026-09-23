# 资产来源与使用说明 · wh-terrain（现状档的地形部分）

## 用途

现状档的**地形单独成档**（不含建筑），用于两类场合：

1. 对照排查：判断画面里的问题是来自地形层、还是来自建筑层（例如"白模挡住了什么"）；
2. 只要看地形的场合：换建筑几何（图纸/BIM/航测）时，地形部分可以复用这一份。

## 数据来源

与 `wh-plant` 完全同源（同一批瓦片、同一锚点、同一基准面）：

| 数据 | 来源 | 规格 |
|---|---|---|
| 正射影像 | swisstopo **SWISSIMAGE z19** | 0.20 m/px |
| 高程 | swissALTI3D 0.5 m → terrarium **z17** | 0.83 m/px |

**许可**：swisstopo 开放数据（OGD），允许离线缓存与再分发，**需署名 swisstopo**。

## 生成方式

```text
tools/imagery_fetch/fetch_imagery.py → tools/terrain_asset/geotiff_to_sources.py
→ tools/terrain_asset/build_terrain_asset.py
```

## 规格

| 项 | 值 |
|---|---|
| 文件 | `wh-terrain.glb`，8.57 MB |
| 三角面 | 135,000（4 m 网格） |
| 纹理 | 4096 × 3072 JPEG 4.9 MB |
| 材质 | `SatelliteTerrainUnlit`（贴正射，unlit） |
| SHA-256 | `483cf9d621092e7aece8403be6e9433a310634002e04de72d39a087de8950b96` |

## 使用边界

演示与内部预览用途；不得用于工程测量、风险评估或应急决策。场址为苏黎世样板。
