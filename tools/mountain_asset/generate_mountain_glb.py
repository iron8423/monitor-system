#!/usr/bin/env python3
"""Generate the project's deterministic, texture-free low-poly mountain GLB.

The generator uses only Python's standard library. Running it again with the
same source produces byte-identical geometry and metadata, which keeps the
asset auditable and easy to regenerate without Blender or an online service.
"""

from __future__ import annotations

import json
import math
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIR = ROOT / "frontend" / "public" / "models" / "mountain-demo"
MODEL_PATH = OUTPUT_DIR / "mountain-demo.glb"
CONFIG_PATH = OUTPUT_DIR / "scene-config.json"
POINTS_PATH = OUTPUT_DIR / "points.json"

WIDTH = 320.0
DEPTH = 240.0
# V2 keeps the asset small enough for an offline browser, but quadruples the
# terrain density so gullies, scarps and access roads no longer collapse into
# a handful of oversized facets.
CELLS_X = 160
CELLS_Y = 120
ANCHOR = {"longitude": 113.0508, "latitude": 23.7208, "height": 2.0}
ASSET_VERSION = "mountain-demo-2.0.0"
RADAR_HEAD_HEIGHT = 10.0
REFLECTOR_HEIGHT = 2.5

POINTS = [
    # code 是设备协议和影像目录使用的稳定标识，沿用既有值；只更新名称和空间位置。
    {"id": 1, "code": "P-HK01", "name": "山脊测点1", "local": [-92.0, 20.0]},
    {"id": 2, "code": "P-HK02", "name": "山脊测点2", "local": [-48.0, 54.0]},
    {"id": 3, "code": "P-HK03", "name": "山脊测点3", "local": [-3.0, 64.0]},
    {"id": 4, "code": "P-BP01", "name": "滑坡体测点1", "local": [31.0, 50.0]},
    {"id": 5, "code": "P-BP02", "name": "滑坡体测点2", "local": [49.0, 18.0]},
    {"id": 6, "code": "P-BP03", "name": "滑坡体测点3", "local": [56.0, -22.0]},
    {"id": 7, "code": "P-BP04", "name": "滑坡体测点4", "local": [39.0, -62.0]},
]

# Positions and assignments are generated from the same local ENU frame as
# the mountain.  A point may only be assigned when it is inside the range,
# horizontal/vertical field of view and has an unobstructed terrain ray.
RADARS = [
    {
        "id": 1,
        "code": "radar-001",
        "name": "北侧山脊形变雷达",
        "local": [100.0, 100.0],
        "headingDegrees": 232.0,
        "pitchDegrees": 10.0,
        "range": 265.0,
        "halfAngleDegrees": 30.0,
        "verticalHalfAngleDegrees": 15.0,
        "points": ["P-HK02", "P-HK03", "P-BP01", "P-BP02"],
    },
    {
        "id": 2,
        "code": "radar-002",
        "name": "南侧滑坡形变雷达",
        "local": [70.0, -70.0],
        "headingDegrees": 314.0,
        "pitchDegrees": 1.0,
        "range": 265.0,
        "halfAngleDegrees": 30.0,
        "verticalHalfAngleDegrees": 15.0,
        "points": ["P-HK01", "P-BP03", "P-BP04"],
    },
]


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def smoothstep(value: float) -> float:
    value = clamp(value, 0.0, 1.0)
    return value * value * (3.0 - 2.0 * value)


def gaussian(x: float, y: float, cx: float, cy: float, sx: float, sy: float) -> float:
    return math.exp(-(((x - cx) / sx) ** 2 + ((y - cy) / sy) ** 2) * 0.5)


