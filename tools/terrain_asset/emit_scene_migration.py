#!/usr/bin/env python3
"""从已生成的场景资产产出「新建项目」的 Flyway 迁移 SQL（草稿）。

多场景是按**项目**组织的：一个项目 = 一处场址 = 一份数字孪生资产 = 自己的一批
测点/设备/标定。大屏的项目下拉框切的就是它。本脚本把
`SITES` 里带 `db` 配置的场景（bridge / railway / factory）读一遍资产生成物，
输出可直接评审后拷进 `db/migration` 的 INSERT 语句。

用法：

    python3 tools/terrain_asset/emit_scene_migration.py \
        --assets-root frontend/public/models \
        --out generated/terrain-asset/scene-migration-V20.sql
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

HEADER = """-- =====================================================================
-- V20 三个新演示场景：野外桥梁 / 山区铁路 / 郊外工厂
--
-- 多场景按**项目**组织：一个项目 = 一处场址 = 一份数字孪生资产 = 自己的一批
-- 测点/设备/标定。大屏顶栏的项目下拉框切的就是它（前端零改动）。
--
-- 每个场景的资产 = 公开 DEM + Sentinel-2 影像烘焙的地形（与 project 1 同一条流水线），
-- 加上 features.py 程序化生成的主体结构：桥梁（桥面/桥墩/护栏/灯柱）、
-- 铁路（路基/道砟/钢轨/轨枕/接触网支柱）、工厂（地坪/厂房/储罐/烟囱/围墙）。
-- 结构体是**示意级**（不是设计图），30m DEM 也给不出桥面与钢轨——这一点写在
-- 各资产目录的 ASSET_PROVENANCE.md 里。
--
-- 本文件由 tools/terrain_asset/emit_scene_migration.py 从资产产物生成，
-- 数值（坐标、高程、方位/俯仰/斜距/净空、资产 SHA-256）全部来自生成器解算，不是手填。
-- 迁移不可变：改场景要新开版本号，不要改这一条。
--
-- 成员关系不在这里写：演示账号由 DataInitializer 在启动时创建、Flyway 跑得更早，
-- 悬空 user_id 会失败——见 ProjectMemberInitializer（已把四个演示账号加进这三个项目）。
-- =====================================================================

