#!/usr/bin/env python3
"""生产候选版综合数据生成/回放工具，只使用 Python 标准库。

generate:
  生成测试档案 SQL、正常/异常混合 NDJSON.gz、负向契约用例和 ground truth。
send:
  将 NDJSON(.gz) 按批发送到标准 ingest；可最大速率或按墙钟节奏回放。
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import json
import math
import random
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

TZ = timezone(timedelta(hours=8))
DEFAULT_ENDPOINT = "http://127.0.0.1:8080/api/v1/ingest/measurements"
DEFAULT_KEY = "dev-ingest-key"
NAMESPACE = uuid.UUID("dd2c2c67-fdda-5ba4-9903-2f28b6bc7961")


def load_mountain_height():
    """Reuse the exact V2 terrain function so the large dataset is spatially self-consistent."""
    source = Path(__file__).resolve().parents[1] / "mountain_asset" / "generate_mountain_glb.py"
    spec = importlib.util.spec_from_file_location("monitor_mountain_asset", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.terrain_height


def iso(dt: datetime) -> str:
    return dt.astimezone(TZ).isoformat(timespec="milliseconds")


def sql_text(value: object) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def point_code(device_no: int, point_no: int) -> str:
    return f"SIM-D{device_no:02d}-P{point_no:03d}"


def device_code(device_no: int) -> str:
    return f"sim-radar-{device_no:02d}"


def offset_wgs84(anchor: dict, east: float, north: float) -> tuple[float, float]:
    lat = float(anchor["latitude"])
    lon = float(anchor["longitude"])
    return (
        lon + east / (111_320.0 * math.cos(math.radians(lat))),
        lat + north / 111_320.0,
    )


def terrain_line_clearance(terrain_height, anchor_height: float,
                           start: tuple[float, float, float],
                           end: tuple[float, float, float], samples: int = 80) -> float:
    """Return the smallest vertical gap between a target ray and the V2 terrain."""
    x0, y0, z0 = start
    x1, y1, z1 = end
    clearance = float("inf")
    for index in range(1, samples):
        ratio = index / samples
        east = x0 + (x1 - x0) * ratio
        north = y0 + (y1 - y0) * ratio
        ray_height = z0 + (z1 - z0) * ratio
        clearance = min(clearance, ray_height - (terrain_height(east, north) + anchor_height))
    return clearance


@dataclass
class PointState:
    code: str
    device_no: int
    point_no: int
    baseline: float
    value: float
    previous: float
    phase: float
    trend_mm_d: float


class ScenarioGenerator:
    def __init__(self, config: dict, start: datetime):
        self.cfg = config
        self.start = start
        self.rng = random.Random(int(config["seed"]))
        self.device_count = int(config["deviceCount"])
        self.points_per_device = int(config["pointsPerDevice"])
        self.rounds = int(config["rounds"])
        self.step_seconds = int(config["sampleIntervalSeconds"])
        self.states: list[PointState] = []
        for d in range(1, self.device_count + 1):
            device_bias = self.rng.uniform(-0.25, 0.25)
            for p in range(1, self.points_per_device + 1):
                baseline = device_bias + self.rng.uniform(-0.7, 0.7)
                self.states.append(PointState(
                    point_code(d, p), d, p, baseline, baseline, baseline,
                    self.rng.uniform(0, math.tau), self.rng.uniform(-0.18, 0.18),
                ))
        self.groups = self._allocate_groups()

    def _allocate_groups(self) -> dict[str, set[str]]:
        available = [s.code for s in self.states if s.device_no not in (7, 8, 10)]
        self.rng.shuffle(available)
        cursor = 0
        groups: dict[str, set[str]] = {}
        for name in (
            "gradualDrift", "suddenStep", "accelerating", "oscillation", "spike",
            "suspectQuality", "faultQuality", "lowSignal", "targetDisappeared",
            "pointOutage", "baselineReset",
        ):
            count = int(self.cfg["anomalies"][name]["points"])
            groups[name] = set(available[cursor:cursor + count])
            cursor += count
        duplicate_candidates = [s.code for s in self.states if s.device_no <= 6]
        groups["duplicates"] = set(duplicate_candidates[:int(self.cfg["anomalies"]["duplicates"]["points"])])
        return groups

    def catalog(self) -> dict:
        anchor = self.cfg["anchor"]
        terrain_height = load_mountain_height()
        anchor_height = float(anchor.get("height", 0))
        devices = []
        points = []
        for d in range(1, self.device_count + 1):
            angle = math.tau * (d - 1) / self.device_count
            east = math.sin(angle) * 135
            north = math.cos(angle) * 95
            lon, lat = offset_wgs84(anchor, east, north)
            ground = terrain_height(east, north) + anchor_height
            devices.append({
                "id": 20_000 + d,
                "code": device_code(d),
                "name": f"生产测试雷达 {d:02d}",
                "longitude": round(lon, 7),
                "latitude": round(lat, 7),
                "east": east,
                "north": north,
                "altitude": round(ground, 3),
                "headingDegrees": round((180 + math.degrees(angle)) % 360, 2),
                "pitchDegrees": 0.0,
                "detectionRangeM": 260.0,
                "halfAngleDegrees": 28.0,
                "antennaHeightM": 10.0,
                "verticalHalfAngleDegrees": 35.0,
            })
        device_by_no = {i + 1: item for i, item in enumerate(devices)}
        # 每台雷达在 10 个水平角带内各挑 10 个无遮挡目标。候选从近到远密集采样，
        # 再均匀抽取，既覆盖不同距离，又保证 1000 条关系都由实际地形 LOS 计算得出。
        geometry_by_device: dict[int, list[dict]] = {}
        for device_no, device in device_by_no.items():
            geometries = []
            radar_head = (
                device["east"], device["north"],
                device["altitude"] + device["antennaHeightM"],
            )
            for column in range(10):
                relative_angle = -23.0 + column * (46.0 / 9.0)
                visible = []
                for distance in (12.0 + step * 2.0 for step in range(50)):
                    azimuth = (device["headingDegrees"] + relative_angle) % 360.0
                    east = device["east"] + math.sin(math.radians(azimuth)) * distance
                    north = device["north"] + math.cos(math.radians(azimuth)) * distance
                    ground = terrain_height(east, north) + anchor_height
                    reflector_height = 2.5
                    target_head = ground + reflector_height
                    dz = target_head - radar_head[2]
                    elevation = math.degrees(math.atan2(dz, distance))
                    slant_range = math.hypot(distance, dz)
                    clearance = terrain_line_clearance(
                        terrain_height, anchor_height, radar_head,
                        (east, north, target_head),
                    )
                    if (clearance > 0.25
                            and slant_range <= device["detectionRangeM"]
                            and abs(elevation - device["pitchDegrees"]) <= device["verticalHalfAngleDegrees"]):
                        visible.append({
                            "east": east, "north": north, "ground": ground,
                            "azimuthDegrees": azimuth,
                            "elevationDegrees": elevation,
                            "slantRangeM": slant_range,
                            "reflectorHeightM": reflector_height,
                            "minimumClearanceM": clearance,
                        })
                if len(visible) < 10:
                    raise RuntimeError(
                        f"device {device['code']} angle column {column} only has {len(visible)} visible targets"
                    )
                # 均匀覆盖这个角带的可见距离范围，而不是只选雷达脚下的近点。
                selected = [visible[round(i * (len(visible) - 1) / 9)] for i in range(10)]
                geometries.extend(selected)
            geometry_by_device[device_no] = geometries

        for state in self.states:
            index = (state.device_no - 1) * self.points_per_device + state.point_no - 1
            device = device_by_no[state.device_no]
            geometry = geometry_by_device[state.device_no][state.point_no - 1]
            lon, lat = offset_wgs84(anchor, geometry["east"], geometry["north"])
            points.append({
                "id": 10_000 + index,
                "deviceNo": state.device_no,
                "objectId": 9_000 + state.device_no,
                "code": state.code,
                "name": f"D{state.device_no:02d} 测点 {state.point_no:03d}",
                "longitude": round(lon, 7),
                "latitude": round(lat, 7),
                "east": round(geometry["east"], 6),
                "north": round(geometry["north"], 6),
                "altitude": round(geometry["ground"], 3),
                "targetCode": f"{device['code']}-{state.code}",
                "azimuthDegrees": round(geometry["azimuthDegrees"], 3),
                "elevationDegrees": round(geometry["elevationDegrees"], 3),
                "slantRangeM": round(geometry["slantRangeM"], 3),
                "reflectorHeightM": geometry["reflectorHeightM"],
                "lineOfSight": True,
                "minimumClearanceM": round(geometry["minimumClearanceM"], 3),
                "calibrationStatus": "ACTIVE",
            })
        return {"devices": devices, "points": points}

    def catalog_sql(self) -> str:
        catalog = self.catalog()
        cfg = self.cfg
        anchor = cfg["anchor"]
        asset = cfg["asset"]
        lines = [
            "-- 仅用于独立测试数据库。要求已执行到 V11；不要在真实生产库直接执行。",
            "-- 建议：新建空测试库 -> 启动后端完成 Flyway -> 再导入本文件。",
            "BEGIN;",
            "INSERT INTO project (id, organization_id, name, code, location, description, created_at, updated_at)",
            f"VALUES ({int(cfg['projectId'])}, 1, '生产规模综合测试项目', {sql_text(cfg['projectCode'])}, '离线测试场', '10 台雷达 / 1000 测点生产候选验收', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);",
            f"INSERT INTO scene (id, project_id, name, type, description, created_at, updated_at) VALUES (900, {int(cfg['projectId'])}, '综合测试场景', 'PRODUCTION_TEST', '确定性综合模拟数据', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);",
        ]
        for d in range(1, self.device_count + 1):
            lines.append(
                "INSERT INTO monitor_object (id, scene_id, name, type, description, created_at, updated_at) "
                f"VALUES ({9000 + d}, 900, '雷达 {d:02d} 监测区', 'SLOPE_ZONE', '100 个测试测点', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);"
            )
        for d in catalog["devices"]:
            lines.append(
                "INSERT INTO device (id, code, name, type, serial_no, longitude, latitude, altitude, "
                "heading_degrees, pitch_degrees, detection_range_m, half_angle_degrees, antenna_height_m, "
                "vertical_half_angle_degrees, status, battery, created_at, updated_at) VALUES "
                f"({d['id']}, {sql_text(d['code'])}, {sql_text(d['name'])}, 'MILLIMETER_WAVE_RADAR', "
                f"{sql_text('SIM-' + d['code'])}, {d['longitude']}, {d['latitude']}, {d['altitude']}, "
                f"{d['headingDegrees']}, {d['pitchDegrees']}, {d['detectionRangeM']}, {d['halfAngleDegrees']}, "
                f"{d['antennaHeightM']}, {d['verticalHalfAngleDegrees']}, "
                "'ONLINE', 100.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);"
            )
        metric_id = 30_000
        binding_id = 40_000
        for p in catalog["points"]:
            lines.append(
                "INSERT INTO monitor_point (id, object_id, code, name, type, longitude, latitude, altitude, enabled, created_at, updated_at) VALUES "
                f"({p['id']}, {p['objectId']}, {sql_text(p['code'])}, {sql_text(p['name'])}, 'POINT_DEFORMATION', "
                f"{p['longitude']}, {p['latitude']}, {p['altitude']}, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);"
            )
            lines.append(
                "INSERT INTO metric (id, point_id, code, name, unit, sort_order, created_at, updated_at) VALUES "
                f"({metric_id}, {p['id']}, 'defo_mm', '累计形变', 'mm', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                f"({metric_id + 1}, {p['id']}, 'rate_mm_d', '形变速率', 'mm/d', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);"
            )
            lines.append(
                "INSERT INTO device_point (id, device_id, point_id, target_code, azimuth_degrees, elevation_degrees, "
                "slant_range_m, reflector_height_m, line_of_sight, minimum_clearance_m, calibration_status, "
                "calibrated_at, valid_from, calibration_note) VALUES "
                f"({binding_id}, {20_000 + p['deviceNo']}, {p['id']}, {sql_text(p['targetCode'])}, "
                f"{p['azimuthDegrees']}, {p['elevationDegrees']}, {p['slantRangeM']}, {p['reflectorHeightM']}, "
                f"TRUE, {p['minimumClearanceM']}, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '综合测试数据集地形 LOS 标定');"
            )
            metric_id += 2
            binding_id += 1
        lines.extend([
            "INSERT INTO digital_twin_scene (id, project_id, enabled, asset_type, coordinate_mode, asset_url, asset_version, asset_sha256, "
            "anchor_longitude, anchor_latitude, anchor_height, heading_degrees, pitch_degrees, roll_degrees, model_scale, "
            "camera_heading_degrees, camera_pitch_degrees, camera_range, maximum_screen_error, maximum_memory_mb, max_heat_points, label_distance, created_at, updated_at) VALUES "
            f"(900, {int(cfg['projectId'])}, TRUE, {sql_text(asset['type'])}, {sql_text(asset['coordinateMode'])}, "
            f"{sql_text(asset['url'])}, {sql_text(asset['version'])}, {sql_text(asset.get('sha256'))}, "
            f"{anchor['longitude']}, {anchor['latitude']}, {anchor.get('height', 0)}, 0, 0, 0, 1, 327, -36, 430, 16, 512, 200, 1200, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);",
            "INSERT INTO project_member (user_id, project_id, created_at) "
            f"SELECT id, {int(cfg['projectId'])}, CURRENT_TIMESTAMP FROM sys_user WHERE username IN ('operator','analyst','maintainer');",
            "COMMIT;",
            "",
        ])
        return "\n".join(lines)

    def _normal_value(self, state: PointState, round_no: int) -> float:
        days = self.step_seconds / 86400.0
        shared = 0.10 * math.sin(round_no / 18.0)
        periodic = 0.08 * math.sin(state.phase + round_no / 9.0)
        state.trend_mm_d = max(-0.5, min(0.5, state.trend_mm_d + self.rng.gauss(0, 0.008)))
        state.value += state.trend_mm_d * days + self.rng.gauss(0, 0.006)
        return state.value + shared + periodic

    def _apply_anomaly(self, state: PointState, round_no: int, value: float) -> tuple[float, str | None, float, str]:
        a = self.cfg["anomalies"]
        quality = None
        signal = self.rng.uniform(0.78, 0.99)
        target_state = "normal"
        if state.code in self.groups["gradualDrift"]:
            spec = a["gradualDrift"]
            if spec["fromRound"] <= round_no <= spec["toRound"]:
                ratio = (round_no - spec["fromRound"]) / max(1, spec["toRound"] - spec["fromRound"])
                value = state.baseline + float(spec["targetMm"]) * ratio
        if state.code in self.groups["suddenStep"]:
            spec = a["suddenStep"]
            if spec["atRound"] <= round_no < spec["recoverRound"]:
                value = float(spec["valueMm"])
            elif round_no >= spec["recoverRound"]:
                value = 0.5
        if state.code in self.groups["accelerating"]:
            spec = a["accelerating"]
            if round_no >= spec["fromRound"]:
                ratio = (round_no - spec["fromRound"]) / max(1, self.rounds - spec["fromRound"])
                value = state.baseline + float(spec["targetMm"]) * ratio * ratio
        if state.code in self.groups["oscillation"] and round_no >= a["oscillation"]["fromRound"]:
            value = float(a["oscillation"]["amplitudeMm"]) * math.sin(round_no * 0.7)
        if state.code in self.groups["spike"] and round_no == a["spike"]["atRound"]:
            value = float(a["spike"]["valueMm"])
        if state.code in self.groups["suspectQuality"] and a["suspectQuality"]["fromRound"] <= round_no <= a["suspectQuality"]["toRound"]:
            quality = "SUSPECT"
            value += self.rng.gauss(0, 1.5)
        if state.code in self.groups["faultQuality"] and a["faultQuality"]["fromRound"] <= round_no <= a["faultQuality"]["toRound"]:
            quality = "FAULT"
            value += self.rng.gauss(0, 4.0)
        if state.code in self.groups["lowSignal"] and a["lowSignal"]["fromRound"] <= round_no <= a["lowSignal"]["toRound"]:
            signal = self.rng.uniform(0.05, 0.22)
            quality = None  # 让服务端按信号推导 SUSPECT
        if state.code in self.groups["targetDisappeared"] and a["targetDisappeared"]["fromRound"] <= round_no <= a["targetDisappeared"]["toRound"]:
            target_state = "disappeared"
        if state.code in self.groups["baselineReset"] and round_no >= a["baselineReset"]["atRound"]:
            value -= state.baseline
        return value, quality, signal, target_state

    def messages(self) -> tuple[list[dict], list[dict]]:
        output: list[dict] = []
        duplicates: list[dict] = []
        a = self.cfg["anomalies"]
        spatial_catalog = self.catalog()
        spatial_by_code = {p["code"]: p for p in spatial_catalog["points"]}
        heading_by_device = {
            index + 1: d["headingDegrees"] for index, d in enumerate(spatial_catalog["devices"])
        }
        duplicate_rounds = set(a["duplicates"]["rounds"])
        out_of_order_rounds = set(a["outOfOrder"]["rounds"])
        for round_no in range(self.rounds):
            collect = self.start + timedelta(seconds=round_no * self.step_seconds)
            current: list[dict] = []
            for state in self.states:
                if state.device_no == int(a["deviceOutage"]["device"]) and a["deviceOutage"]["fromRound"] <= round_no <= a["deviceOutage"]["toRound"]:
                    continue
                if state.code in self.groups["pointOutage"] and a["pointOutage"]["fromRound"] <= round_no <= a["pointOutage"]["toRound"]:
                    continue
                value = self._normal_value(state, round_no)
                value, quality, signal, target_state = self._apply_anomaly(state, round_no, value)
                # rate_mm_d 代表设备算法的平滑日速率估计，不用相邻 5 秒样本的噪声直接外推；
                # 后者会把 0.006mm 的正常测量噪声放大成上百 mm/d，物理上没有意义。
                rate = max(-8.0, min(8.0, state.trend_mm_d + (value - state.baseline) * 0.35))
                state.previous = value
                receive = collect + timedelta(seconds=self.rng.uniform(0.05, 2.5))
                if state.device_no == int(a["delayedUpload"]["device"]) and a["delayedUpload"]["fromRound"] <= round_no <= a["delayedUpload"]["toRound"]:
                    receive = collect + timedelta(minutes=float(a["delayedUpload"]["delayMinutes"]))
                if (state.device_no == int(a["outOfOrder"]["device"])
                        and round_no == min(out_of_order_rounds)):
                    # 把前一轮的到达时间推迟到后一轮之后，排序后仍保持真正的乱序到达。
                    receive += timedelta(seconds=self.step_seconds * 2)
                message = {
                    "schemaVersion": "1.0",
                    "messageId": str(uuid.uuid5(NAMESPACE, f"{self.cfg['datasetName']}|{state.code}|{round_no}")),
                    "deviceId": device_code(state.device_no),
                    "pointCode": state.code,
                    "collectTime": iso(collect),
                    "receiveTime": iso(receive),
                    "sequence": round_no + 1,
                    "metrics": {"defo_mm": round(value, 4), "rate_mm_d": round(rate, 4)},
                    "position": {
                        "angleDeg": round(
                            (spatial_by_code[state.code]["azimuthDegrees"]
                             - heading_by_device[state.device_no] + 540.0) % 360.0 - 180.0,
                            3,
                        ),
                        "distanceM": spatial_by_code[state.code]["slantRangeM"],
                    },
                    "signal": round(signal, 3),
                    "state": target_state,
                }
                if quality is not None:
                    message["quality"] = quality
                current.append(message)
                if round_no in duplicate_rounds and state.code in self.groups["duplicates"]:
                    duplicates.append(dict(message))
            output.extend(current)
        # 真实链路按「到达平台的时间」排序。设备 08 的延迟样本因此会在采集 12 分钟后
        # 才发送，而不是现在就带着一个未来 receiveTime 写库。
        output.sort(key=lambda m: parse_iso(m["receiveTime"]))
        # 重复报文分散插入尾部，messageId 与原报文完全一致，用于验证整条幂等。
        output.extend(duplicates)
        return output, duplicates

    def ground_truth(self, message_count: int, duplicate_count: int) -> dict:
        return {
            "datasetName": self.cfg["datasetName"],
            "seed": self.cfg["seed"],
            "scale": {
                "devices": self.device_count,
                "points": len(self.states),
                "rounds": self.rounds,
                "sampleIntervalSeconds": self.step_seconds,
                "messagesIncludingDuplicates": message_count,
                "exactDuplicateMessages": duplicate_count,
            },
            "anomalyPointCodes": {name: sorted(values) for name, values in self.groups.items()},
            "expected": {
                "duplicates": "相同 deviceId + messageId 必须计为 DUPLICATE，不能新增 measurement 或警情",
                "qualityGate": "SUSPECT/FAULT 超限值落库但不参与测值阈值告警",
                "threshold": "VALID 的 defo_mm 越过 ±3mm 产生 warning，越过 ±5mm 就地升级为 alarm",
                "recovery": "回到恢复阈值内后自动 RESOLVED",
                "outOfOrder": "迟到的旧 collectTime 可落库，但不得覆盖 latest 的新时间点",
                "delayedUpload": "实时墙钟回放时，设备 08 可触发 DATA_DELAY 质量告警",
                "deviceOutage": "原速墙钟回放时设备 10 静默 405 秒，必须触发 OFFLINE，恢复上报后自动解除",
                "pointOutage": "当前版本没有测点级中断告警；测试应把它记录为已知缺口而不是假通过",
                "baselineReset": "当前版本未建立基准重置事件模型；应人工核对曲线连续性并登记缺口",
            },
        }


def negative_cases(start: datetime) -> list[dict]:
    good = {
        "schemaVersion": "1.0",
        "messageId": "negative-good-reference",
        "deviceId": "sim-radar-01",
        "pointCode": "SIM-D01-P001",
        "collectTime": iso(start),
        "sequence": 1,
        "metrics": {"defo_mm": 0.1, "rate_mm_d": 0.0},
        "quality": "VALID",
    }
    cases = []
    def add(name: str, mutate, expected: str):
        msg = json.loads(json.dumps(good))
        msg["messageId"] = f"negative-{name}"
        mutate(msg)
        cases.append({"name": name, "message": msg, "expected": expected})
    add("missing-message-id", lambda m: m.pop("messageId"), "REJECTED")
    add("missing-device", lambda m: m.pop("deviceId"), "REJECTED")
    add("unknown-device", lambda m: m.update(deviceId="not-registered"), "REJECTED")
    add("missing-point", lambda m: m.pop("pointCode"), "REJECTED")
    add("unknown-point", lambda m: m.update(pointCode="NOT-REGISTERED"), "REJECTED")
    add("missing-collect-time", lambda m: m.pop("collectTime"), "REJECTED")
    add("invalid-collect-time", lambda m: m.update(collectTime="yesterday"), "REJECTED")
    add("empty-metrics", lambda m: m.update(metrics={}), "REJECTED")
    add("missing-schema", lambda m: m.pop("schemaVersion"), "REJECTED:UNSUPPORTED_SCHEMA_VERSION")
    add("unsupported-schema", lambda m: m.update(schemaVersion="9.9"), "REJECTED:UNSUPPORTED_SCHEMA_VERSION")
    add("missing-sequence", lambda m: m.pop("sequence"), "REJECTED:INVALID_SEQUENCE")
    add("negative-sequence", lambda m: m.update(sequence=-1), "REJECTED:INVALID_SEQUENCE")
    add("invalid-receive-time", lambda m: m.update(receiveTime="tomorrow"), "REJECTED:INVALID_RECEIVE_TIME")
    add("unknown-metric", lambda m: m.update(metrics={"unknown_metric": 1.0}), "REJECTED:UNKNOWN_METRIC")
    add("unknown-quality", lambda m: m.update(quality="MAYBE"), "REJECTED:UNKNOWN_QUALITY")
    add("invalid-signal", lambda m: m.update(signal=1.5), "REJECTED:INVALID_SIGNAL")
    add("unknown-state", lambda m: m.update(state="lost-ish"), "REJECTED:UNKNOWN_STATE")
    add("invalid-position", lambda m: m.update(position={"angleDeg": "east", "distanceM": -2}), "REJECTED:INVALID_POSITION")
    add("position-calibration-mismatch",
        lambda m: m.update(position={"angleDeg": 179.0, "distanceM": 999.0}),
        "REJECTED:POSITION_CALIBRATION_MISMATCH")
    add("unbound-device-point", lambda m: m.update(deviceId="sim-radar-02"), "REJECTED:DEVICE_POINT_NOT_BOUND")
    return cases


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def file_record(path: Path, description: str) -> dict:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"description": description, "bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def generate(args) -> int:
    cfg = json.loads(Path(args.scenario).read_text(encoding="utf-8"))
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    start = datetime.now(TZ).replace(microsecond=0)
    generator = ScenarioGenerator(cfg, start)
    messages, duplicates = generator.messages()
    (out / "catalog.sql").write_text(generator.catalog_sql(), encoding="utf-8")
    write_json(out / "catalog.json", generator.catalog())
    write_json(out / "contract-negative-cases.json", negative_cases(start))
    truth = generator.ground_truth(len(messages), len(duplicates))
    write_json(out / "ground-truth.json", truth)
    with gzip.open(out / "messages.ndjson.gz", "wt", encoding="utf-8", compresslevel=9) as fh:
        for message in messages:
            fh.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    descriptions = {
        "catalog.sql": "独立测试数据库档案",
        "catalog.json": "档案机器可读副本",
        "messages.ndjson.gz": "标准消息，一行一条，gzip 压缩",
        "contract-negative-cases.json": "非法/边界消息与期望",
        "ground-truth.json": "异常点集合和验收预期",
    }
    manifest = {
        "formatVersion": "1.0",
        "generatedAt": iso(datetime.now(TZ)),
        "scenario": cfg,
        "files": {name: file_record(out / name, description) for name, description in descriptions.items()},
    }
    write_json(out / "manifest.json", manifest)
    print(f"generated: {out}")
    print(f"devices={generator.device_count} points={len(generator.states)} messages={len(messages)} duplicates={len(duplicates)}")
    return 0


def iter_messages(path: Path) -> Iterable[dict]:
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no} 不是合法 JSON: {exc}") from exc


def post_batch(url: str, key: str, items: list[dict], timeout: float, ingest_mode: str) -> dict:
    body = json.dumps({"ingestMode": ingest_mode, "items": items}, ensure_ascii=False).encode("utf-8")
    req = Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json; charset=utf-8",
        "X-Ingest-Key": key,
    })
    with urlopen(req, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") != 0:
        raise RuntimeError(payload.get("message") or "ingest 返回业务错误")
    return payload["data"]


def parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def rebase(message: dict, origin: datetime, target: datetime) -> dict:
    msg = dict(message)
    delta = target - origin
    for key in ("collectTime", "receiveTime"):
        if msg.get(key):
            msg[key] = iso(parse_iso(msg[key]) + delta)
    return msg


def send(args) -> int:
    path = Path(args.input).resolve()
    messages = iter_messages(path)
    origin = None
    target = datetime.now(TZ)
    totals = {"accepted": 0, "rejected": 0, "duplicates": 0, "batches": 0, "messages": 0}
    batch: list[dict] = []
    first_due = None
    wall_start = time.monotonic()
    ingest_mode = args.ingest_mode or ("REALTIME" if args.rebase_now else "BACKFILL")
    print(f"ingestMode={ingest_mode}")

    def flush() -> None:
        if not batch:
            return
        result = post_batch(args.url, args.key, batch, args.timeout, ingest_mode)
        totals["accepted"] += int(result.get("accepted", 0))
        totals["rejected"] += int(result.get("rejected", 0))
        totals["duplicates"] += int(result.get("duplicates", 0))
        totals["batches"] += 1
        totals["messages"] += len(batch)
        if totals["batches"] % 10 == 0:
            print(json.dumps(totals, ensure_ascii=False))
        batch.clear()

    try:
        for raw in messages:
            if origin is None:
                origin = min(parse_iso(raw["collectTime"]), parse_iso(raw.get("receiveTime", raw["collectTime"])))
            message = rebase(raw, origin, target) if args.rebase_now else raw
            if args.wall_clock:
                current = parse_iso(message.get("receiveTime", message["collectTime"]))
                if first_due is None:
                    first_due = current
                due = (current - first_due).total_seconds() / max(0.001, args.speed)
                wait = due - (time.monotonic() - wall_start)
                if wait > 0:
                    flush()
                    time.sleep(wait)
            batch.append(message)
            if len(batch) >= args.batch_size:
                flush()
        flush()
    except HTTPError as exc:
        print(f"HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')[:500]}", file=sys.stderr)
        return 1
    except URLError as exc:
        print(f"无法连接 ingest: {exc.reason}", file=sys.stderr)
        return 1
    print(json.dumps(totals, ensure_ascii=False, indent=2))
    return 0 if totals["rejected"] == 0 else 3


def main() -> int:
    parser = argparse.ArgumentParser(description="生产规模数字孪生综合模拟器")
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("generate", help="生成确定性档案与消息数据集")
    gen.add_argument("--scenario", default=str(Path(__file__).with_name("scenario.production.json")))
    gen.add_argument("--output", default="generated/production-baseline")
    gen.set_defaults(func=generate)

    replay = sub.add_parser("send", help="批量或按墙钟回放标准消息")
    replay.add_argument("--input", required=True)
    replay.add_argument("--url", default=DEFAULT_ENDPOINT)
    replay.add_argument("--key", default=DEFAULT_KEY)
    replay.add_argument("--batch-size", type=int, default=1000)
    replay.add_argument("--timeout", type=float, default=30.0)
    replay.add_argument("--rebase-now", action="store_true", help="整体平移时间，使第一条 collectTime 为现在")
    replay.add_argument("--wall-clock", action="store_true", help="按 collectTime 间隔发送，而不是最大速率")
    replay.add_argument("--speed", type=float, default=1.0, help="墙钟回放倍速；仅 --wall-clock 生效")
    replay.add_argument("--ingest-mode", choices=("REALTIME", "BACKFILL"), default=None,
                        help="接入模式；默认 --rebase-now 时 REALTIME，否则 BACKFILL")
    replay.set_defaults(func=send)
    args = parser.parse_args()
    if getattr(args, "batch_size", 1) <= 0:
        parser.error("--batch-size 必须大于 0")
    if getattr(args, "speed", 1) <= 0:
        parser.error("--speed 必须大于 0")
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
