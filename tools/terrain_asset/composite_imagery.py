"""把"外圈低清 + 中心高清"合成一张地形纹理（供 GLB 贴图使用）。

为什么需要它：外扩地形范围后，中心区（场址）要保留高清影像、外围只要"没有边"，
但 GLB 的一个材质只有一张纹理。做法是把外围影像放大成整张纹理，再把中心高清影像
按其在场址里的**相对位置**贴回去，并做色彩匹配 + 边缘羽化，避免两家/两级影像的接缝。

输入是 `tools/imagery_fetch/fetch_imagery.py` 的产物（`stitched.png` + manifest.json）。

用法示例（清远电厂 6000x4500 外圈 + 1000x750 中心）：
    python tools/terrain_asset/composite_imagery.py \
        --outer  <...>\\outer-google-z17\\stitched.png \
        --center <...>\\center-z19\\stitched.png \
        --outer-width 6000 --outer-depth 4500 \
        --center-width 1000 --center-depth 750 \
        --size 8192x6144 --out <...>\\composite-8192.jpg
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass


def parse_size(text: str) -> tuple[int, int]:
    width, _, height = text.lower().partition("x")
    return int(width), int(height)


def colour_match(source: np.ndarray, reference: np.ndarray) -> np.ndarray:
    """把 source 的三通道均值/标准差对齐到 reference（保留 source 自身对比）。"""
    out = source.astype(np.float32)
    for channel in range(3):
        src = out[:, :, channel]
        ref = reference[:, :, channel].astype(np.float32)
        src_std = float(src.std())
        ref_std = float(ref.std())
        scale = ref_std / src_std if src_std > 1e-6 else 1.0
        # 限幅，避免极端情况下把中心区对比拉爆
        scale = min(max(scale, 0.6), 1.6)
        out[:, :, channel] = (src - float(src.mean())) * scale + float(ref.mean())
    return np.clip(out, 0, 255).astype(np.uint8)


def main() -> int:
    parser = argparse.ArgumentParser(description="合成外圈+中心地形纹理")
    parser.add_argument("--outer", type=Path, required=True, help="外圈 stitched.png")
    parser.add_argument("--center", type=Path, required=True, help="中心 stitched.png")
    parser.add_argument("--outer-width", type=float, default=6000.0, help="外圈东西向米数")
    parser.add_argument("--outer-depth", type=float, default=4500.0, help="外圈南北向米数")
    parser.add_argument("--center-width", type=float, default=1000.0, help="中心东西向米数")
    parser.add_argument("--center-depth", type=float, default=750.0, help="中心南北向米数")
    parser.add_argument("--size", default="8192x6144", help="输出纹理尺寸，如 8192x6144")
    parser.add_argument("--feather", type=int, default=64, help="中心贴图边缘羽化像素")
    parser.add_argument("--match-colour", action="store_true", default=True)
    parser.add_argument("--no-match-colour", dest="match_colour", action="store_false")
    parser.add_argument("--quality", type=int, default=88, help="JPEG 质量")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--preview", type=Path, default=None, help="可选：写一张预览图")
    args = parser.parse_args()

    tex_w, tex_h = parse_size(args.size)
    outer = Image.open(args.outer).convert("RGB").resize((tex_w, tex_h), Image.LANCZOS)

    # 中心区在整张纹理里的位置（默认居中；按米数比例换算成像素）
    inner_w = max(1, round(tex_w * args.center_width / args.outer_width))
    inner_h = max(1, round(tex_h * args.center_depth / args.outer_depth))
    left = (tex_w - inner_w) // 2
    top = (tex_h - inner_h) // 2

    center = Image.open(args.center).convert("RGB").resize((inner_w, inner_h), Image.LANCZOS)

    if args.match_colour:
        reference = np.asarray(outer.crop((left, top, left + inner_w, top + inner_h)))
        matched = Image.fromarray(colour_match(np.asarray(center), reference))
    else:
        matched = center

    # 羽化：只在中心贴图边缘过渡，内部保持原样
    if args.feather > 0:
        mask = Image.new("L", (inner_w, inner_h), 255)
        border = min(args.feather, inner_w // 4, inner_h // 4)
        if border > 0:
            gradient = Image.new("L", (inner_w, inner_h), 0)
            ramp_h = Image.linear_gradient("L").resize((inner_w, border))
            ramp_v = Image.linear_gradient("L").rotate(90, expand=True).resize((border, inner_h))
            gradient.paste(ramp_h, (0, 0))
            gradient.paste(ramp_h.transpose(Image.FLIP_TOP_BOTTOM), (0, inner_h - border))
            gradient.paste(ramp_v, (0, 0))
            gradient.paste(ramp_v.transpose(Image.FLIP_LEFT_RIGHT), (inner_w - border, 0))
            mask = gradient.filter(ImageFilter.GaussianBlur(border / 4))
    else:
        mask = None

    merged = outer.copy()
    merged.paste(matched, (left, top), mask)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    merged.save(args.out, format="JPEG", quality=args.quality, subsampling=0, optimize=True)

    applied = "已色彩匹配" if args.match_colour else "未色彩匹配"
    print(f"{args.out}  {tex_w}x{tex_h}  中心贴图 {inner_w}x{inner_h}px "
          f"@({left},{top})  羽化 {args.feather}px  {applied}")
    print(f"  文件 {args.out.stat().st_size / 1e6:.2f} MB")

    if args.preview:
        merged.resize((min(1600, tex_w), round(min(1600, tex_w) * tex_h / tex_w)),
                      Image.LANCZOS).save(args.preview, quality=86)
        print(f"  预览 {args.preview}")

    (args.out.with_suffix(".json")).write_text(json.dumps({
        "generator": "tools/terrain_asset/composite_imagery.py",
        "outer": str(args.outer),
        "center": str(args.center),
        "outerMetres": {"width": args.outer_width, "depth": args.outer_depth},
        "centerMetres": {"width": args.center_width, "depth": args.center_depth},
        "texture": {"width": tex_w, "height": tex_h},
        "centerRectPx": {"left": left, "top": top, "width": inner_w, "height": inner_h},
        "featherPx": args.feather,
        "colourMatched": bool(args.match_colour),
        "metresPerPixel": round(args.outer_width / tex_w, 4),
        "centerMetresPerPixel": round(args.center_width / inner_w, 4),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
