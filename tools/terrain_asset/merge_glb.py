"""把多个 GLB 合并成一个（每个各成一个 primitive / material），坐标保持原样。

用途：把 CityGML 转出的建筑几何（citygml_to_glb.py 的产物）并进地形资产，
这样前端仍然是"一份资产"，不需要任何代码改动。

约定：输入 GLB 的坐标必须已经在同一套本地 ENU 坐标系里（见 citygml_to_glb.py 的说明）。

用法：
    python tools/terrain_asset/merge_glb.py \
      --base work/.../dixence-t2.glb \
      --add  work/.../buildings.glb \
      --out  work/.../plant-with-buildings.glb
"""

import argparse
import json
import struct
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass


def read_glb(path: Path) -> tuple[dict, bytes]:
    data = path.read_bytes()
    if data[:4] != b"glTF":
        raise SystemExit(f"{path} 不是 GLB")
    offset = 12
    gltf, binary = None, b""
    while offset + 8 <= len(data):
        chunk_len, chunk_type = struct.unpack("<I4s", data[offset:offset + 8])
        body = data[offset + 8:offset + 8 + chunk_len]
        if chunk_type == b"JSON":
            gltf = json.loads(body.decode("utf-8"))
        elif chunk_type.startswith(b"BIN"):
            binary = body
        offset += 8 + chunk_len
    if gltf is None:
        raise SystemExit(f"{path} 里没有 JSON chunk")
    return gltf, binary


def write_glb(path: Path, gltf: dict, binary: bytes) -> None:
    json_chunk = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    json_chunk += b" " * ((-len(json_chunk)) % 4)
    bin_chunk = binary + b"\x00" * ((-len(binary)) % 4)
    total = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    payload = bytearray(struct.pack("<4sII", b"glTF", 2, total))
    payload += struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
    payload += struct.pack("<I4s", len(bin_chunk), b"BIN\x00") + bin_chunk
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(payload))


def append_model(target: dict, target_bin: bytearray, source: dict, source_bin: bytes) -> None:
    """把 source 的 bufferViews/accessors/materials/meshes 追加进 target（索引整体后移）。"""
    view_offset = len(target["bufferViews"])
    accessor_offset = len(target["accessors"])
    material_offset = len(target.get("materials", []))
    base = len(target_bin)
    target_bin += b"\x00" * ((-len(target_bin)) % 4)
    base = len(target_bin)
    target_bin += source_bin

    for view in source.get("bufferViews", []):
        new_view = dict(view)
        new_view["buffer"] = 0
        new_view["byteOffset"] = view.get("byteOffset", 0) + base
        target["bufferViews"].append(new_view)
    for accessor in source.get("accessors", []):
        new_accessor = dict(accessor)
        new_accessor["bufferView"] = accessor["bufferView"] + view_offset
        target["accessors"].append(new_accessor)
    for material in source.get("materials", []):
        target.setdefault("materials", []).append(material)
    for extension in source.get("extensionsUsed", []):
        if extension not in target.setdefault("extensionsUsed", []):
            target["extensionsUsed"].append(extension)

    # 只取 source 的第一个 mesh 的第一个 primitive（我们的产物都是单 primitive）
    primitives = []
    for mesh in source.get("meshes", []):
        for primitive in mesh.get("primitives", []):
            new_primitive = dict(primitive)
            new_primitive["attributes"] = {
                key: value + accessor_offset for key, value in primitive["attributes"].items()
            }
            if "indices" in primitive:
                new_primitive["indices"] = primitive["indices"] + accessor_offset
            if "material" in primitive:
                new_primitive["material"] = primitive["material"] + material_offset
            primitives.append(new_primitive)
    target["meshes"].append({"name": source.get("meshes", [{}])[0].get("name", "added"),
                             "primitives": primitives})
    target["nodes"].append({"mesh": len(target["meshes"]) - 1,
                            "name": source.get("nodes", [{}])[0].get("name", "added")})
    target["scenes"][0]["nodes"].append(len(target["nodes"]) - 1)


def main() -> int:
    parser = argparse.ArgumentParser(description="合并多个 GLB（各成一个 primitive）")
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--add", type=Path, action="append", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    gltf, binary = read_glb(args.base)
    buffer = bytearray(binary)
    total_vertices = 0
    for extra in args.add:
        extra_gltf, extra_bin = read_glb(extra)
        append_model(gltf, buffer, extra_gltf, extra_bin)
        print(f"  并入 {extra.name}（{len(extra_bin) / 1e6:.2f} MB BIN）")

    gltf["buffers"][0]["byteLength"] = len(buffer)
    write_glb(args.out, gltf, bytes(buffer))
    print(f"{args.out}  {args.out.stat().st_size / 1e6:.2f} MB  meshes={len(gltf['meshes'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
