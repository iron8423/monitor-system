"""高清影像抓取（多源）→ 拼接 + 世界文件 + 清单。

与 tools/terrain_asset/fetch_sources.py 的分工：那个只抓 Sentinel-2（10m，够做地形纹理、
不够"像照片"）；这里补的是**高清影像**这条需求，供 QGIS 对位或更高等级资产的纹理源。

数据源与坐标系：
  gaode     高德卫星影像（GCJ-02，需做 GCJ-02→WGS-84 反算才能与我们的测点对齐）
  esri      Esri World Imagery（WGS-84/Web Mercator，大陆访问通常需要代理）
  eox       Sentinel-2 cloudless（10m，免费可离线，作为参照）
  tianditu  天地图影像（需 --key，官方渠道）

许可提醒：高德/Esri 的服务条款禁止离线缓存与二次分发，抓下来的图**只能做内部对位与参考**，
不得烘焙进交付资产；要进交付物请走天地图授权或购买影像。
"""

import argparse
import hashlib
import io
import json
import math
import sys
import time
import urllib.request
from pathlib import Path

from PIL import Image

TILE = 256
UA = "monitor-system imagery fetch (internal use)"

SOURCES = {
    "gaode": {
        "url": "https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}",
        "sub": ["1", "2", "3", "4"],
        "crs": "GCJ-02 (Web Mercator)",
        "dataset": "高德地图卫星影像",
        "license": "服务条款禁止离线缓存与二次分发；仅内部参考",
    },
    "esri": {
        "url": "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "sub": [""],
        "crs": "WGS-84 (Web Mercator)",
        "dataset": "Esri World Imagery",
        "license": "服务条款禁止离线缓存与二次分发；仅内部参考",
    },
    "google": {
        "url": "https://mt{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
        "sub": ["0", "1", "2", "3"],
        "crs": "WGS-84 (Web Mercator)",
        "dataset": "Google 卫星影像",
        "license": "服务条款禁止离线缓存与二次分发；仅内部参考",
    },
    "bing": {
        # Bing 用 quadkey 编号（不是 x/y/z），所以这里占位符是 {quadkey}；国内可直连，不需要 VPN。
        # 用的是 Cesium ion 里那套 Bing Aerial，目的是**让地块纹理和远景影像同源**，交界不留色差。
        "url": "https://ecn.t{s}.tiles.virtualearth.net/tiles/a{quadkey}.jpeg?g=1",
        "sub": ["0", "1", "2", "3"],
        "crs": "WGS-84 (Web Mercator)",
        "dataset": "Bing Maps Aerial",
        "license": "服务条款禁止离线缓存与二次分发；仅内部参考",
    },
    "swisstopo": {
        # 瑞士官方正射影像（SWISSIMAGE），**0.1 m 级**，标准 Web Mercator 瓦片路径。
        # 用途：做"高精度数据对照实验"——验证数据升级后画质能到什么程度（见
        # docs/开源高精度数据对照实验方案_20260923.md ）。国内实测可直连，不需要代理。
        "url": ("https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swissimage"
                "/default/current/3857/{z}/{x}/{y}.jpeg"),
        "sub": [""],
        "crs": "WGS-84 (Web Mercator)",
        "dataset": "swisstopo SWISSIMAGE（瑞士联邦官方正射影像）",
        "license": "swisstopo 开放数据（OGD），允许离线缓存与再分发，需署名 swisstopo",
    },
    "eox": {
        "url": ("https://tiles.maps.eox.at/wmts?layer=s2cloudless-2023_3857&style=default"
                "&tilematrixset=GoogleMapsCompatible&Service=WMTS&Request=GetTile"
                "&Version=1.0.0&Format=image%2Fjpeg&TileMatrix={z}&TileRow={y}&TileCol={x}"),
        "sub": [""],
        "crs": "WGS-84 (Web Mercator)",
        "dataset": "Sentinel-2 cloudless 2023 (EOX)",
        "license": "CC BY 4.0，允许离线缓存与再分发（需署名）",
    },
    "tianditu": {
        "url": ("https://t{s}.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile"
                "&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles"
                "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={key}"),
        "sub": ["0", "1", "2", "3", "4", "5", "6", "7"],
        "crs": "WGS-84 (Web Mercator)",
        "dataset": "天地图·影像",
        "license": "需申请 key；署名与商用条款见 tianditu.gov.cn",
    },
}