def terrain_height(x: float, y: float) -> float:
    """Local terrain height in metres, including ridges, gullies and a slide."""
    edge_distance = min(x + WIDTH / 2, WIDTH / 2 - x, y + DEPTH / 2, DEPTH / 2 - y)
    edge = smoothstep(edge_distance / 24.0)

    main_mass = 46.0 * gaussian(x, y, -10.0, 6.0, 112.0, 82.0)
    ridge_axis = y - (30.0 - 0.12 * x)
    ridge = 24.0 * math.exp(-(ridge_axis / 23.0) ** 2) * math.exp(-(x / 145.0) ** 2)
    west_peak = 14.0 * gaussian(x, y, -86.0, 28.0, 38.0, 34.0)
    east_shoulder = 9.0 * gaussian(x, y, 77.0, 35.0, 48.0, 45.0)
    north_spur = 6.5 * gaussian(x, y, 12.0, 82.0, 78.0, 21.0)
    south_spur = 5.0 * gaussian(x, y, -48.0, -55.0, 83.0, 30.0)

    # The channel is deliberately visible from the default camera: a shallow
    # scar at the head, a narrow runout groove, then a small accumulation fan.
    scar = -13.0 * gaussian(x, y, 38.0, 38.0, 22.0, 31.0)
    groove_axis = x - (46.0 + 0.10 * (y + 5.0))
    groove = -7.0 * math.exp(-(groove_axis / 11.0) ** 2) * gaussian(x, y, 47.0, -8.0, 55.0, 75.0)
    deposit = 8.0 * gaussian(x, y, 40.0, -65.0, 31.0, 20.0)

    # Secondary drainage lines and a shallow head scarp make the synthetic
    # slope read like terrain instead of a smooth Gaussian mound.
    west_gully_axis = x + 74.0 - 0.20 * (y + 20.0)
    west_gully = -2.8 * math.exp(-(west_gully_axis / 7.0) ** 2) * gaussian(x, y, -58.0, -2.0, 48.0, 92.0)
    east_gully_axis = x - 91.0 + 0.14 * (y - 2.0)
    east_gully = -2.4 * math.exp(-(east_gully_axis / 6.5) ** 2) * gaussian(x, y, 88.0, -4.0, 42.0, 87.0)
    head_scarp_axis = y - (62.0 - 0.08 * (x - 35.0))
    head_scarp = -3.3 * math.exp(-(head_scarp_axis / 4.5) ** 2) * gaussian(x, y, 35.0, 58.0, 43.0, 20.0)

    # Fixed harmonics break the perfect Gaussian silhouette without random
    # state, keeping the output deterministic.
    relief = (
        2.2 * math.sin(x * 0.071 + y * 0.024)
        + 1.6 * math.sin(x * 0.037 - y * 0.083)
        + 0.9 * math.cos(x * 0.139 + y * 0.052)
        + 0.55 * math.sin(x * 0.29 + y * 0.17)
        + 0.35 * math.cos(x * 0.41 - y * 0.23)
    )
    height = (
        main_mass + ridge + west_peak + east_shoulder + north_spur + south_spur
        + scar + groove + deposit + west_gully + east_gully + head_scarp + relief
    )
    return max(0.0, edge * height)


def face_color(x: float, y: float, z: float, normal_z: float) -> tuple[int, int, int, int]:
    scar_zone = gaussian(x, y, 42.0, -4.0, 28.0, 72.0)
    road_y = -86.0 + 0.11 * x + 7.0 * math.sin((x + 125.0) / 48.0)
    on_road = abs(y - road_y) < 3.0 and z < 33.0
    drainage_axis = x - (75.0 + 0.12 * (y + 15.0))
    on_drainage = abs(drainage_axis) < 2.2 and y < 48.0
    color_noise = 0.5 + 0.5 * math.sin(x * 0.19 + y * 0.13) * math.cos(x * 0.07 - y * 0.21)
    if on_road:
        rgb = (102, 98, 88)
    elif on_drainage:
        rgb = (57, 76, 72)
    elif scar_zone > 0.42:
        rgb = (142, 104, 65) if z > 25.0 else (128, 91, 55)
    elif normal_z < 0.78 or z > 58.0:
        rgb = (121, 118, 101)
    elif z > 38.0:
        rgb = (118, 127 + round(8 * color_noise), 79)
    elif z > 16.0:
        rgb = (78, 107 + round(10 * color_noise), 67)
    else:
        rgb = (57, 85 + round(8 * color_noise), 61)
    shade = 0.90 + 0.10 * clamp(normal_z, 0.0, 1.0)
    return tuple(round(c * shade) for c in rgb) + (255,)


