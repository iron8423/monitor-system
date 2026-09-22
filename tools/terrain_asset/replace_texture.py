"""替换 GLB 里内嵌的贴图（不动几何），并重建 BIN 块。

比"直接覆盖最后一段字节"那种做法稳的地方：**不假设贴图是最后一个 bufferView**。
它按 GLB 规范解出 JSON/BIN 两个 chunk，把所有 bufferView 按原顺序重新排布到新的 BIN 里
（4 字节对齐），再把新贴图作为最后一个 bufferView 追加进去，最后更新长度字段。
几何 accessor 只引用 bufferView 索引 + 视图内偏移，所以重排后不需要改 accessor。

改完必须跑 `validate_glb.py`。

用法：
    python tools/terrain_asset/replace_texture.py \
        --glb <in.glb> --image <texture.jpg> --out <out.glb> --size 8192x6144
"""

import argparse
import io
import json
import struct
import sys
from pathlib import Path

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass


def parse_size(text: str) -> tuple[int, int]:
    width, _, height = text.lower().partition("x")
    return int(width), int(height)


def read_glb(path: Path) -> tuple[dict, bytes]:
    data = path.read_bytes()
    if data[:4] != b"glTF":
        raise SystemExit("不是 GLB 文件")
    offset = 12
    gltf = None
    binary = b""
    while offset + 8 <= len(data):
        chunk_len, chunk_type = struct.unpack("<I4s", data[offset:offset + 8])
        body = data[offset + 8:offset + 8 + chunk_len]
        if chunk_type == b"JSON":
            gltf = json.loads(body.decode("utf-8"))
        elif chunk_type.startswith(b"BIN"):
            binary = body
        offset += 8 + chunk_len
    if gltf is None:
        raise SystemExit("GLB 里没有 JSON chunk")
    return gltf, binary


def write_glb(path: Path, gltf: dict, binary: bytes) -> None:
    json_chunk = json.dumps(gltf, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    json_chunk += b" " * ((-len(json_chunk)) % 4)
    bin_chunk = binary + b"\x00" * ((-len(binary)) % 4)
    total = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    payload = bytearray(struct.pack("<4sII", b"glTF", 2, total))
    payload += struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
    payload += struct.pack("<I4s", len(bin_chunk), b"BIN\x00") + bin_chunk
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(payload))


def main() -> int:
    parser = argparse.ArgumentParser(description="替换 GLB 内嵌贴图")
    parser.add_argument("--glb", type=Path, required=True)
    parser.add_argument("--image", type=Path, required=True, help="新贴图（任意 PIL 支持的格式）")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--size", default="", help="写入的贴图尺寸，如 8192x6144（默认沿用原尺寸）")
    parser.add_argument("--quality", type=int, default=88)
    parser.add_argument("--image-index", type=int, default=-1,
                        help="要替换的 images 下标；默认取占用字节最大的那张（通常是地形纹理）")
    args = parser.parse_args()

    gltf, binary = read_glb(args.glb)
    images = gltf.get("images", [])
    views = gltf.get("bufferViews", [])
    if not images:
        raise SystemExit("GLB 里没有 images")

    if args.image_index >= 0:
        target = args.image_index
    else:
        target = max(range(len(images)), key=lambda i: views[images[i]["bufferView"]]["byteLength"])
    view_index = images[target].get("bufferView")
    if view_index is None:
        raise SystemExit(f"images[{target}] 没有内嵌 bufferView（可能是外部 URI，本脚本不支持）")

    size = parse_size(args.size) if args.size else None
    texture = Image.open(args.image).convert("RGB")
    if size:
        texture = texture.resize(size, Image.LANCZOS)
    buffer = io.BytesIO()
    texture.save(buffer, format="JPEG", quality=args.quality, subsampling=0, optimize=True)
    jpeg = buffer.getvalue()

    # 按原顺序重排所有 bufferView（跳过被替换的那张），新贴图放最后
    new_blob = bytearray()
    for index, view in enumerate(views):
        if index == view_index:
            continue
        start = view.get("byteOffset", 0)
        length = view.get("byteLength", 0)
        new_blob += b"\x00" * ((-len(new_blob)) % 4)
        view["byteOffset"] = len(new_blob)
        new_blob += binary[start:start + length]
    new_blob += b"\x00" * ((-len(new_blob)) % 4)
    views[view_index]["byteOffset"] = len(new_blob)
    views[view_index]["byteLength"] = len(jpeg)
    new_blob += jpeg

    images[target]["mimeType"] = "image/jpeg"
    gltf["buffers"][0]["byteLength"] = len(new_blob)
    extras = gltf.setdefault("extras", {})
    texture_info = extras.setdefault("texture", {})
    texture_info["width"], texture_info["height"] = texture.width, texture.height
    texture_info["source"] = args.image.name

    write_glb(args.out, gltf, bytes(new_blob))
    print(f"{args.out}  {args.out.stat().st_size / 1e6:.2f} MB  "
          f"替换 images[{target}] -> {texture.width}x{texture.height}  jpeg {len(jpeg) / 1024:.0f} KiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
