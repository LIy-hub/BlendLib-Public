"""Read-only verifier for the ADR-014/ADR-019 P4 material-matrix resource-pack fixtures.

This fixture-only script intentionally stays outside the Blender Add-on scope.
It creates no runtime assets and writes no files.
"""

from __future__ import annotations

import hashlib
import json
import struct
import sys
from pathlib import Path

sys.dont_write_bytecode = True

from jsonschema import Draft202012Validator


PACK_FORMAT = 84
DESCRIPTOR_RELATIVE_PATH = Path(
    "assets/blendlib_showcase/blend_models/fixtures/static_model.json"
)
EXPECTED_DESCRIPTOR_KEYS = {
    "extensions",
    "extensions_required",
    "extensions_used",
    "format_version",
    "materials",
    "mesh",
    "profile",
    "units_per_block",
}
EXPECTED_BASE_COLOR = (
    "blendlib_showcase:textures/blendlib/fixtures_rigid_model__rigidsurface.png"
)
EXPECTED_MESH = "blendlib_showcase:models3d/fixtures/rigid_model.glb"
EXPECTED_GLB_HASH = "a2b7f063c8806f3e3eceec2533ed8fe84c91f967ac71c25ca59faf4349a21764"
EXPECTED_TEXTURE_HASH = "851035d6863e6aea6a03c7a93d8734872e16918938c6f8b962392c24b25502e1"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def strict_json(path: Path) -> object:
    def reject_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"{path}: duplicate JSON key {key!r}")
            result[key] = value
        return result

    def reject_constant(value: str) -> object:
        raise ValueError(f"{path}: forbidden JSON constant {value!r}")

    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicates,
        parse_constant=reject_constant,
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_keys(value: object, expected: set[str], label: str) -> dict[str, object]:
    require(isinstance(value, dict), f"{label} must be a JSON object")
    actual = set(value)
    require(actual == expected, f"{label} keys differ: expected={sorted(expected)} actual={sorted(actual)}")
    return value


def supported_by_accepted_public_path(mode: str, double_sided: bool, cutout_threshold: object) -> bool:
    if mode == "opaque":
        return not double_sided
    if mode == "cutout":
        return cutout_threshold == 0.1
    if mode == "translucent":
        return double_sided
    if mode == "additive":
        return False
    raise AssertionError(f"Unexpected material mode: {mode!r}")


def read_glb_json(path: Path) -> dict[str, object]:
    data = path.read_bytes()
    require(len(data) >= 20, f"GLB is too short: {path}")
    magic, version, declared_length = struct.unpack_from("<4sII", data, 0)
    require(magic == b"glTF", f"GLB magic mismatch: {path}")
    require(version == 2 and declared_length == len(data), f"GLB header mismatch: {path}")
    json_length, chunk_type = struct.unpack_from("<II", data, 12)
    require(chunk_type == 0x4E4F534A, f"GLB must start with JSON chunk: {path}")
    return json.loads(data[20 : 20 + json_length].decode("utf-8"))


def verify_shared_p2_asset(root: Path, matrix: dict[str, object]) -> None:
    assets = root / "blendlib-showcase/src/main/resources/assets/blendlib_showcase"
    glb = assets / "models3d/fixtures/rigid_model.glb"
    texture = assets / "textures/blendlib/fixtures_rigid_model__rigidsurface.png"
    require(glb.is_file(), f"Missing P2 rigid GLB: {glb}")
    require(texture.is_file(), f"Missing P2 rigid texture: {texture}")
    shared_assets = require_keys(
        matrix["shared_assets"],
        {"rigid_glb_sha256", "texture_sha256"},
        "matrix shared_assets",
    )
    require(shared_assets["rigid_glb_sha256"] == EXPECTED_GLB_HASH, "Unexpected matrix GLB hash")
    require(shared_assets["texture_sha256"] == EXPECTED_TEXTURE_HASH, "Unexpected matrix texture hash")
    require(sha256(glb) == EXPECTED_GLB_HASH, "P2 rigid GLB hash changed")
    require(sha256(texture) == EXPECTED_TEXTURE_HASH, "P2 rigid texture hash changed")

    gltf = read_glb_json(glb)
    require(gltf.get("asset", {}).get("version") == "2.0", "P2 rigid GLB is not GLB 2.0")
    require(
        not {"images", "textures", "samplers"}.intersection(gltf),
        "P2 rigid GLB must retain external runtime textures",
    )
    material_names = [material.get("name") for material in gltf.get("materials", [])]
    require(material_names == ["RigidSurface"], f"Unexpected P2 material slots: {material_names}")
    primitives = [
        primitive
        for mesh in gltf.get("meshes", [])
        for primitive in mesh.get("primitives", [])
    ]
    require(len(primitives) == 2, f"Expected two rigid triangle primitives, got {len(primitives)}")
    for primitive in primitives:
        require(
            primitive.get("mode", 4) == 4,
            "P2 rigid matrix asset must retain TRIANGLES primitives",
        )
        require(
            set(primitive.get("attributes", {})) == {"POSITION", "NORMAL", "TEXCOORD_0"},
            "P2 rigid matrix asset must retain position/normal/UV0 attributes",
        )
        require(primitive.get("material") == 0 and "indices" in primitive, "Unexpected P2 rigid primitive")

    png = texture.read_bytes()
    require(png.startswith(b"\x89PNG\r\n\x1a\n"), "P2 rigid texture is not a PNG")
    width, height = struct.unpack_from(">II", png, 16)
    require((width, height) == (2, 2), f"Unexpected P2 rigid texture size: {(width, height)}")