def normal_of(a: tuple[float, float, float], b: tuple[float, float, float], c: tuple[float, float, float]):
    ux, uy, uz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
    vx, vy, vz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
    nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
    length = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
    return nx / length, ny / length, nz / length


def geodetic_to_ecef(longitude: float, latitude: float, height: float) -> tuple[float, float, float]:
    semi_major = 6_378_137.0
    eccentricity_sq = 6.69437999014e-3
    lon = math.radians(longitude)
    lat = math.radians(latitude)
    prime_vertical = semi_major / math.sqrt(1.0 - eccentricity_sq * math.sin(lat) ** 2)
    return (
        (prime_vertical + height) * math.cos(lat) * math.cos(lon),
        (prime_vertical + height) * math.cos(lat) * math.sin(lon),
        (prime_vertical * (1.0 - eccentricity_sq) + height) * math.sin(lat),
    )


def ecef_to_geodetic(x: float, y: float, z: float) -> tuple[float, float, float]:
    semi_major = 6_378_137.0
    eccentricity_sq = 6.69437999014e-3
    longitude = math.atan2(y, x)
    radius = math.hypot(x, y)
    latitude = math.atan2(z, radius * (1.0 - eccentricity_sq))
    height = 0.0
    for _ in range(8):
        prime_vertical = semi_major / math.sqrt(1.0 - eccentricity_sq * math.sin(latitude) ** 2)
        height = radius / math.cos(latitude) - prime_vertical
        latitude = math.atan2(z, radius * (1.0 - eccentricity_sq * prime_vertical / (prime_vertical + height)))
    return math.degrees(longitude), math.degrees(latitude), height


def local_to_geodetic(east: float, north: float, up: float) -> tuple[float, float, float]:
    """Convert the model's ENU metres to WGS84 longitude, latitude and height."""
    lon = math.radians(ANCHOR["longitude"])
    lat = math.radians(ANCHOR["latitude"])
    origin = geodetic_to_ecef(ANCHOR["longitude"], ANCHOR["latitude"], ANCHOR["height"])
    east_axis = (-math.sin(lon), math.cos(lon), 0.0)
    north_axis = (-math.sin(lat) * math.cos(lon), -math.sin(lat) * math.sin(lon), math.cos(lat))
    up_axis = (math.cos(lat) * math.cos(lon), math.cos(lat) * math.sin(lon), math.sin(lat))
    world = tuple(
        origin[i] + east * east_axis[i] + north * north_axis[i] + up * up_axis[i]
        for i in range(3)
    )
    return ecef_to_geodetic(*world)


def signed_angle_delta(angle: float, centre: float) -> float:
    return (angle - centre + 180.0) % 360.0 - 180.0


def line_of_sight(start: tuple[float, float, float], end: tuple[float, float, float]) -> tuple[bool, float]:
    """Sample the deterministic height field and return visibility/minimum clearance."""
    minimum_clearance = float("inf")
    for index in range(1, 200):
        t = index / 200.0
        x = start[0] + (end[0] - start[0]) * t
        y = start[1] + (end[1] - start[1]) * t
        ray_height = start[2] + (end[2] - start[2]) * t
        clearance = ray_height - terrain_height(x, y)
        minimum_clearance = min(minimum_clearance, clearance)
    return minimum_clearance > 0.05, minimum_clearance


