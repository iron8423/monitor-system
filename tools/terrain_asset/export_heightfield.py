#!/usr/bin/env python3
"""把离线地形资产的高程场导出成后端可读的网格资源（复查清单 P1-10）。

为什么要这一步：平台此前对「目标到底看不看得见」只有一个**存库的布尔**
（`device_point.line_of_sight`），值是离线生成器解算出来写进迁移的。于是
  · 新绑一个目标、改一次雷达位姿，平台自己没有任何校核能力；
  · 迁移里的 TRUE 来自哪一版资产、当时地形什么样，事后无从复核。
后端拿不到 DEM 瓦片（它只装自己那份 jar），所以这里把生成器解算出的**最终高程场**
原样导出：同一套锚点/尺寸/网格/细节参数重建，浮点原值写成 float32 网格。

关键约定（与 build_terrain_asset.py 逐字对齐，改一处必须改两处）：
  · 本地坐标：+X 东、+Y 北、+Z 上，原点在锚点；xs = linspace(-W/2, W/2, cellsX+1)；
  · `grid[j][i]` 是**相对基准面**的高度，绝对高程 = floor + grid，floor 也一并导出；
  · 经纬度 → 本地米用 metres_per_degree(anchor.latitude) 的常数（不是逐点重算），
    否则后端算出来的 x 会和 GLB 差出好几米。

导出前会**自校验**：用资产目录里的 points.json + scene-config.json 重算每条视线的净空，
与 scene-config 里记的值比对（生成器是同一份代码，不一致就是参数对错了）。

用法：

    python3 tools/terrain_asset/export_heightfield.py \
        --asset frontend/public/models/qingyuan-hillside-v2 \
        --sources generated/terrain-asset/sources-v2 \
        --out backend/src/main/resources/terrain
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

# 与生成器同一份实现：不复制粘贴算法，直接引用，杜绝"两份代码慢慢漂移"。
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_terrain_asset as bta  # noqa: E402

DETAIL_SEED = 20260918  # build_terrain_asset.py --detail-seed 的默认值


def log(message: str) -> None:
    print(message, flush=True)


def load_asset(asset_dir: Path) -> tuple[dict, dict, dict]:
    manifest = json.loads((asset_dir / "build-manifest.json").read_text(encoding="utf-8"))
    points = json.loads((asset_dir / "points.json").read_text(encoding="utf-8"))
    scene = json.loads((asset_dir / "scene-config.json").read_text(encoding="utf-8"))
    return manifest, points, scene


def verify_bindings(terrain, points: list[dict], scene: dict,
                    reflector_height: float) -> tuple[int, float]:
    """用导出的高程场重算每条视线净空，与资产里记录的值比对。

    返回（比对条数, 最大偏差）。偏差只允许来自浮点舍入——对不上说明这套参数
    重建的不是同一片地形，导出去的后端只会给出一个"看起来像"的错误答案。
    """
    by_id = {p["id"]: p for p in points}
    worst = 0.0
    count = 0
    for radar in scene.get("radars", []):
        head_x, head_y, head_rel = radar["localPosition"]
        head_z = terrain.floor + head_rel + radar.get("antennaHeightM", 0.0)
        for binding in radar.get("bindings", []):
            point = by_id.get(binding["pointId"])
            if point is None:
                continue
            px, py = point["local"][0], point["local"][1]
            target_z = point["altitude"] + reflector_height
            visible, clearance = bta.line_of_sight(
                terrain, (head_x, head_y, head_z), (px, py, target_z))
            stored = binding["minimumClearanceM"]
            delta = abs(clearance - stored)
            worst = max(worst, delta)
            count += 1
            log(f"  视线 {radar['code']} -> {binding['pointCode']}: "
                f"重算 {clearance:.3f}m / 记录 {stored:.3f}m "
                f"（{'通视' if visible else '被遮挡'}）")
            if delta > 0.01:
                raise SystemExit(
                    f"{binding['pointCode']} 净空对不上：重算 {clearance:.3f} vs 记录 {stored:.3f}。"
                    "高程场参数（sources 目录 / 网格 / 细节幅度或种子）与资产不一致。")
    return count, worst


def write_grid(out_dir: Path, asset_version: str, terrain) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    name = f"{asset_version}.bin"
    # float32 小端、行主序、i（x）最快：Java 侧 ByteBuffer LITTLE_ENDIAN.getFloat 直读。
    payload = np.asarray(terrain.heights, dtype="<f4").tobytes(order="C")
    (out_dir / name).write_bytes(payload)
    return {
        "file": name,
        "encoding": "float32-le",
        "layout": "row-major, i (x) fastest, j (y) slowest",
        "bytes": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
    }


def build_meta(*, asset_version: str, scene_dir: str, terrain, params: dict,
               grid: dict, verified: dict) -> dict:
    return {
        "assetVersion": asset_version,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "generator": "tools/terrain_asset/export_heightfield.py",
        "sceneDir": scene_dir,
        "anchor": terrain.anchor,
        "floor": terrain.floor,
        "width": float(terrain.width),
        "depth": float(terrain.depth),
        "cellsX": int(params["cells"][0]),
        "cellsY": int(params["cells"][1]),
        "mPerLon": terrain.m_per_lon,
        "mPerLat": terrain.m_per_lat,
        "detailAmplitude": params.get("detailAmplitude"),
        "detailSeed": DETAIL_SEED,
        "minRelativeHeight": float(terrain.heights.min()),
        "maxRelativeHeight": float(terrain.heights.max()),
        "grid": grid,
        "verified": verified,
    }


def export_one(asset_dir: Path, sources: Path, out_dir: Path) -> dict:
    manifest, points, scene = load_asset(asset_dir)
    params = manifest["parameters"]
    asset_version = manifest["assetVersion"]
    source_manifest = json.loads((sources / "manifest.json").read_text(encoding="utf-8"))
    anchor = params["anchor"]
    if (abs(anchor["longitude"] - scene["anchor"]["longitude"]) > 1e-9
            or abs(anchor["latitude"] - scene["anchor"]["latitude"]) > 1e-9):
        raise SystemExit(f"{asset_dir}: build-manifest 与 scene-config 的锚点不一致")

    log(f"重建高程场：{asset_version}（{asset_dir}）")
    dem = bta.load_dem_mosaic(sources, source_manifest)
    terrain = bta.build_heightfield(
        dem, anchor, params["width"], params["depth"],
        int(params["cells"][0]), int(params["cells"][1]),
        params["detailAmplitude"], DETAIL_SEED)
    if abs(terrain.floor - scene["anchor"]["height"]) > 1e-6:
        raise SystemExit(
            f"{asset_dir}: 基准面 {terrain.floor} 与 scene-config 的 anchor.height "
            f"{scene['anchor']['height']} 不一致")
    log(f"  基准面 {terrain.floor:.1f}m，相对高程 "
        f"{terrain.heights.min():.3f}–{terrain.heights.max():.3f}m，"
        f"网格 {terrain.heights.shape[1]}×{terrain.heights.shape[0]}")

    reflector = min((b.get("reflectorHeightM") for r in scene.get("radars", [])
                     for b in r.get("bindings", []) if b.get("reflectorHeightM")), default=None)
    reflector = bta.DEFAULT_REFLECTOR_HEIGHT_M if reflector is None else reflector
    count, worst = verify_bindings(terrain, points, scene, reflector)
    if count == 0:
        raise SystemExit(f"{asset_dir}: 没有可比对的视线，拒绝导出（无法自校验）")

    grid = write_grid(out_dir, asset_version, terrain)
    meta = build_meta(asset_version=asset_version,
                      scene_dir=str(asset_dir).replace("\\", "/"),
                      terrain=terrain, params=params, grid=grid,
                      verified={"bindings": count, "maxClearanceDeltaM": round(worst, 6),
                                "reflectorHeightM": reflector})
    (out_dir / f"{asset_version}.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log(f"  已写出 {grid['file']}（{grid['bytes'] / 1024:.0f} KiB）"
        f" + {asset_version}.json，最大偏差 {worst:.6f}m")
    return meta


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="导出后端通视计算用的高程场网格")
    parser.add_argument("--asset", type=Path, action="append", required=True,
                        help="资产目录（frontend/public/models/xxx），可重复")
    parser.add_argument("--sources", type=Path, action="append", required=True,
                        help="瓦片素材目录（generated/terrain-asset/sources-xxx），与 --asset 一一对应")
    parser.add_argument("--out", type=Path,
                        default=Path("backend/src/main/resources/terrain"))
    args = parser.parse_args(argv)
    if len(args.asset) != len(args.sources):
        parser.error("--asset 与 --sources 数量必须一致、且按顺序一一对应")

    for asset_dir, sources in zip(args.asset, args.sources):
        meta = export_one(asset_dir, sources, args.out)
        log(f"完成 {meta['assetVersion']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