"""


def load_sites() -> dict:
    """复用生成器里的 SITES 定义（唯一数据源，避免 id/坐标抄两份）。"""
    spec = importlib.util.spec_from_file_location(
        "terrain_builder", Path(__file__).resolve().parent / "build_terrain_asset.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules["terrain_builder"] = module
    spec.loader.exec_module(module)
    return module.SITES


def q(value: str) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def emit(sites: dict, assets_root: Path) -> str:
    lines: list[str] = []
    for scene_key, site in sites.items():
        db = site.get("db")
        if not db:
            continue
        asset_dir = assets_root / site["asset_prefix"]
        points = json.loads((asset_dir / "points.json").read_text(encoding="utf-8"))
        coverage = json.loads((asset_dir / "coverage.json").read_text(encoding="utf-8"))
        build = json.loads((asset_dir / "build-manifest.json").read_text(encoding="utf-8"))
        config = json.loads((asset_dir / "scene-config.json").read_text(encoding="utf-8"))
        radar = coverage["radars"][0]
        bindings = coverage["bindings"]
        asset_url = f"/models/{site['asset_prefix']}/{site['asset_prefix']}.glb"

        lines.append(f"-- ---------- {site['title']}（project {db['project_id']}） ----------")
        lines.append(
            "INSERT INTO project (id, organization_id, name, code, location, description, "
            "created_at, updated_at) VALUES ({pid}, 1, {name}, {code}, {loc}, {desc}, "
            "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);".format(
                pid=db["project_id"], name=q(db["project_name"]), code=q(db["project_code"]),
                loc=q(db["location"]), desc=q(db["description"])))
        lines.append(
            "INSERT INTO scene (id, project_id, name, type, description, created_at, updated_at) "
            "VALUES ({sid}, {pid}, {name}, {typ}, {desc}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);".format(
                sid=db["scene_id"], pid=db["project_id"], name=q(site["scene_name"]),
                typ=q(db["scene_type"]), desc=q(site["title"])))
        lines.append(
            "INSERT INTO monitor_object (id, scene_id, name, type, description, created_at, "
            "updated_at) VALUES ({oid}, {sid}, {name}, {typ}, {desc}, CURRENT_TIMESTAMP, "
            "CURRENT_TIMESTAMP);".format(
                oid=db["object_id"], sid=db["scene_id"], name=q(site["title"]),
                typ=q(db["object_type"]), desc=q("程序化结构体 + 真实地形")))
        lines.append("INSERT INTO monitor_point (id, object_id, code, name, type, longitude, "
                     "latitude, altitude, enabled, created_at, updated_at) VALUES")
        lines.append(",\n".join(
            "({pid}, {oid}, {code}, {name}, 'DEFORMATION', {lon:.7f}, {lat:.7f}, {alt:.3f}, "
            "TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)".format(
                pid=p["id"], oid=db["object_id"], code=q(p["code"]), name=q(p["name"]),
                lon=p["longitude"], lat=p["latitude"], alt=p["altitude"])
            for p in points) + ";")
        metric_rows = []
        for index, point in enumerate(points):
            base = db["metric_id_start"] + index * 2
            metric_rows.append(
                "({mid}, {pid}, 'defo_mm', '累计形变', 'mm', 0, CURRENT_TIMESTAMP, "
                "CURRENT_TIMESTAMP)".format(mid=base, pid=point["id"]))
            metric_rows.append(
                "({mid}, {pid}, 'rate_mm_d', '形变速率', 'mm/d', 1, CURRENT_TIMESTAMP, "
                "CURRENT_TIMESTAMP)".format(mid=base + 1, pid=point["id"]))
        lines.append("INSERT INTO metric (id, point_id, code, name, unit, sort_order, "
                     "created_at, updated_at) VALUES")
        lines.append(",\n".join(metric_rows) + ";")
        lines.append(
            "INSERT INTO device (id, code, name, type, serial_no, longitude, latitude, altitude, "
            "heading_degrees, pitch_degrees, detection_range_m, half_angle_degrees, "
            "antenna_height_m, vertical_half_angle_degrees, status, battery, last_report_time, "
            "created_at, updated_at) VALUES "
            "({did}, {code}, {name}, 'MILLIMETER_WAVE_RADAR', {serial}, {lon:.7f}, {lat:.7f}, "
            "{alt:.3f}, {heading:.3f}, {pitch:.3f}, {rng:.3f}, {half:.3f}, {ant:.3f}, "
            "{vhalf:.3f}, 'ONLINE', 100.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
            "CURRENT_TIMESTAMP);".format(
                did=db.get("device_id", radar["id"]), code=q(radar["code"]),
                name=q(radar["name"]), serial=q(db["device_serial"]),
                lon=radar["longitude"], lat=radar["latitude"],
                alt=radar["absoluteAltitude"] if "absoluteAltitude" in radar
                else radar["localPosition"][2] + config["anchor"]["height"],
                heading=radar["headingDegrees"], pitch=radar["pitchDegrees"],
                rng=radar["range"], half=radar["halfAngleDegrees"],
                ant=radar["antennaHeightM"], vhalf=radar["verticalHalfAngleDegrees"]))
        rows = []
        for index, binding in enumerate(bindings):
            rows.append(
                "({bid}, {did}, {pid}, {target}, {az:.3f}, {el:.3f}, {slant:.3f}, 2.500, "
                "{los}, {clear:.3f}, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                "{note})".format(
                    bid=db["binding_id_start"] + index, did=db.get("device_id", radar["id"]),
                    pid=binding["pointId"], target=q(binding["targetCode"]),
                    az=binding["azimuthDegrees"], el=binding["elevationDegrees"],
                    slant=binding["slantRangeM"],
                    los="TRUE" if binding["lineOfSight"] else "FALSE",
                    clear=binding["minimumClearanceM"], note=q("程序化结构体视线校验通过")))
        lines.append("INSERT INTO device_point (id, device_id, point_id, target_code, "
                     "azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m, "
                     "line_of_sight, minimum_clearance_m, calibration_status, calibrated_at, "
                     "valid_from, calibration_note) VALUES")
        lines.append(",\n".join(rows) + ";")
        lines.append(
            "INSERT INTO digital_twin_scene (id, project_id, enabled, asset_type, "
            "coordinate_mode, asset_url, asset_version, asset_sha256, anchor_longitude, "
            "anchor_latitude, anchor_height, heading_degrees, pitch_degrees, roll_degrees, "
            "model_scale, camera_heading_degrees, camera_pitch_degrees, camera_range, "
            "maximum_screen_error, maximum_memory_mb, max_heat_points, label_distance, "
            "created_at, updated_at) VALUES "
            "({tid}, {pid}, TRUE, 'GLB', 'ENU', {url}, {ver}, {sha}, {lon:.7f}, {lat:.7f}, "
            "{height:.3f}, 0, 0, 0, 1, {ch:.3f}, {cp:.3f}, {cr:.3f}, 16, 512, 200, 2000, "
            "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);".format(
                tid=db["twin_id"], pid=db["project_id"], url=q(asset_url),
                ver=q(site["version"]), sha=q(build["glb"]["sha256"]),
                lon=config["anchor"]["longitude"], lat=config["anchor"]["latitude"],
                height=config["anchor"]["height"], ch=config["camera"]["headingDegrees"],
                cp=config["camera"]["pitchDegrees"], cr=config["camera"]["range"]))
        lines.append("")
    return "\n".join(lines)


def emit_positions(sites: dict, assets_root: Path) -> dict:
    """产出 `tools/radar_simulator/scene_positions.json`。

    <p>为什么要有这份文件：严格契约模式下，报文里的 `position.angleDeg / distanceM`
    必须与标定（device_point.azimuth/slant_range）在容差内一致，否则整条被拒
    （`POSITION_CALIBRATION_MISMATCH`）。而 `radar_simulator.py` 里原来那份位置表是
    V11 时代手抄的——V18/V19 换地形、V20 加场景之后它已经全部过期，
    症状是"数据一条都进不去，但界面看起来一切正常"。
    位置表与标定同源（都从 coverage.json 生成），就不会再各自漂移。</p>
    """
    positions: dict[str, dict] = {}
    for site in sites.values():
        asset_dir = assets_root / site["asset_prefix"]
        coverage_path = asset_dir / "coverage.json"
        if not coverage_path.exists():
            continue
        coverage = json.loads(coverage_path.read_text(encoding="utf-8"))
        for radar in coverage["radars"]:
            heading = float(radar.get("headingDegrees", 0.0))
            for binding in radar["bindings"]:
                angle = (float(binding["azimuthDegrees"]) - heading + 180.0) % 360.0 - 180.0
                positions[binding["pointCode"]] = {
                    "angleDeg": round(angle, 3),
                    "distanceM": round(float(binding["slantRangeM"]), 3),
                    "device": radar["code"],
                }
    return positions


def main() -> int:
    parser = argparse.ArgumentParser(description="产出多场景建库迁移草稿")
    parser.add_argument("--assets-root", type=Path,
                        default=ROOT / "frontend" / "public" / "models")
    parser.add_argument("--out", type=Path,
                        default=ROOT / "generated" / "terrain-asset" / "scene-migration-V20.sql")
    args = parser.parse_args()
    sql = HEADER + emit(load_sites(), args.assets_root)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(sql, encoding="utf-8")
    print(f"已写出 {args.out}（{len(sql.splitlines())} 行）")
    positions = emit_positions(load_sites(), args.assets_root)
    positions_path = Path(__file__).resolve().parent.parent / "radar_simulator" / "scene_positions.json"
    positions_path.write_text(
        json.dumps(positions, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8")
    print(f"已写出 {positions_path}（{len(positions)} 个测点的标定位置）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