def binding_geometry(radar: dict, point: dict) -> dict:
    rx, ry = radar["local"]
    px, py = point["local"]
    radar_ground = terrain_height(rx, ry)
    point_ground = terrain_height(px, py)
    start = (rx, ry, radar_ground + RADAR_HEAD_HEIGHT)
    end = (px, py, point_ground + REFLECTOR_HEIGHT)
    dx, dy, dz = end[0] - start[0], end[1] - start[1], end[2] - start[2]
    horizontal = math.hypot(dx, dy)
    slant = math.sqrt(horizontal * horizontal + dz * dz)
    azimuth = math.degrees(math.atan2(dx, dy)) % 360.0
    elevation = math.degrees(math.atan2(dz, horizontal))
    visible, clearance = line_of_sight(start, end)
    heading_delta = signed_angle_delta(azimuth, radar["headingDegrees"])
    pitch_delta = elevation - radar["pitchDegrees"]
    valid = (
        slant <= radar["range"]
        and abs(heading_delta) <= radar["halfAngleDegrees"]
        and abs(pitch_delta) <= radar["verticalHalfAngleDegrees"]
        and visible
    )
    return {
        "pointCode": point["code"],
        "targetCode": f"{radar['code']}-{point['code']}",
        "azimuthDegrees": round(azimuth, 3),
        "elevationDegrees": round(elevation, 3),
        "slantRangeM": round(slant, 3),
        "headingDeltaDegrees": round(heading_delta, 3),
        "pitchDeltaDegrees": round(pitch_delta, 3),
        "lineOfSight": visible,
        "minimumClearanceM": round(clearance, 3),
        "valid": valid,
    }


def pad4(data: bytes, byte: bytes = b"\x00") -> bytes:
    return data + byte * ((-len(data)) % 4)


def build_mesh() -> tuple[bytes, dict]:
    xs = [-WIDTH / 2 + WIDTH * i / CELLS_X for i in range(CELLS_X + 1)]
    ys = [-DEPTH / 2 + DEPTH * j / CELLS_Y for j in range(CELLS_Y + 1)]
    grid = [[(x, y, terrain_height(x, y)) for x in xs] for y in ys]

    positions: list[float] = []
    normals: list[float] = []
    colors = bytearray()

    for j in range(CELLS_Y):
        for i in range(CELLS_X):
            p00, p10 = grid[j][i], grid[j][i + 1]
            p01, p11 = grid[j + 1][i], grid[j + 1][i + 1]
            for tri in ((p00, p10, p11), (p00, p11, p01)):
                normal = normal_of(*tri)
                cx = sum(p[0] for p in tri) / 3.0
                cy = sum(p[1] for p in tri) / 3.0
                cz = sum(p[2] for p in tri) / 3.0
                color = face_color(cx, cy, cz, normal[2])
                for vertex in tri:
                    positions.extend(vertex)
                    normals.extend(normal)
                    colors.extend(color)

    vertex_count = len(positions) // 3
    position_bytes = struct.pack(f"<{len(positions)}f", *positions)
    normal_bytes = struct.pack(f"<{len(normals)}f", *normals)
    color_bytes = bytes(colors)
    binary = pad4(position_bytes) + pad4(normal_bytes) + pad4(color_bytes)

    position_offset = 0
    normal_offset = len(pad4(position_bytes))
    color_offset = normal_offset + len(pad4(normal_bytes))
    gltf = {
        "asset": {"version": "2.0", "generator": "monitor-system deterministic mountain generator v2"},
        "scene": 0,
        "scenes": [{"name": "Offline mountain demo", "nodes": [0]}],
        "nodes": [{"name": "MountainTerrain", "mesh": 0}],
        "meshes": [{
            "name": "LowPolyMountain",
            "primitives": [{
                "attributes": {"POSITION": 0, "NORMAL": 1, "COLOR_0": 2},
                "material": 0,
                "mode": 4,
            }],
        }],
        "materials": [{
            "name": "TerrainVertexColors",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                "metallicFactor": 0.0,
                "roughnessFactor": 1.0,
            },
        }],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": position_offset, "byteLength": len(position_bytes), "target": 34962},
            {"buffer": 0, "byteOffset": normal_offset, "byteLength": len(normal_bytes), "target": 34962},
            {"buffer": 0, "byteOffset": color_offset, "byteLength": len(color_bytes), "target": 34962},
        ],
        "accessors": [
            {
                "bufferView": 0, "componentType": 5126, "count": vertex_count, "type": "VEC3",
                "min": [-WIDTH / 2, -DEPTH / 2, min(positions[2::3])],
                "max": [WIDTH / 2, DEPTH / 2, max(positions[2::3])],
            },
            {"bufferView": 1, "componentType": 5126, "count": vertex_count, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5121, "normalized": True, "count": vertex_count, "type": "VEC4"},
        ],
        "extras": {
            "ownership": "Procedurally generated for monitor-system; no third-party mesh or texture embedded.",
            "assetVersion": ASSET_VERSION,
            "dimensionsMetres": [WIDTH, DEPTH],
            "triangleCount": CELLS_X * CELLS_Y * 2,
        },
    }
    return binary, gltf