def verify_pack(
    matrix_root: Path,
    schema_validator: Draft202012Validator,
    row: dict[str, object],
) -> None:
    pack_name = row["pack"]
    require(isinstance(pack_name, str) and pack_name, f"Invalid matrix pack name: {pack_name!r}")
    pack_root = matrix_root / pack_name
    require(pack_root.is_dir(), f"Missing matrix pack directory: {pack_root}")

    metadata = require_keys(strict_json(pack_root / "pack.mcmeta"), {"pack"}, f"{pack_name} pack.mcmeta")
    pack = require_keys(
        metadata["pack"],
        {"description", "min_format", "max_format"},
        f"{pack_name} pack metadata",
    )
    require(pack["min_format"] == PACK_FORMAT, f"{pack_name} min_format must be {PACK_FORMAT}")
    require(pack["max_format"] == PACK_FORMAT, f"{pack_name} max_format must be {PACK_FORMAT}")
    require(
        isinstance(pack["description"], str) and pack_name in pack["description"],
        f"{pack_name} pack description must identify the pack",
    )

    descriptor_path = pack_root / DESCRIPTOR_RELATIVE_PATH
    descriptor = strict_json(descriptor_path)
    errors = sorted(
        schema_validator.iter_errors(descriptor),
        key=lambda error: list(error.absolute_path),
    )
    require(not errors, f"{pack_name} descriptor violates frozen v1 schema: {errors}")
    descriptor = require_keys(descriptor, EXPECTED_DESCRIPTOR_KEYS, f"{pack_name} descriptor")
    require(descriptor["format_version"] == 1, f"{pack_name} must use format_version 1")
    require(descriptor["profile"] == "blendlib:rigid_v1", f"{pack_name} must use rigid_v1")
    require(descriptor["mesh"] == EXPECTED_MESH, f"{pack_name} mesh drifted")
    require(descriptor["units_per_block"] == 1.0, f"{pack_name} units_per_block drifted")
    require(descriptor["extensions"] == {}, f"{pack_name} must not add extensions")
    require(descriptor["extensions_used"] == [], f"{pack_name} must not add extensions_used")
    require(descriptor["extensions_required"] == [], f"{pack_name} must not add extensions_required")

    materials = require_keys(descriptor["materials"], {"RigidSurface"}, f"{pack_name} materials")
    material = materials["RigidSurface"]
    require(isinstance(material, dict), f"{pack_name} material must be an object")
    mode = row["mode"]
    double_sided = row["double_sided"]
    emissive = row["emissive"]
    threshold = row["cutout_threshold"]
    require(isinstance(mode, str), f"{pack_name} mode is not a string")
    require(isinstance(double_sided, bool), f"{pack_name} double_sided is not boolean")
    require(isinstance(emissive, bool), f"{pack_name} emissive is not boolean")
    if mode == "cutout":
        require(
            set(material) == {"base_color", "mode", "emissive", "double_sided", "cutout_threshold"},
            f"{pack_name} cutout material fields drifted",
        )
        require(isinstance(threshold, (int, float)) and not isinstance(threshold, bool), f"{pack_name} threshold is invalid")
        require(0 <= threshold <= 1, f"{pack_name} threshold is outside schema bounds")
        require(material["cutout_threshold"] == threshold, f"{pack_name} threshold drifted")
    else:
        require(
            set(material) == {"base_color", "mode", "emissive", "double_sided"},
            f"{pack_name} non-cutout material must not carry cutout_threshold",
        )
        require(threshold is None, f"{pack_name} non-cutout matrix threshold must be null")
    require(material["base_color"] == EXPECTED_BASE_COLOR, f"{pack_name} texture drifted")
    require(material["mode"] == mode, f"{pack_name} mode drifted")
    require(material["double_sided"] is double_sided, f"{pack_name} double_sided drifted")
    require(material["emissive"] is emissive, f"{pack_name} emissive drifted")

    expected_status = "supported" if supported_by_accepted_public_path(mode, double_sided, threshold) else "rejected"
    require(row["status"] == expected_status, f"{pack_name} ADR-014/ADR-019 status drifted")
    manual_expected = row["manual_expected"]
    require(isinstance(manual_expected, str) and manual_expected, f"{pack_name} missing manual expectation")
    if expected_status == "supported":
        require(
            "without missing model or BLENDLIB-MAT-004" in manual_expected,
            f"{pack_name} supported expectation must state the no-missing-model result",
        )
    else:
        require(
            "missing model plus BLENDLIB-MAT-004" in manual_expected,
            f"{pack_name} rejected expectation must state MAT-004 missing model",
        )

    actual_files = {
        path.relative_to(pack_root).as_posix()
        for path in pack_root.rglob("*")
        if path.is_file()
    }
    expected_files = {"pack.mcmeta", DESCRIPTOR_RELATIVE_PATH.as_posix()}
    require(actual_files == expected_files, f"{pack_name} files drifted: {sorted(actual_files)}")
    require(
        not any(path.suffix.lower() in {".glb", ".png"} for path in pack_root.rglob("*") if path.is_file()),
        f"{pack_name} must not copy P2 GLB/PNG assets",
    )


