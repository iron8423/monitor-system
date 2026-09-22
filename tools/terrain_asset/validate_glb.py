"""GLB 完整性校验（改纹理/合并资产之后必跑）。

为什么必须有这个：GLB 有两个长度字段（文件总长、每个 chunk 长度）和一堆
bufferView 偏移，只要有一个算错，浏览器就会"一直转圈不出图"或者加载到一半报
RuntimeError——2026-09-20 那次 2m 加密版就是这么坏的（声明 8.69 MB、实际 5.13 MB）。

检查项：
  1. 头部魔数/版本/声明长度 == 实际文件长度
  2. JSON chunk / BIN chunk 的长度、类型、4 字节对齐
  3. 每个 bufferView 的 byteOffset + byteLength 落在 buffer 内
  4. 每个 accessor 的访问范围落在它的 bufferView 内
  5. images / textures / materials 的索引与 mimeType 合法
  6. 汇总输出：mesh/primitive 数、顶点数、三角面数、贴图张数与像素尺寸
"""

import json
import struct
import sys
from pathlib import Path
from typing import Any

# 控制台默认可能是 GBK，中文/符号会直接抛 UnicodeEncodeError（校验器因此在报错时崩掉）
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass


COMPONENT_BYTES = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}
TYPE_COMPONENTS = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT2": 4, "MAT3": 9, "MAT4": 16}


def fail(problems: list[str], message: str) -> None:
    problems.append(message)
    print(f"  ✗ {message}")


def main() -> int:
    if len(sys.argv) < 2:
        print("用法：validate_glb.py <file.glb>")
        return 2

    path = Path(sys.argv[1])
    data = path.read_bytes()
    problems: list[str] = []
    print(f"{path}  {len(data) / 1e6:.2f} MB")

    if data[:4] != b"glTF":
        fail(problems, "魔数不是 glTF")
        return 1
    version, declared = struct.unpack("<II", data[4:12])
    print(f"  版本 {version}，声明长度 {declared}")
    if version != 2:
        fail(problems, f"版本不是 2（{version}）")
    if declared != len(data):
        fail(problems, f"声明长度 {declared} != 实际 {len(data)}")

    offset = 12
    gltf: dict[str, Any] | None = None
    binary = b""
    while offset + 8 <= len(data):
        chunk_len, chunk_type = struct.unpack("<I4s", data[offset:offset + 8])
        body = data[offset + 8:offset + 8 + chunk_len]
        if len(body) != chunk_len:
            fail(problems, f"chunk {chunk_type!r} 长度越界（声明 {chunk_len}，实际 {len(body)}）")
        if chunk_len % 4 != 0:
            fail(problems, f"chunk {chunk_type!r} 长度 {chunk_len} 不是 4 的倍数")
        if chunk_type == b"JSON":
            gltf = json.loads(body.decode("utf-8"))
        elif chunk_type.startswith(b"BIN"):
            binary = body
        else:
            fail(problems, f"未知 chunk 类型 {chunk_type!r}")
        offset += 8 + chunk_len

    if gltf is None:
        fail(problems, "没有 JSON chunk")
        return 1
    if offset != len(data):
        fail(problems, f"chunk 结束位置 {offset} != 文件长度 {len(data)}")

    buffers = gltf.get("buffers", [])
    print(f"  buffers={len(buffers)}  binary={len(binary) / 1e6:.2f} MB")
    if buffers:
        declared_buffer = buffers[0].get("byteLength")
        # 规范允许 BIN chunk 比 buffer.byteLength 多最多 3 个补齐字节
        if declared_buffer != len(binary) and not (0 <= len(binary) - declared_buffer <= 3):
            fail(problems, f"buffer[0].byteLength={declared_buffer} 与 BIN 实际 {len(binary)} 不一致"
                           f"（差的应是 0~3 个对齐字节）")

    views = gltf.get("bufferViews", [])
    for index, view in enumerate(views):
        start = view.get("byteOffset", 0)
        length = view.get("byteLength", 0)
        if start < 0 or length < 0 or start + length > len(binary):
            fail(problems, f"bufferView[{index}] 越界：{start}+{length} > {len(binary)}")

    accessors = gltf.get("accessors", [])
    for index, accessor in enumerate(accessors):
        view_index = accessor.get("bufferView")
        if view_index is None:
            continue
        if not (0 <= view_index < len(views)):
            fail(problems, f"accessor[{index}] 的 bufferView 索引 {view_index} 不存在")
            continue
        component = COMPONENT_BYTES.get(accessor.get("componentType"), 0)
        components = TYPE_COMPONENTS.get(accessor.get("type"), 0)
        count = accessor.get("count", 0)
        view = views[view_index]
        stride = view.get("byteStride") or component * components
        needed = accessor.get("byteOffset", 0) + (count - 1) * stride + component * components if count else 0
        if needed > view.get("byteLength", 0):
            fail(problems, f"accessor[{index}] 超出 bufferView[{view_index}]（需要 {needed}，视图 {view.get('byteLength')}）")
        if component == 0 or components == 0:
            fail(problems, f"accessor[{index}] 的 componentType/type 非法")

    images = gltf.get("images", [])
    for index, image in enumerate(images):
        view_index = image.get("bufferView")
        if view_index is not None and not (0 <= view_index < len(views)):
            fail(problems, f"images[{index}] 的 bufferView {view_index} 不存在")
        if not image.get("mimeType"):
            fail(problems, f"images[{index}] 缺少 mimeType")

    for index, texture in enumerate(gltf.get("textures", [])):
        source = texture.get("source")
        if source is not None and not (0 <= source < len(images)):
            fail(problems, f"textures[{index}].source {source} 不存在")
    for index, material in enumerate(gltf.get("materials", [])):
        base = (material.get("pbrMetallicRoughness") or {}).get("baseColorTexture") or {}
        if "index" in base and not (0 <= base["index"] < len(gltf.get("textures", []))):
            fail(problems, f"materials[{index}] 的 baseColorTexture 索引非法")

    triangles = 0
    vertices = 0
    for mesh_index, mesh in enumerate(gltf.get("meshes", [])):
        for primitive in mesh.get("primitives", []):
            position = primitive.get("attributes", {}).get("POSITION")
            if position is not None and 0 <= position < len(accessors):
                vertices += accessors[position].get("count", 0)
            indices = primitive.get("indices")
            mode = primitive.get("mode", 4)
            if indices is not None and 0 <= indices < len(accessors):
                count = accessors[indices].get("count", 0)
                triangles += count // 3 if mode == 4 else 0
            elif position is not None and 0 <= position < len(accessors):
                triangles += accessors[position].get("count", 0) // 3

    print(f"  meshes={len(gltf.get('meshes', []))}  vertices={vertices:,}  三角面={triangles:,}")
    for index, image in enumerate(images):
        size = ""
        view_index = image.get("bufferView")
        if view_index is not None and 0 <= view_index < len(views):
            start = views[view_index].get("byteOffset", 0)
            length = views[view_index].get("byteLength", 0)
            try:
                from io import BytesIO

                from PIL import Image

                with Image.open(BytesIO(binary[start:start + length])) as decoded:
                    size = f"{decoded.width}x{decoded.height} {decoded.format}"
            except Exception as error:  # noqa: BLE001
                fail(problems, f"images[{index}] 无法解码：{error}")
        print(f"  image[{index}] {image.get('mimeType')} {length / 1024:.0f} KiB {size}")

    if problems:
        print(f"\n结论：不合格，{len(problems)} 个问题")
        return 1
    print("\n结论：通过（长度字段一致、bufferView/accessor 全部在范围内、贴图可解码）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