def write_glb(binary: bytes, gltf: dict) -> None:
    json_chunk = pad4(json.dumps(gltf, separators=(",", ":"), ensure_ascii=False).encode("utf-8"), b" ")
    bin_chunk = pad4(binary)
    total_length = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    payload = bytearray(struct.pack("<4sII", b"glTF", 2, total_length))
    payload.extend(struct.pack("<I4s", len(json_chunk), b"JSON"))
    payload.extend(json_chunk)
    payload.extend(struct.pack("<I4s", len(bin_chunk), b"BIN\x00"))
    payload.extend(bin_chunk)
    MODEL_PATH.write_bytes(payload)


def write_metadata(gltf: dict) -> None:
    point_lookup = {item["code"]: item for item in POINTS}
    radars = []
    all_bindings = []
    for radar in RADARS:
        radar_x, radar_y = radar["local"]
        radar_z = terrain_height(radar_x, radar_y)
        longitude, latitude, altitude = local_to_geodetic(radar_x, radar_y, radar_z)
        bindings = [binding_geometry(radar, point_lookup[code]) for code in radar["points"]]
        invalid = [item["pointCode"] for item in bindings if not item["valid"]]
        if invalid:
            raise RuntimeError(f"invalid radar coverage for {radar['code']}: {', '.join(invalid)}")
        radars.append({
            **radar,
            "localPosition": [radar_x, radar_y, round(radar_z, 3)],
            "longitude": round(longitude, 7),
            "latitude": round(latitude, 7),
            "altitude": round(altitude, 3),
            "antennaHeightM": RADAR_HEAD_HEIGHT,
            "bindings": bindings,
        })
        all_bindings.extend({"radarCode": radar["code"], **item} for item in bindings)

    config = {
        "name": "清远山地边坡模拟场景",
        "mode": "offline-mountain",
        "asset": "/models/mountain-demo/mountain-demo.glb",
        "assetVersion": ASSET_VERSION,
        "anchor": ANCHOR,
        "dimensions": {"width": WIDTH, "depth": DEPTH, "maxHeight": round(gltf["accessors"][0]["max"][2], 3)},
        "modelAxes": {"east": "+X", "north": "+Y", "up": "+Z"},
        "radars": radars,
        "camera": {"headingDegrees": 327.0, "pitchDegrees": -36.0, "range": 430.0},
        "triangleCount": CELLS_X * CELLS_Y * 2,
    }
    CONFIG_PATH.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    points = []
    for item in POINTS:
        x, y = item["local"]
        z = terrain_height(x, y)
        lon, lat, altitude = local_to_geodetic(x, y, z + 0.35)
        points.append({
            **item,
            "local": [x, y, round(z, 3)],
            "longitude": round(lon, 7),
            "latitude": round(lat, 7),
            "altitude": round(altitude, 3),
        })
    POINTS_PATH.write_text(json.dumps(points, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUTPUT_DIR / "coverage.json").write_text(json.dumps({
        "assetVersion": ASSET_VERSION,
        "radarHeadHeightM": RADAR_HEAD_HEIGHT,
        "reflectorHeightM": REFLECTOR_HEIGHT,
        "radars": radars,
        "bindings": all_bindings,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    binary, gltf = build_mesh()
    write_glb(binary, gltf)
    write_metadata(gltf)
    print(f"wrote {MODEL_PATH} ({MODEL_PATH.stat().st_size:,} bytes)")
    print(f"triangles={gltf['extras']['triangleCount']}, vertices={gltf['accessors'][0]['count']}")
    print(f"max_height={gltf['accessors'][0]['max'][2]:.3f} m")


if __name__ == "__main__":
    main()
