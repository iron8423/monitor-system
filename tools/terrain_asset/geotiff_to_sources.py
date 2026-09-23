"""把「GeoTIFF 高程 + 影像瓦片」组装成 build_terrain_asset 可直接用的 sources 目录。

为什么需要它：我们原有的数据源是 terrarium 编码的 PNG 瓦片（`fetch_sources.py` 产物），
但很多国家的开放高精度高程是 **GeoTIFF**（例如瑞士 SWISSALTI3D 0.5 m / EPSG:2056、
荷兰 AHN）。这份脚本做两件事：

  1. 从 STAC API 找到并下载覆盖目标范围的 GeoTIFF（带断点续抓）；
  2. 重投影 → 重采样到 Web Mercator 瓦片网格 → 编码成 terrarium PNG（`h = R*256+G+B/256-32768`）；
  3. 把影像瓦片（`fetch_imagery.py` 的产物）复制/链接进来，写出与 `fetch_sources.py`
     完全同构的 `manifest.json`。

产物：`<out>/dem/<z>/<x>_<y>.png`、`<out>/imagery/<z>/<x>_<y>.jpg`、`<out>/manifest.json`
之后直接：`build_terrain_asset.py --sources <out> ...`

依赖：rasterio、pyproj、numpy、Pillow
    pip install rasterio pyproj numpy pillow

用法（以瑞士 Grande Dixence 为例）：
    python tools/terrain_asset/geotiff_to_sources.py \
      --bbox 7.391,46.074,7.417,46.088 \
      --zoom 17 \
      --imagery-dir work/dixence/t2/imagery-z18 \
      --out work/dixence/t2/sources
"""

import argparse
import hashlib
import json
import math
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image
from rasterio.merge import merge
from rasterio.warp import Resampling, reproject, transform_bounds

# STAC 集合 → 我们要挑的 GeoTIFF 资产（按名称关键字匹配，优先分辨率更高/年份更新的）
STAC_ITEMS = "https://data.geo.admin.ch/api/stac/v0.9/collections/{collection}/items"
TILE = 256

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass


def log(message: str) -> None:
    print(message, flush=True)


def lonlat_to_px(lon: float, lat: float, zoom: int) -> tuple[float, float]:
    scale = TILE * (2 ** zoom)
    x = (lon + 180.0) / 360.0 * scale
    y = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * scale
    return x, y


def tile_bounds_3857(tx: int, ty: int, zoom: int) -> tuple[float, float, float, float]:
    """返回瓦片在 EPSG:3857 下的 (west, south, east, north)，单位米。"""
    world = 20037508.342789244 * 2
    size = world / (2 ** zoom)
    west = -20037508.342789244 + tx * size
    north = 20037508.342789244 - ty * size
    return west, north - size, west + size, north


