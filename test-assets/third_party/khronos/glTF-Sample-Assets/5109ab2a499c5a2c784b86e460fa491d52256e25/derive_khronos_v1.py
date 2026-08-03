#!/usr/bin/env python3
"""Deterministically derive strict BlendLib v1 GLBs from fixed Khronos payloads.

Only the exact raw model payloads listed in ``RAW_INPUTS`` are consumed.  The
script deliberately does not fetch the network, inspect a branch/tag, or copy
upstream metadata/license documents.  Run ``--write`` after placing the
verified payloads in ``raw/``; run ``--verify`` to prove the committed derived
artifacts and test-resource mirrors are byte-for-byte reproducible.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path
from typing import Any


UPSTREAM_REVISION = "5109ab2a499c5a2c784b86e460fa491d52256e25"
UPSTREAM_BASE = (
    "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/"
    + UPSTREAM_REVISION
    + "/Models"
)
NAMESPACE = "blendlib_khronos_fixture"
HERE = Path(__file__).resolve().parent
RAW_ROOT = HERE / "raw"
DERIVED_ROOT = HERE / "derived"

# relative path, byte length, SHA-256 (lowercase)
RAW_INPUTS: dict[str, tuple[str, int, str]] = {
    "simple_gltf": (
        "SimpleSkin/glTF/SimpleSkin.gltf",
        2386,
        "8d92e9888340eb98e82a65a1f8b037d9a1d9f09e5fd0b6ba6d40fd93700b3239",
    ),
    "simple_geometry": (
        "SimpleSkin/glTF/SimpleSkin_geometry.bin",
        168,
        "35f0ca6bc07976c0aadc163d4737758989cfcc9870a99800004c4478fe698b63",
    ),
    "simple_skinning": (
        "SimpleSkin/glTF/SimpleSkin_skinningData.bin",
        320,
        "413bc8a8c0da673df767874e6f05f0b00c9261756a08586b9d555bd5535414b5",
    ),
    "simple_inverse_binds": (
        "SimpleSkin/glTF/SimpleSkin_inverseBindMatrices.bin",
        128,
        "5844ab221cd2ad367420a248f6387b46e5f8498604427730a37ee96b4d4ec599",
    ),
    "simple_animation": (
        "SimpleSkin/glTF/SimpleSkin_animation.bin",
        240,
        "ff21b1bc3d0abbe53f7665060ec6c22d873b6e85a7669171a2b6b8ae01c39347",
    ),
    "cube_gltf": (
        "AnimatedCube/glTF/AnimatedCube.gltf",
        4991,
        "59a3c451a5167b8ac4b59d61d4b94b2b7a215ee827efaed75629677a1217b552",
    ),
    "cube_binary": (
        "AnimatedCube/glTF/AnimatedCube.bin",
        1860,
        "82de770fc82b48a77a33bb26abd4d7e75620491b36ddf98e8dca69fa73dd798c",
    ),
}

# Self-authored opaque-white 1x1 RGBA PNG. It contains no upstream image data.
SELF_AUTHORED_TEXTURE = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000b4944415478da63f80f040009fb03fd68fa1ccc0000000049454e44ae426082"
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def repository_root() -> Path:
    for candidate in HERE.parents:
        if (candidate / "settings.gradle.kts").is_file():
            return candidate
    raise ValueError("Could not locate repository root from derivation script")


def raw_url(relative_path: str) -> str:
    return f"{UPSTREAM_BASE}/{relative_path}"


def read_raw(name: str) -> bytes:
    relative_path, expected_length, expected_hash = RAW_INPUTS[name]
    path = RAW_ROOT / relative_path
    require(path.is_file(), f"Missing required fixed-revision input: {path}")
    payload = path.read_bytes()
    require(len(payload) == expected_length, f"Unexpected byte length for {relative_path}")
    require(sha256(payload) == expected_hash, f"Unexpected SHA-256 for {relative_path}")
    return payload


def validate_raw_inventory() -> None:
    actual = {
        path.relative_to(RAW_ROOT).as_posix()
        for path in RAW_ROOT.rglob("*")
        if path.is_file()
    }
    expected = {item[0] for item in RAW_INPUTS.values()}
    require(actual == expected, "raw/ must contain exactly the seven approved CC0 payload files")


def json_object(payload: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid fixed input JSON for {label}") from error
    require(isinstance(value, dict), f"{label} must be a JSON object")
    return value


def assert_simple_skin_source(source: dict[str, Any]) -> None:
    require([buffer["uri"] for buffer in source["buffers"]] == [
        "SimpleSkin_geometry.bin",
        "SimpleSkin_skinningData.bin",
        "SimpleSkin_inverseBindMatrices.bin",
        "SimpleSkin_animation.bin",
    ], "SimpleSkin buffer order changed at fixed source")
    require(source["accessors"][0]["componentType"] == 5123, "SimpleSkin must retain U16 indices")
    require(source["accessors"][1]["count"] == 10, "SimpleSkin vertex count changed")
    require(source["accessors"][2]["type"] == "VEC4", "SimpleSkin JOINTS_0 layout changed")
    require(source["accessors"][3]["type"] == "VEC4", "SimpleSkin WEIGHTS_0 layout changed")
    require(source["accessors"][4]["type"] == "MAT4", "SimpleSkin inverse-bind layout changed")
    sampler = source["animations"][0]["samplers"][0]
    require(sampler["interpolation"] == "LINEAR", "SimpleSkin animation is no longer LINEAR")
    require(sampler["input"] == 5 and sampler["output"] == 6, "SimpleSkin animation accessor mapping changed")


def assert_animated_cube_source(source: dict[str, Any]) -> None:
    require(source["buffers"] == [{"byteLength": 1860, "uri": "AnimatedCube.bin"}], "AnimatedCube buffer changed")
    primitive = source["meshes"][0]["primitives"][0]
    require(primitive["mode"] == 4 and primitive["indices"] == 2, "AnimatedCube triangle mapping changed")
    require(primitive["attributes"] == {
        "NORMAL": 4,
        "POSITION": 3,
        "TANGENT": 5,
        "TEXCOORD_0": 6,
    }, "AnimatedCube source attributes changed")
    require(source["accessors"][2]["componentType"] == 5123, "AnimatedCube must retain U16 indices")
    sampler = source["animations"][0]["samplers"][0]
    require(sampler["interpolation"] == "LINEAR", "AnimatedCube animation is no longer LINEAR")
    require(sampler["input"] == 0 and sampler["output"] == 1, "AnimatedCube animation mapping changed")


def pack_floats(values: list[float]) -> bytes:
    return struct.pack("<" + "f" * len(values), *values)


def append_blob(binary: bytearray, views: list[dict[str, int]], payload: bytes) -> int:
    while len(binary) % 4:
        binary.append(0)
    index = len(views)
    views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(payload)})
    binary.extend(payload)
    return index


def compact_json(value: dict[str, Any]) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def aligned(payload: bytes, padding: int) -> bytes:
    return payload + bytes([padding]) * ((-len(payload)) % 4)


def build_glb(document: dict[str, Any], binary: bytes) -> bytes:
    json_chunk = aligned(compact_json(document), 0x20)
    binary_chunk = aligned(binary, 0)
    total = 12 + 8 + len(json_chunk) + 8 + len(binary_chunk)
    return b"".join((
        struct.pack("<III", 0x46546C67, 2, total),
        struct.pack("<II", len(json_chunk), 0x4E4F534A),
        json_chunk,
        struct.pack("<II", len(binary_chunk), 0x004E4942),
        binary_chunk,
    ))


def descriptor(profile: str, mesh_name: str, material_name: str) -> bytes:
    value = {
        "format_version": 1,
        "materials": {
            material_name: {
                "base_color": f"{NAMESPACE}:textures/khronos/{mesh_name}.png",
                "double_sided": False,
                "emissive": False,
                "mode": "opaque",
            }
        },
        "mesh": f"{NAMESPACE}:models3d/khronos/{mesh_name}.glb",
        "profile": profile,
        "units_per_block": 1.0,
    }
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode("utf-8")


def derive_simple_skin() -> tuple[bytes, bytes]:
    source = json_object(read_raw("simple_gltf"), "SimpleSkin")
    assert_simple_skin_source(source)
    geometry = read_raw("simple_geometry")
    skinning = read_raw("simple_skinning")
    inverse_binds = read_raw("simple_inverse_binds")
    animation = read_raw("simple_animation")

    indices = geometry[:48]
    positions = geometry[48:168]
    joints = b"".join(skinning[index * 16:index * 16 + 8] for index in range(10))
    weights = skinning[160:320]
    times = animation[:48]
    rotations = animation[48:240]
    require(len(indices) == 48 and len(positions) == 120, "Unexpected SimpleSkin geometry ranges")
    require(len(joints) == 80 and len(weights) == 160, "Unexpected SimpleSkin skin ranges")
    require(len(inverse_binds) == 128 and len(times) == 48 and len(rotations) == 192, "Unexpected SimpleSkin animation ranges")
    sample_times = struct.unpack("<12f", times)
    require(sample_times[0] == 0.0 and sample_times[-1] == 5.5, "SimpleSkin key time range changed")
    require(all(right > left for left, right in zip(sample_times, sample_times[1:])), "SimpleSkin key times must be monotonic")
    require(max(struct.unpack("<40H", joints)) < 2, "SimpleSkin joint indices must address the two source joints")

    # Self-authored v1-required attributes; upstream SimpleSkin lacks both NORMAL and UV0.
    normals = pack_floats([component for _ in range(10) for component in (0.0, 0.0, 1.0)])
    uvs = pack_floats([component for index in range(10) for component in (float(index % 2), float((index // 2) % 2))])
    binary = bytearray()
    views: list[dict[str, int]] = []
    index_view = append_blob(binary, views, indices)
    position_view = append_blob(binary, views, positions)
    normal_view = append_blob(binary, views, normals)
    uv_view = append_blob(binary, views, uvs)
    joints_view = append_blob(binary, views, joints)
    weights_view = append_blob(binary, views, weights)
    inverse_view = append_blob(binary, views, inverse_binds)
    time_view = append_blob(binary, views, times)
    rotation_view = append_blob(binary, views, rotations)
    document = {
        "accessors": [
            {"bufferView": index_view, "componentType": 5123, "count": 24, "type": "SCALAR"},
            {"bufferView": position_view, "componentType": 5126, "count": 10, "type": "VEC3",
             "min": source["accessors"][1]["min"], "max": source["accessors"][1]["max"]},
            {"bufferView": normal_view, "componentType": 5126, "count": 10, "type": "VEC3"},
            {"bufferView": uv_view, "componentType": 5126, "count": 10, "type": "VEC2"},
            {"bufferView": joints_view, "componentType": 5123, "count": 10, "type": "VEC4"},
            {"bufferView": weights_view, "componentType": 5126, "count": 10, "type": "VEC4"},
            {"bufferView": inverse_view, "componentType": 5126, "count": 2, "type": "MAT4"},
            {"bufferView": time_view, "componentType": 5126, "count": 12, "type": "SCALAR",
             "min": source["accessors"][5]["min"], "max": source["accessors"][5]["max"]},
            {"bufferView": rotation_view, "componentType": 5126, "count": 12, "type": "VEC4"},
        ],
        "animations": [{
            "channels": [{"sampler": 0, "target": {"node": 2, "path": "rotation"}}],
            "name": "SimpleSkinDerivedRotation",
            "samplers": [{"input": 7, "interpolation": "LINEAR", "output": 8}],
        }],
        "asset": {"generator": "BlendLib deterministic strict-v1 derivation", "version": "2.0"},
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
        "materials": [{"name": "SimpleSkinDerived"}],
        "meshes": [{"primitives": [{
            "attributes": {"JOINTS_0": 4, "NORMAL": 2, "POSITION": 1, "TEXCOORD_0": 3, "WEIGHTS_0": 5},
            "indices": 0,
            "material": 0,
            "mode": 4,
        }]}],
        "nodes": [
            {"mesh": 0, "name": "SimpleSkinMesh", "skin": 0},
            {"children": [2], "name": "SimpleSkinJointRoot"},
            {"name": "SimpleSkinJointTip", "rotation": [0.0, 0.0, 0.0, 1.0], "translation": [0.0, 1.0, 0.0]},
        ],
        "scene": 0,
        "scenes": [{"nodes": [0, 1]}],
        "skins": [{"inverseBindMatrices": 6, "joints": [1, 2], "name": "SimpleSkinDerivedSkin"}],
    }
    return build_glb(document, bytes(binary)), descriptor("blendlib:skinned_v1", "simple-skin-derived", "SimpleSkinDerived")


def derive_animated_cube() -> tuple[bytes, bytes]:
    source = json_object(read_raw("cube_gltf"), "AnimatedCube")
    assert_animated_cube_source(source)
    source_binary = read_raw("cube_binary")
    times = source_binary[0:12]
    rotations = source_binary[12:60]
    indices = source_binary[60:132]
    positions = source_binary[132:564]
    normals = source_binary[564:996]
    uvs = source_binary[1572:1860]
    require(sum(map(len, (times, rotations, indices, positions, normals, uvs))) == 1284, "Unexpected AnimatedCube source ranges")
    source_times = struct.unpack("<3f", times)
    require(source_times == (0.0, 1.0, 2.0), "AnimatedCube key times changed")
    require(max(struct.unpack("<36H", indices)) == 35, "AnimatedCube U16 index range changed")

    binary = bytearray()
    views: list[dict[str, int]] = []
    time_view = append_blob(binary, views, times)
    rotation_view = append_blob(binary, views, rotations)
    index_view = append_blob(binary, views, indices)
    position_view = append_blob(binary, views, positions)
    normal_view = append_blob(binary, views, normals)
    uv_view = append_blob(binary, views, uvs)
    document = {
        "accessors": [
            {"bufferView": time_view, "componentType": 5126, "count": 3, "type": "SCALAR",
             "min": source["accessors"][0]["min"], "max": source["accessors"][0]["max"]},
            {"bufferView": rotation_view, "componentType": 5126, "count": 3, "type": "VEC4"},
            {"bufferView": index_view, "componentType": 5123, "count": 36, "type": "SCALAR"},
            {"bufferView": position_view, "componentType": 5126, "count": 36, "type": "VEC3",
             "min": source["accessors"][3]["min"], "max": source["accessors"][3]["max"]},
            {"bufferView": normal_view, "componentType": 5126, "count": 36, "type": "VEC3"},
            {"bufferView": uv_view, "componentType": 5126, "count": 36, "type": "VEC2"},
        ],
        "animations": [{
            "channels": [{"sampler": 0, "target": {"node": 0, "path": "rotation"}}],
            "name": "animation_AnimatedCube",
            "samplers": [{"input": 0, "interpolation": "LINEAR", "output": 1}],
        }],
        "asset": {"generator": "BlendLib deterministic strict-v1 derivation", "version": "2.0"},
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
        "materials": [{"name": "AnimatedCubeDerived"}],
        "meshes": [{"primitives": [{
            "attributes": {"NORMAL": 4, "POSITION": 3, "TEXCOORD_0": 5},
            "indices": 2,
            "material": 0,
            "mode": 4,
        }]}],
        "nodes": [{"mesh": 0, "name": "AnimatedCube", "rotation": source["nodes"][0]["rotation"]}],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
    }
    return build_glb(document, bytes(binary)), descriptor("blendlib:rigid_v1", "animated-cube-derived", "AnimatedCubeDerived")


def expected_outputs() -> dict[Path, bytes]:
    simple_glb, simple_descriptor = derive_simple_skin()
    cube_glb, cube_descriptor = derive_animated_cube()
    repo = repository_root()
    test_resources = repo / "blendlib-core" / "src" / "test" / "resources" / "p3" / "fixtures" / "khronos"
    outputs = {
        DERIVED_ROOT / "SimpleSkin" / "simple-skin-derived.glb": simple_glb,
        DERIVED_ROOT / "SimpleSkin" / "simple-skin-derived.json": simple_descriptor,
        DERIVED_ROOT / "AnimatedCube" / "animated-cube-derived.glb": cube_glb,
        DERIVED_ROOT / "AnimatedCube" / "animated-cube-derived.json": cube_descriptor,
        test_resources / "simple-skin-derived.glb": simple_glb,
        test_resources / "simple-skin-derived.json": simple_descriptor,
        test_resources / "animated-cube-derived.glb": cube_glb,
        test_resources / "animated-cube-derived.json": cube_descriptor,
        DERIVED_ROOT / "textures" / "khronos" / "simple-skin-derived.png": SELF_AUTHORED_TEXTURE,
        DERIVED_ROOT / "textures" / "khronos" / "animated-cube-derived.png": SELF_AUTHORED_TEXTURE,
        test_resources / "textures" / "khronos" / "simple-skin-derived.png": SELF_AUTHORED_TEXTURE,
        test_resources / "textures" / "khronos" / "animated-cube-derived.png": SELF_AUTHORED_TEXTURE,
    }
    manifest = {
        "format_version": 1,
        "upstream": {
            "repository": "KhronosGroup/glTF-Sample-Assets",
            "revision": UPSTREAM_REVISION,
            "raw_base_url": UPSTREAM_BASE,
        },
        "raw_inputs": [
            {
                "path": relative_path,
                "source_url": raw_url(relative_path),
                "byte_length": byte_length,
                "sha256": digest,
            }
            for relative_path, byte_length, digest in RAW_INPUTS.values()
        ],
        "derived_outputs": [
            {
                "path": path.relative_to(repository_root()).as_posix(),
                "byte_length": len(payload),
                "sha256": sha256(payload),
            }
            for path, payload in sorted(outputs.items(), key=lambda item: item[0].as_posix())
        ],
    }
    outputs[HERE / "DERIVATION-MANIFEST.json"] = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode("utf-8")
    return outputs


def write_outputs(outputs: dict[Path, bytes]) -> None:
    for path, payload in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
        print(f"WRITE {path.relative_to(repository_root()).as_posix()} {sha256(payload)}")


def verify_outputs(outputs: dict[Path, bytes]) -> None:
    failures: list[str] = []
    for path, expected in outputs.items():
        if not path.is_file():
            failures.append(f"missing {path}")
        elif path.read_bytes() != expected:
            failures.append(f"non-deterministic or stale {path}")
    if failures:
        raise ValueError("; ".join(failures))
    for path, payload in outputs.items():
        print(f"VERIFY {path.relative_to(repository_root()).as_posix()} {sha256(payload)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="write deterministic derived artifacts and mirrors")
    mode.add_argument("--verify", action="store_true", help="verify raw inputs and every committed derived byte")
    arguments = parser.parse_args()
    try:
        validate_raw_inventory()
        outputs = expected_outputs()
        if arguments.write:
            write_outputs(outputs)
        else:
            verify_outputs(outputs)
    except (OSError, ValueError, KeyError, struct.error) as error:
        print(f"DERIVATION FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
