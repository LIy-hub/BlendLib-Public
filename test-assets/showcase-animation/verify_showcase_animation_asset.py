"""Verify the P5 Showcase asset, including two isolated deterministic exports."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path
from typing import Any

sys.dont_write_bytecode = True

ASSET_DIRECTORY = Path(__file__).resolve().parent
PROJECT_DIRECTORY = ASSET_DIRECTORY.parents[1]
ADDON_DIRECTORY = PROJECT_DIRECTORY / "blender-addon"
if str(ASSET_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(ASSET_DIRECTORY))
if str(ADDON_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(ADDON_DIRECTORY))

from blendlib_exporter import read_glb, sha256_file  # noqa: E402
from export_showcase_animation_asset import animation_semantics, export_asset  # noqa: E402


TIP_SOCKET_DECLARATION = {
    "blendlib_showcase:tip": {
        "node": (
            "ShowcaseAnimationRoot/ShowcaseAnimationArmature/"
            "ShowcaseRootBone/ShowcaseTipBone"
        ),
    },
}


def parse_args(argv: list[str]) -> argparse.Namespace:
    if "--" not in argv:
        raise SystemExit("Verifier arguments must follow Blender's '--' separator.")
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--record-golden", action="store_true")
    return parser.parse_args(argv[argv.index("--") + 1 :])


def canonical_hash(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def read_contract(root: Path) -> dict[str, Any]:
    return json.loads((root / "test-assets" / "showcase-animation" / "expected.json").read_text(encoding="utf-8"))


def runtime_paths(output_root: Path, contract: dict[str, Any]) -> tuple[Path, Path, Path]:
    assets = output_root / "src" / "main" / "resources" / "assets" / contract["namespace"]
    model_id = Path(contract["model_id"])
    descriptor = assets / "blend_models" / model_id.with_suffix(".json")
    mesh = assets / "models3d" / model_id.with_suffix(".glb")
    texture = assets / contract["expected_base_color"].split(":", 1)[1]
    return descriptor, mesh, texture


def inspect_export(output_root: Path, report: dict[str, Any], contract: dict[str, Any]) -> dict[str, Any]:
    descriptor_path, mesh_path, texture_path = runtime_paths(output_root, contract)
    if not all(path.is_file() for path in (descriptor_path, mesh_path, texture_path)):
        raise AssertionError(f"Missing runtime asset under {output_root}")
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    gltf, binary = read_glb(mesh_path)
    scenes = gltf.get("scenes", [])
    scene_index = gltf.get("scene", 0)
    if not isinstance(scene_index, int) or scene_index < 0 or scene_index >= len(scenes):
        raise AssertionError("GLB lacks a valid active default scene")
    root_indices = scenes[scene_index].get("nodes", [])
    node_names = [gltf["nodes"][index]["name"] for index in root_indices]
    if node_names != contract["expected_default_scene_roots"]:
        raise AssertionError(f"Active scene roots differ: {node_names}")
    if descriptor.get("animation") != contract["descriptor_animation"]:
        raise AssertionError("Descriptor does not truthfully expose the three Showcase states")
    if contract.get("descriptor_sockets") != TIP_SOCKET_DECLARATION:
        raise AssertionError("Source contract does not declare exactly the canonical Showcase tip socket")
    if descriptor.get("sockets") != TIP_SOCKET_DECLARATION:
        raise AssertionError("Descriptor does not declare exactly the canonical Showcase tip socket")
    if descriptor.get("profile") != "blendlib:skinned_v1":
        raise AssertionError("Showcase profile is not skinned_v1")
    if gltf.get("images") or gltf.get("textures") or gltf.get("samplers"):
        raise AssertionError("Runtime GLB contains embedded texture state")
    actual_names = sorted(animation["name"] for animation in gltf.get("animations", []))
    if actual_names != contract["expected_animation_names"]:
        raise AssertionError(f"Unexpected clip names: {actual_names}")
    for animation in gltf["animations"]:
        for sampler in animation["samplers"]:
            if sampler.get("interpolation", "LINEAR") not in {"LINEAR", "STEP"}:
                raise AssertionError("CUBICSPLINE or unsupported interpolation was exported")
    if not gltf.get("skins"):
        raise AssertionError("skinned_v1 asset lacks skins")
    primitive_attributes = [
        primitive.get("attributes", {})
        for mesh in gltf.get("meshes", [])
        for primitive in mesh.get("primitives", [])
    ]
    if not any({"JOINTS_0", "WEIGHTS_0"}.issubset(attributes) for attributes in primitive_attributes):
        raise AssertionError("skinned_v1 asset lacks JOINTS_0/WEIGHTS_0")
    semantics = animation_semantics(gltf, binary)
    semantic_hashes = {name: canonical_hash(value) for name, value in sorted(semantics.items())}
    if len(set(semantic_hashes.values())) != 3:
        raise AssertionError("idle/walk/attack are alias clip data rather than distinct Actions")
    if not isinstance(report.get("normalized_structure"), dict):
        raise AssertionError("Normalized structure must be JSON-shaped")
    return {
        "normalized_structure": report["normalized_structure"],
        "animation_semantics": semantics,
        "animation_semantic_hashes": semantic_hashes,
        "default_scene_roots": node_names,
        "sha256": {
            "source_blend": sha256_file(Path(report["blend"])),
            "source_png": sha256_file(
                Path(report["blend"]).parent / contract["source_texture"]
            ),
            "exported_glb": sha256_file(mesh_path),
            "descriptor": sha256_file(descriptor_path),
            "external_runtime_png": sha256_file(texture_path),
            "normalized_structure": canonical_hash(report["normalized_structure"]),
        },
    }


def source_tree_hygiene(root: Path) -> None:
    unexpected = []
    for directory in (root / "test-assets" / "showcase-animation",):
        for pattern in ("*.pyc", "*.blend1"):
            unexpected.extend(path.relative_to(root).as_posix() for path in directory.rglob(pattern))
    if unexpected:
        raise AssertionError(f"Source fixture contains generated residue: {sorted(unexpected)}")


def golden_paths(root: Path) -> tuple[Path, Path]:
    golden = root / "test-assets" / "showcase-animation" / "golden"
    return golden / "structure.json", golden / "sha256.json"


def require_project_root(root: Path) -> Path:
    resolved = root.resolve()
    if resolved != PROJECT_DIRECTORY:
        raise AssertionError(
            f"P5 Showcase verifier only accepts {PROJECT_DIRECTORY}, not {resolved}."
        )
    return resolved


def deterministic_output_root(root: Path) -> Path:
    expected = (PROJECT_DIRECTORY / "build" / "p5-showcase-animation-determinism").resolve()
    result = (root / "build" / "p5-showcase-animation-determinism").resolve()
    if result != expected or result.parent != (root / "build").resolve():
        raise AssertionError("P5 deterministic output root is outside the checked-in build directory.")
    return result


def write_goldens(root: Path, inspected: dict[str, Any]) -> None:
    structure_path, hashes_path = golden_paths(root)
    structure_path.parent.mkdir(parents=True, exist_ok=True)
    structure_path.write_text(
        json.dumps(
            {
                "format": "blendlib-p5-showcase-animation-structure-v1",
                **{key: inspected[key] for key in ("normalized_structure", "animation_semantics", "animation_semantic_hashes", "default_scene_roots")},
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    hashes_path.write_text(
        json.dumps(
            {
                "format": "blendlib-p5-showcase-animation-sha256-v1",
                **inspected["sha256"],
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )


def assert_goldens(root: Path, inspected: dict[str, Any]) -> None:
    structure_path, hashes_path = golden_paths(root)
    if not structure_path.is_file() or not hashes_path.is_file():
        raise AssertionError("Missing Showcase animation golden records; run with --record-golden once.")
    actual_structure = json.loads(structure_path.read_text(encoding="utf-8"))
    expected_structure = {
        "format": "blendlib-p5-showcase-animation-structure-v1",
        **{key: inspected[key] for key in ("normalized_structure", "animation_semantics", "animation_semantic_hashes", "default_scene_roots")},
    }
    if actual_structure != expected_structure:
        raise AssertionError("Committed Showcase animation structure golden differs from current export")
    actual_hashes = json.loads(hashes_path.read_text(encoding="utf-8"))
    expected_hashes = {"format": "blendlib-p5-showcase-animation-sha256-v1", **inspected["sha256"]}
    if actual_hashes != expected_hashes:
        raise AssertionError("Committed Showcase animation SHA-256 record differs from runtime assets")


def main() -> None:
    args = parse_args(sys.argv)
    root = require_project_root(Path(args.project_root))
    contract = read_contract(root)
    source_tree_hygiene(root)
    deterministic_root = deterministic_output_root(root)
    if deterministic_root.exists():
        shutil.rmtree(deterministic_root)
    first_output = deterministic_root / "first"
    second_output = deterministic_root / "second"
    first_report = export_asset(root, first_output, first_output / "report.json")
    second_report = export_asset(root, second_output, second_output / "report.json")
    first = inspect_export(first_output, first_report, contract)
    second = inspect_export(second_output, second_report, contract)
    if first["normalized_structure"] != second["normalized_structure"]:
        raise AssertionError("Two isolated exports have different normalized structure")
    if first["sha256"] != second["sha256"]:
        raise AssertionError("Two isolated exports have different SHA-256 values")

    actual_report_path = root / "test-assets" / "showcase-animation" / "export-report.json"
    if not actual_report_path.is_file():
        raise AssertionError("Missing committed Showcase asset export report")
    actual_report = json.loads(actual_report_path.read_text(encoding="utf-8"))
    actual = inspect_export(root / "blendlib-showcase", actual_report, contract)
    if actual["normalized_structure"] != first["normalized_structure"] or actual["sha256"] != first["sha256"]:
        raise AssertionError("Committed Showcase runtime asset differs from isolated deterministic export")
    if args.record_golden:
        write_goldens(root, actual)
    else:
        assert_goldens(root, actual)
    source_tree_hygiene(root)
    print("BLENDLIB_P5_SHOWCASE_ANIMATION_DETERMINISM_PASS idle walk attack")


if __name__ == "__main__":
    main()
