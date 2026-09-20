#!/usr/bin/env python3
"""控制点验证：拿一组**实测点**去量模型到底准不准（复查清单 P1-9）。

为什么需要它：平台此前没有任何"输入已知点 → 输出残差"的能力，"模型和现实对得上吗"
只能靠眼看。眼看不出两件事——锚点整体偏了 3m，和 DEM 在这片坡上系统性偏低 2m。
两者在屏幕上都只是"测点好像没踩在地形上"，而处置完全不同：前者改锚点，后者改高程基准。

本工具算三组量：

  ① **平面残差**（ΔE / ΔN）：实测经纬度 → ENU 本地坐标，与模型里该点的声明位置比。
     残差同号且量级接近 → 锚点整体偏了，工具直接给出锚点该往哪移多少。
  ② **高程残差**（ΔZ）：实测高程 − 模型地面高程（基准面 + 该点处的高程场采样）。
     30m 公开 DEM 在陡坡上有米级偏差是正常的，**量出来比"应该对"有用**——
     它是这个场景能用模型做高程判断的误差上界。
  ③ 上述两者的 RMS / 最大值 / 均值。

用的是后端那份高程场（`backend/src/main/resources/terrain/<资产版本>.bin`）而不是另算一遍：
要校核的是**平台实际在用的那份模型**。网格没有导出时（比如新资产刚生成）可以用
`--sources` 从 DEM 素材重建，两条路给出的是同一个高程场。

用法：

    # 1) 用示例控制点跑一遍（示例就是资产里声明的 7 个点，残差应当接近 0）
    python3 tools/twin_check/check_control_points.py \
        --asset frontend/public/models/qingyuan-hillside-v2 \
        --points tools/twin_check/samples/control-points-hillside.csv

    # 2) 现场实测数据（RTK / 全站仪）按同样的表头写一份 CSV 再跑
    #    表头：code,longitude,latitude,altitude    （多余列忽略，顺序不限）

    # 3) 自检：拿一组**已知偏移**的合成控制点验证这套数学（离线、秒级）
    python3 tools/twin_check/check_control_points.py --self-test

退出码：0 = 通过（残差在阈值内）；1 = 有超阈值的点；2 = 输入/环境问题。
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent

# 与生成器/后端同源的换算（不得重新发明一份）
sys.path.insert(0, str(REPO / "tools" / "terrain_asset"))


def metres_per_degree(lat_deg: float) -> tuple[float, float]:
    """与 build_terrain_asset.metres_per_degree 同一套公式（WGS84 椭球，锚点处）。"""
    a = 6378137.0
    e2 = 6.6943799901413165e-3
    sin_lat = math.sin(math.radians(lat_deg))
    n = a / math.sqrt(1.0 - e2 * sin_lat * sin_lat)
    m = a * (1.0 - e2) / (1.0 - e2 * sin_lat * sin_lat) ** 1.5
    return math.pi / 180.0 * n * math.cos(math.radians(lat_deg)), math.pi / 180.0 * m


@dataclass
class Heightfield:
    """后端通视计算用的同一份高程场（float32 网格 + 元数据）。"""

    asset_version: str
    anchor_lon: float
    anchor_lat: float
    floor: float
    width: float
    depth: float
    m_per_lon: float
    m_per_lat: float
    cells_x: int
    cells_y: int
    grid: np.ndarray
    source: str

    def x_of(self, lon: float) -> float:
        return (lon - self.anchor_lon) * self.m_per_lon

    def y_of(self, lat: float) -> float:
        return (lat - self.anchor_lat) * self.m_per_lat

    def lon_of(self, x: float) -> float:
        return self.anchor_lon + x / self.m_per_lon

    def lat_of(self, y: float) -> float:
        return self.anchor_lat + y / self.m_per_lat

    def covers(self, x: float, y: float) -> bool:
        return (abs(x) <= self.width / 2.0 + 1e-9) and (abs(y) <= self.depth / 2.0 + 1e-9)

    def sample(self, x: float, y: float) -> float:
        """相对基准面的双线性采样（与后端 TerrainHeightfield#sample 同一口径）。"""
        i = (x + self.width / 2.0) / (self.width / self.cells_x)
        j = (y + self.depth / 2.0) / (self.depth / self.cells_y)
        i = min(max(i, 0.0), self.cells_x - 0.001)
        j = min(max(j, 0.0), self.cells_y - 0.001)
        i0, j0 = int(math.floor(i)), int(math.floor(j))
        fx, fy = i - i0, j - j0
        g = self.grid
        return float(
            (g[j0, i0] * (1 - fx) + g[j0, i0 + 1] * fx) * (1 - fy)
            + (g[j0 + 1, i0] * (1 - fx) + g[j0 + 1, i0 + 1] * fx) * fy
        )

    def ground_altitude(self, x: float, y: float) -> float:
        return self.floor + self.sample(x, y)


