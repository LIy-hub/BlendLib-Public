# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Validate canonical P2 descriptors and the frozen PNG schema contract."""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True

from jsonschema import Draft202012Validator

ADDON_ROOT = Path(__file__).resolve().parents[1]
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

from blendlib_exporter import read_glb  # noqa: E402


def _parse_root(argv: list[str]) -> Path:
    if len(argv) != 3 or argv[1] != "--project-root":
        raise SystemExit("Usage: verify_p2_descriptor_schema.py --project-root <root>")
    return Path(argv[2]).resolve()


def _errors(validator: Draft202012Validator, document: object) -> list[object]:
    return sorted(validator.iter_errors(document), key=lambda error: list(error.absolute_path))


def main() -> None:
    root = _parse_root(sys.argv)
    schema_path = root / "schemas" / "blendlib-model-v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)
    assets_root = (
        root
        / "blendlib-showcase"
        / "src"
        / "main"
        / "resources"
        / "assets"
        / "blendlib_showcase"
    )

    descriptors = sorted((assets_root / "blend_models" / "fixtures").glob("*.json"))
    assert len(descriptors) == 3, descriptors
    for descriptor_path in descriptors:
        descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
        errors = _errors(validator, descriptor)
        assert not errors, (descriptor_path, errors)
        for material in descriptor["materials"].values():
            resource = material["base_color"]
            namespace, relative_path = resource.split(":", 1)
            assert namespace == "blendlib_showcase", resource
            assert relative_path.endswith(".png"), resource
            assert (assets_root / relative_path).is_file(), (descriptor_path, resource)
        mesh_namespace, mesh_relative_path = descriptor["mesh"].split(":", 1)
        assert mesh_namespace == "blendlib_showcase", descriptor["mesh"]
        gltf, _ = read_glb(assets_root / mesh_relative_path)
        assert not {"images", "textures", "samplers"}.intersection(gltf), descriptor_path

    negative_fixtures = {
        "descriptor-invalid-base-color-without-png.json": "blendlib_showcase:textures/blendlib/fixtures_static_model__staticsurface",
        "descriptor-invalid-base-color-parent-segment.json": "blendlib_showcase:textures/../escaped.png",
        "descriptor-invalid-base-color-double-slash.json": "blendlib_showcase:textures//double-slash.png",
        "descriptor-invalid-base-color-dot-segment.json": "blendlib_showcase:textures/./dot.png",
        "descriptor-invalid-base-color-terminal-lf.json": "blendlib_showcase:textures/safe.png\n",
        "descriptor-invalid-base-color-terminal-crlf.json": "blendlib_showcase:textures/safe.png\r\n",
        "descriptor-invalid-base-color-leading-whitespace.json": " blendlib_showcase:textures/safe.png",
        "descriptor-invalid-base-color-embedded-whitespace.json": "blendlib_showcase:textures/safe value.png",
        "descriptor-invalid-base-color-embedded-control.json": "blendlib_showcase:textures/safe\u0001.png",
    }
    target = ("materials", "StaticSurface", "base_color")
    for fixture_name, expected_resource in negative_fixtures.items():
        negative_path = root / "test-assets" / fixture_name
        negative = json.loads(negative_path.read_text(encoding="utf-8"))
        assert negative["materials"]["StaticSurface"]["base_color"] == expected_resource
        errors = _errors(validator, negative)
        assert errors, f"Schema accepted invalid texture resource in {fixture_name}"
        assert any(tuple(error.absolute_path) == target for error in errors), errors
    print("P2_DESCRIPTOR_SCHEMA_STRICT_TEXTURE_PATH_CONTROL_SAFE_AND_RUNTIME_TEXTURE_PASS 3")


if __name__ == "__main__":
    main()
