"""把 CityGML 建筑模型（LoD2）转成与地形资产同坐标系的 GLB。

用途：验证"拿到官方 3D 建筑/厂区几何后，我们的三维场景能不能直接呈现真几何"。
数据源举例：瑞士 swissBUILDINGS3D 3.0（CityGML，含屋顶的 LoD2 建筑体块）、荷兰 3D BAG。

坐标与对齐（**必须与 build_terrain_asset 完全一致**，否则建筑会偏出地形）：
  · 源：EPSG:2056（瑞士 LV95）平面 + LN02 高程；脚本用 pyproj 转到 WGS-84 经纬度；
  · 本地：x = (lon - anchor_lon) × m_per_lon，y = (lat - anchor_lat) × m_per_lat，
          z = 高程 − floor（floor 取地形资产的 scene-config.json 里的 anchor.height）；
  · 顶点顺序 [x东, y北, z上]，与地形网格同一约定；
  · 材质：unlit + 顶点色（墙面/屋面分色 + 按固定太阳方向烘焙面着色），与地形资产风格一致。

用法：
    python tools/terrain_asset/citygml_to_glb.py \
      --citygml work/werdhoelzli/buildings/xxx.citygml.zip \
      --config work/werdhoelzli/build/scene-config.json \
      --bbox 8.5000,47.3880,8.5120,47.3990 \
      --out work/werdhoelzli/buildings/buildings.glb
"""

import argparse
import io
import json
import math
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import numpy as np
from pyproj import Transformer

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

NS = {
    "gml": "http://www.opengis.net/gml",
    "bldg": "http://www.opengis.net/citygml/building/2.0",
    "core": "http://www.opengis.net/citygml/2.0",
}

# 面着色：墙面浅灰、屋面深灰蓝、底面更暗；再乘一个固定方向的日照系数做出体积感
SURFACE_COLOR = {
    "RoofSurface": (150, 148, 145),
    "WallSurface": (196, 194, 190),
    "GroundSurface": (120, 118, 115),
    "default": (180, 178, 175),
}
# 受光档（--material lit）单独一套面色（2026-09-23 实测后调整）：
#   实时日照本身会吃掉一截亮度（受光面约为面色的 0.55~0.7），
#   沿用上面那套"已经烘过阴影"的颜色，成品会明显发灰、像水泥墩子。
#   所以受光档把面色整体提亮——明暗仍然全部交给日照，面色只表达"墙还是屋顶"。
LIT_SURFACE_COLOR = {
    "RoofSurface": (176, 174, 170),
    "WallSurface": (236, 234, 230),
    "GroundSurface": (150, 148, 145),
    "default": (228, 226, 222),
}
SUN = np.array([-0.45, 0.52, 0.72])
SUN = SUN / np.linalg.norm(SUN)


def metres_per_degree(lat: float) -> tuple[float, float]:
    """与 build_terrain_asset.metres_per_degree 完全一致（改一处必须改两处）。"""
    m_per_lat = 111132.92 - 559.82 * math.cos(2 * math.radians(lat)) + 1.175
    m_per_lon = 111412.84 * math.cos(math.radians(lat)) - 93.5 * math.cos(3 * math.radians(lat))
    return m_per_lon, m_per_lat


def iter_gml_texts(citygml: Path):
    """逐个吐出 GML 文本块（支持 .zip / .gml / 目录）。"""
    if citygml.is_dir():
        for path in sorted(citygml.rglob("*.gml")):
            yield path.name, path.read_text(encoding="utf-8", errors="replace")
        return
    if citygml.suffix.lower() == ".zip":
        with zipfile.ZipFile(citygml) as archive:
            for name in archive.namelist():
                if name.lower().endswith(".gml"):
                    with archive.open(name) as handle:
                        yield name, io.TextIOWrapper(handle, encoding="utf-8", errors="replace").read()
        return
    yield citygml.name, citygml.read_text(encoding="utf-8", errors="replace")