def load_exported_heightfield(terrain_dir: Path, asset_version: str) -> Heightfield:
    meta_path = terrain_dir / f"{asset_version}.json"
    if not meta_path.exists():
        raise FileNotFoundError(
            f"没有找到后端高程场 {meta_path}；先跑 tools/terrain_asset/export_heightfield.py，"
            "或给本工具加 --sources 直接从 DEM 素材重建")
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    grid_path = terrain_dir / meta["grid"]["file"]
    payload = grid_path.read_bytes()
    actual = hashlib.sha256(payload).hexdigest()
    if actual != meta["grid"]["sha256"]:
        raise ValueError(
            f"{grid_path} 的 SHA-256 与 {meta_path.name} 记录的不一致："
            f"文件被人换过或写坏了（{actual[:12]}… vs {meta['grid']['sha256'][:12]}…）")
    cells_x, cells_y = int(meta["cellsX"]), int(meta["cellsY"])
    grid = np.frombuffer(payload, dtype="<f4").reshape(cells_y + 1, cells_x + 1).astype(np.float64)
    return Heightfield(
        asset_version=meta["assetVersion"],
        anchor_lon=meta["anchor"]["longitude"], anchor_lat=meta["anchor"]["latitude"],
        floor=meta["floor"], width=meta["width"], depth=meta["depth"],
        m_per_lon=meta["mPerLon"], m_per_lat=meta["mPerLat"],
        cells_x=cells_x, cells_y=cells_y, grid=grid, source=str(grid_path),
    )


def rebuild_heightfield(asset_dir: Path, sources: Path) -> Heightfield:
    """没有导出网格时，从 DEM 素材按资产参数重建（与导出脚本同一条路）。"""
    sys.path.insert(0, str(REPO / "tools" / "terrain_asset"))
    import build_terrain_asset as bta  # noqa: E402

    manifest = json.loads((asset_dir / "build-manifest.json").read_text(encoding="utf-8"))
    params = manifest["parameters"]
    source_manifest = json.loads((sources / "manifest.json").read_text(encoding="utf-8"))
    dem = bta.load_dem_mosaic(sources, source_manifest)
    terrain = bta.build_heightfield(
        dem, params["anchor"], params["width"], params["depth"],
        int(params["cells"][0]), int(params["cells"][1]),
        params["detailAmplitude"], 20260918)
    return Heightfield(
        asset_version=manifest["assetVersion"],
        anchor_lon=params["anchor"]["longitude"], anchor_lat=params["anchor"]["latitude"],
        floor=terrain.floor, width=params["width"], depth=params["depth"],
        m_per_lon=terrain.m_per_lon, m_per_lat=terrain.m_per_lat,
        cells_x=int(params["cells"][0]), cells_y=int(params["cells"][1]),
        grid=np.asarray(terrain.heights, dtype=np.float64),
        source=f"DEM 重建（{sources}）",
    )


@dataclass
class ControlPoint:
    code: str
    longitude: float
    latitude: float
    altitude: float
    note: str = ""


