"""抓"远景层"瓦片金字塔（默认 Google 影像）→ 前端 public/farfield/，随应用离线发布。

为什么要单独做这个：地块之外的远景如果用在线底图（ion/Bing），一来色调和地块对不上
（实测同一场址 Bing 饱和度是 Google 的两倍多、还偏黄），二来演示现场不一定能联网。
这里按**嵌套金字塔**抓：低层级覆盖大范围（几十公里），高层级只覆盖场址周边，
瓦片数可控（约 250 张），运行时由 Cesium 的 UrlTemplateImageryProvider 按需取层。

用法（需要能访问 Google 的网络，例如本地代理）：
    $env:HTTPS_PROXY='http://127.0.0.1:7897'
    python tools/imagery_fetch/fetch_farfield.py \
        --anchor-lon 113.541523 --anchor-lat 24.4162209 \
        --out frontend/public/farfield
"""

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from fetch_imagery import (  # noqa: E402
    SOURCES,
    TILE,
    download,
    lonlat_to_px,
    meters_per_degree,
    tile_is_usable,
)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

# 每层：(缩放级别, 覆盖东西向米数, 覆盖南北向米数) —— 由内到外嵌套。
# 末尾那几层（z6~z11）只为"补齐祖先瓦片"：Cesium 在远视角要往上找可用层，
# 缺一层就 404 一次；这一段的瓦片数很少（每层 1~40 张），但能把控制台刷屏消掉。
#
# 2026-09-23 把外圈从 z0 收到 **z6**：
#   源站（swisstopo）在 z0~z5 没有影像，返回纯白占位图；那几张"白瓦片"铺满整个
#   72 km 矩形，一旦镜头拉到能看见它们，远景就是**一块白斑**（比糊更糟）。
#   z0~z5 的瓦片总数只有 7 张，但覆盖范围极大，收益为零、风险全在前端画面上。
#   Cesium 侧配合把 manifest 的 minimumLevel 一起抬到 6，就不会再请求它们。
DEFAULT_LEVELS = [
    (15, 9000, 6750),
    (14, 18000, 13500),
    (13, 36000, 27000),
    (12, 72000, 54000),
] + [(zoom, 72000, 54000) for zoom in range(11, 5, -1)]


def main() -> int:
    parser = argparse.ArgumentParser(description="抓远景层瓦片金字塔")
    parser.add_argument("--source", default="google", choices=sorted(SOURCES))
    parser.add_argument("--anchor-lon", type=float, required=True)
    parser.add_argument("--anchor-lat", type=float, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--levels", default="",
                        help="自定义层，如 '15:9000:6750,13:36000:27000'；默认由内到外四层")
    parser.add_argument("--overwrite", action="store_true", help="重抓（默认续抓）")
    args = parser.parse_args()

    source = SOURCES[args.source]
    levels = DEFAULT_LEVELS
    if args.levels:
        levels = []
        for chunk in args.levels.split(","):
            zoom, width, depth = chunk.split(":")
            levels.append((int(zoom), float(width), float(depth)))
    levels.sort(key=lambda item: -item[0])

    m_lon, m_lat = meters_per_degree(args.anchor_lat)
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)

    # 最外层决定 provider 的矩形范围
    outer_zoom, outer_w, outer_d = levels[-1]
    west = args.anchor_lon - outer_w / 2 / m_lon
    east = args.anchor_lon + outer_w / 2 / m_lon
    north = args.anchor_lat + outer_d / 2 / m_lat
    south = args.anchor_lat - outer_d / 2 / m_lat

    print(f"场址 {args.anchor_lat:.6f}N {args.anchor_lon:.6f}E  源={args.source}")
    print(f"外层 z{outer_zoom} 覆盖 {outer_w / 1000:.0f}x{outer_d / 1000:.0f} km，"
          f"bbox {west:.6f},{south:.6f} ~ {east:.6f},{north:.6f}")

    total_tiles = 0
    reused = 0
    failures = []
    started = time.time()
    level_summary = []

    for zoom, width, depth in levels:
        x0, y0 = lonlat_to_px(args.anchor_lon - width / 2 / m_lon,
                              args.anchor_lat + depth / 2 / m_lat, zoom)
        x1, y1 = lonlat_to_px(args.anchor_lon + width / 2 / m_lon,
                              args.anchor_lat - depth / 2 / m_lat, zoom)
        tx0, ty0, tx1, ty1 = int(x0 // TILE), int(y0 // TILE), int(x1 // TILE), int(y1 // TILE)
        count = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
        print(f"  z{zoom}: {tx1 - tx0 + 1} x {ty1 - ty0 + 1} = {count} 张")
        done = 0
        for ty in range(ty0, ty1 + 1):
            for tx in range(tx0, tx1 + 1):
                dest = out / str(zoom) / f"{tx}_{ty}.jpg"
                done += 1
                if not args.overwrite and tile_is_usable(dest):
                    reused += 1
                    continue
                url = source["url"].format(s=source["sub"][done % len(source["sub"])],
                                           x=tx, y=ty, z=zoom, key="", quadkey="")
                try:
                    download(url, dest)
                except RuntimeError as error:
                    failures.append((zoom, tx, ty, str(error)))
                    print(f"    ! 失败 z{zoom} x={tx} y={ty}: {error}")
                total_tiles += 1
                if done % 25 == 0 or done == count:
                    print(f"    进度 {done}/{count}（新抓 {total_tiles}，续用 {reused}，"
                          f"失败 {len(failures)}）耗时 {time.time() - started:.0f}s")
        level_summary.append({"zoom": zoom, "widthM": width, "depthM": depth, "tiles": count})

    (out / "manifest.json").write_text(json.dumps({
        "generator": "tools/imagery_fetch/fetch_farfield.py",
        "source": args.source,
        "dataset": source["dataset"],
        "license": source["license"],
        "anchor": {"longitude": args.anchor_lon, "latitude": args.anchor_lat},
        # 注意：盘上是 `{z}/{x}_{y}.jpg`。Cesium 默认模板是 `{z}/{x}/{y}.jpg`，
        # 两者不一致会全 404（页面看上去"还行"，其实是一直在用底下的兜底图层）。
        "urlTemplate": "/farfield/{z}/{x}_{y}.jpg",
        # 换场址/换数据源时**必须换 URL**（2026-09-23 加）。
        # 瓦片路径 `{z}/{x}_{y}.jpg` 在不同场址下会**撞名**（z0 的 0_0、以及相邻级别的同一格），
        # 而浏览器是按 URL 缓存的：换了内容但 URL 没变，用户那边就一直看到旧场址的影像
        # （实测症状：清远的绿色底图叠在瑞士场景外面，"周围的场景加载不出来"）。
        # 前端会把它拼成 `?v=...`，所以只要这里变了，缓存自动失效。
        "urlVersion": f"{args.source}-{args.anchor_lat:.4f}-{args.anchor_lon:.4f}",
        "minimumLevel": min(item[0] for item in levels),
        "maximumLevel": max(item[0] for item in levels),
        "rectangle": {"west": west, "south": south, "east": east, "north": north},
        "levels": level_summary,
        "credit": f"{source['dataset']}（内部预览；服务条款禁止再分发）",
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"新抓 {total_tiles} 张，续用 {reused} 张，失败 {len(failures)} 张，"
          f"耗时 {time.time() - started:.0f}s → {out}")
    if failures:
        print("有失败：重跑同一条命令即可续抓。")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
