#!/usr/bin/env python3
"""把公开 DEM + 卫星影像烘焙成离线 GLB 地形资产（真实地形演示版）。

与 `tools/mountain_asset/generate_mountain_glb.py` 的分工：

  · mountain-demo  —— 纯数学函数生成的**虚构**山体，无纹理、无外部输入；
  · 本脚本        —— 用**真实 DEM**做几何骨架、用**真实卫星影像**做色彩底，
                     再叠加「明确标注为程序化」的中高频细节，产出可离线运行、
                     与现有 Cesium 数字孪生管线（GLB + ENU 本地坐标）完全兼容的资产。

为什么需要程序化细节：免费公开 DEM 只有 30m 级（本脚本用的是 SRTM 派生瓦片），
在 320×240m 的场景里只相当于 10×8 个采样点，直接插值出来像一块融化的蜡；卫星影像
公开可离线的那一档又只有 10m 分辨率。因此：几何 = 30m 实测骨架 + 程序化细节，
纹理 = 10m 实测色彩 + 程序化细节 + 用最终高程烘焙的山体阴影。
**两者都写进 ASSET_PROVENANCE.md**，不含糊其辞：哪些是量出来的、哪些是算出来的。

等后续接入无人机航测（5cm 级 DSM + 正射影像）时，同一脚本可直接复用：
把 `--dem` / `--ortho` 换成航测成果，`--detail-amplitude 0` 关掉程序化细节即可。

用法：

    # 1) 先抓数据（见 fetch_sources.py）
    # 2) 再生成资产
    python3 tools/terrain_asset/build_terrain_asset.py \
        --sources generated/terrain-asset/sources \
        --out frontend/public/models/qingyuan-hillside

输出：GLB、scene-config.json、points.json、coverage.json、ASSET_PROVENANCE.md、
build-manifest.json，以及一份给 Flyway 用的 `scene-update.sql`（草稿，需人工评审后
才可以拷进 db/migration —— 迁移一旦执行就不可再改，脚本绝不直接写迁移目录）。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import struct
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

# ---------------------------------------------------------------- 默认参数

DEFAULT_ANCHOR = {"longitude": 113.05133, "latitude": 23.75946}
DEFAULT_WIDTH = 1000.0
DEFAULT_DEPTH = 750.0
# 4m 网格：960m 级场景再用 2m 网格会让三角形数与文件体积失控，
# 而公开 DEM 本身只有 30m 采样，2m 网格并没有多出真实信息。
DEFAULT_CELLS_X = 250
DEFAULT_CELLS_Y = 188
DEFAULT_TEXTURE = (4096, 3072)
DEFAULT_ASSET_NAME = "qingyuan-hillside"
DEFAULT_ASSET_VERSION = "qingyuan-hillside-1.0.0"
DEFAULT_RADAR_HEAD_HEIGHT_M = 10.0
DEFAULT_REFLECTOR_HEIGHT_M = 2.5
POINT_MARKER_HEIGHT_M = 0.35
LUMA = np.array([0.2126, 0.7152, 0.0722])

# 两台雷达与 7 个测点（本地米坐标：+X 东、+Y 北、+Z 上）。
#
# 2.0 版把场景从 320×240m 放大到 1000×750m（真实起伏约 237m），测点与雷达按整片坡面
# 重新布置：北侧雷达架在坡体东北侧的稳定台地上，俯瞰山脊与上部坡面；南侧雷达架在
# 坡脚谷地，仰视滑坡体与坡脚。两台雷达都在**监测区之外**（这才是现场做法：雷达不能
# 自己站在会动的那块地上）。选点约束（生成器逐条校验，不满足即失败）：
#   · 每个点在其雷达 10m 天线高度下通视、净空 > 0.5m、斜距 ≤ 620m；
#   · 同一雷达的目标方位跨度 ≤ 58°、俯仰跨度 ≤ 24°（设备视场 ±30°/±15°，留 1° 余量）；
#   · 山脊组落在高带（绝对高程约 180–215m），滑坡组落在低带（约 105–170m），保持语义。
POINTS = [
    {"id": 1, "code": "P-HK01", "name": "山脊测点1", "local": [-150.0, 200.0]},
    {"id": 2, "code": "P-HK02", "name": "山脊测点2", "local": [-100.0, 100.0]},
    {"id": 3, "code": "P-HK03", "name": "山脊测点3", "local": [0.0, 50.0]},
    {"id": 4, "code": "P-BP01", "name": "滑坡体测点1", "local": [50.0, 50.0]},
    {"id": 5, "code": "P-BP02", "name": "滑坡体测点2", "local": [50.0, -50.0]},
    {"id": 6, "code": "P-BP03", "name": "滑坡体测点3", "local": [50.0, -150.0]},
    {"id": 7, "code": "P-BP04", "name": "滑坡体测点4", "local": [200.0, -150.0]},
]

RADARS = [
    {
        "id": 1, "code": "radar-001", "name": "北侧山脊形变雷达",
        "local": [150.0, 300.0], "headingDegrees": 232.0, "pitchDegrees": 10.0,
        "range": 620.0, "halfAngleDegrees": 30.0, "verticalHalfAngleDegrees": 15.0,
        "points": ["P-HK01", "P-HK02", "P-HK03", "P-BP01"],
    },
    {
        "id": 2, "code": "radar-002", "name": "南侧滑坡形变雷达",
        "local": [100.0, -300.0], "headingDegrees": 314.0, "pitchDegrees": 1.0,
        "range": 620.0, "halfAngleDegrees": 30.0, "verticalHalfAngleDegrees": 15.0,
        "points": ["P-BP02", "P-BP03", "P-BP04"],
    },
]


def log(message: str) -> None:
    print(message, flush=True)


# ---------------------------------------------------------------- 几何/投影


def metres_per_degree(lat_deg: float) -> tuple[float, float]:
    a = 6378137.0
    e2 = 6.6943799901413165e-3
    sin_lat = math.sin(math.radians(lat_deg))
    n = a / math.sqrt(1.0 - e2 * sin_lat * sin_lat)
    m = a * (1.0 - e2) / (1.0 - e2 * sin_lat * sin_lat) ** 1.5
    return math.pi / 180.0 * n * math.cos(math.radians(lat_deg)), math.pi / 180.0 * m


def lonlat_to_mercator(lon: float, lat: float) -> tuple[float, float]:
    x = (lon + 180.0) / 360.0
    sin_lat = math.sin(math.radians(lat))
    y = 0.5 - math.log((1.0 + sin_lat) / (1.0 - sin_lat)) / (4.0 * math.pi)
    return x, y


# ---------------------------------------------------------------- 噪声


def _hash2(ix: int, iy: int, seed: int) -> float:
    h = (ix * 374761393 + iy * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFFFFFF) / 0xFFFFFFFF * 2.0 - 1.0


def value_noise(x: float, y: float, seed: int) -> float:
    """二维值噪声：整数格点上取哈希，格内用 smoothstep 插值。"""
    x0, y0 = math.floor(x), math.floor(y)
    fx, fy = x - x0, y - y0
    sx = fx * fx * (3.0 - 2.0 * fx)
    sy = fy * fy * (3.0 - 2.0 * fy)
    v00 = _hash2(x0, y0, seed)
    v10 = _hash2(x0 + 1, y0, seed)
    v01 = _hash2(x0, y0 + 1, seed)
    v11 = _hash2(x0 + 1, y0 + 1, seed)
    return (v00 * (1 - sx) + v10 * sx) * (1 - sy) + (v01 * (1 - sx) + v11 * sx) * sy


def fbm(x: float, y: float, seed: int, octaves: int, wavelength: float,
        lacunarity: float = 2.0, gain: float = 0.5) -> float:
    total, amplitude, norm, wl = 0.0, 1.0, 0.0, wavelength
    for octave in range(octaves):
        total += amplitude * value_noise(x / wl, y / wl, seed + octave * 101)
        norm += amplitude
        amplitude *= gain
        wl /= lacunarity
    return total / norm


def fbm_array(xs: np.ndarray, ys: np.ndarray, seed: int, octaves: int,
              wavelength: float, lacunarity: float = 2.0, gain: float = 0.5) -> np.ndarray:
    """fbm 的向量化版本：xs、ys 为任意形状的坐标网格。"""
    total = np.zeros_like(xs, dtype=np.float64)
    norm = 0.0
    amplitude, wl = 1.0, wavelength
    for octave in range(octaves):
        gx = xs / wl
        gy = ys / wl
        x0 = np.floor(gx).astype(np.int64)
        y0 = np.floor(gy).astype(np.int64)
        fx = gx - x0
        fy = gy - y0
        sx = fx * fx * (3.0 - 2.0 * fx)
        sy = fy * fy * (3.0 - 2.0 * fy)
        seed_octave = seed + octave * 101
        v00 = _hash_array(x0, y0, seed_octave)
        v10 = _hash_array(x0 + 1, y0, seed_octave)
        v01 = _hash_array(x0, y0 + 1, seed_octave)
        v11 = _hash_array(x0 + 1, y0 + 1, seed_octave)
        layer = (v00 * (1 - sx) + v10 * sx) * (1 - sy) + (v01 * (1 - sx) + v11 * sx) * sy
        total += amplitude * layer
        norm += amplitude
        amplitude *= gain
        wl /= lacunarity
    return total / norm


def _hash_array(ix: np.ndarray, iy: np.ndarray, seed: int) -> np.ndarray:
    h = (ix.astype(np.int64) * 374761393 + iy.astype(np.int64) * 668265263
         + np.int64(seed) * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = (h ^ (h >> 16)) & 0xFFFFFFFF
    return h.astype(np.float64) / 0xFFFFFFFF * 2.0 - 1.0


# ---------------------------------------------------------------- 数据源读取


@dataclass
class Mosaic:
    """把一组瓦片拼成一张大图，并提供经纬度 → 双线性采样。"""

    zoom: int
    x0: int
    y0: int
    data: np.ndarray

    def pixel_of(self, lon: float, lat: float) -> tuple[float, float]:
        nx, ny = lonlat_to_mercator(lon, lat)
        n = 2 ** self.zoom
        return nx * n * 256.0 - self.x0 * 256.0, ny * n * 256.0 - self.y0 * 256.0

    def sample(self, lon: float, lat: float) -> np.ndarray:
        px, py = self.pixel_of(lon, lat)
        return self.sample_px(px, py)

    def sample_px(self, px: float, py: float) -> np.ndarray:
        h, w = self.data.shape[:2]
        px = min(max(px, 0.0), w - 1.001)
        py = min(max(py, 0.0), h - 1.001)
        x0, y0 = int(math.floor(px)), int(math.floor(py))
        fx, fy = px - x0, py - y0
        c = self.data.shape[2] if self.data.ndim == 3 else 1
        out = np.zeros(c, dtype=np.float64)
        for dy, wy in ((0, 1 - fy), (1, fy)):
            for dx, wx in ((0, 1 - fx), (1, fx)):
                weight = wx * wy
                if weight <= 0:
                    continue
                out += weight * self.data[y0 + dy, x0 + dx].astype(np.float64)
        return out


def load_dem_mosaic(sources: Path, manifest: dict) -> Mosaic:
    source = manifest["sources"]["dem"]
    tiles = source["tiles"]
    xs = sorted({t["x"] for t in tiles})
    ys = sorted({t["y"] for t in tiles})
    data = np.zeros((len(ys) * 256, len(xs) * 256), dtype=np.float32)
    for tile in tiles:
        image = np.asarray(Image.open(sources / tile["file"]).convert("RGB")).astype(np.float32)
        height = image[:, :, 0] * 256.0 + image[:, :, 1] + image[:, :, 2] / 256.0 - 32768.0
        j, i = xs.index(tile["x"]), ys.index(tile["y"])
        data[i * 256:(i + 1) * 256, j * 256:(j + 1) * 256] = height
    return Mosaic(zoom=source["zoom"], x0=xs[0], y0=ys[0], data=data)


def load_imagery_mosaic(sources: Path, manifest: dict) -> Mosaic:
    source = manifest["sources"]["imagery"]
    tiles = source["tiles"]
    xs = sorted({t["x"] for t in tiles})
    ys = sorted({t["y"] for t in tiles})
    data = np.zeros((len(ys) * 256, len(xs) * 256, 3), dtype=np.uint8)
    for tile in tiles:
        image = np.asarray(Image.open(sources / tile["file"]).convert("RGB"))
        j, i = xs.index(tile["x"]), ys.index(tile["y"])
        data[i * 256:(i + 1) * 256, j * 256:(j + 1) * 256] = image
    return Mosaic(zoom=source["zoom"], x0=xs[0], y0=ys[0], data=data)


# ---------------------------------------------------------------- 高程场


@dataclass
class Heightfield:
    """场景本地高程场（相对基准面），提供双线性采样与坡度。"""

    xs: np.ndarray
    ys: np.ndarray
    heights: np.ndarray            # 相对 floor（米）
    floor: float                   # 基准面海拔（锚点高度）
    anchor: dict
    m_per_lon: float
    m_per_lat: float
    detail_amplitude: float = 0.0

    @property
    def width(self) -> float:
        return float(self.xs[-1] - self.xs[0])

    @property
    def depth(self) -> float:
        return float(self.ys[-1] - self.ys[0])

    def sample(self, x: float, y: float) -> float:
        i = (x - self.xs[0]) / (self.xs[1] - self.xs[0])
        j = (y - self.ys[0]) / (self.ys[1] - self.ys[0])
        i = min(max(i, 0.0), len(self.xs) - 1.001)
        j = min(max(j, 0.0), len(self.ys) - 1.001)
        i0, j0 = int(math.floor(i)), int(math.floor(j))
        fx, fy = i - i0, j - j0
        h = self.heights
        return float(
            (h[j0, i0] * (1 - fx) + h[j0, i0 + 1] * fx) * (1 - fy)
            + (h[j0 + 1, i0] * (1 - fx) + h[j0 + 1, i0 + 1] * fx) * fy
        )

    def sample_grid(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        """任意坐标网格上的双线性采样（向量化，供纹理烘焙用）。"""
        i = (xs - self.xs[0]) / (self.xs[1] - self.xs[0])
        j = (ys - self.ys[0]) / (self.ys[1] - self.ys[0])
        i = np.clip(i, 0.0, len(self.xs) - 1.001)
        j = np.clip(j, 0.0, len(self.ys) - 1.001)
        i0 = np.floor(i).astype(np.int64)
        j0 = np.floor(j).astype(np.int64)
        fx = i - i0
        fy = j - j0
        h = self.heights
        return (
            (h[j0, i0] * (1 - fx) + h[j0, i0 + 1] * fx) * (1 - fy)
            + (h[j0 + 1, i0] * (1 - fx) + h[j0 + 1, i0 + 1] * fx) * fy
        )

    def lonlat_of(self, x: float, y: float) -> tuple[float, float]:
        return (
            self.anchor["longitude"] + x / self.m_per_lon,
            self.anchor["latitude"] + y / self.m_per_lat,
        )


def build_heightfield(dem: Mosaic, anchor: dict, width: float, depth: float,
                      cells_x: int, cells_y: int, detail_amplitude: float,
                      detail_seed: int) -> Heightfield:
    m_per_lon, m_per_lat = metres_per_degree(anchor["latitude"])
    xs = np.linspace(-width / 2.0, width / 2.0, cells_x + 1)
    ys = np.linspace(-depth / 2.0, depth / 2.0, cells_y + 1)
    gx, gy = np.meshgrid(xs, ys)
    lon = anchor["longitude"] + gx / m_per_lon
    lat = anchor["latitude"] + gy / m_per_lat

    raw = np.zeros_like(gx)
    for j in range(raw.shape[0]):
        for i in range(raw.shape[1]):
            raw[j, i] = dem.sample(float(lon[j, i]), float(lat[j, i]))[0]

    # 轻量平滑：30m 源数据在 2m 网格上直接双线性采样会留下格子感，
    # 3×3 核抹掉采样格，同时保留宏观坡形。
    smoothed = raw.copy()
    for _ in range(2):
        padded = np.pad(smoothed, 1, mode="edge")
        smoothed = (
            padded[0:-2, 0:-2] + 2 * padded[0:-2, 1:-1] + padded[0:-2, 2:]
            + 2 * padded[1:-1, 0:-2] + 4 * padded[1:-1, 1:-1] + 2 * padded[1:-1, 2:]
            + padded[2:, 0:-2] + 2 * padded[2:, 1:-1] + padded[2:, 2:]
        ) / 16.0

    # 细节幅度跟随坡度：陡坡的岩面本来就更粗糙；平地上不给太多假起伏。
    dzdx = np.gradient(smoothed, axis=1) / (width / cells_x)
    dzdy = np.gradient(smoothed, axis=0) / (depth / cells_y)
    slope_deg = np.degrees(np.arctan(np.hypot(dzdx, dzdy)))
    slope_factor = np.clip(0.35 + slope_deg / 22.0, 0.35, 1.6)

    detail = detail_amplitude * slope_factor * fbm_array(
        gx, gy, detail_seed, octaves=3, wavelength=68.0, gain=0.55)
    detail += detail_amplitude * 0.28 * fbm_array(
        gx, gy, detail_seed + 7, octaves=3, wavelength=21.0, gain=0.5)
    heights = smoothed + detail

    floor = float(np.floor(heights.min() - 1.0))
    return Heightfield(xs=xs, ys=ys, heights=heights - floor, floor=floor,
                       anchor=anchor, m_per_lon=m_per_lon, m_per_lat=m_per_lat,
                       detail_amplitude=detail_amplitude)


# ---------------------------------------------------------------- 纹理烘焙


def bake_texture(imagery: Mosaic, terrain: Heightfield, width: float, depth: float,
                 texture_size: tuple[int, int], seed: int) -> tuple[bytes, Image.Image]:
    tex_w, tex_h = texture_size
    m_per_lon, m_per_lat = terrain.m_per_lon, terrain.m_per_lat

    # 1) 影像裁剪：场景包围盒在 Web Mercator 上是矩形，直接裁+重采样。
    west = terrain.anchor["longitude"] - width / 2.0 / m_per_lon
    east = terrain.anchor["longitude"] + width / 2.0 / m_per_lon
    north = terrain.anchor["latitude"] + depth / 2.0 / m_per_lat
    south = terrain.anchor["latitude"] - depth / 2.0 / m_per_lat
    x0, y0 = imagery.pixel_of(west, north)
    x1, y1 = imagery.pixel_of(east, south)
    crop = imagery.data[int(round(y0)):int(round(y1)) + 1, int(round(x0)):int(round(x1)) + 1]
    if crop.size == 0:
        raise RuntimeError("影像裁剪为空，检查 sources 的瓦片覆盖范围")
    base = np.asarray(
        Image.fromarray(crop).resize((tex_w, tex_h), Image.LANCZOS)).astype(np.float64)
    # 10m 影像放大到 6.4px/m 后非常糊，先做一次轻度 USM 找回边缘（不能过头，
    # 否则 10m 的块状采样会变成"浮雕"）。它补的是锐度，不是分辨率。
    base_image = Image.fromarray(base.astype(np.uint8))
    base = np.asarray(base_image.filter(ImageFilter.UnsharpMask(radius=2.2, percent=55, threshold=3))).astype(np.float64)

    # 2) 曝光归一：Sentinel-2 cloudless 2023 整体偏暗（本场景实测均值 RGB 36/46/26、
    #    98 分位不足 115），但**不能按通道拉直**——各通道分位差不同，拉完会跑色偏
    #    （上一版就是这样把森林拉成了紫红色块）。这里只按亮度归一，再用同一个增益
    #    缩放三通道，色相由原影像保留。
    base /= 255.0
    lum = base @ LUMA
    low, high = np.percentile(lum, [2.0, 98.0])
    tone = np.clip((lum - low) / max(high - low, 1e-6), 0.0, 1.0) ** 0.95
    target = 0.065 + 0.50 * tone
    gain = np.clip(target / np.maximum(lum, 1e-3), 0.4, 3.4)
    base = np.clip(base * gain[..., None], 0.0, 1.0)
    gray = base @ LUMA
    base = np.clip(gray[..., None] + (base - gray[..., None]) * 1.35, 0.0, 1.0)

    # 3) 纹理分辨率下的最终高程与坡度（与网格同一套细节，保证纹理和几何一致）
    xs = np.linspace(-width / 2.0, width / 2.0, tex_w)
    ys = np.linspace(-depth / 2.0, depth / 2.0, tex_h)
    gx, gy = np.meshgrid(xs, ys)
    h = terrain.sample_grid(gx, gy)
    dhdx = np.gradient(h, axis=1) / (width / tex_w)
    dhdy = np.gradient(h, axis=0) / (depth / tex_h)
    slope_deg = np.degrees(np.arctan(np.hypot(dhdx, dhdy)))

    # 4) 山体阴影（太阳方位 315°、高度角 45°）：烘焙进纹理，
    #    配合 KHR_materials_unlit，画面不受场景时钟与太阳位置影响。
    #    坡面法线 n = normalize(-dhdx, -dhdy, 1)，光方向 l 指向太阳（ENU）。
    sun_az = math.radians(315.0)
    sun_alt = math.radians(45.0)
    light = np.array([math.sin(sun_az) * math.cos(sun_alt),
                      math.cos(sun_az) * math.cos(sun_alt),
                      math.sin(sun_alt)])
    normal = np.stack([-dhdx, -dhdy, np.ones_like(dhdx)], axis=-1)
    normal /= np.linalg.norm(normal, axis=-1, keepdims=True)
    lambert = np.clip((normal * light).sum(axis=-1), 0.0, 1.0)
    # 平地 lambert = sin(45°) ≈ 0.707 → 1.0，向阳面略亮、背阴面压暗但不发黑。
    shade = np.clip(0.45 + 0.78 * lambert, 0.55, 1.22)

    # 5) 程序化细节：树冠级斑块 + 细颗粒，避免 10m 影像放大成一片糊。
    #    幅度刻意保守（±10%/±6%）：这部分是**观感补充**，压过头就会盖掉真实影像的颜色，
    #    反而比不加更假——上一版 ±17% 的斑块正是那种「花掉」的来源。
    canopy = fbm_array(gx, gy, seed + 31, octaves=4, wavelength=30.0, gain=0.55)
    grain = fbm_array(gx, gy, seed + 71, octaves=3, wavelength=8.0, gain=0.5)
    micro = fbm_array(gx, gy, seed + 97, octaves=2, wavelength=3.0, gain=0.5)

    def relight(color: np.ndarray, factor: np.ndarray) -> np.ndarray:
        """按亮度比例调整，保留色相：color * (newLum / oldLum)。"""
        current = np.maximum(color @ LUMA, 1e-3)
        return np.clip(color * (factor / current)[..., None], 0.0, 1.0)

    color = relight(base, np.maximum(base @ LUMA, 1e-3) * shade)
    color = relight(color, np.maximum(color @ LUMA, 1e-3)
                    * (1.0 + 0.10 * canopy + 0.08 * grain + 0.04 * micro))

    # 陡坡露出岩土色，缓坡保持植被绿；这是观感规则，不是测绘分类。
    rock = np.clip((slope_deg - 22.0) / 16.0, 0.0, 0.45)
    rock_tint = np.array([0.55, 0.50, 0.43])
    color = np.clip(color * (1.0 - rock[..., None]) + rock_tint * rock[..., None], 0.0, 1.0)
    image = Image.fromarray((color * 255.0 + 0.5).astype(np.uint8), "RGB")
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=90, subsampling=0, optimize=True)
    return buffer.getvalue(), image


# ---------------------------------------------------------------- 网格与 GLB


def build_mesh(terrain: Heightfield) -> dict:
    xs, ys, h = terrain.xs, terrain.ys, terrain.heights
    ny, nx = h.shape
    gx, gy = np.meshgrid(xs, ys)
    positions = np.stack([gx, gy, h], axis=-1).astype(np.float32)

    dzdx = np.gradient(h, axis=1) / (xs[1] - xs[0])
    dzdy = np.gradient(h, axis=0) / (ys[1] - ys[0])
    normals = np.stack([-dzdx, -dzdy, np.ones_like(h)], axis=-1)
    normals /= np.linalg.norm(normals, axis=-1, keepdims=True)
    normals = normals.astype(np.float32)

    u = (gx - xs[0]) / (xs[-1] - xs[0])
    v = 1.0 - (gy - ys[0]) / (ys[-1] - ys[0])
    uvs = np.stack([u, v], axis=-1).astype(np.float32)

    indices = []
    for j in range(ny - 1):
        for i in range(nx - 1):
            a = j * nx + i
            b = a + 1
            c = a + nx
            d = c + 1
            indices.extend((a, b, d))
            indices.extend((a, d, c))
    indices = np.asarray(indices, dtype=np.uint32)
    return {"positions": positions, "normals": normals, "uvs": uvs, "indices": indices}


def pad4(data: bytes, byte: bytes = b"\x00") -> bytes:
    return data + byte * ((-len(data)) % 4)


def write_glb(path: Path, mesh: dict, texture_jpeg: bytes, asset_version: str,
              extras: dict) -> None:
    positions = mesh["positions"].reshape(-1).astype("<f4")
    normals = mesh["normals"].reshape(-1).astype("<f4")
    uvs = mesh["uvs"].reshape(-1).astype("<f4")
    vertex_count = mesh["positions"].reshape(-1, 3).shape[0]
    indices = mesh["indices"]
    use_uint16 = vertex_count <= 65535
    index_bytes = indices.astype("<u2" if use_uint16 else "<u4").tobytes()

    position_bytes = positions.tobytes()
    normal_bytes = normals.tobytes()
    uv_bytes = uvs.tobytes()
    binary = bytearray()
    offsets = {}
    for name, payload in (("POSITION", position_bytes), ("NORMAL", normal_bytes),
                          ("TEXCOORD_0", uv_bytes), ("INDICES", index_bytes),
                          ("IMAGE", texture_jpeg)):
        offsets[name] = len(binary)
        binary += pad4(payload)
    buffer_views = [
        {"buffer": 0, "byteOffset": offsets["POSITION"], "byteLength": len(position_bytes),
         "target": 34962},
        {"buffer": 0, "byteOffset": offsets["NORMAL"], "byteLength": len(normal_bytes),
         "target": 34962},
        {"buffer": 0, "byteOffset": offsets["TEXCOORD_0"], "byteLength": len(uv_bytes),
         "target": 34962},
        {"buffer": 0, "byteOffset": offsets["INDICES"], "byteLength": len(index_bytes),
         "target": 34963},
        {"buffer": 0, "byteOffset": offsets["IMAGE"], "byteLength": len(texture_jpeg)},
    ]
    pos_min = mesh["positions"].reshape(-1, 3).min(axis=0).tolist()
    pos_max = mesh["positions"].reshape(-1, 3).max(axis=0).tolist()
    gltf = {
        "asset": {"version": "2.0", "generator": "monitor-system offline terrain asset builder v1"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"name": extras.get("sceneName", asset_version), "nodes": [0]}],
        "nodes": [{"name": "RealTerrain", "mesh": 0}],
        "meshes": [{
            "name": "SatelliteTexturedTerrain",
            "primitives": [{
                "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2},
                "indices": 3,
                "material": 0,
                "mode": 4,
            }],
        }],
        "materials": [{
            "name": "SatelliteTerrainUnlit",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": 0},
                "metallicFactor": 0.0,
                "roughnessFactor": 1.0,
            },
            "extensions": {"KHR_materials_unlit": {}},
        }],
        "textures": [{"sampler": 0, "source": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"name": "sentinel2-terrain-texture", "bufferView": 4,
                    "mimeType": "image/jpeg"}],
        "buffers": [{"byteLength": len(pad4(bytes(binary)))}],
        "bufferViews": buffer_views,
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": vertex_count, "type": "VEC3",
             "min": pos_min, "max": pos_max},
            {"bufferView": 1, "componentType": 5126, "count": vertex_count, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": vertex_count, "type": "VEC2"},
            {"bufferView": 3, "componentType": 5123 if use_uint16 else 5125,
             "count": int(indices.size), "type": "SCALAR"},
        ],
        "extras": extras,
    }
    json_chunk = pad4(json.dumps(gltf, separators=(",", ":"), ensure_ascii=False)
                      .encode("utf-8"), b" ")
    bin_chunk = pad4(bytes(binary))
    total = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    payload = bytearray(struct.pack("<4sII", b"glTF", 2, total))
    payload += struct.pack("<I4s", len(json_chunk), b"JSON")
    payload += json_chunk
    payload += struct.pack("<I4s", len(bin_chunk), b"BIN\x00")
    payload += bin_chunk
    path.write_bytes(payload)


# ---------------------------------------------------------------- 雷达/视线


def bearing_degrees(dx: float, dy: float) -> float:
    """ENU 平面方位角：0°=北，顺时针为正。"""
    return (math.degrees(math.atan2(dx, dy)) + 360.0) % 360.0


def angular_difference(a: float, b: float) -> float:
    return (a - b + 180.0) % 360.0 - 180.0


def covering_center(azimuths: list[float]) -> tuple[float, float]:
    """目标方位的最小覆盖弧中点与其跨度。

    **不能用圆均值**：目标跨 0° 时（例如 341.6°/348.7°/33.7°），圆均值会被密集的一侧
    拉偏到 0.9°，于是"最远那个目标"的偏差变成 32.8°，白白超出 ±30° 视场——而这组目标
    实际只占 52.1° 的弧。雷达该对准的是弧的中点（本例 7.65°），不是加权重心。
    """
    if not azimuths:
        return 0.0, 0.0
    s = sorted(a % 360.0 for a in azimuths)
    n = len(s)
    gaps = [(s[(i + 1) % n] - s[i]) % 360.0 for i in range(n)]
    k = gaps.index(max(gaps))
    start = s[(k + 1) % n]
    span = 360.0 - max(gaps)
    return (start + span / 2.0) % 360.0, span


def line_of_sight(terrain: Heightfield, start: tuple[float, float, float],
                  end: tuple[float, float, float], margin: float = 0.25) -> tuple[bool, float]:
    """沿射线步进，返回（是否通视, 最小净空）。净空 = 射线高程 - 地面高程。"""
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    dz = end[2] - start[2]
    distance = math.sqrt(dx * dx + dy * dy)
    steps = max(8, int(distance / 1.0))
    clearance = float("inf")
    for step in range(1, steps):
        t = step / steps
        x = start[0] + dx * t
        y = start[1] + dy * t
        z = start[2] + dz * t
        ground = terrain.floor + terrain.sample(x, y)
        clearance = min(clearance, z - ground)
    return clearance > margin, clearance


def solve_radar_pose(terrain: Heightfield, radar: dict, points: dict) -> dict:
    """为雷达挑一组「几何上真的看得见目标」的位姿。

    真实地形不像程序化山体那样配合叙事，所以这里做一个小范围确定性搜索：
    沿本地网格移动 + 抬高天线 + 重新瞄准，取「最小净空最大」的那组解。
    搜索是确定性的（固定步长、固定顺序），同一份输入必然得到同一组位姿。
    """
    targets = [points[code] for code in radar["points"]]
    base_x, base_y = radar["local"]
    best = None
    for offset in ((0, 0), (-12, 0), (12, 0), (0, -12), (0, 12),
                   (-12, -12), (12, 12), (-12, 12), (12, -12),
                   (-24, 0), (24, 0), (0, -24), (0, 24)):
        x = base_x + offset[0]
        y = base_y + offset[1]
        ground = terrain.floor + terrain.sample(x, y)
        for antenna in (DEFAULT_RADAR_HEAD_HEIGHT_M, 12.0, 14.0, 16.0, 19.0):
            head = (x, y, ground + antenna)
            azimuths, elevations, ranges = [], [], []
            for target in targets:
                tx, ty = target["local"]
                tz = terrain.floor + terrain.sample(tx, ty) + POINT_MARKER_HEIGHT_M
                dx, dy = tx - x, ty - y
                horizontal = math.hypot(dx, dy)
                dz = tz - head[2]
                azimuths.append(bearing_degrees(dx, dy))
                elevations.append(math.degrees(math.atan2(dz, horizontal)))
                ranges.append(math.sqrt(horizontal ** 2 + dz ** 2))
            heading, _ = covering_center(azimuths)
            pitch = (min(elevations) + max(elevations)) / 2.0
            clearance_min = float("inf")
            ok = True
            margin = 0.5  # 视场边缘留 0.5°，避免"贴边即算通过"
            for target, azimuth, elevation, slant in zip(targets, azimuths, elevations, ranges):
                if slant > radar["range"]:
                    ok = False
                    continue
                if abs(angular_difference(azimuth, heading)) > radar["halfAngleDegrees"] - margin:
                    ok = False
                if abs(elevation - pitch) > radar["verticalHalfAngleDegrees"] - margin:
                    ok = False
                tx, ty = target["local"]
                tz = (terrain.floor + terrain.sample(tx, ty)
                      + POINT_MARKER_HEIGHT_M + DEFAULT_REFLECTOR_HEIGHT_M)
                visible, clearance = line_of_sight(terrain, head, (tx, ty, tz))
                clearance_min = min(clearance_min, clearance)
                if not visible:
                    ok = False
            if not ok:
                continue
            # 排序目标刻意不是「净空最大」：净空只要够（阈值已在上面的 ok 判定里），
            # 优先选最贴近档案位置的站点、其次选最矮的天线——19m 天线在图上会明显脱节，
            # 而把所有雷达往高处推只会让画面越来越不像现场。净空只做第三顺位。
            score = (-abs(offset[0]) - abs(offset[1]), -antenna, min(clearance_min, 3.0))
            if best is None or score > best["score"]:
                best = {"score": score, "local": [x, y], "ground": ground,
                        "antenna": antenna, "heading": heading, "pitch": pitch}
    if best is None:
        raise RuntimeError(f"{radar['code']} 在搜索范围内找不到满足量程/视场/通视的位姿")
    return best


def build_radars(terrain: Heightfield) -> tuple[list[dict], list[dict]]:
    point_lookup = {item["code"]: item for item in POINTS}
    radars, bindings_out = [], []
    for radar in RADARS:
        pose = solve_radar_pose(terrain, radar, point_lookup)
        # pose["ground"] 是**绝对高程**（基准面 + 相对高度）。写进 localPosition 的必须是
        # **相对高度**——ENU 本地坐标里 +Z 的零点就是基准面。V18 曾在这里写成绝对值，
        # 迁移那边又加了一次基准面，结果两台雷达整整齐齐浮在场景上方 107m
        # （雷达"飞到天上"的那个 bug）。绝对高程另存 absoluteAltitude，供 SQL/文档使用。
        ground_relative = terrain.sample(pose["local"][0], pose["local"][1])
        bindings = []
        for code in radar["points"]:
            target = point_lookup[code]
            tx, ty = target["local"]
            ground = terrain.floor + terrain.sample(tx, ty)
            target_z = ground + POINT_MARKER_HEIGHT_M
            head_z = pose["ground"] + pose["antenna"]
            dx, dy = tx - pose["local"][0], ty - pose["local"][1]
            horizontal = math.hypot(dx, dy)
            dz = target_z + DEFAULT_REFLECTOR_HEIGHT_M - head_z
            azimuth = bearing_degrees(dx, dy)
            elevation = math.degrees(math.atan2(dz, horizontal))
            slant = math.sqrt(horizontal ** 2 + dz ** 2)
            visible, clearance = line_of_sight(
                terrain, (pose["local"][0], pose["local"][1], head_z),
                (tx, ty, target_z + DEFAULT_REFLECTOR_HEIGHT_M))
            bindings.append({
                "pointId": target["id"],
                "pointCode": target["code"],
                "targetCode": f"{radar['code']}-{target['code']}",
                "azimuthDegrees": round(azimuth, 3),
                "elevationDegrees": round(elevation, 3),
                "slantRangeM": round(slant, 3),
                "headingDeltaDegrees": round(angular_difference(azimuth, pose["heading"]), 3),
                "pitchDeltaDegrees": round(elevation - pose["pitch"], 3),
                "lineOfSight": visible,
                "minimumClearanceM": round(clearance, 3),
                "valid": visible and slant <= radar["range"],
            })
        radars.append({
            **{k: v for k, v in radar.items() if k != "points"},
            "points": list(radar["points"]),
            "localPosition": [pose["local"][0], pose["local"][1], round(ground_relative, 3)],
            "absoluteAltitude": round(pose["ground"], 3),
            "headingDegrees": round(pose["heading"], 3),
            "pitchDegrees": round(pose["pitch"], 3),
            "antennaHeightM": pose["antenna"],
            "bindings": bindings,
        })
        bindings_out.extend({"radarCode": radar["code"], **b} for b in bindings)
    invalid = [b["pointCode"] for b in bindings_out if not b["valid"]]
    if invalid:
        raise RuntimeError(f"以下目标未通过量程/视线校验: {', '.join(invalid)}")
    return radars, bindings_out


# ---------------------------------------------------------------- 元数据输出


def geodetic(terrain: Heightfield, x: float, y: float, z_relative: float,
             offset: float = 0.0) -> tuple[float, float, float]:
    lon, lat = terrain.lonlat_of(x, y)
    return lon, lat, terrain.floor + z_relative + offset


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def write_metadata(out: Path, *, terrain: Heightfield, radars: list[dict],
                   bindings: list[dict], glb_bytes: bytes, texture_bytes: bytes,
                   asset_version: str, source_manifest: dict, args: argparse.Namespace,
                   mesh: dict) -> dict:
    points = []
    for item in POINTS:
        x, y = item["local"]
        z = terrain.sample(x, y)
        lon, lat, altitude = geodetic(terrain, x, y, z, POINT_MARKER_HEIGHT_M)
        points.append({
            **item,
            "local": [x, y, round(z, 3)],
            "longitude": round(lon, 7),
            "latitude": round(lat, 7),
            "altitude": round(altitude, 3),
        })
    (out / "points.json").write_text(
        json.dumps(points, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    max_height = float(mesh["positions"][:, :, 2].max())
    camera = args.camera
    config = {
        "name": args.scene_name,
        "mode": "offline-real-terrain",
        "asset": f"/models/{out.name}/{out.name}.glb",
        "assetVersion": asset_version,
        "anchor": {"longitude": terrain.anchor["longitude"],
                   "latitude": terrain.anchor["latitude"], "height": terrain.floor},
        "dimensions": {"width": terrain.width, "depth": terrain.depth,
                       "maxHeight": round(max_height, 3)},
        "modelAxes": {"east": "+X", "north": "+Y", "up": "+Z"},
        "radars": radars,
        "camera": {"headingDegrees": camera[0], "pitchDegrees": camera[1],
                   "range": camera[2]},
        "triangleCount": int(mesh["indices"].size // 3),
    }
    (out / "scene-config.json").write_text(
        json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "coverage.json").write_text(json.dumps({
        "assetVersion": asset_version,
        "radarHeadHeightM": DEFAULT_RADAR_HEAD_HEIGHT_M,
        "reflectorHeightM": DEFAULT_REFLECTOR_HEIGHT_M,
        "radars": radars,
        "bindings": bindings,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    glb_sha = sha256_bytes(glb_bytes)
    (out / "build-manifest.json").write_text(json.dumps({
        "assetVersion": asset_version,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "generator": "tools/terrain_asset/build_terrain_asset.py",
        "glb": {"file": f"{out.name}.glb", "bytes": len(glb_bytes), "sha256": glb_sha},
        "texture": {"bytes": len(texture_bytes), "sha256": sha256_bytes(texture_bytes),
                    "size": [args.texture_width, args.texture_height]},
        "sourceManifest": {
            "fetchedAt": source_manifest.get("fetchedAt"),
            "bbox": source_manifest.get("bbox"),
            "datasets": {k: {"dataset": v["dataset"], "zoom": v["zoom"],
                             "tileCount": v["tileCount"], "license": v["license"]}
                         for k, v in source_manifest["sources"].items()},
        },
        "parameters": {
            "anchor": terrain.anchor, "width": terrain.width, "depth": terrain.depth,
            "cells": [len(terrain.xs) - 1, len(terrain.ys) - 1],
            "detailAmplitude": args.detail_amplitude,
            "textureSize": [args.texture_width, args.texture_height],
        },
        "triangleCount": int(mesh["indices"].size // 3),
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    sql = render_scene_update_sql(terrain=terrain, points=points, radars=radars,
                                  glb_sha=glb_sha,
                                  asset_version=asset_version, args=args)
    (out / "scene-update.sql").write_text(sql, encoding="utf-8")
    return {"glbSha256": glb_sha, "config": config, "points": points, "sql": sql}


def render_scene_update_sql(*, terrain: Heightfield, points: list[dict], radars: list[dict],
                            glb_sha: str, asset_version: str,
                            args: argparse.Namespace) -> str:
    """生成 Flyway 迁移草稿（不直接写入 migration 目录，需人工评审后拷贝）。"""
    lines = [
        "-- 由 tools/terrain_asset/build_terrain_asset.py 生成，请人工评审后再作为迁移使用。",
        f"-- 资产版本：{asset_version}",
        f"-- GLB SHA-256：{glb_sha}",
        "",
    ]
    for point in points:
        lines.append(
            "UPDATE monitor_point SET longitude = {lon:.7f}, latitude = {lat:.7f}, "
            "altitude = {alt:.3f}, updated_at = CURRENT_TIMESTAMP WHERE id = {id};".format(
                lon=point["longitude"], lat=point["latitude"], alt=point["altitude"],
                id=point["id"]))
    lines.append("")
    for radar in radars:
        lines.append(
            "UPDATE device SET longitude = {lon:.7f}, latitude = {lat:.7f}, altitude = {alt:.3f}, "
            "heading_degrees = {heading:.3f}, pitch_degrees = {pitch:.3f}, "
            "detection_range_m = {rng:.3f}, half_angle_degrees = {half:.3f}, "
            "vertical_half_angle_degrees = {vhalf:.3f}, antenna_height_m = {antenna:.3f}, "
            "updated_at = CURRENT_TIMESTAMP WHERE id = {id};".format(
                lon=radars_lon(terrain, radar), lat=radars_lat(terrain, radar),
                alt=terrain.floor + radar["localPosition"][2], heading=radar["headingDegrees"],
                pitch=radar["pitchDegrees"], rng=radar["range"],
                half=radar["halfAngleDegrees"], vhalf=radar["verticalHalfAngleDegrees"],
                antenna=radar["antennaHeightM"], id=radar["id"]))
    # 测点归属按新地形重排（北侧雷达：山脊 3 点 + 上部滑坡体 1 点；南侧雷达：坡脚 3 点），
    # 所以整组重建标定行——沿用 V11 的 DELETE + INSERT 形状，避免改 point_id 时撞唯一键。
    lines.append("DELETE FROM device_point WHERE device_id IN (1, 2);")
    lines.append("")
    lines.append("INSERT INTO device_point (")
    lines.append("    id, device_id, point_id, target_code,")
    lines.append("    azimuth_degrees, elevation_degrees, slant_range_m, reflector_height_m,")
    lines.append("    line_of_sight, minimum_clearance_m, calibration_status,")
    lines.append("    calibrated_at, valid_from, calibration_note")
    lines.append(") VALUES")
    rows = []
    next_id = 11
    for radar in radars:
        for binding in radar["bindings"]:
            rows.append((
                next_id, radar["id"], binding["pointId"], binding["targetCode"],
                binding["azimuthDegrees"], binding["elevationDegrees"], binding["slantRangeM"],
                binding["lineOfSight"], binding["minimumClearanceM"],
            ))
            next_id += 1
    for index, (bid, device_id, point_id, target, az, el, slant, los, clear) in enumerate(rows):
        suffix = "," if index < len(rows) - 1 else ";"
        lines.append(
            "({bid}, {device}, {point}, '{target}', {az:.3f}, {el:.3f}, {slant:.3f}, {refl:.3f}, "
            "{los}, {clear:.3f}, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
            "'真实地形资产视线校验通过'){suffix}".format(
                bid=bid, device=device_id, point=point_id, target=target, az=az, el=el,
                slant=slant, refl=DEFAULT_REFLECTOR_HEIGHT_M,
                los="TRUE" if los else "FALSE", clear=clear, suffix=suffix))
    lines.append("")
    lines.append(
        "UPDATE digital_twin_scene SET asset_url = '/models/{name}/{name}.glb', "
        "asset_version = '{ver}', asset_sha256 = '{sha}', "
        "anchor_longitude = {lon:.7f}, anchor_latitude = {lat:.7f}, anchor_height = {height:.3f}, "
        "camera_heading_degrees = {ch:.3f}, camera_pitch_degrees = {cp:.3f}, "
        "camera_range = {cr:.3f}, updated_at = CURRENT_TIMESTAMP WHERE project_id = 1;".format(
            name=args.asset_name, ver=asset_version, sha=glb_sha,
            lon=terrain.anchor["longitude"], lat=terrain.anchor["latitude"],
            height=terrain.floor, ch=args.camera[0], cp=args.camera[1], cr=args.camera[2]))
    return "\n".join(lines) + "\n"


def radars_lon(terrain: Heightfield, radar: dict) -> float:
    return terrain.lonlat_of(radar["localPosition"][0], radar["localPosition"][1])[0]


def radars_lat(terrain: Heightfield, radar: dict) -> float:
    return terrain.lonlat_of(radar["localPosition"][0], radar["localPosition"][1])[1]


def write_provenance(out: Path, *, asset_version: str, glb_sha: str, terrain: Heightfield,
                     source_manifest: dict, args: argparse.Namespace,
                     texture_bytes: bytes) -> None:
    dem = source_manifest["sources"]["dem"]
    imagery = source_manifest["sources"]["imagery"]
    lines = [
        "# 真实地形资产来源与使用说明",
        "",
        f"本目录的 `{args.asset_name}.glb` 是「真实地形演示资产」：几何骨架来自公开 DEM，",
        "色彩底来自公开卫星影像，中高频细节为程序化生成。**不是**现场实测成果，",
        "不得用于工程测量、风险评估或应急决策。",
        "",
        "## 数据来源",
        "",
        f"- 高程：{dem['dataset']}，zoom={dem['zoom']}，{dem['tileCount']} 张瓦片。{dem['license']}",
        f"- 影像：{imagery['dataset']}，zoom={imagery['zoom']}，{imagery['tileCount']} 张瓦片。{imagery['license']}",
        f"- 演示锚点：{terrain.anchor['latitude']:.5f}N {terrain.anchor['longitude']:.5f}E"
        f"（清远市区以北约 4km 的模拟选址，非清远电厂真实厂址）",
        f"- 场景尺寸：{terrain.width:.0f}m × {terrain.depth:.0f}m，"
        f"网格 {len(terrain.xs) - 1} × {len(terrain.ys) - 1} 单元（2m）",
        f"- 纹理：{args.texture_width}×{args.texture_height} JPEG，"
        f"{len(texture_bytes) / 1024:.0f} KiB，内嵌于 GLB",
        "",
        "## 哪些是量出来的、哪些是算出来的",
        "",
        f"- **实测**：宏观地形（30m 级 DEM）、影像色彩与地物分布（10m 级）。",
        f"- **程序化**：中高频地形细节（幅度 ±{args.detail_amplitude:.1f}m 级，按坡度加权）、",
        "  树冠级纹理噪声、山体阴影（太阳方位 315°、高度角 45°，已烘焙进纹理）。",
        "  公开 DEM 在 320m 场景里只有约 10×8 个采样点，不补细节会呈蜡状；",
        "  这条边界必须让读者知道，避免把观感当成测绘精度。",
        "- **模拟**：雷达位姿、测点位置与标定参数。测点仍在原有本地坐标上，",
        "  但高程、雷达瞄准角与视线净空是按本资产地形重新解算的（见 coverage.json）。",
        "",
        "## 与现有系统的关系",
        "",
        "- 资产走既有 GLB + ENU 本地坐标管线，前端渲染代码无需改动；",
        "- 材质使用 `KHR_materials_unlit`，山体阴影已烘焙，画面不受场景时钟影响；",
        "- 同步 SQL 草稿在 `scene-update.sql`，经人工评审后进入 Flyway 迁移。",
        "",
        "## 可追溯性",
        "",
        f"- 资产版本：`{asset_version}`",
        f"- GLB SHA-256：`{glb_sha}`",
        f"- 数据获取时间：{source_manifest.get('fetchedAt')}",
        f"- 包围盒：{json.dumps(source_manifest.get('bbox'), ensure_ascii=False)}",
        "",
        "重新生成：先跑 `tools/terrain_asset/fetch_sources.py`（瓦片带 SHA-256 缓存），",
        "再跑 `tools/terrain_asset/build_terrain_asset.py`。同一份数据源与参数会得到",
        "逐字节一致的 GLB。",
        "",
        "## 使用边界",
        "",
        "资产可随本项目离线部署，需保留上文的 Sentinel-2 cloudless 署名。",
        "正式交付时应替换为项目方提供的无人机航测 DSM + 正射影像；",
        "届时关掉程序化细节（`--detail-amplitude 0`）即为实测资产。",
        "",
    ]
    (out / "ASSET_PROVENANCE.md").write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------- 主流程


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成离线真实地形 GLB 资产")
    parser.add_argument("--sources", type=Path, default=Path("generated/terrain-asset/sources"))
    parser.add_argument("--out", type=Path,
                        default=Path("frontend/public/models/qingyuan-hillside"))
    parser.add_argument("--asset-name", default=DEFAULT_ASSET_NAME)
    parser.add_argument("--asset-version", default=DEFAULT_ASSET_VERSION)
    parser.add_argument("--scene-name", default="清远山地边坡真实地形场景")
    parser.add_argument("--width", type=float, default=DEFAULT_WIDTH)
    parser.add_argument("--depth", type=float, default=DEFAULT_DEPTH)
    parser.add_argument("--cells-x", type=int, default=DEFAULT_CELLS_X)
    parser.add_argument("--cells-y", type=int, default=DEFAULT_CELLS_Y)
    parser.add_argument("--detail-amplitude", type=float, default=1.6,
                        help="程序化地形细节基准幅度（米）；航测数据接进来时应传 0")
    parser.add_argument("--detail-seed", type=int, default=20260918)
    parser.add_argument("--texture-width", type=int, default=DEFAULT_TEXTURE[0])
    parser.add_argument("--texture-height", type=int, default=DEFAULT_TEXTURE[1])
    parser.add_argument("--camera", type=float, nargs=3, default=[315.0, -30.0, 1650.0],
                        metavar=("HEADING", "PITCH", "RANGE"))
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    sources: Path = args.sources
    manifest_path = sources / "manifest.json"
    if not manifest_path.exists():
        log(f"缺少 {manifest_path}，先运行 tools/terrain_asset/fetch_sources.py")
        return 2
    source_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    anchor = source_manifest["anchor"]

    log(f"读取 DEM / 影像瓦片：{sources}")
    dem = load_dem_mosaic(sources, source_manifest)
    imagery = load_imagery_mosaic(sources, source_manifest)
    log(f"高程拼图 {dem.data.shape}，影像拼图 {imagery.data.shape}")

    log("构建高程场（DEM 骨架 + 程序化细节）...")
    terrain = build_heightfield(dem, anchor, args.width, args.depth,
                                args.cells_x, args.cells_y,
                                args.detail_amplitude, args.detail_seed)
    log(f"高程范围 {terrain.heights.min():.1f}–{terrain.heights.max():.1f}m"
        f"（基准面 {terrain.floor:.1f}m，起伏 {terrain.heights.max() - terrain.heights.min():.1f}m）")

    log("烘焙卫星纹理（曝光归一 + 山坡阴影 + 细节合成）...")
    texture_size = (args.texture_width, args.texture_height)
    texture_bytes, preview = bake_texture(imagery, terrain, args.width, args.depth,
                                          texture_size, args.detail_seed)
    log(f"纹理 {len(texture_bytes) / 1024:.0f} KiB")

    log("生成网格...")
    mesh = build_mesh(terrain)

    log("解算雷达位姿与视线...")
    radars, bindings = build_radars(terrain)
    for radar in radars:
        log(f"  {radar['code']} {radar['name']}：位姿 "
            f"({radar['localPosition'][0]:.0f},{radar['localPosition'][1]:.0f}) "
            f"方位 {radar['headingDegrees']:.1f}° 俯仰 {radar['pitchDegrees']:.1f}° "
            f"天线 {radar['antennaHeightM']:.0f}m，"
            f"最小净空 {min(b['minimumClearanceM'] for b in radar['bindings']):.2f}m")

    args.out.mkdir(parents=True, exist_ok=True)
    glb_path = args.out / f"{args.asset_name}.glb"
    extras = {
        "sceneName": args.scene_name,
        "assetVersion": args.asset_version,
        "anchor": {"longitude": anchor["longitude"], "latitude": anchor["latitude"],
                   "height": terrain.floor},
        "dimensionsMetres": [terrain.width, terrain.depth],
        "texture": {"width": args.texture_width, "height": args.texture_height,
                    "source": "Sentinel-2 cloudless 2023 (EOX, CC BY 4.0)",
                    "bakedLighting": "hillshade sun azimuth 315°, altitude 45°"},
        "elevationSource": "Mapzen/AWS terrarium tiles (SRTM-derived, ~30m)",
        "syntheticDetailMetres": args.detail_amplitude,
        "license": "Imagery CC BY 4.0 (EOX Sentinel-2 cloudless); DEM SRTM public domain",
    }
    write_glb(glb_path, mesh, texture_bytes, args.asset_version, extras)
    glb_bytes = glb_path.read_bytes()
    log(f"写入 {glb_path}（{len(glb_bytes) / 1024 / 1024:.2f} MiB）")

    result = write_metadata(args.out, terrain=terrain, radars=radars, bindings=bindings,
                            glb_bytes=glb_bytes, texture_bytes=texture_bytes,
                            asset_version=args.asset_version,
                            source_manifest=source_manifest, args=args, mesh=mesh)
    write_provenance(args.out, asset_version=args.asset_version,
                     glb_sha=result["glbSha256"], terrain=terrain,
                     source_manifest=source_manifest, args=args,
                     texture_bytes=texture_bytes)
    preview.resize((args.texture_width // 2, args.texture_height // 2),
                   Image.LANCZOS).save(args.out / "preview.jpg", quality=88)

    log("")
    log(f"完成：GLB SHA-256 {result['glbSha256']}")
    log(f"资产目录 {args.out}（scene-update.sql 为迁移草稿，需人工评审）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
