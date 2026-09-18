"""程序化结构体几何：野外桥梁 / 铁路路基 / 郊外工厂。

这三个场景的"主体"不是地形而是人造物，30m 公开 DEM 与 10m 影像都不可能给出桥面、
钢轨或厂房——它们由本模块按参数**建模**，与地形拼在同一个 GLB 里（第二个 primitive，
顶点着色）。这样做的好处是资产仍然是一份、离线、可复现，且与既有 GLB + ENU 管线
完全兼容；代价是结构是示意级（不是设计图），这一点写进资产的 ASSET_PROVENANCE.md。

几何约定与地形一致：+X 东、+Y 北、+Z 上，单位米，相对场景基准面。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np


@dataclass
class Geometry:
    """顶点着色的小几何体集合（与 terrain 网格分开，写进 GLB 的第二个 primitive）。"""

    positions: list[np.ndarray] = field(default_factory=list)
    normals: list[np.ndarray] = field(default_factory=list)
    colors: list[np.ndarray] = field(default_factory=list)
    indices: list[np.ndarray] = field(default_factory=list)
    vertex_offset: int = 0

    def add(self, positions: np.ndarray, normals: np.ndarray, color: tuple[float, float, float],
            faces: list[tuple[int, int, int]]) -> None:
        base = self.vertex_offset
        self.positions.append(positions.astype(np.float64))
        self.normals.append(normals.astype(np.float64))
        rgba = np.tile(np.array([*color, 1.0]), (len(positions), 1))
        self.colors.append(rgba)
        self.indices.append(np.asarray([i + base for tri in faces for i in tri], dtype=np.int64))
        self.vertex_offset += len(positions)

    def empty(self) -> bool:
        return not self.positions

    def packed(self) -> dict:
        positions = np.concatenate(self.positions, axis=0)
        normals = np.concatenate(self.normals, axis=0)
        colors = np.concatenate(self.colors, axis=0)
        indices = np.concatenate(self.indices, axis=0)
        return {"positions": positions, "normals": normals, "colors": colors,
                "indices": indices}


def _rot_z(angle_deg: float) -> np.ndarray:
    a = math.radians(angle_deg)
    return np.array([[math.cos(a), -math.sin(a), 0.0],
                     [math.sin(a), math.cos(a), 0.0],
                     [0.0, 0.0, 1.0]])


def _rot_y(angle_deg: float) -> np.ndarray:
    a = math.radians(angle_deg)
    return np.array([[math.cos(a), 0.0, math.sin(a)],
                     [0.0, 1.0, 0.0],
                     [-math.sin(a), 0.0, math.cos(a)]])


def add_box(geom: Geometry, center: tuple[float, float, float],
            size: tuple[float, float, float], color: tuple[float, float, float],
            rot_z: float = 0.0, rot_y: float = 0.0) -> None:
    """轴对齐长方体（可绕 Z/Y 旋转）。六个面各自独立顶点 → 平面法线。"""
    hx, hy, hz = size[0] / 2.0, size[1] / 2.0, size[2] / 2.0
    rotation = _rot_z(rot_z) @ _rot_y(rot_y)
    corners = []
    for sx in (-1, 1):
        for sy in (-1, 1):
            for sz in (-1, 1):
                local = np.array([sx * hx, sy * hy, sz * hz])
                corners.append(np.asarray(center) + rotation @ local)
    # 面的顶点顺序（外法线朝外）：(下标按 sx,sy,sz 枚举顺序 0..7)
    faces_def = [
        ((0, 1, 3, 2), (0, 0, -1)),   # z-
        ((4, 6, 7, 5), (0, 0, 1)),    # z+
        ((0, 4, 5, 1), (-1, 0, 0)),   # x-
        ((2, 3, 7, 6), (1, 0, 0)),    # x+
        ((0, 2, 6, 4), (0, -1, 0)),   # y-
        ((1, 5, 7, 3), (0, 1, 0)),    # y+
    ]
    positions, normals, tris = [], [], []
    for quad, normal in faces_def:
        base = len(positions)
        rotated_normal = rotation @ np.asarray(normal, dtype=np.float64)
        for index in quad:
            positions.append(corners[index])
            normals.append(rotated_normal)
        tris.append((base, base + 1, base + 2))
        tris.append((base, base + 2, base + 3))
    geom.add(np.asarray(positions), np.asarray(normals), color, tris)


def add_cylinder(geom: Geometry, center: tuple[float, float, float], radius: float,
                 height: float, color: tuple[float, float, float], segments: int = 16,
                 top_color: tuple[float, float, float] | None = None) -> None:
    """立式圆柱（罐体/桥墩/烟囱），侧面 + 顶面。"""
    positions, normals, tris = [], [], []
    z0 = center[2] - height / 2.0
    z1 = center[2] + height / 2.0
    for i in range(segments):
        a0 = 2 * math.pi * i / segments
        a1 = 2 * math.pi * (i + 1) / segments
        for a in (a0, a1):
            for z in (z0, z1):
                positions.append([center[0] + radius * math.cos(a),
                                  center[1] + radius * math.sin(a), z])
        normal0 = np.array([math.cos(a0), math.sin(a0), 0.0])
        normal1 = np.array([math.cos(a1), math.sin(a1), 0.0])
        base = len(positions) - 4
        normals.extend([normal0, normal0, normal1, normal1])
        tris.append((base, base + 1, base + 3))
        tris.append((base, base + 3, base + 2))
    top_base = len(positions)
    for i in range(segments):
        a = 2 * math.pi * i / segments
        positions.append([center[0] + radius * math.cos(a), center[1] + radius * math.sin(a), z1])
        normals.append([0.0, 0.0, 1.0])
    for i in range(1, segments - 1):
        tris.append((top_base, top_base + i, top_base + i + 1))
    geom.add(np.asarray(positions), np.asarray(normals), color, tris)
    if top_color is not None:
        add_box(geom, (center[0], center[1], z1 + 0.05), (radius * 1.6, radius * 1.6, 0.1),
                top_color)


CONCRETE = (0.72, 0.71, 0.68)
CONCRETE_DARK = (0.55, 0.55, 0.54)
ASPHALT = (0.30, 0.31, 0.33)
STEEL = (0.58, 0.60, 0.63)
RAIL_STEEL = (0.62, 0.63, 0.66)
BALLAST = (0.50, 0.48, 0.45)
WALL = (0.82, 0.82, 0.80)
ROOF = (0.47, 0.52, 0.58)
TANK = (0.86, 0.87, 0.88)
CHIMNEY = (0.78, 0.76, 0.74)
SAFETY = (0.85, 0.72, 0.25)


def build_bridge(terrain, cfg: dict) -> tuple[Geometry, dict[str, float]]:
    """桥梁：桥面沿 +X 跨过场景中部的谷地，桥墩落在谷底，两端设桥台。"""
    geom = Geometry()
    span = float(cfg.get("span", 320.0))
    deck_width = float(cfg.get("deckWidth", 14.0))
    deck_thickness = float(cfg.get("deckThickness", 1.6))
    x0, x1 = -span / 2.0, span / 2.0
    samples = [terrain.sample(x, 0.0) for x in np.linspace(x0, x1, 97)]
    deck_rel = float(max(samples)) + float(cfg.get("clearance", 2.0))
    deck_bottom = deck_rel - deck_thickness
    # 桥面 + 两侧护栏 + 桥面铺装
    add_box(geom, (0.0, 0.0, deck_rel - deck_thickness / 2.0),
            (span, deck_width, deck_thickness), CONCRETE)
    add_box(geom, (0.0, 0.0, deck_rel + 0.02), (span, deck_width - 1.2, 0.12), ASPHALT)
    for side in (-1, 1):
        add_box(geom, (0.0, side * (deck_width / 2.0 - 0.3), deck_rel + 0.6),
                (span, 0.25, 1.2), STEEL)
    # 桥墩：每 40m 一根，只在桥面明显高于地面处
    pier_rel = []
    for x in np.arange(x0 + 40.0, x1 - 20.0, 40.0):
        ground = terrain.sample(float(x), 0.0)
        height = deck_bottom - ground
        if height < 2.0:
            continue
        add_cylinder(geom, (float(x), 0.0, ground + height / 2.0), 2.0, height,
                     CONCRETE_DARK, segments=14)
        add_box(geom, (float(x), 0.0, ground + 0.6), (6.0, 5.0, 1.2), CONCRETE_DARK)
        pier_rel.append((float(x), ground, height))
    # 桥台（两端）
    for x in (x0 + 4.0, x1 - 4.0):
        ground = terrain.sample(x, 0.0)
        height = max(3.0, deck_bottom - ground + 1.0)
        add_box(geom, (x, 0.0, ground + height / 2.0), (12.0, deck_width + 4.0, height),
                CONCRETE_DARK)
    # 桥上灯柱（示意）
    for x in np.arange(x0 + 30.0, x1 - 20.0, 60.0):
        add_cylinder(geom, (float(x), deck_width / 2.0 - 0.6, deck_rel + 3.0), 0.18, 6.0,
                     STEEL, segments=8)
    # 测点：沿桥面中心线均匀 6 个
    codes = cfg["pointCodes"]
    heights: dict[str, float] = {}
    for index, code in enumerate(codes):
        x = x0 + span * (index + 0.5) / len(codes)
        heights[code] = deck_rel + 0.12
    return geom, heights


def build_railway(terrain, cfg: dict) -> tuple[Geometry, dict[str, float]]:
    """铁路：沿 +X 以近似恒定纵坡通过，路基按地形填挖，钢轨与轨枕示意。"""
    geom = Geometry()
    span = float(cfg.get("span", 540.0))
    x0, x1 = -span / 2.0, span / 2.0
    grid = np.linspace(x0, x1, 55)
    profile = np.asarray([terrain.sample(float(x), 0.0) for x in grid])
    # 最小二乘拟合纵坡（铁路要的是直线坡，不是起伏地形）
    slope, intercept = np.polyfit(grid, profile, 1)
    slope = float(np.clip(slope, -0.05, 0.05))
    track_rel = lambda x: slope * x + intercept + 0.9  # 轨面比拟合地面高 0.9m（道砟）
    # 路基：每 15m 一段，按地形填挖（下沉段画路堑边坡、抬升段画路堤）
    step = 15.0
    edges = np.arange(x0, x1 + 1e-6, step)
    for i in range(len(edges) - 1):
        xa, xb = float(edges[i]), float(edges[i + 1])
        xm = (xa + xb) / 2.0
        top = track_rel(xm) - 0.5
        ground = min(terrain.sample(xa, 0.0), terrain.sample(xm, 0.0), terrain.sample(xb, 0.0))
        height = max(top - ground, 0.6)
        add_box(geom, (xm, 0.0, ground + height / 2.0), (step + 0.2, 7.0, height), BALLAST)
    # 钢轨（沿纵坡倾斜）
    pitch = -math.degrees(math.atan(slope))
    rail_z = track_rel(0.0) + 0.12
    for side in (-1, 1):
        add_box(geom, (0.0, side * 0.7175, rail_z), (span, 0.14, 0.18), RAIL_STEEL,
                rot_y=pitch)
    # 轨枕
    spacing = float(cfg.get("sleeperSpacing", 1.6))
    count = int(span / spacing)
    for i in range(count):
        x = x0 + (i + 0.5) * spacing
        add_box(geom, (x, 0.0, track_rel(x) - 0.08), (0.26, 2.7, 0.2), CONCRETE_DARK)
    # 接触网支柱
    for x in np.arange(x0 + 20.0, x1 - 10.0, 60.0):
        ground = terrain.sample(float(x), -4.5)
        top = track_rel(float(x)) + 6.5
        height = max(top - ground, 3.0)
        add_cylinder(geom, (float(x), -4.5, ground + height / 2.0), 0.22, height, STEEL,
                     segments=8)
    codes = cfg["pointCodes"]
    heights = {}
    for index, code in enumerate(codes):
        x = x0 + span * (index + 0.5) / len(codes)
        heights[code] = track_rel(x) - 0.55  # 路肩/道砟顶面
    return geom, heights


def build_factory(terrain, cfg: dict) -> tuple[Geometry, dict[str, float]]:
    """郊外工厂：先整平一块场地，再放厂房、储罐、烟囱与围墙。"""
    geom = Geometry()
    pad_w = float(cfg.get("padWidth", 240.0))
    pad_d = float(cfg.get("padDepth", 170.0))
    grid = np.linspace(-pad_w / 2.0, pad_w / 2.0, 25)
    grid_y = np.linspace(-pad_d / 2.0, pad_d / 2.0, 21)
    samples = [terrain.sample(float(x), float(y)) for x in grid for y in grid_y]
    pad_rel = float(max(samples)) + 0.4
    add_box(geom, (0.0, 0.0, pad_rel - 0.2), (pad_w, pad_d, 0.4), CONCRETE_DARK)
    # 厂房（三栋）
    buildings = [
        ((-70.0, 30.0), (78.0, 36.0, 15.0)),
        ((20.0, -35.0), (52.0, 30.0, 12.0)),
        ((70.0, 40.0), (34.0, 24.0, 9.0)),
    ]
    for (bx, by), (w, d, h) in buildings:
        add_box(geom, (bx, by, pad_rel + h / 2.0), (w, d, h), WALL)
        add_box(geom, (bx, by, pad_rel + h + 0.35), (w * 1.04, d * 1.06, 0.7), ROOF)
    # 储罐区（三个罐 + 防火堤）
    tank_positions = [(-95.0, -70.0), (-60.0, -70.0), (-25.0, -70.0)]
    for tx, ty in tank_positions:
        add_cylinder(geom, (tx, ty, pad_rel + 8.0), 9.0, 16.0, TANK, segments=18,
                     top_color=STEEL)
    add_box(geom, (-60.0, -70.0, pad_rel + 0.5), (110.0, 60.0, 1.0), CONCRETE)
    # 烟囱
    add_cylinder(geom, (95.0, -10.0, pad_rel + 20.0), 3.0, 40.0, CHIMNEY, segments=16)
    add_box(geom, (95.0, -10.0, pad_rel + 0.6), (10.0, 10.0, 1.2), CONCRETE_DARK)
    # 围墙
    for (cx, cy, w, d) in [(0.0, pad_d / 2.0, pad_w, 0.4), (0.0, -pad_d / 2.0, pad_w, 0.4),
                           (-pad_w / 2.0, 0.0, 0.4, pad_d), (pad_w / 2.0, 0.0, 0.4, pad_d)]:
        add_box(geom, (cx, cy, pad_rel + 1.3), (w, d, 2.6), WALL)
    codes = cfg["pointCodes"]
    # 测点：厂房角点、储罐区、烟囱基础 —— 平均分布在场区
    anchors = [(-70.0, 48.0), (-30.0, 30.0), (20.0, -50.0), (-60.0, -40.0),
               (70.0, 28.0), (95.0, 5.0)]
    heights = {}
    for code, (x, y) in zip(codes, anchors):
        heights[code] = pad_rel + 0.35
    return geom, heights


FEATURES = {"bridge": build_bridge, "railway": build_railway, "factory": build_factory}