def log(message: str) -> None:
    print(message, flush=True)


def meters_per_degree(lat: float) -> tuple[float, float]:
    m_per_lat = 111132.92 - 559.82 * math.cos(2 * math.radians(lat)) + 1.175
    m_per_lon = 111412.84 * math.cos(math.radians(lat)) - 93.5 * math.cos(3 * math.radians(lat))
    return m_per_lon, m_per_lat


def lonlat_to_px(lon: float, lat: float, zoom: int) -> tuple[float, float]:
    scale = TILE * (2 ** zoom)
    x = (lon + 180.0) / 360.0 * scale
    y = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * scale
    return x, y


_A = 6378245.0
_EE = 0.00669342162296594323


def _out_of_china(lon: float, lat: float) -> bool:
    return not (73.66 < lon < 135.05 and 3.86 < lat < 53.55)


def _transform_lat(lon: float, lat: float) -> float:
    ret = -100.0 + 2.0 * lon + 3.0 * lat + 0.2 * lat * lat + 0.1 * lon * lat + 0.2 * math.sqrt(abs(lon))
    ret += (20.0 * math.sin(6.0 * lon * math.pi) + 20.0 * math.sin(2.0 * lon * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(lat * math.pi) + 40.0 * math.sin(lat / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (160.0 * math.sin(lat / 12.0 * math.pi) + 320 * math.sin(lat * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def _transform_lon(lon: float, lat: float) -> float:
    ret = 300.0 + lon + 2.0 * lat + 0.1 * lon * lon + 0.1 * lon * lat + 0.1 * math.sqrt(abs(lon))
    ret += (20.0 * math.sin(6.0 * lon * math.pi) + 20.0 * math.sin(2.0 * lon * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(lon * math.pi) + 40.0 * math.sin(lon / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (150.0 * math.sin(lon / 12.0 * math.pi) + 300.0 * math.sin(lon / 30.0 * math.pi)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lon: float, lat: float) -> tuple[float, float]:
    if _out_of_china(lon, lat):
        return lon, lat
    d_lat = _transform_lat(lon - 105.0, lat - 35.0)
    d_lon = _transform_lon(lon - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * math.pi
    magic = math.sin(rad_lat)
    magic = 1 - _EE * magic * magic
    sqrt_magic = math.sqrt(magic)
    d_lat = (d_lat * 180.0) / ((_A * (1 - _EE)) / (magic * sqrt_magic) * math.pi)
    d_lon = (d_lon * 180.0) / (_A / sqrt_magic * math.cos(rad_lat) * math.pi)
    return lon + d_lon, lat + d_lat


def gcj02_to_wgs84(lon: float, lat: float) -> tuple[float, float]:
    """正算迭代逼近（一次反算的残差是米级，必须迭代）。"""
    if _out_of_china(lon, lat):
        return lon, lat
    wlon, wlat = lon, lat
    for _ in range(6):
        glon, glat = wgs84_to_gcj02(wlon, wlat)
        wlon += lon - glon
        wlat += lat - glat
    return wlon, wlat


def download(url: str, dest: Path, attempts: int = 4) -> int:
    """带重试的下载（2026-09-21）。

    为什么必须重试：一次抓几百张瓦片时，源站会中途 reset 连接
    （实测 Google 33 秒、高德 78 秒就断），一次失败不该让整轮抓取白跑。
    退避 1.5s → 3s → 6s，仍失败才抛出。
    """
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
            if len(payload) < 100:
                raise RuntimeError(f"瓦片内容过小（{len(payload)}B），可能该层级没有影像：{url}")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(payload)
            # 每张之间稍作停顿，降低被限流的概率（抓几百张时不至于被掐）
            time.sleep(0.05)
            return len(payload)
        except Exception as error:  # noqa: BLE001 - 网络异常种类多，统一重试
            last_error = error
            if attempt < attempts - 1:
                time.sleep(1.5 * (2 ** attempt))
    raise RuntimeError(f"下载失败（已重试 {attempts} 次）：{last_error}")


def tile_is_usable(path: Path) -> bool:
    """已有瓦片是否可以直接复用（断点续抓）。

    为什么要有这个：源站抓几百张时会在中途 reset（实测 Google ~33s、高德 ~78s），
    一轮跑不完很正常；能续抓的脚本才不会被限流拖死。判据是"能解码 + 不是占位小文件"，
    避免上一次被 kill 时留下的半张瓦片被当成好数据。

    2026-09-23 追加一条：**纯色/空白瓦片不算可用**。
    源站在没有影像的层级会返回一张纯白占位图（swisstopo 的 z0~z5 实测就是 255,255,255），
    它能解码、体积也够，于是被当成好数据存了下来；前端渲染到那一层就是**一块白斑**，
    而且"断点续抓"会一直复用它、永远不会自己好。判据用"像素值是否几乎无起伏"。
    """
    try:
        if path.stat().st_size < 100:
            return False
        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            probe = image.convert("L").resize((16, 16))
            values = list(probe.getdata())
        if not values or max(values) - min(values) < 6:
            return False
        return True
    except Exception:  # noqa: BLE001 - 任何解码问题都当作"需要重下"
        return False


def tile_to_quadkey(tx: int, ty: int, zoom: int) -> str:
    """(x, y, z) → Bing quadkey（Bing 的唯一编号方式）。"""
    digits = []
    for level in range(zoom, 0, -1):
        digit = 0
        mask = 1 << (level - 1)
        if tx & mask:
            digit += 1
        if ty & mask:
            digit += 2
        digits.append(str(digit))
    return "".join(digits)


def main() -> int:
    parser = argparse.ArgumentParser(description="高清影像抓取（多源）")
    parser.add_argument("--source", choices=sorted(SOURCES), default="gaode")
    parser.add_argument("--anchor-lon", type=float, required=True)
    parser.add_argument("--anchor-lat", type=float, required=True)
    parser.add_argument("--width", type=float, default=1000.0, help="东西向米数")
    parser.add_argument("--depth", type=float, default=750.0, help="南北向米数")
    parser.add_argument("--zoom", type=int, default=18)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--key", default="", help="天地图 key")
    parser.add_argument("--overwrite", action="store_true",
                        help="忽略已下载的瓦片，全部重抓（默认续抓）")
    args = parser.parse_args()

    source = SOURCES[args.source]
    if args.source == "tianditu" and not args.key:
        log("天地图需要 --key（在 tianditu.gov.cn 注册申请）")
        return 2

    m_lon, m_lat = meters_per_degree(args.anchor_lat)
    half_w, half_d = args.width / 2.0, args.depth / 2.0
    west, east = args.anchor_lon - half_w / m_lon, args.anchor_lon + half_w / m_lon
    south, north = args.anchor_lat - half_d / m_lat, args.anchor_lat + half_d / m_lat
    log(f"场址 {args.anchor_lat:.5f}N {args.anchor_lon:.5f}E 范围 {args.width:.0f}x{args.depth:.0f}m "
        f"源={args.source}（{source['crs']}）z={args.zoom}")

    if args.source == "gaode":
        w2, s2 = wgs84_to_gcj02(west, south)
        e2, n2 = wgs84_to_gcj02(east, north)
        log(f"  GCJ-02 偏移：西 {w2 - west:+.6f} 南 {s2 - south:+.6f} 东 {e2 - east:+.6f} 北 {n2 - north:+.6f}")
    else:
        w2, s2, e2, n2 = west, south, east, north

    x0f, y0f = lonlat_to_px(w2, n2, args.zoom)
    x1f, y1f = lonlat_to_px(e2, s2, args.zoom)
    tx0, ty0, tx1, ty1 = int(x0f // TILE), int(y0f // TILE), int(x1f // TILE), int(y1f // TILE)

    suffix = ".jpg" if args.source in ("gaode", "eox", "bing", "swisstopo") else ".png"
    canvas = Image.new("RGB", (TILE * (tx1 - tx0 + 1), TILE * (ty1 - ty0 + 1)), (0, 0, 0))
    entries = []
    failures = []
    index = 0
    reused = 0
    total = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
    started = time.time()
    for ty in range(ty0, ty1 + 1):
        for tx in range(tx0, tx1 + 1):
            index += 1
            sub = source["sub"][index % len(source["sub"])]
            url = source["url"].format(
                s=sub, x=tx, y=ty, z=args.zoom, key=args.key,
                quadkey=tile_to_quadkey(tx, ty, args.zoom),
            )
            rel = Path("imagery") / str(args.zoom) / f"{tx}_{ty}{suffix}"
            dest = args.out / rel
            if not args.overwrite and tile_is_usable(dest):
                size = dest.stat().st_size
                reused += 1
            else:
                try:
                    size = download(url, dest)
                except RuntimeError as error:
                    failures.append((tx, ty, str(error)))
                    log(f"  ! 失败 x={tx} y={ty}：{error}")
                    continue
            canvas.paste(Image.open(dest).convert("RGB"), (TILE * (tx - tx0), TILE * (ty - ty0)))
            entries.append({"z": args.zoom, "x": tx, "y": ty, "file": str(rel).replace("\\", "/"),
                            "bytes": size, "sha256": hashlib.sha256(dest.read_bytes()).hexdigest()})
            if index % 25 == 0 or index == total:
                log(f"  进度 {index}/{total}（续用 {reused}，失败 {len(failures)}）"
                    f" 耗时 {time.time() - started:.0f}s")
    log(f"  瓦片 {len(entries)}/{total} 张（其中续用 {reused} 张）")

    if failures:
        log(f"  {len(failures)} 张没抓到，本次不生成拼接图。**直接重跑同一条命令即可续抓**：")
        for tx, ty, error in failures[:10]:
            log(f"    x={tx} y={ty} {error}")
        if len(failures) > 10:
            log(f"    …另外 {len(failures) - 10} 张")
        return 1

    crop = canvas.crop((round(x0f - tx0 * TILE), round(y0f - ty0 * TILE),
                        round(x1f - tx0 * TILE), round(y1f - ty0 * TILE)))
    args.out.mkdir(parents=True, exist_ok=True)
    crop.save(args.out / "stitched.png")

    m_per_px = 156543.03392 * math.cos(math.radians(args.anchor_lat)) / (2 ** args.zoom)
    (args.out / "stitched.pgw").write_text(
        f"{m_per_px}\n0.0\n0.0\n{-m_per_px}\n{west}\n{north}\n", encoding="utf-8")
    note = ("GCJ-02 影像按 WGS-84 请求并裁剪；对位仍有米级残差" if args.source == "gaode"
            else "WGS-84 / Web Mercator，可直接与我们的经纬度叠加")
    (args.out / "manifest.json").write_text(json.dumps({
        "generator": "tools/imagery_fetch/fetch_imagery.py",
        "source": args.source,
        "dataset": source["dataset"],
        "license": source["license"],
        "crs": "EPSG:3857 (Web Mercator)",
        "anchor": {"longitude": args.anchor_lon, "latitude": args.anchor_lat},
        "footprintMetres": {"width": args.width, "depth": args.depth},
        "bboxWgs84": {"west": west, "south": south, "east": east, "north": north},
        "zoom": args.zoom,
        "metresPerPixel": round(m_per_px, 4),
        "note": note,
        "tiles": entries,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"  拼接 {crop.width}x{crop.height}px，{m_per_px:.2f} m/px -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
