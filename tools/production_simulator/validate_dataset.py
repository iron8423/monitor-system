#!/usr/bin/env python3
"""离线校验综合数据集：文件哈希、消息数量、标定范围与 LOS。"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def angle_delta(angle: float, centre: float) -> float:
    return abs((angle - centre + 540.0) % 360.0 - 180.0)


def main() -> int:
    parser = argparse.ArgumentParser(description="校验生产规模模拟数据集")
    parser.add_argument("dataset", nargs="?", default="generated/production-baseline-20260916")
    args = parser.parse_args()
    root = Path(args.dataset)
    manifest = load(root / "manifest.json")
    for name, expected in manifest["files"].items():
        data = (root / name).read_bytes()
        actual_hash = hashlib.sha256(data).hexdigest()
        if len(data) != expected["bytes"] or actual_hash != expected["sha256"]:
            raise SystemExit(f"文件校验失败: {name}")

    catalog = load(root / "catalog.json")
    devices = {index + 1: item for index, item in enumerate(catalog["devices"])}
    if len(devices) != 10 or len(catalog["points"]) != 1000:
        raise SystemExit("档案规模不等于 10 台雷达 / 1000 个点")
    for point in catalog["points"]:
        device = devices[point["deviceNo"]]
        if not point["lineOfSight"] or point["calibrationStatus"] != "ACTIVE":
            raise SystemExit(f"标定未激活或 LOS 失败: {point['code']}")
        if point["minimumClearanceM"] <= 0.25:
            raise SystemExit(f"地形净空不足: {point['code']}")
        if point["slantRangeM"] > device["detectionRangeM"]:
            raise SystemExit(f"超出量程: {point['code']}")
        if angle_delta(point["azimuthDegrees"], device["headingDegrees"]) > device["halfAngleDegrees"]:
            raise SystemExit(f"超出水平视场: {point['code']}")
        if abs(point["elevationDegrees"] - device["pitchDegrees"]) > device["verticalHalfAngleDegrees"]:
            raise SystemExit(f"超出垂直视场: {point['code']}")

    messages = 0
    with gzip.open(root / "messages.ndjson.gz", "rt", encoding="utf-8") as source:
        for line in source:
            json.loads(line)
            messages += 1
    truth = load(root / "ground-truth.json")
    if messages != truth["scale"]["messagesIncludingDuplicates"]:
        raise SystemExit("消息数量与 ground-truth 不一致")

    print(
        f"OK: devices={len(devices)} points={len(catalog['points'])} messages={messages} "
        f"minClearance={min(p['minimumClearanceM'] for p in catalog['points']):.3f}m"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
