#!/usr/bin/env python3
"""抓取离线地形资产所需的公开数据源：DEM 高程 + 卫星正射影像。

这是「真实地形资产」流水线的第一步，产物给 `build_terrain_asset.py` 用：

  DEM   —— Mapzen/AWS 公开地形瓦片（terrarium 编码 PNG，SRTM 派生，30m 级）。
           选它而不是直接下 SRTM `.hgt.gz` 的原因很实在：整幅 1°×1° 的 SRTM 瓦片
           压缩后仍有 20MB 以上，实测本机下载速度只有十几 KB/s；同样覆盖范围用
           terrarium 分块只要几百 KB，而且 PNG 可以直接用 Pillow 解码，不需要 GDAL。
           代价是最高只有 z=15（z=16 返回 404），但其底图本来就是 30m 采样，
           z=15 的 4.4m/px 已经把源数据过采样了——没有丢信息。

  影像  —— EOX「Sentinel-2 cloudless」（CC BY 4.0，可离线缓存与再分发，注明出处）。
           10m 分辨率的真彩色，覆盖全球、无云拼接。对 320m 级的演示场景，
           10m 只够做色彩底，细节由生成器按地形程序化补充（见 build 脚本的说明）。
           选它而不是 Google/Esri 瓦片：那两家的服务条款禁止离线缓存与二次分发，
           内网部署要用就得买授权；Sentinel-2 是唯一「免费 + 允许离线」的高频选择。

所有瓦片按 URL 去重缓存，重复执行不会重复下载；`manifest.json` 记录每个文件的
SHA-256、数据源、许可与获取时间，供资产生成与交付材料引用。

用法：

    python3 tools/terrain_asset/fetch_sources.py \
        --anchor-lon 113.05133 --anchor-lat 23.75946 \
        --width 320 --depth 240 \
        --out generated/terrain-asset/sources
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# ---------------------------------------------------------------- 数据源常量

DEM_ZOOM = 15
DEM_URL_TEMPLATE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
DEM_DATASET = "Mapzen/AWS terrain tiles（terrarium 编码，SRTM 派生）"
DEM_LICENSE = (
    "高程数据 SRTM：NASA/USGS 公有领域；瓦片打包：Mapzen Terrain Tiles（CC BY 4.0）。"
    "引用：https://registry.opendata.aws/terrain-tiles/"
)

IMAGERY_ZOOM = 16
IMAGERY_LAYER = "s2cloudless-2023_3857"
IMAGERY_URL_TEMPLATE = (
    "https://tiles.maps.eox.at/wmts?layer={layer}&style=default"
    "&tilematrixset=GoogleMapsCompatible&Service=WMTS&Request=GetTile"
    "&Version=1.0.0&Format=image%2Fjpeg&TileMatrix={z}&TileRow={y}&TileCol={x}"
)
IMAGERY_DATASET = "Sentinel-2 cloudless 2023（EOX IT Services GmbH）"
IMAGERY_LICENSE = (
    "CC BY 4.0。引用：Sentinel-2 cloudless – https://s2maps.eu by EOX IT Services GmbH "
    "(Contains modified Copernicus Sentinel data 2023)。允许离线缓存与再分发，需保留署名。"
)

USER_AGENT = "monitor-system-terrain-asset/1.0 (offline dev asset pipeline)"
RETRIES = 3
RETRY_WAIT_SECONDS = 2.0


def log(message: str) -> None:
    print(message, flush=True)


# ---------------------------------------------------------------- 投影换算


def lonlat_to_mercator(lon: float, lat: float) -> tuple[float, float]:
    """WGS84 经纬度 -> 归一化 Web Mercator（x、y 均落在 [0,1]）。"""
    x = (lon + 180.0) / 360.0
    sin_lat = math.sin(math.radians(lat))
    y = 0.5 - math.log((1.0 + sin_lat) / (1.0 - sin_lat)) / (4.0 * math.pi)
    return x, y


def mercator_to_lonlat(x: float, y: float) -> tuple[float, float]:
    lon = x * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * y))))
    return lon, lat


def metres_per_degree(lat: float) -> tuple[float, float]:
    """该纬度处 1° 经度 / 1° 纬度对应的地面米数（WGS84 一阶近似，足够 300m 场景）。"""
    a = 6378137.0
    e2 = 6.6943799901413165e-3
    sin_lat = math.sin(math.radians(lat))
    n = a / math.sqrt(1.0 - e2 * sin_lat * sin_lat)          # 卯酉圈曲率半径
    m = a * (1.0 - e2) / (1.0 - e2 * sin_lat * sin_lat) ** 1.5  # 子午圈曲率半径
    return math.pi / 180.0 * n * math.cos(math.radians(lat)), math.pi / 180.0 * m


def footprint_bbox(anchor_lon: float, anchor_lat: float, width: float, depth: float,
                   margin_m: float) -> tuple[float, float, float, float]:
    """以锚点为中心、含外扩边距的经纬度包围盒（west, south, east, north）。"""
    m_per_lon, m_per_lat = metres_per_degree(anchor_lat)
    half_w = width / 2.0 + margin_m
    half_d = depth / 2.0 + margin_m
    return (
        anchor_lon - half_w / m_per_lon,
        anchor_lat - half_d / m_per_lat,
        anchor_lon + half_w / m_per_lon,
        anchor_lat + half_d / m_per_lat,
    )


def tiles_for_bbox(bbox: tuple[float, float, float, float], zoom: int) -> list[tuple[int, int]]:
    west, south, east, north = bbox
    x0f, y0f = lonlat_to_mercator(west, north)
    x1f, y1f = lonlat_to_mercator(east, south)
    n = 2 ** zoom
    x0, x1 = math.floor(x0f * n), math.floor(x1f * n)
    y0, y1 = math.floor(y0f * n), math.floor(y1f * n)
    x0 = max(0, x0)
    y0 = max(0, y0)
    x1 = min(n - 1, x1)
    y1 = min(n - 1, y1)
    return [(x, y) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)]


# ---------------------------------------------------------------- 下载


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, dest: Path) -> bool:
    """下载到 dest（已存在且非空则跳过）。返回是否真的发生了下载。"""
    if dest.exists() and dest.stat().st_size > 0:
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for attempt in range(1, RETRIES + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
            if not payload:
                raise RuntimeError("响应为空")
            tmp = dest.with_suffix(dest.suffix + ".part")
            tmp.write_bytes(payload)
            tmp.replace(dest)
            return True
        except (urllib.error.URLError, urllib.error.HTTPError, RuntimeError, TimeoutError) as error:
            last_error = error
            if attempt < RETRIES:
                time.sleep(RETRY_WAIT_SECONDS * attempt)
    raise RuntimeError(f"下载失败 {url}: {last_error}")


def collect_source(kind: str, url_template: str, zoom: int, tiles: list[tuple[int, int]],
                   root: Path, extension: str, extra: dict) -> dict:
    entries = []
    downloaded = 0
    for index, (x, y) in enumerate(tiles, start=1):
        url = url_template.format(z=zoom, x=x, y=y, layer=IMAGERY_LAYER)
        rel = Path(kind) / str(zoom) / f"{x}_{y}{extension}"
        dest = root / rel
        if download(url, dest):
            downloaded += 1
        entries.append({
            "z": zoom, "x": x, "y": y,
            "file": rel.as_posix(),
            "bytes": dest.stat().st_size,
            "sha256": sha256_of(dest),
        })
        log(f"  [{kind}] {index}/{len(tiles)} z={zoom} x={x} y={y} "
            f"{dest.stat().st_size:,}B")
    log(f"[{kind}] 瓦片 {len(tiles)} 张（新下载 {downloaded} 张，其余命中缓存）")
    return {**extra, "zoom": zoom, "urlTemplate": url_template, "tileCount": len(tiles),
            "tiles": entries}


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取离线地形资产的公开数据源")
    parser.add_argument("--anchor-lon", type=float, default=113.05133)
    parser.add_argument("--anchor-lat", type=float, default=23.75946)
    parser.add_argument("--width", type=float, default=320.0, help="场景东西向米数")
    parser.add_argument("--depth", type=float, default=240.0, help="场景南北向米数")
    parser.add_argument("--margin", type=float, default=180.0,
                        help="瓦片外扩边距（米）：给影像重采样与地形细节留余量")
    parser.add_argument("--out", type=Path,
                        default=Path("generated/terrain-asset/sources"))
    parser.add_argument("--imagery-zoom", type=int, default=IMAGERY_ZOOM)
    parser.add_argument("--skip-dem", action="store_true")
    parser.add_argument("--skip-imagery", action="store_true")
    args = parser.parse_args()

    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    bbox = footprint_bbox(args.anchor_lon, args.anchor_lat, args.width, args.depth, args.margin)
    log(f"锚点 {args.anchor_lat:.5f}N {args.anchor_lon:.5f}E，"
        f"场景 {args.width:.0f}×{args.depth:.0f}m，外扩 {args.margin:.0f}m")
    log(f"包围盒 west={bbox[0]:.6f} south={bbox[1]:.6f} east={bbox[2]:.6f} north={bbox[3]:.6f}")

    manifest: dict = {
        "generator": "tools/terrain_asset/fetch_sources.py",
        "fetchedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "anchor": {"longitude": args.anchor_lon, "latitude": args.anchor_lat},
        "footprintMetres": {"width": args.width, "depth": args.depth},
        "marginMetres": args.margin,
        "bbox": {"west": bbox[0], "south": bbox[1], "east": bbox[2], "north": bbox[3]},
        "sources": {},
    }

    if not args.skip_dem:
        dem_tiles = tiles_for_bbox(bbox, DEM_ZOOM)
        manifest["sources"]["dem"] = collect_source(
            "dem", DEM_URL_TEMPLATE, DEM_ZOOM, dem_tiles, out, ".png",
            {"dataset": DEM_DATASET, "license": DEM_LICENSE, "encoding": "terrarium",
             "heightDecode": "h = R*256 + G + B/256 - 32768"},
        )
    if not args.skip_imagery:
        imagery_tiles = tiles_for_bbox(bbox, args.imagery_zoom)
        manifest["sources"]["imagery"] = collect_source(
            "imagery", IMAGERY_URL_TEMPLATE, args.imagery_zoom, imagery_tiles, out, ".jpg",
            {"dataset": IMAGERY_DATASET, "license": IMAGERY_LICENSE,
             "layer": IMAGERY_LAYER,
             "attribution": "Sentinel-2 cloudless by EOX IT Services GmbH (modified Copernicus Sentinel data 2023)"},
        )

    manifest_path = out / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
    total = sum(entry["bytes"] for source in manifest["sources"].values()
                for entry in source["tiles"])
    log(f"清单写入 {manifest_path}（{total / 1024 / 1024:.2f} MiB）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
