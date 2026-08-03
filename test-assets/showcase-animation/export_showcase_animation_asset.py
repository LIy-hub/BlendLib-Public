"""Export the P5 Showcase animation asset through the existing P2 exporter.

This wrapper intentionally does not implement GLB export itself. It constructs
the established ``blendlib_exporter`` CLI contract, calls ``run_cli``, then
applies only the asset's explicit descriptor state mapping. That mapping keeps
the three source Actions and their presentation-only attack event truthful.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

sys.dont_write_bytecode = True

ASSET_DIRECTORY = Path(__file__).resolve().parent
PROJECT_DIRECTORY = ASSET_DIRECTORY.parents[1]
ADDON_DIRECTORY = PROJECT_DIRECTORY / "blender-addon"
if str(ADDON_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(ADDON_DIRECTORY))

TIP_SOCKET_DECLARATION = {
    "blendlib_showcase:tip": {
        "node": (
            "ShowcaseAnimationRoot/ShowcaseAnimationArmature/"
            "ShowcaseRootBone/ShowcaseTipBone"
        ),
    },
}

from blendlib_exporter import (  # noqa: E402
    ExportError,
    normalized_structure_summary,
    parse_blender_arguments,
    read_glb,
    run_cli,
    sha256_file,
    validate_descriptor,
    validate_glb,
)


def _require_project_root(project_root: Path) -> Path:
    """The fixture may only consume the checkout that owns this script."""

    resolved = project_root.resolve()
    if resolved != PROJECT_DIRECTORY:
        raise ExportError(
            "BLENDLIB-CLI-002",
            f"Showcase source project must be {PROJECT_DIRECTORY}, not {resolved}.",
        )
    return resolved


def _require_output_root(output_project_root: Path) -> Path:
    """Constrain writes to the committed module or deterministic scratch outputs."""

    resolved = output_project_root.resolve()
    deterministic_root = (PROJECT_DIRECTORY / "build" / "p5-showcase-animation-determinism").resolve()
    allowed = {
        (PROJECT_DIRECTORY / "blendlib-showcase").resolve(),
        (deterministic_root / "first").resolve(),
        (deterministic_root / "second").resolve(),
    }
    if resolved not in allowed:
        allowed_text = ", ".join(str(path) for path in sorted(allowed))
        raise ExportError(
            "BLENDLIB-CLI-002",
            f"Showcase output root must be one of [{allowed_text}], not {resolved}.",
        )
    return resolved


def _require_descendant(path: Path, root: Path, label: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as error:
        raise ExportError("BLENDLIB-CLI-002", f"{label} escapes its allowed root: {resolved}") from error
    return resolved


def parse_args(argv: list[str]) -> argparse.Namespace:
    if "--" not in argv:
        raise SystemExit("Asset export arguments must follow Blender's '--' separator.")
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-project-root", required=True)
    parser.add_argument("--output-project-root", required=True)
    parser.add_argument("--report", required=True, help="Source-project-relative JSON report path.")
    return parser.parse_args(argv[argv.index("--") + 1 :])


def _safe_report_path(output_root: Path, raw: str) -> Path:
    path = Path(raw)
    if path.is_absolute() or ".." in path.parts:
        raise ExportError("BLENDLIB-CLI-002", "Report must be output-project-relative without '..'.")
    resolved_root = output_root.resolve()
    result = (resolved_root / path).resolve()
    try:
        result.relative_to(resolved_root)
    except ValueError as error:
        raise ExportError("BLENDLIB-CLI-002", "Report escapes the output project root.") from error
    return result


def _canonical_json_hash(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _load_contract(source_root: Path) -> dict[str, Any]:
    path = source_root / "test-assets" / "showcase-animation" / "expected.json"
    if not path.is_file():
        raise ExportError("BLENDLIB-CLI-001", f"Showcase animation contract is missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def animation_semantics(gltf: dict[str, Any], binary: bytes) -> dict[str, list[dict[str, Any]]]:
    """Return data-bearing channel fingerprints, not just clip display names."""

    # Import locally so normal P2 exporter import tests do not expose this
    # private diagnostic helper as a public Blender Add-on API.
    from blendlib_exporter import _read_accessor  # noqa: PLC0415

    output: dict[str, list[dict[str, Any]]] = {}
    for animation in gltf.get("animations", []):
        channels: list[dict[str, Any]] = []
        for channel in animation["channels"]:
            sampler = animation["samplers"][channel["sampler"]]
            target = channel["target"]
            input_values = _read_accessor(gltf, binary, sampler["input"])["values"]
            output_values = _read_accessor(gltf, binary, sampler["output"])["values"]
            channels.append(
                {
                    "node": target["node"],
                    "path": target["path"],
                    "interpolation": sampler.get("interpolation", "LINEAR"),
                    "input": [[float(value) for value in item] for item in input_values],
                    "output": [[float(value) for value in item] for item in output_values],
                }
            )
        output[animation["name"]] = sorted(
            channels,
            key=lambda item: (item["node"], item["path"], json.dumps(item, sort_keys=True)),
        )
    return output


def _clip_duration(gltf: dict[str, Any], binary: bytes, clip_name: str) -> float:
    from blendlib_exporter import _read_accessor  # noqa: PLC0415

    for animation in gltf.get("animations", []):
        if animation.get("name") != clip_name:
            continue
        maxima = []
        for sampler in animation.get("samplers", []):
            values = _read_accessor(gltf, binary, sampler["input"])["values"]
            maxima.extend(float(item[0]) for item in values)
        if not maxima:
            raise ExportError("BLENDLIB-ANIM-006", f"Clip '{clip_name}' has no sampled time values.")
        return max(maxima)
    raise ExportError("BLENDLIB-ANIM-006", f"Expected clip '{clip_name}' was not exported.")


def _assert_descriptor_contract(
    descriptor: dict[str, Any], gltf: dict[str, Any], binary: bytes, contract: dict[str, Any]
) -> None:
    expected_animation = contract["descriptor_animation"]
    if descriptor.get("animation") != expected_animation:
        raise ExportError("BLENDLIB-DESC-001", "Showcase descriptor state mapping differs from its source contract.")
    expected_sockets = contract.get("descriptor_sockets")
    if expected_sockets != TIP_SOCKET_DECLARATION:
        raise ExportError("BLENDLIB-DESC-001", "Showcase source contract must declare exactly the canonical tip socket.")
    if descriptor.get("sockets") != expected_sockets:
        raise ExportError("BLENDLIB-DESC-001", "Showcase descriptor socket mapping differs from its source contract.")
    clips = {animation["name"] for animation in gltf.get("animations", [])}
    for state_key, state in expected_animation["states"].items():
        if state["clip"] not in clips:
            raise ExportError("BLENDLIB-ANIM-006", f"State '{state_key}' refers to a missing clip.")
        next_state = state.get("next")
        if next_state is not None and next_state not in expected_animation["states"]:
            raise ExportError("BLENDLIB-DESC-001", f"State '{state_key}' has an unresolved next state.")
        for event in state.get("events", []):
            if float(event["time_seconds"]) > _clip_duration(gltf, binary, state["clip"]):
                raise ExportError("BLENDLIB-ANIM-006", f"State '{state_key}' event exceeds clip duration.")


def _relocate_texture(
    descriptor: dict[str, Any], descriptor_path: Path, result: dict[str, Any], contract: dict[str, Any]
) -> None:
    """Keep the generated PNG inside the task's nested Showcase resource path.

    The generic P2 exporter deliberately flattens a slash-bearing model id in
    the copied texture filename. This asset's accepted layout reserves a
    dedicated ``textures/blendlib/showcase_animation/`` directory, so move only
    the just-generated source file and update its newly generated descriptor.
    """

    texture_paths = result["texture_paths"]
    if len(texture_paths) != 1:
        raise ExportError("BLENDLIB-MAT-003", "Showcase source must export exactly one material texture.")
    material_name, raw_source = next(iter(texture_paths.items()))
    source = Path(raw_source)
    expected_resource = contract["expected_base_color"]
    namespace, relative = expected_resource.split(":", 1)
    if namespace != contract["namespace"]:
        raise ExportError("BLENDLIB-DESC-001", "Showcase texture namespace differs from its contract.")
    relative_path = Path(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts or not relative_path.parts or relative_path.parts[0] != "textures":
        raise ExportError("BLENDLIB-DESC-001", "Showcase texture path must stay under assets/textures.")
    assets_root = descriptor_path.parents[2].resolve()
    source = _require_descendant(source, assets_root, "Generated Showcase texture")
    target = _require_descendant(assets_root / relative_path, assets_root, "Showcase texture target")
    if not source.is_file():
        raise ExportError("BLENDLIB-DESC-001", f"Generated Showcase texture is missing: {source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.resolve() != target.resolve():
        if target.exists():
            target.unlink()
        source.replace(target)
    descriptor["materials"][material_name]["base_color"] = expected_resource
    result["texture_paths"] = {material_name: str(target)}


def export_asset(source_project_root: Path, output_project_root: Path, report_path: Path) -> dict[str, Any]:
    source_root = _require_project_root(source_project_root)
    output_root = _require_output_root(output_project_root)
    contract = _load_contract(source_root)
    blend = source_root / "test-assets" / "showcase-animation" / "source.blend"
    if not blend.is_file():
        raise ExportError("BLENDLIB-CLI-001", f"Showcase animation source is missing: {blend}")
    report_path = Path(report_path).resolve()
    allowed_report_roots = (source_root, output_root)
    if not any(
        report_path.is_relative_to(allowed_root)
        for allowed_root in allowed_report_roots
    ):
        raise ExportError("BLENDLIB-CLI-002", "Report must remain under source or output project root.")
    options = parse_blender_arguments(
        [
            "blender.exe",
            "--",
            "--blend",
            str(blend),
            "--project-root",
            str(output_root),
            "--namespace",
            contract["namespace"],
            "--model-id",
            contract["model_id"],
            "--profile",
            contract["profile"],
            "--collection",
            contract["collection"],
        ]
    )
    result = run_cli(options)
    descriptor_path = Path(result["descriptor_path"])
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    _relocate_texture(descriptor, descriptor_path, result, contract)
    descriptor["animation"] = contract["descriptor_animation"]
    descriptor["sockets"] = contract["descriptor_sockets"]
    descriptor_path.write_text(json.dumps(descriptor, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    gltf, binary = read_glb(Path(result["mesh_path"]))
    validation = validate_glb(
        Path(result["mesh_path"]),
        profile=contract["profile"],
        expected_animation_names=contract["expected_animation_names"],
        expected_bounds=result["source_bounds"],
        expected_material_names=tuple(result["texture_paths"]),
        expected_source_node_names=contract["expected_nodes"],
    )
    assets_root = descriptor_path.parents[2]
    validate_descriptor(descriptor, assets_root, tuple(result["texture_paths"]))
    _assert_descriptor_contract(descriptor, gltf, binary, contract)
    semantics = animation_semantics(gltf, binary)
    if len({_canonical_json_hash(value) for value in semantics.values()}) != len(semantics):
        raise ExportError("BLENDLIB-ANIM-006", "Showcase Actions were exported as alias clip data.")

    result["validation"] = validation
    result["normalized_structure"] = normalized_structure_summary(gltf, descriptor, validation)
    result["showcase_animation_contract"] = {
        "default_scene_roots": contract["expected_default_scene_roots"],
        "animation_semantics": semantics,
        "animation_semantic_hashes": {
            name: _canonical_json_hash(value) for name, value in sorted(semantics.items())
        },
    }
    result["sha256"]["mesh_glb"] = sha256_file(Path(result["mesh_path"]))
    result["sha256"]["descriptor"] = sha256_file(descriptor_path)
    result["sha256"]["normalized_structure"] = _canonical_json_hash(result["normalized_structure"])
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main() -> None:
    args = parse_args(sys.argv)
    source_root = Path(args.source_project_root).resolve()
    result = export_asset(
        source_root,
        Path(args.output_project_root),
        _safe_report_path(source_root, args.report),
    )
    print(
        "BLENDLIB_P5_SHOWCASE_ANIMATION_EXPORT_OK "
        + json.dumps(
            {
                "animations": result["validation"]["animation_names"],
                "descriptor": result["descriptor_path"],
                "mesh": result["mesh_path"],
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