def polygons_of(building: ET.Element):
    """从一栋建筑里取出所有面：[(面类型, [顶点(E,N,H)...])]。

    LoD2 的几何在 bldg:boundedBy → bldg:WallSurface/RoofSurface/GroundSurface → gml:Polygon，
    也可能直接挂在 bldg:lod2Solid 下（没有语义）——两种都收。
    """
    faces = []
    for bounded in building.iter():
        tag = bounded.tag.split("}")[-1]
        if tag not in ("WallSurface", "RoofSurface", "GroundSurface", "ClosureSurface"):
            continue
        kind = tag
        for polygon in bounded.iter(f"{{{NS['gml']}}}Polygon"):
            ring = polygon.find(".//gml:exterior/gml:LinearRing/gml:posList", NS)
            if ring is None or not ring.text:
                continue
            values = [float(v) for v in ring.text.split()]
            if len(values) % 3 != 0 or len(values) < 9:
                continue
            points = [(values[i], values[i + 1], values[i + 2]) for i in range(0, len(values), 3)]
            faces.append((kind, points))
    if faces:
        return faces
    # 退路：没有语义就整块收（按 lod2Solid 里的多边形）
    for polygon in building.iter(f"{{{NS['gml']}}}Polygon"):
        ring = polygon.find(".//gml:exterior/gml:LinearRing/gml:posList", NS)
        if ring is None or not ring.text:
            continue
        values = [float(v) for v in ring.text.split()]
        if len(values) % 3 or len(values) < 9:
            continue
        points = [(values[i], values[i + 1], values[i + 2]) for i in range(0, len(values), 3)]
        faces.append(("default", points))
    return faces