def stac_download(collection: str, bbox: tuple[float, float, float, float], out_dir: Path,
                  prefer: str = "0.5") -> list[Path]:
    """按范围查 STAC，下载覆盖范围的 GeoTIFF（同名的取最新年份，优先 `prefer` 分辨率）。"""
    query = f"{STAC_ITEMS.format(collection=collection)}?bbox={bbox[0]},{bbox[1]},{bbox[2]},{bbox[3]}&limit=50"
    with urllib.request.urlopen(query, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    features = payload.get("features", [])
    log(f"  STAC 返回 {len(features)} 个条目")

    # 同一次查询里可能有多期（2019/2024），按"图幅名"归组，取年份最大的那期
    best: dict[str, tuple[str, str]] = {}
    for feature in features:
        item_id = feature["id"]
        # swissalti3d_2019_2596-1102 → 图幅键 2596-1102、年份 2019
        parts = item_id.split("_")
        if len(parts) < 3:
            continue
        year, sheet = parts[1], parts[2]
        for name, asset in (feature.get("assets") or {}).items():
            if not name.endswith(".tif") or prefer not in name:
                continue
            current = best.get(sheet)
            if current is None or year > current[0]:
                best[sheet] = (year, asset["href"])
    log(f"  需要下载 {len(best)} 幅 GeoTIFF（分辨率含 '{prefer}'）")

    out_dir.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for sheet, (year, href) in sorted(best.items()):
        dest = out_dir / f"{year}_{sheet}.tif"
        if dest.exists() and dest.stat().st_size > 1024:
            log(f"  [续用] {dest.name} {dest.stat().st_size / 1e6:.1f} MB")
            paths.append(dest)
            continue
        log(f"  [下载] {dest.name} ← {href}")
        with urllib.request.urlopen(href, timeout=300) as response, dest.open("wb") as handle:
            handle.write(response.read())
        log(f"         完成 {dest.stat().st_size / 1e6:.1f} MB")
        paths.append(dest)
        time.sleep(0.3)
    return paths


def encode_terrarium(heights: np.ndarray) -> np.ndarray:
    """高程（米）→ terrarium 三通道。范围外的填 0（= 海平面 -32768 无效值由调用方处理）。"""
    shifted = np.clip(heights, -32000, 32000) + 32768.0
    r = np.floor(shifted / 256.0)
    g = np.floor(shifted - r * 256.0)
    b = np.floor((shifted - np.floor(shifted)) * 256.0)
    rgb = np.stack([r, g, b], axis=-1).astype(np.uint8)
    return rgb


def main() -> int:
    parser = argparse.ArgumentParser(description="GeoTIFF 高程 → terrarium 瓦片，并组装 sources 目录")
    parser.add_argument("--bbox", required=True, help="west,south,east,north（WGS-84）")
    parser.add_argument("--zoom", type=int, default=17, help="输出 terrarium 瓦片层级（Web Mercator）")
    parser.add_argument("--stac-collection", default="ch.swisstopo.swissalti3d")
    parser.add_argument("--geotiff", type=Path, default=None, help="已有 GeoTIFF 目录（跳过 STAC 下载）")
    parser.add_argument("--prefer", default="0.5", help="优先的 GeoTIFF 分辨率关键字（0.5 / 2）")
    parser.add_argument("--imagery-dir", type=Path, default=None,
                        help="fetch_imagery.py 的产物目录（含 imagery/<z>/…）；不给则只做 DEM")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    west, south, east, north = (float(v) for v in args.bbox.split(","))
    out: Path = args.out
    dem_dir = out / "dem" / str(args.zoom)
    dem_dir.mkdir(parents=True, exist_ok=True)

    # ---------- 1) 高程 ----------
    if args.geotiff:
        tifs = sorted(args.geotiff.glob("*.tif"))
        log(f"使用本地 GeoTIFF {len(tifs)} 幅：{args.geotiff}")
    else:
        tifs = stac_download(args.stac_collection, (west, south, east, north),
                             out / "_tif", prefer=args.prefer)
    if not tifs:
        log("没有可用的 GeoTIFF，退出")
        return 1

    log("  合并 GeoTIFF 为一张镶嵌图 …")
    datasets = [rasterio.open(path) for path in tifs]
    mosaic, mosaic_transform = merge(datasets)
    src_crs = datasets[0].crs
    log(f"  镶嵌图 {mosaic.shape[2]}x{mosaic.shape[1]} 像素，源坐标系 {src_crs}")

    x0f, y0f = lonlat_to_px(west, north, args.zoom)
    x1f, y1f = lonlat_to_px(east, south, args.zoom)
    tx0, ty0, tx1, ty1 = int(x0f // TILE), int(y0f // TILE), int(x1f // TILE), int(y1f // TILE)
    total = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
    log(f"  目标 terrarium 瓦片 {tx1 - tx0 + 1} x {ty1 - ty0 + 1} = {total} 张（z={args.zoom}）")

    tiles = []
    started = time.time()
    done = 0
    for ty in range(ty0, ty1 + 1):
        for tx in range(tx0, tx1 + 1):
            w3857, s3857, e3857, n3857 = tile_bounds_3857(tx, ty, args.zoom)
            dst = np.full((TILE, TILE), np.nan, dtype=np.float32)
            dst_transform = rasterio.transform.from_bounds(w3857, s3857, e3857, n3857, TILE, TILE)
            reproject(
                source=mosaic[0],
                destination=dst,
                src_transform=mosaic_transform,
                src_crs=src_crs,
                dst_transform=dst_transform,
                dst_crs="EPSG:3857",
                resampling=Resampling.bilinear,
                src_nodata=None,
                dst_nodata=np.nan,
            )
            if np.isnan(dst).all():
                continue  # 范围外（或该图幅没有数据）
            fill = float(np.nanmin(dst))
            dst = np.where(np.isnan(dst), fill, dst)
            rgb = encode_terrarium(dst)
            rel = Path("dem") / str(args.zoom) / f"{tx}_{ty}.png"
            path = out / rel
            Image.fromarray(rgb).save(path)
            payload = path.read_bytes()
            tiles.append({"z": args.zoom, "x": tx, "y": ty,
                          "file": str(rel).replace("\\", "/"),
                          "bytes": len(payload),
                          "sha256": hashlib.sha256(payload).hexdigest()})
            done += 1
            if done % 20 == 0 or done == total:
                log(f"    进度 {done}/{total}  耗时 {time.time() - started:.0f}s")

    dem_manifest = {
        "dataset": f"{args.stac_collection}（GeoTIFF → terrarium 转换，脚本 geotiff_to_sources.py）",
        "license": "见数据源官方许可（swisstopo 为开放数据 OGD，需署名）",
        "encoding": "terrarium",
        "heightDecode": "h = R*256 + G + B/256 - 32768",
        "zoom": args.zoom,
        "urlTemplate": "local geotiff",
        "tileCount": len(tiles),
        "tiles": tiles,
    }
    log(f"  高程瓦片完成：{len(tiles)} 张")

    # ---------- 2) 影像 ----------
    sources = {"dem": dem_manifest}
    if args.imagery_dir:
        imagery_root = args.imagery_dir / "imagery"
        levels = sorted((p.name for p in imagery_root.iterdir() if p.is_dir()), key=int)
        zoom = int(levels[-1])
        src_level = imagery_root / str(zoom)
        dst_level = out / "imagery" / str(zoom)
        dst_level.mkdir(parents=True, exist_ok=True)
        imagery_tiles = []
        files = sorted(src_level.glob("*"))
        for src in files:
            dst = dst_level / src.name
            if not dst.exists():
                dst.write_bytes(src.read_bytes())
            payload = dst.read_bytes()
            tx, ty = src.stem.split("_")[:2]
            imagery_tiles.append({"z": zoom, "x": int(tx), "y": int(ty),
                                  "file": str(Path("imagery") / str(zoom) / src.name).replace("\\", "/"),
                                  "bytes": len(payload),
                                  "sha256": hashlib.sha256(payload).hexdigest()})
        imagery_manifest = {
            "dataset": "影像瓦片（外部来源，见 manifest 备注）",
            "license": "见来源说明",
            "zoom": zoom,
            "tileCount": len(imagery_tiles),
            "tiles": imagery_tiles,
        }
        src_manifest = args.imagery_dir / "manifest.json"
        if src_manifest.exists():
            meta = json.loads(src_manifest.read_text(encoding="utf-8"))
            imagery_manifest["dataset"] = meta.get("dataset", imagery_manifest["dataset"])
            imagery_manifest["license"] = meta.get("license", imagery_manifest["license"])
            imagery_manifest["sourceUrl"] = meta.get("source")
        sources["imagery"] = imagery_manifest
        log(f"  影像瓦片 {len(imagery_tiles)} 张（z={zoom}）已并入 sources")

    (out / "manifest.json").write_text(json.dumps({
        "generator": "tools/terrain_asset/geotiff_to_sources.py",
        "anchor": {"longitude": (west + east) / 2, "latitude": (south + north) / 2},
        "footprintMetres": {
            "width": (east - west) * 111320.0 * math.cos(math.radians((south + north) / 2)),
            "depth": (north - south) * 110574.0,
        },
        "bbox": {"west": west, "south": south, "east": east, "north": north},
        "sources": sources,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"完成 → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