def read_control_points(path: Path) -> list[ControlPoint]:
    """读控制点 CSV：表头必须含 code/longitude/latitude/altitude（列顺序任意，多余列忽略）。"""
    rows: list[ControlPoint] = []
    with path.open(encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        header = [h.strip().lower() for h in (reader.fieldnames or [])]
        required = {"code", "longitude", "latitude"}
        missing = required - set(header)
        if missing:
            raise ValueError(f"{path} 缺少列：{', '.join(sorted(missing))}（表头：{header}）")
        for index, raw in enumerate(reader, start=2):
            row = {(k or "").strip().lower(): (v or "").strip() for k, v in raw.items()}
            if not any(row.values()):
                continue
            try:
                rows.append(ControlPoint(
                    code=row["code"],
                    longitude=float(row["longitude"]),
                    latitude=float(row["latitude"]),
                    # 高程可以留空：那这条只算平面残差，不参与 ΔZ 统计
                    altitude=float(row["altitude"]) if row.get("altitude") else math.nan,
                    note=row.get("note", ""),
                ))
            except (TypeError, ValueError) as exc:
                raise ValueError(f"{path} 第 {index} 行读不出数值：{raw}（{exc}）") from exc
    if not rows:
        raise ValueError(f"{path} 里没有任何控制点")
    return rows


@dataclass
class Residual:
    code: str
    declared_x: float | None
    declared_y: float | None
    measured_x: float
    measured_y: float
    delta_e: float | None
    delta_n: float | None
    model_z: float
    measured_z: float
    delta_z: float | None
    inside: bool
    note: str


def evaluate(field: Heightfield, points: list[ControlPoint],
             declared: dict[str, tuple[float, float]]) -> list[Residual]:
    out: list[Residual] = []
    for p in points:
        mx, my = field.x_of(p.longitude), field.y_of(p.latitude)
        model_z = field.ground_altitude(mx, my)
        inside = field.covers(mx, my)
        pair = declared.get(p.code)
        delta_e = mx - pair[0] if pair else None
        delta_n = my - pair[1] if pair else None
        delta_z = None if math.isnan(p.altitude) else p.altitude - model_z
        out.append(Residual(
            code=p.code,
            declared_x=pair[0] if pair else None, declared_y=pair[1] if pair else None,
            measured_x=mx, measured_y=my,
            delta_e=delta_e, delta_n=delta_n,
            model_z=model_z,
            measured_z=p.altitude,
            delta_z=delta_z,
            inside=inside,
            note=p.note,
        ))
    return out


def rms(values: list[float]) -> float:
    return math.sqrt(sum(v * v for v in values) / len(values)) if values else float("nan")


def summarise(residuals: list[Residual], field: Heightfield) -> dict:
    de = [r.delta_e for r in residuals if r.delta_e is not None]
    dn = [r.delta_n for r in residuals if r.delta_n is not None]
    dz = [r.delta_z for r in residuals if r.delta_z is not None]
    plane = [math.hypot(r.delta_e, r.delta_n) for r in residuals
             if r.delta_e is not None and r.delta_n is not None]
    summary: dict = {
        "points": len(residuals),
        "matched": len(de),
        "outsideModel": [r.code for r in residuals if not r.inside],
        "planeRmsM": rms(plane) if plane else None,
        "planeMaxM": max(plane) if plane else None,
        "heightRmsM": rms(dz) if dz else None,
        "heightMaxAbsM": max(abs(v) for v in dz) if dz else None,
        "meanDeltaEM": (sum(de) / len(de)) if de else None,
        "meanDeltaNM": (sum(dn) / len(dn)) if dn else None,
        "meanDeltaZM": (sum(dz) / len(dz)) if dz else None,
    }
    # 锚点建议：整体偏移量就是把锚点往残差均值的方向挪同样的米数
    if de and dn:
        shift_e = sum(de) / len(de)
        shift_n = sum(dn) / len(dn)
        if math.hypot(shift_e, shift_n) > 0.01:
            summary["anchorSuggestion"] = {
                "shiftEastM": shift_e,
                "shiftNorthM": shift_n,
                "suggestedLongitude": field.lon_of(shift_e),
                "suggestedLatitude": field.lat_of(shift_n),
                "currentLongitude": field.anchor_lon,
                "currentLatitude": field.anchor_lat,
            }
    return summary


def verdict_of(summary: dict, tol_plane: float, tol_height: float) -> tuple[str, list[str]]:
    problems: list[str] = []
    if summary["points"] == 0:
        problems.append("没有控制点")
    if summary["outsideModel"]:
        problems.append("落在模型范围外：" + "、".join(summary["outsideModel"]))
    if summary["planeRmsM"] is not None and summary["planeRmsM"] > tol_plane:
        problems.append(f"平面 RMS {summary['planeRmsM']:.3f}m 超过阈值 {tol_plane:.3f}m")
    if summary["heightRmsM"] is not None and summary["heightRmsM"] > tol_height:
        problems.append(f"高程 RMS {summary['heightRmsM']:.3f}m 超过阈值 {tol_height:.3f}m")
    if summary["matched"] < summary["points"]:
        problems.append(f"{summary['points'] - summary['matched']} 个控制点在资产里找不到同名测点"
                        "（只能算高程残差，算不了平面残差）")
    return ("PASS" if not problems else "FAIL"), problems


def render_report(*, field: Heightfield, residuals: list[Residual], summary: dict,
                  verdict: str, problems: list[str], tol_plane: float,
                  tol_height: float, source_file: str) -> str:
    lines: list[str] = []
    add = lines.append
    add(f"# 控制点验证报告 · {field.asset_version}")
    add("")
    add(f"- 控制点文件：`{source_file}`（{summary['points']} 个点，"
        f"其中 {summary['matched']} 个能在资产里按编号匹配到声明位置）")
    add(f"- 高程场：`{field.source}`（{field.cells_x}×{field.cells_y} 网格，"
        f"基准面 {field.floor:.3f}m）")
    add(f"- 锚点：({field.anchor_lon:.7f}, {field.anchor_lat:.7f})，"
        f"换算常数 mPerLon={field.m_per_lon:.3f} / mPerLat={field.m_per_lat:.3f}")
    add(f"- 判据：平面 RMS ≤ {tol_plane:.3f}m、高程 RMS ≤ {tol_height:.3f}m")
    add(f"- **结论：{verdict}**")
    if problems:
        add("")
        for p in problems:
            add(f"  - {p}")
    add("")
    add("## 逐点残差")
    add("")
    add("| 点号 | 声明位置 (E,N) | 实测位置 (E,N) | ΔE | ΔN | 模型地面 Z | 实测 Z | ΔZ |")
    add("|---|---|---|---|---|---|---|---|")
    for r in residuals:
        declared = ("—" if r.declared_x is None
                    else f"{r.declared_x:.3f}, {r.declared_y:.3f}")
        de = "—" if r.delta_e is None else f"{r.delta_e:+.3f}"
        dn = "—" if r.delta_n is None else f"{r.delta_n:+.3f}"
        mz = f"{r.model_z:.3f}"
        z = "—" if math.isnan(r.measured_z) else f"{r.measured_z:.3f}"
        dz = "—" if r.delta_z is None else f"{r.delta_z:+.3f}"
        flag = "" if r.inside else " ⚠范围外"
        add(f"| {r.code}{flag} | {declared} | {r.measured_x:.3f}, {r.measured_y:.3f} "
            f"| {de} | {dn} | {mz} | {z} | {dz} |")
    add("")
    add("## 汇总")
    add("")
    add("| 指标 | 值 |")
    add("|---|---|")
    add(f"| 平面 RMS | {fmt(summary['planeRmsM'])} m |")
    add(f"| 平面最大偏差 | {fmt(summary['planeMaxM'])} m |")
    add(f"| 高程 RMS | {fmt(summary['heightRmsM'])} m |")
    add(f"| 高程最大绝对偏差 | {fmt(summary['heightMaxAbsM'])} m |")
    add(f"| ΔE 均值 | {fmt(summary['meanDeltaEM'])} m |")
    add(f"| ΔN 均值 | {fmt(summary['meanDeltaNM'])} m |")
    add(f"| ΔZ 均值 | {fmt(summary['meanDeltaZM'])} m |")
    add("")
    if "anchorSuggestion" in summary:
        s = summary["anchorSuggestion"]
        add("## 系统偏移与锚点建议")
        add("")
        add(f"所有控制点的残差**同向**（ΔE 均值 {s['shiftEastM']:+.3f}m、"
            f"ΔN 均值 {s['shiftNorthM']:.3f}m），这不像随机测量误差，更像锚点整体偏了。"
            "若确认实测可信，可把场景锚点改成：")
        add("")
        add("```")
        add(f"longitude: {s['currentLongitude']:.7f} -> {s['suggestedLongitude']:.7f}")
        add(f"latitude : {s['currentLatitude']:.7f} -> {s['suggestedLatitude']:.7f}")
        add("```")
        add("")
        add("改锚点等于重配准：**已生效的雷达标定会全部失效**（位姿与视线的几何前提变了），"
            "必须先想清楚这一点再动。")
        add("")
    add("## 怎么读这份报告")
    add("")
    add("- **平面残差**量的是配准误差：锚点、投影常数、以及资产里声明的测点位置对不对。"
        "它是「模型与现实对不对得齐」的问题，不是地形本身的问题。")
    add("- **高程残差**量的是公开 DEM（30m 级）与实测地面的差。在陡坡上米级偏差是正常的，"
        "它的用途是给出「拿模型做高程判断」的误差上界，而不是判模型不合用。")
    add("- 控制点太少（<3）时残差均值没有统计意义，只看逐点值与最大值。")
    add("- 阈值是**判据不是真理**：平面 0.1m 对雷达形变监测是合理要求，"
        "高程 0.5m 是 30m DEM 在缓坡上的典型水平；换到航测 DSM（5cm 级）应当收紧到 0.05m。")
    return "\n".join(lines) + "\n"


def fmt(value: float | None) -> str:
    return "—" if value is None else f"{value:.4f}"


def declared_of(asset_dir: Path) -> dict[str, tuple[float, float]]:
    path = asset_dir / "points.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {item["code"]: (float(item["local"][0]), float(item["local"][1])) for item in data}


def resolve_field(args: argparse.Namespace, asset_dir: Path, asset_version: str) -> Heightfield:
    if args.heightfield.exists() and (args.heightfield / f"{asset_version}.json").exists():
        return load_exported_heightfield(args.heightfield, asset_version)
    if args.sources is not None:
        return rebuild_heightfield(asset_dir, args.sources)
    raise FileNotFoundError(
        f"{args.heightfield / (asset_version + '.json')} 不存在；"
        "跑一遍 tools/terrain_asset/export_heightfield.py，或用 --sources 指定 DEM 素材目录")


def run_once(args: argparse.Namespace) -> int:
    asset_dir: Path = args.asset
    scene = json.loads((asset_dir / "scene-config.json").read_text(encoding="utf-8"))
    asset_version = scene["assetVersion"]
    field = resolve_field(args, asset_dir, asset_version)
    control_points = read_control_points(args.points)
    residuals = evaluate(field, control_points, declared_of(asset_dir))
    summary = summarise(residuals, field)
    verdict, problems = verdict_of(summary, args.tol_plane, args.tol_height)
    report = render_report(field=field, residuals=residuals, summary=summary, verdict=verdict,
                           problems=problems, tol_plane=args.tol_plane,
                           tol_height=args.tol_height, source_file=str(args.points))
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(report, encoding="utf-8")
        print(f"报告已写出：{args.out}", flush=True)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(
            {"assetVersion": asset_version, "summary": summary, "verdict": verdict,
             "problems": problems,
             "residuals": [r.__dict__ for r in residuals]},
            ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"机器可读结果：{args.json}", flush=True)
    print(report, flush=True)
    if args.assert_rms is not None:
        planar = summary["planeRmsM"]
        if planar is None or planar > args.assert_rms:
            print(f"平面 RMS {fmt(planar)}m 超过 --assert-rms {args.assert_rms}m", file=sys.stderr)
            return 1
    return 0 if verdict == "PASS" else 1


def self_test() -> int:
    """用**已知偏移**的合成控制点验证这套数学：3.0m 东、-1.5m 北、+0.8m 高程。

    断言的是"工具能不能把它自己造出来的偏移原样量回来"——这是唯一能在没有实测数据时
    证明残差计算没写反、锚点建议方向没搞错的办法。
    """
    asset_dir = REPO / "frontend" / "public" / "models" / "qingyuan-hillside-v2"
    scene = json.loads((asset_dir / "scene-config.json").read_text(encoding="utf-8"))
    field = load_exported_heightfield(REPO / "backend" / "src" / "main" / "resources" / "terrain",
                                     scene["assetVersion"])
    declared = declared_of(asset_dir)
    offset_e, offset_n, offset_z = 3.0, -1.5, 0.8
    points: list[ControlPoint] = []
    for item in json.loads((asset_dir / "points.json").read_text(encoding="utf-8")):
        x, y = declared[item["code"]]
        points.append(ControlPoint(
            code=item["code"],
            longitude=field.lon_of(x + offset_e),
            latitude=field.lat_of(y + offset_n),
            altitude=field.ground_altitude(x + offset_e, y + offset_n) + offset_z,
        ))
    residuals = evaluate(field, points, declared)
    summary = summarise(residuals, field)

    failures: list[str] = []
    for r in residuals:
        if abs(r.delta_e - offset_e) > 1e-6:
            failures.append(f"{r.code} ΔE 应为 {offset_e}，实得 {r.delta_e}")
        if abs(r.delta_n - offset_n) > 1e-6:
            failures.append(f"{r.code} ΔN 应为 {offset_n}，实得 {r.delta_n}")
        if abs(r.delta_z - offset_z) > 1e-6:
            failures.append(f"{r.code} ΔZ 应为 {offset_z}，实得 {r.delta_z}")
    if abs(summary["meanDeltaEM"] - offset_e) > 1e-6:
        failures.append(f"ΔE 均值应为 {offset_e}，实得 {summary['meanDeltaEM']}")
    if abs(summary["meanDeltaNM"] - offset_n) > 1e-6:
        failures.append(f"ΔN 均值应为 {offset_n}，实得 {summary['meanDeltaNM']}")
    if abs(summary["meanDeltaZM"] - offset_z) > 1e-6:
        failures.append(f"ΔZ 均值应为 {offset_z}，实得 {summary['meanDeltaZM']}")
    if "anchorSuggestion" not in summary:
        failures.append("残差同向时必须给出锚点建议")
    else:
        s = summary["anchorSuggestion"]
        if abs(s["suggestedLongitude"] - field.lon_of(offset_e)) > 1e-9:
            failures.append("锚点经度建议算错了")
        if abs(s["suggestedLatitude"] - field.lat_of(offset_n)) > 1e-9:
            failures.append("锚点纬度建议算错了")
    # 平面 RMS 应当是偏移量的模长
    expected_rms = math.hypot(offset_e, offset_n)
    if abs(summary["planeRmsM"] - expected_rms) > 1e-6:
        failures.append(f"平面 RMS 应为 {expected_rms:.6f}，实得 {summary['planeRmsM']}")

    if failures:
        print("自检失败：", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print(f"自检通过：{len(residuals)} 个控制点的 ΔE/ΔN/ΔZ、均值、RMS 与锚点建议全部"
          f"复现出预设偏移（{offset_e}m 东 / {offset_n}m 北 / +{offset_z}m 高程），"
          f"平面 RMS {summary['planeRmsM']:.6f}m。")
    return 0


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="控制点验证：量出模型与实测的残差")
    parser.add_argument("--asset", type=Path,
                        default=Path("frontend/public/models/qingyuan-hillside-v2"))
    parser.add_argument("--points", type=Path, help="控制点 CSV：code,longitude,latitude,altitude[,note]")
    parser.add_argument("--heightfield", type=Path,
                        default=Path("backend/src/main/resources/terrain"))
    parser.add_argument("--sources", type=Path, default=None,
                        help="高程场没导出时，从这里（DEM 素材目录）重建")
    parser.add_argument("--out", type=Path, default=None, help="Markdown 报告落盘位置")
    parser.add_argument("--json", type=Path, default=None, help="机器可读结果落盘位置")
    parser.add_argument("--tol-plane", type=float, default=0.10, help="平面 RMS 阈值（米）")
    parser.add_argument("--tol-height", type=float, default=0.50, help="高程 RMS 阈值（米）")
    parser.add_argument("--assert-rms", type=float, default=None,
                        help="平面 RMS 超过这个值就以退出码 1 结束（给流水线用）")
    parser.add_argument("--self-test", action="store_true", help="用已知偏移的合成控制点自检")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.self_test:
        return self_test()
    if args.points is None:
        print("需要 --points（控制点 CSV），或用 --self-test 跑自检", file=sys.stderr)
        return 2
    try:
        return run_once(args)
    except (FileNotFoundError, ValueError) as exc:
        print(f"输入有问题：{exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