def main() -> int:
    parser = argparse.ArgumentParser(description="CityGML(LoD2) → 与地形同坐标系的 GLB")
    parser.add_argument("--citygml", type=Path, required=True, help=".zip / .gml / 目录")
    parser.add_argument("--config", type=Path, required=True,
                        help="地形资产的 scene-config.json（取锚点经纬度与基准面）")
    parser.add_argument("--bbox", default="", help="只保留该范围内的建筑：west,south,east,north")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--max-buildings", type=int, default=4000)
    # 材质档位（2026-09-23 新增，为"航拍级参考档"用）：
    #   unlit（默认）＝ 顶点色 + 把太阳方向烘进面色的 lambert 项，历史行为不变；
    #   lit          ＝ PBR 材质、不做烘焙，把受光交给 Cesium 的实时日照与阴影。
    # 两者不能叠：用 lit 材质却保留烘焙面色，近景会出现两层阴影（同一面既被烘焙压暗、
    # 又被实时日照压暗），这与地形资产 --material lit 的约定一致。
    parser.add_argument("--material", choices=("unlit", "lit"), default="unlit",
                        help="unlit=顶点色+烘焙面着色（历史一致）；lit=PBR 受光材质")
    parser.add_argument("--baked-shade", type=float, default=1.0,
                        help="烘焙面着色强度：1.0=原样，0=不烘焙（配 --material lit 用）")
    # 屋面贴正射影像（2026-09-23 新增，为"航拍级参考档"用）：
    #   LoD2 的真几何本来只有体块，屋面是**纯色**的；而同一批数据里的正射影像
    #   （SWISSIMAGE 0.1 m）本来就拍到了每一片屋面的真实颜色与设备。把影像按
    #   "平面投影 UV"贴回屋面（与地形同一套 UV 约定），屋面就与地面影像逐像素对齐，
    #   建筑从"灰盒子"变成"看得见屋顶设备与斑驳"的真建筑；墙面仍走纯色受光，
    #   免得斜看时被平面投影拉成条纹。
    #   代价：产出的 GLB 里屋面材质会引用 texture 0——**必须**与地形 GLB 合并后
    #   才是合法资产（合并时纹理索引不重排，正好复用地形那张正射图，不增体积）。
    parser.add_argument("--roof-texture", action="store_true",
                        help="屋面改用正射影像贴图（材质引用 texture 0，须与地形资产合并后使用）")
    parser.add_argument("--roof-roughness", type=float, default=1.0,
                        help="屋面材质粗糙度（unlit 屋面忽略此项）")
    parser.add_argument("--roof-lit", action="store_true",
                        help="屋面也参与实时日照（默认屋面用 unlit，逐像素等于影像本身）")
    args = parser.parse_args()

    config = json.loads(args.config.read_text(encoding="utf-8"))
    anchor_lon = float(config["anchor"]["longitude"])
    anchor_lat = float(config["anchor"]["latitude"])
    floor = float(config["anchor"]["height"])
    log = lambda message: print(message, flush=True)  # noqa: E731
    # 地形资产的平面范围（build_terrain_asset 的 xs/ys 取 ±width/2、±depth/2），
    # 屋面 UV 必须与它同一套约定，否则贴图会整体偏移。
    texture_box = None
    if args.roof_texture:
        dimensions = config.get("dimensions") or {}
        if "width" not in dimensions or "depth" not in dimensions:
            log("--roof-texture 需要 config 里有 dimensions.width/depth（地形资产的平面范围）")
            return 1
        texture_box = (float(dimensions["width"]), float(dimensions["depth"]))
    m_lon, m_lat = metres_per_degree(anchor_lat)
    log(f"锚点 {anchor_lat:.6f}N {anchor_lon:.6f}E，基准面 {floor:.1f} m")

    bbox = [float(v) for v in args.bbox.split(",")] if args.bbox else None
    to_wgs84 = Transformer.from_crs("EPSG:2056", "EPSG:4326", always_xy=True)
    palette = LIT_SURFACE_COLOR if args.material == "lit" else SURFACE_COLOR

    positions: list[tuple[float, float, float]] = []
    normals: list[tuple[float, float, float]] = []
    colors: list[tuple[int, int, int]] = []
    # 屋面单独收集（--roof-texture 时才有内容）：贴正射影像、不带顶点色
    roof_positions: list[tuple[float, float, float]] = []
    roof_normals: list[tuple[float, float, float]] = []
    roof_uvs: list[tuple[float, float]] = []
    buildings = 0
    faces_total = 0

    for name, text in iter_gml_texts(args.citygml):
        log(f"解析 {name}（{len(text) / 1e6:.1f} MB 文本）…")
        root = ET.fromstring(text)
        for building in root.iter(f"{{{NS['bldg']}}}Building"):
            faces = polygons_of(building)
            if not faces:
                continue
            # 先用第一个点判断是否落在目标范围（LV95 → 经纬度）
            sample_lon, sample_lat = to_wgs84.transform(faces[0][1][0][0], faces[0][1][0][1])
            if bbox and not (bbox[0] <= sample_lon <= bbox[2] and bbox[1] <= sample_lat <= bbox[3]):
                continue
            buildings += 1
            if buildings > args.max_buildings:
                break
            for kind, ring in faces:
                base_rgb = np.array(palette.get(kind, palette["default"]), dtype=float)
                # 屋面且开了贴图档 → 走正射影像，不进纯色墙面那一支
                as_roof = bool(args.roof_texture) and kind == "RoofSurface"
                # 每个面单独成三角形（非索引，保证平面法线与面着色干净）
                for index in range(1, len(ring) - 1):
                    triangle = [ring[0], ring[index], ring[index + 1]]
                    local = []
                    for east, north, height in triangle:
                        lon, lat = to_wgs84.transform(east, north)
                        local.append(((lon - anchor_lon) * m_lon,
                                      (lat - anchor_lat) * m_lat,
                                      height - floor))
                    a, b, c = (np.array(p, dtype=float) for p in local)
                    normal = np.cross(b - a, c - a)
                    norm = np.linalg.norm(normal)
                    if norm < 1e-9:
                        continue
                    normal /= norm
                    if as_roof:
                        # 平面投影 UV：与 build_terrain_asset.build_mesh 同一约定
                        # （xs/ys 取 ±width/2、±depth/2：u 向东、v 向北翻转）。
                        width_m, depth_m = texture_box
                        for point in local:
                            roof_positions.append(point)
                            roof_normals.append((float(normal[0]), float(normal[1]), float(normal[2])))
                            roof_uvs.append(((point[0] + width_m / 2.0) / width_m,
                                             1.0 - (point[1] + depth_m / 2.0) / depth_m))
                        faces_total += 1
                        continue
                    bake = max(0.0, min(1.0, args.baked_shade))
                    lambert = (1.0 - bake) + bake * (0.62 + 0.38 * abs(float(np.dot(normal, SUN))))
                    rgb = np.clip(base_rgb * lambert, 0, 255).astype(int)
                    for point in local:
                        positions.append(point)
                        colors.append(tuple(int(v) for v in rgb))
                        # 面法线（三角形未索引，三个顶点同法线）。
                        # **必须写**：glTF 里缺 NORMAL 的图元配 PBR 材质属于未定义行为，
                        # 实测（2026-09-23 航拍级参考档）墙面上会出现规则的斜纹/编织纹，
                        # 换成带法线的版本才恢复正常受光。
                        normals.append((float(normal[0]), float(normal[1]), float(normal[2])))
                    faces_total += 1
        if buildings > args.max_buildings:
            break

    if not positions and not roof_positions:
        log("没有解析到建筑（检查 --bbox 是否落在图幅内）")
        return 1
    log(f"建筑 {buildings} 栋 / 三角面 {faces_total} / 顶点 墙面 {len(positions)} + 屋面 {len(roof_positions)}")

    # ---------- 写 GLB ----------
    pad = lambda data: data + b"\x00" * ((-len(data)) % 4)  # noqa: E731
    buffer_parts: list[bytes] = []
    buffer_views: list[dict] = []
    accessors: list[dict] = []
    materials: list[dict] = []
    primitives: list[dict] = []
    extensions_used: list[str] = []

    def add_attributes(channel: list[tuple[str, np.ndarray, str]]) -> dict:
        """把一路几何的属性写进 buffer，返回 glTF 的 attributes 字典。"""
        attributes = {}
        for name, data, kind in channel:
            raw = data.tobytes()
            offset = sum(len(part) for part in buffer_parts)
            buffer_parts.append(pad(raw))
            buffer_views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(raw)})
            accessor = {"bufferView": len(buffer_views) - 1, "componentType": 5126,
                        "count": int(len(data)), "type": kind}
            if name == "POSITION":
                accessor["min"] = data.min(axis=0).tolist()
                accessor["max"] = data.max(axis=0).tolist()
            accessors.append(accessor)
            attributes[name] = len(accessors) - 1
        return attributes

    if roof_positions:
        roof_material = {
            "name": "RoofOrtho",
            "pbrMetallicRoughness": {"baseColorTexture": {"index": 0},
                                     "metallicFactor": 0.0,
                                     "roughnessFactor": float(args.roof_roughness)},
        }
        if not args.roof_lit:
            roof_material["extensions"] = {"KHR_materials_unlit": {}}
            extensions_used.append("KHR_materials_unlit")
        materials.append(roof_material)
        attributes = add_attributes([
            ("POSITION", np.asarray(roof_positions, dtype=np.float32), "VEC3"),
            ("NORMAL", np.asarray(roof_normals, dtype=np.float32), "VEC3"),
            ("TEXCOORD_0", np.asarray(roof_uvs, dtype=np.float32), "VEC2"),
        ])
        primitives.append({"attributes": attributes, "material": len(materials) - 1, "mode": 4})

    if positions:
        col = np.asarray(colors, dtype=np.float32) / 255.0
        col = np.concatenate([col, np.ones((len(col), 1), dtype=np.float32)], axis=1)
        if args.material == "lit":
            # 受光档：面色只保留"这是墙还是屋顶"，明暗交给实时日照与阴影
            materials.append({"name": "BuildingPBR",
                              "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1],
                                                       "metallicFactor": 0.0, "roughnessFactor": 0.85}})
        else:
            materials.append({"name": "BuildingUnlit",
                              "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1],
                                                       "metallicFactor": 0.0, "roughnessFactor": 1.0},
                              "extensions": {"KHR_materials_unlit": {}}})
            extensions_used.append("KHR_materials_unlit")
        attributes = add_attributes([
            ("POSITION", np.asarray(positions, dtype=np.float32), "VEC3"),
            ("NORMAL", np.asarray(normals, dtype=np.float32), "VEC3"),
            ("COLOR_0", col, "VEC4"),
        ])
        primitives.append({"attributes": attributes, "material": len(materials) - 1, "mode": 4})

    buffer = b"".join(buffer_parts)
    gltf = {
        "asset": {"version": "2.0", "generator": "tools/terrain_asset/citygml_to_glb.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "CityGML-Buildings"}],
        "meshes": [{"primitives": primitives}],
        "materials": materials,
        "buffers": [{"byteLength": len(buffer)}],
        "bufferViews": buffer_views,
        "accessors": accessors,
    }
    if extensions_used:
        gltf["extensionsUsed"] = list(dict.fromkeys(extensions_used))
    json_chunk = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    json_chunk += b" " * ((-len(json_chunk)) % 4)
    total = 12 + 8 + len(json_chunk) + 8 + len(buffer)
    payload = bytearray(struct.pack("<4sII", b"glTF", 2, total))
    payload += struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
    payload += struct.pack("<I4s", len(buffer), b"BIN\x00") + buffer
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(bytes(payload))
    log(f"写出 {args.out}  {len(payload) / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