def main() -> None:
    if len(sys.argv) != 3 or sys.argv[1] != "--project-root":
        raise SystemExit("Usage: verify-material-matrix.py --project-root <root>")
    root = Path(sys.argv[2]).resolve()
    matrix_root = root / "test-assets/p4-resource-packs/material-matrix"
    matrix = require_keys(
        strict_json(matrix_root / "material-matrix-v1.json"),
        {
            "format",
            "pack_format",
            "descriptor_resource_id",
            "descriptor_relative_path",
            "shared_mesh",
            "shared_texture",
            "shared_assets",
            "rows",
        },
        "material matrix",
    )
    require(matrix["format"] == "blendlib-p4-material-matrix-v1", "Unexpected material matrix format")
    require(matrix["pack_format"] == PACK_FORMAT, "Unexpected material matrix pack format")
    require(
        matrix["descriptor_resource_id"] == "blendlib_showcase:fixtures/static_model",
        "Unexpected matrix descriptor resource ID",
    )
    require(
        matrix["descriptor_relative_path"] == DESCRIPTOR_RELATIVE_PATH.as_posix(),
        "Unexpected matrix descriptor path",
    )
    require(matrix["shared_mesh"] == EXPECTED_MESH, "Unexpected matrix mesh resource ID")
    require(matrix["shared_texture"] == EXPECTED_BASE_COLOR, "Unexpected matrix texture resource ID")
    rows = matrix["rows"]
    require(isinstance(rows, list) and len(rows) == 18, "Material matrix must contain exactly 18 rows")
    expected_row_keys = {
        "pack",
        "status",
        "mode",
        "double_sided",
        "emissive",
        "cutout_threshold",
        "manual_expected",
    }
    normalized_rows = [require_keys(row, expected_row_keys, "material matrix row") for row in rows]
    pack_names = [row["pack"] for row in normalized_rows]
    require(len(pack_names) == len(set(pack_names)), f"Duplicate matrix packs: {pack_names}")
    supported_count = sum(row["status"] == "supported" for row in normalized_rows)
    rejected_count = sum(row["status"] == "rejected" for row in normalized_rows)
    require(supported_count == 8 and rejected_count == 10, "Material matrix must contain 8 supported and 10 rejected rows")

    schema = strict_json(root / "schemas/blendlib-model-v1.schema.json")
    validator = Draft202012Validator(schema)
    verify_shared_p2_asset(root, matrix)
    for row in normalized_rows:
        verify_pack(matrix_root, validator, row)

    actual_pack_names = {
        path.name
        for path in matrix_root.iterdir()
        if path.is_dir() and (path / "pack.mcmeta").is_file()
    }
    require(actual_pack_names == set(pack_names), f"Matrix pack directories differ: {sorted(actual_pack_names)}")
    require(
        not list(matrix_root.rglob("__pycache__")) and not list(matrix_root.rglob("*.pyc")),
        "Material-matrix verifier must not leave Python bytecode in the source tree",
    )
    print(
        "P4_MATERIAL_MATRIX_FIXTURES_VERIFIED "
        "rows=18 supported=8 rejected=10 pack_format=84 schema=strict "
        "p2_glb_png=verified"
    )


if __name__ == "__main__":
    main()
