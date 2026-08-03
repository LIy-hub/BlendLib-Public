# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Deterministic BlendLib v1 GLB exporter for Blender 5.x.

The runtime consumes only the produced strict GLB and descriptor.  This module
is intentionally a Blender-bound export boundary: no generated artifact is
read by the Java runtime during P2.
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import math
import re
import struct
import sys
from pathlib import Path
from typing import Any, BinaryIO, Iterable, Sequence

# Blender headless validation is run against a source checkout. Never create
# `__pycache__` artifacts there while importing this exporter.
sys.dont_write_bytecode = True

try:  # Allows the parser and GLB utilities to be tested outside Blender.
    import bpy  # type: ignore
except ModuleNotFoundError:  # pragma: no cover - Blender supplies bpy.
    bpy = None


GLB_MAGIC = 0x46546C67
GLB_VERSION = 2
JSON_CHUNK_TYPE = 0x4E4F534A
BIN_CHUNK_TYPE = 0x004E4942
MAX_GLB_BYTES = 64 * 1024 * 1024
MAX_PNG_BYTES = 64 * 1024 * 1024
MAX_SOURCE_BLEND_BYTES = 1024 * 1024 * 1024
IO_BUFFER_BYTES = 8 * 1024
MAX_VERTICES = 1_000_000
MAX_INDICES = 3_000_000
MAX_NODES = 4_096
MAX_MATERIALS = 256
MAX_SKIN_JOINTS = 512
MAX_CLIPS = 256
RESOURCE_TOKEN = re.compile(r"^[a-z0-9._/-]+$")
EPSILON = 1.0e-5


class ExportError(RuntimeError):
    """A user-facing export failure with a stable BlendLib-style code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


@dataclasses.dataclass(frozen=True)
class ExportOptions:
    blend_path: Path
    project_root: Path
    namespace: str
    model_id: str
    profile: str
    collection_name: str | None
    output_resource_root: str
    report_path: Path | None
    # X5 authoring-only controls.  Defaults keep the P2 API and output surface
    # unchanged for existing callers and scripts.
    authoring_output_root: str = "build/blendlib-authoring"
    dev_refresh_path: Path | None = None
    dev_session_token: str | None = None
    dev_generation: int | None = None
    batch_manifest_path: Path | None = None
    texture_source_roots: tuple[Path, ...] | None = None


def parse_blender_arguments(argv: Sequence[str]) -> ExportOptions:
    """Parse only the user arguments following Blender's ``--`` separator."""

    tokens = list(argv)
    if "--" not in tokens:
        raise ExportError(
            "BLENDLIB-CLI-001",
            "BlendLib arguments must appear after Blender's '--' separator.",
        )

    parser = argparse.ArgumentParser(
        prog="export_blendlib.py --",
        description="Export one strict BlendLib v1 GLB asset.",
    )
    parser.add_argument("--blend", required=True)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--model-id", required=True)
    parser.add_argument(
        "--profile",
        required=True,
        choices=("blendlib:rigid_v1", "blendlib:skinned_v1"),
    )
    parser.add_argument("--collection")
    parser.add_argument(
        "--output-resource-root",
        default="src/main/resources",
        help="Project-relative root containing assets/<namespace>/...",
    )
    parser.add_argument(
        "--report",
        help="Optional project-relative JSON export report.",
    )
    parser.add_argument(
        "--authoring-output-root",
        default="build/blendlib-authoring",
        help="X5-only project-relative authoring sidecar/report root.",
    )
    parser.add_argument(
        "--dev-refresh-file",
        help="X5-only project-relative local refresh message file.",
    )
    parser.add_argument(
        "--dev-session-token",
        help="X5-only local development session token (never emitted in reports).",
    )
    parser.add_argument(
        "--dev-generation",
        type=int,
        help="X5-only monotonic local development generation.",
    )
    parser.add_argument(
        "--batch-manifest",
        help="X5-only project-relative batch manifest; used by x5_export_blendlib.py.",
    )
    try:
        parsed = parser.parse_args(tokens[tokens.index("--") + 1 :])
    except SystemExit as error:
        raise ExportError("BLENDLIB-CLI-001", "Invalid BlendLib CLI arguments.") from error

    namespace = _require_resource_token(parsed.namespace, "namespace")
    model_id = _require_resource_token(parsed.model_id, "model id")
    project_root = Path(parsed.project_root).expanduser().resolve()
    output_root = _require_safe_relative(parsed.output_resource_root, "output resource root")
    authoring_output_root = _require_safe_relative(parsed.authoring_output_root, "authoring output root")
    report_path = (
        _resolve_under(project_root, parsed.report, "report") if parsed.report else None
    )
    blend_path = Path(parsed.blend).expanduser().resolve()
    if not blend_path.is_file():
        raise ExportError("BLENDLIB-CLI-001", f"Blend source does not exist: {blend_path}")
    return ExportOptions(
        blend_path=blend_path,
        project_root=project_root,
        namespace=namespace,
        model_id=model_id,
        profile=parsed.profile,
        collection_name=parsed.collection,
        output_resource_root=output_root.as_posix(),
        report_path=report_path,
        authoring_output_root=authoring_output_root.as_posix(),
        dev_refresh_path=(
            _resolve_under(project_root, parsed.dev_refresh_file, "dev refresh file")
            if parsed.dev_refresh_file
            else None
        ),
        dev_session_token=parsed.dev_session_token,
        dev_generation=parsed.dev_generation,
        batch_manifest_path=(
            _resolve_under(project_root, parsed.batch_manifest, "batch manifest")
            if parsed.batch_manifest
            else None
        ),
        texture_source_roots=(blend_path.parent.resolve(strict=True), project_root.resolve()),
    )


def _require_resource_token(value: str, label: str) -> str:
    if not value or not RESOURCE_TOKEN.fullmatch(value) or ".." in value:
        raise ExportError(
            "BLENDLIB-CLI-002",
            f"Invalid {label} '{value}'; use only [a-z0-9._/-] without '..'.",
        )
    return value


def _require_safe_relative(value: str, label: str) -> Path:
    path = Path(value)
    if path.is_absolute() or any(part == ".." for part in path.parts):
        raise ExportError(
            "BLENDLIB-CLI-002",
            f"{label} must be a project-relative path without '..': {value}",
        )
    return path


def _resolve_under(root: Path, raw_path: str, label: str) -> Path:
    relative = _require_safe_relative(raw_path, label)
    resolved_root = root.resolve()
    resolved = (resolved_root / relative).resolve()
    try:
        resolved.relative_to(resolved_root)
    except ValueError as error:
        raise ExportError(
            "BLENDLIB-CLI-002", f"{label} escapes the project root: {raw_path}"
        ) from error
    return resolved


def _require_blender() -> Any:
    if bpy is None:
        raise ExportError("BLENDLIB-CLI-003", "This operation requires Blender's bpy module.")
    return bpy


def run_cli(options: ExportOptions) -> dict[str, Any]:
    blender = _require_blender()
    blender.ops.wm.open_mainfile(filepath=str(options.blend_path))
    result = export_open_blend(options)
    if options.report_path is not None:
        options.report_path.parent.mkdir(parents=True, exist_ok=True)
        _write_json(options.report_path, result)
    return result


def export_open_blend(options: ExportOptions) -> dict[str, Any]:
    """Export the currently open .blend file into a resource-root layout."""

    blender = _require_blender()
    collection = _select_collection(options.collection_name)
    objects, warnings = _collect_export_objects(collection)
    _validate_source_objects(objects, options.profile)
    action_names = _discover_actions(objects)
    source_bounds = _source_world_bounds(objects)

    resource_root = _resolve_under(
        options.project_root, options.output_resource_root, "output resource root"
    )
    assets_root = resource_root / "assets" / options.namespace
    mesh_path = assets_root / "models3d" / f"{options.model_id}.glb"
    descriptor_path = assets_root / "blend_models" / f"{options.model_id}.json"
    mesh_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor_path.parent.mkdir(parents=True, exist_ok=True)

    raw_path = mesh_path.with_suffix(".raw.glb")
    try:
        _export_raw_glb(collection, raw_path)
        raw_gltf, raw_binary = read_glb(raw_path, allowed_roots=(options.project_root,))
        gltf, binary = strip_runtime_images(raw_gltf, raw_binary)
        write_glb(mesh_path, gltf, binary)
    finally:
        if raw_path.exists():
            raw_path.unlink()

    textures = _copy_external_material_textures(objects, assets_root, options)
    descriptor = _build_descriptor(options, action_names, textures)
    _write_json(descriptor_path, descriptor)
    validation = validate_glb(
        mesh_path,
        profile=options.profile,
        expected_animation_names=action_names,
        expected_bounds=source_bounds,
        expected_material_names=tuple(textures),
        expected_source_node_names=_source_node_names(objects),
    )
    validate_descriptor(descriptor, assets_root, tuple(textures))
    summary = normalized_structure_summary(gltf, descriptor, validation)
    result = {
        "format": "blendlib-p2-export-report-v1",
        "blend": str(options.blend_path),
        "collection": collection.name,
        "namespace": options.namespace,
        "model_id": options.model_id,
        "profile": options.profile,
        "mesh_path": str(mesh_path),
        "descriptor_path": str(descriptor_path),
        "texture_paths": {
            name: str(assets_root / Path(resource.split(":", 1)[1]))
            for name, resource in textures.items()
        },
        "warnings": warnings,
        "source_bounds": source_bounds,
        "validation": validation,
        "normalized_structure": summary,
        "sha256": {
            "source_blend": sha256_file(
                options.blend_path,
                maximum_bytes=MAX_SOURCE_BLEND_BYTES,
                allowed_roots=(options.blend_path.parent,),
            ),
            "mesh_glb": sha256_file(
                mesh_path, maximum_bytes=MAX_GLB_BYTES, allowed_roots=(options.project_root,)
            ),
            "descriptor": sha256_file(
                descriptor_path, maximum_bytes=MAX_GLB_BYTES, allowed_roots=(options.project_root,)
            ),
            "normalized_structure": sha256_bytes(_canonical_json_bytes(summary)),
        },
    }
    return result


def _select_collection(name: str | None) -> Any:
    blender = _require_blender()
    if name:
        collection = blender.data.collections.get(name)
        if collection is None:
            raise ExportError("BLENDLIB-EXPORT-001", f"Collection '{name}' was not found.")
        return collection

    top_level = [
        collection
        for collection in blender.context.scene.collection.children
        if collection.all_objects
    ]
    if len(top_level) != 1:
        names = ", ".join(sorted(collection.name for collection in top_level)) or "none"
        raise ExportError(
            "BLENDLIB-EXPORT-001",
            "Exactly one non-empty top-level collection is required when --collection is omitted; "
            f"found: {names}.",
        )
    return top_level[0]


def _collect_export_objects(collection: Any) -> tuple[list[Any], list[str]]:
    supported_types = {"EMPTY", "MESH", "ARMATURE"}
    warnings: list[str] = []
    objects: list[Any] = []
    if len(collection.all_objects) > MAX_NODES:
        raise ExportError("BLENDLIB-LIMIT-001", "Export collection contains more than 4096 objects.")
    for obj in sorted(collection.all_objects, key=lambda item: item.name):
        if obj.type in {"CAMERA", "LIGHT"}:
            warnings.append(f"Filtered non-runtime {obj.type.lower()} '{obj.name}'.")
            continue
        if obj.type not in supported_types:
            raise ExportError(
                "BLENDLIB-EXPORT-002",
                f"Unsupported object type '{obj.type}' on '{obj.name}'.",
            )
        objects.append(obj)

    if not objects:
        raise ExportError("BLENDLIB-EXPORT-001", "The selected collection has no exportable objects.")
    object_set = set(objects)
    roots = [obj for obj in objects if obj.parent not in object_set]
    if len(roots) != 1:
        names = ", ".join(sorted(obj.name for obj in roots)) or "none"
        raise ExportError(
            "BLENDLIB-EXPORT-001",
            f"Selected collection must have exactly one export root; found: {names}.",
        )
    return objects, warnings


def _validate_source_objects(objects: Sequence[Any], profile: str) -> None:
    names: list[str] = []
    skin_meshes = 0
    for obj in objects:
        names.append(obj.name)
        if obj.rigid_body is not None or obj.rigid_body_constraint is not None:
            raise ExportError(
                "BLENDLIB-EXPORT-003",
                f"Physics is unsupported; bake it before exporting '{obj.name}'.",
            )
        if len(obj.particle_systems) != 0:
            raise ExportError(
                "BLENDLIB-EXPORT-003",
                f"Particle systems are unsupported; bake or remove '{obj.name}'.",
            )
        if len(obj.constraints) != 0:
            raise ExportError(
                "BLENDLIB-EXPORT-003",
                f"Object constraints are unsupported at export; bake '{obj.name}' first.",
            )
        scale = tuple(float(value) for value in obj.scale)
        if min(scale) <= 0.0 or max(scale) - min(scale) > EPSILON:
            raise ExportError(
                "BLENDLIB-EXPORT-004",
                f"Non-uniform or negative scale on '{obj.name}'; apply/bake scale first.",
            )
        _validate_modifiers(obj)
        if obj.type == "MESH":
            _validate_mesh(obj)
            armature_modifiers = [
                modifier for modifier in obj.modifiers if modifier.type == "ARMATURE"
            ]
            if armature_modifiers:
                skin_meshes += 1
                _validate_skin_weights(obj)
            elif profile == "blendlib:skinned_v1":
                raise ExportError(
                    "BLENDLIB-EXPORT-005",
                    f"Skinned profile requires an Armature modifier on mesh '{obj.name}'.",
                )
        if obj.type == "ARMATURE":
            names.extend(bone.name for bone in obj.data.bones)

    duplicate_names = _duplicates(names)
    if duplicate_names:
        raise ExportError(
            "BLENDLIB-EXPORT-006",
            f"Duplicate export node/bone names are forbidden: {', '.join(duplicate_names)}.",
        )
    if profile == "blendlib:rigid_v1" and skin_meshes:
        raise ExportError(
            "BLENDLIB-EXPORT-005", "Rigid profile cannot contain Armature-modified meshes."
        )
    if profile == "blendlib:skinned_v1" and skin_meshes == 0:
        raise ExportError(
            "BLENDLIB-EXPORT-005", "Skinned profile requires at least one skinned mesh."
        )


def _validate_modifiers(obj: Any) -> None:
    unsupported = {
        "CLOTH",
        "COLLISION",
        "DYNAMIC_PAINT",
        "FLUID",
        "PARTICLE_SYSTEM",
        "SOFT_BODY",
    }
    active_unsupported = sorted(
        modifier.type for modifier in obj.modifiers if modifier.type in unsupported
    )
    if active_unsupported:
        raise ExportError(
            "BLENDLIB-EXPORT-003",
            f"Unsupported dynamic modifier(s) on '{obj.name}': {', '.join(active_unsupported)}.",
        )


def _validate_mesh(obj: Any) -> None:
    mesh = obj.data
    if len(mesh.vertices) == 0 or len(mesh.polygons) == 0:
        raise ExportError("BLENDLIB-EXPORT-007", f"Mesh '{obj.name}' has no geometry.")
    if mesh.uv_layers.active is None or len(mesh.uv_layers.active.data) == 0:
        raise ExportError("BLENDLIB-EXPORT-007", f"Mesh '{obj.name}' is missing UV0.")
    if len(obj.material_slots) == 0:
        raise ExportError(
            "BLENDLIB-EXPORT-007", f"Mesh '{obj.name}' must have a named material slot."
        )
    for slot in obj.material_slots:
        if slot.material is None or not slot.material.name.strip():
            raise ExportError(
                "BLENDLIB-EXPORT-007",
                f"Mesh '{obj.name}' has an unnamed or empty material slot.",
            )
    for vertex in mesh.vertices:
        normal = vertex.normal
        if not all(math.isfinite(component) for component in normal) or normal.length <= EPSILON:
            raise ExportError(
                "BLENDLIB-EXPORT-007", f"Mesh '{obj.name}' has invalid vertex normals."
            )


def _validate_skin_weights(obj: Any) -> None:
    if not obj.vertex_groups:
        raise ExportError(
            "BLENDLIB-EXPORT-005", f"Skinned mesh '{obj.name}' has no vertex groups."
        )
    for vertex in obj.data.vertices:
        weights = [group.weight for group in vertex.groups if group.weight > EPSILON]
        if not weights or len(weights) > 4:
            raise ExportError(
                "BLENDLIB-EXPORT-005",
                f"Vertex {vertex.index} in '{obj.name}' must have one to four effective weights.",
            )
        if not math.isclose(sum(weights), 1.0, rel_tol=0.0, abs_tol=EPSILON):
            raise ExportError(
                "BLENDLIB-EXPORT-005",
                f"Vertex {vertex.index} in '{obj.name}' has non-normalized skin weights.",
            )


def _discover_action_objects(objects: Sequence[Any]) -> tuple[Any, ...]:
    """Return exactly the bound/NLA Actions considered by strict-v1 export."""

    actions: dict[str, Any] = {}
    for obj in objects:
        animation_data = obj.animation_data
        if animation_data is None:
            continue
        if animation_data.action is not None:
            actions[animation_data.action.name] = animation_data.action
        for track in animation_data.nla_tracks:
            for strip in track.strips:
                if strip.action is not None:
                    actions[strip.action.name] = strip.action
    if len(actions) > MAX_CLIPS:
        raise ExportError("BLENDLIB-LIMIT-001", "Exported Action count exceeds 256.")
    ordered = tuple(actions[name] for name in sorted(actions))
    for action in ordered:
        for curve in _action_fcurves(action):
            for keyframe in curve.keyframe_points:
                if not all(math.isfinite(value) for value in keyframe.co):
                    raise ExportError(
                        "BLENDLIB-EXPORT-008",
                        f"Action '{action.name}' contains a non-finite keyframe value.",
                    )
    return ordered


def _discover_actions(objects: Sequence[Any]) -> tuple[str, ...]:
    return tuple(action.name for action in _discover_action_objects(objects))


def _action_fcurves(action: Any) -> Iterable[Any]:
    """Yield legacy and Blender 5 layered-action F-curves uniformly."""

    legacy = getattr(action, "fcurves", None)
    if legacy is not None:
        yield from legacy
        return
    for layer in action.layers:
        for strip in layer.strips:
            channelbags = getattr(strip, "channelbags", ())
            for channelbag in channelbags:
                yield from channelbag.fcurves


def _source_world_bounds(objects: Sequence[Any]) -> dict[str, list[float]]:
    blender = _require_blender()
    depsgraph = blender.context.evaluated_depsgraph_get()
    points: list[tuple[float, float, float]] = []
    for obj in objects:
        if obj.type != "MESH":
            continue
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh()
        try:
            for vertex in mesh.vertices:
                world = evaluated.matrix_world @ vertex.co
                points.append((float(world.x), float(world.z), float(-world.y)))
        finally:
            evaluated.to_mesh_clear()
    if not points:
        raise ExportError("BLENDLIB-EXPORT-007", "No mesh vertices were available for bounds.")
    return {
        "min": [min(point[index] for point in points) for index in range(3)],
        "max": [max(point[index] for point in points) for index in range(3)],
    }


def _source_node_names(objects: Sequence[Any]) -> tuple[str, ...]:
    names: list[str] = []
    for obj in objects:
        names.append(obj.name)
        if obj.type == "ARMATURE":
            names.extend(bone.name for bone in obj.data.bones)
    return tuple(sorted(names))


def _find_layer_collection(layer_collection: Any, collection: Any) -> Any | None:
    if layer_collection.collection == collection:
        return layer_collection
    for child in layer_collection.children:
        result = _find_layer_collection(child, collection)
        if result is not None:
            return result
    return None


def _export_raw_glb(collection: Any, output_path: Path) -> None:
    blender = _require_blender()
    layer_collection = _find_layer_collection(blender.context.view_layer.layer_collection, collection)
    if layer_collection is None:
        raise ExportError(
            "BLENDLIB-EXPORT-001", f"Collection '{collection.name}' is not linked to the active scene."
        )
    blender.context.view_layer.active_layer_collection = layer_collection
    result = blender.ops.export_scene.gltf(
        filepath=str(output_path),
        export_format="GLB",
        use_selection=False,
        use_active_collection=True,
        use_active_collection_with_nested=True,
        export_yup=True,
        export_apply=True,
        export_texcoords=True,
        export_normals=True,
        export_materials="EXPORT",
        export_image_format="NONE",
        export_cameras=False,
        export_lights=False,
        export_animations=True,
        export_animation_mode="ACTIONS",
        export_force_sampling=True,
        export_sampling_interpolation_fallback="LINEAR",
        export_skins=True,
        export_influence_nb=4,
        export_all_influences=False,
        export_morph=False,
        export_morph_animation=False,
        export_draco_mesh_compression_enable=False,
        export_vertex_color="NONE",
    )
    if "FINISHED" not in result or not output_path.is_file():
        raise ExportError("BLENDLIB-EXPORT-009", "Blender glTF exporter did not produce a GLB file.")


def _copy_external_material_textures(
    objects: Sequence[Any], assets_root: Path, options: ExportOptions
) -> dict[str, str]:
    material_sources: dict[str, Path] = {}
    for obj in objects:
        if obj.type != "MESH":
            continue
        for slot in obj.material_slots:
            material = slot.material
            assert material is not None
            source = _material_texture_source(material)
            prior = material_sources.setdefault(material.name, source)
            if prior != source:
                raise ExportError(
                    "BLENDLIB-EXPORT-010",
                    f"Material '{material.name}' maps to more than one base-color image.",
                )

    resources: dict[str, str] = {}
    for material_name, source in sorted(material_sources.items()):
        if source.suffix.lower() != ".png":
            raise ExportError(
                "BLENDLIB-EXPORT-010",
                f"Material '{material_name}' texture must be an external PNG: {source}",
            )
        texture_name = f"{_path_slug(options.model_id)}__{_path_slug(material_name)}.png"
        target = assets_root / "textures" / "blendlib" / texture_name
        target.parent.mkdir(parents=True, exist_ok=True)
        _copy_bounded_file(
            source,
            target,
            maximum_bytes=MAX_PNG_BYTES,
            allowed_roots=_authorized_texture_roots(options),
            output_root=assets_root,
        )
        # Descriptor resource IDs must name the concrete external PNG file.
        # Do not rely on a later adapter to infer a missing extension.
        resources[material_name] = f"{options.namespace}:textures/blendlib/{texture_name}"
    if not resources:
        raise ExportError("BLENDLIB-EXPORT-010", "No named material texture was found.")
    if len(resources) > MAX_MATERIALS:
        raise ExportError("BLENDLIB-LIMIT-001", "Material slot count exceeds 256.")
    return resources


def _authorized_texture_roots(options: ExportOptions) -> tuple[Path, ...]:
    """Freeze the roots accepted by both X5 preflight and staged legacy copy."""

    configured = getattr(options, "texture_source_roots", None)
    candidates = configured if configured is not None else (options.blend_path.parent, options.project_root)
    roots: list[Path] = []
    try:
        for candidate in candidates:
            try:
                resolved = Path(candidate).resolve(strict=True)
            except FileNotFoundError:
                # A first export may create its project root later.  A root
                # that does not exist cannot authorize an existing texture,
                # so omitting it is the fail-closed equivalent until staging.
                continue
            if not resolved.is_dir():
                raise OSError("texture root is not a directory")
            if resolved not in roots:
                roots.append(resolved)
    except OSError as error:
        raise ExportError("BLENDLIB-EXPORT-010", "Texture source roots cannot be resolved safely.") from error
    if not roots or len(roots) > 4:
        raise ExportError("BLENDLIB-EXPORT-010", "Texture source roots must contain one to four bounded directories.")
    return tuple(roots)


def _material_texture_source(material: Any) -> Path:
    blender = _require_blender()
    if not material.use_nodes or material.node_tree is None:
        raise ExportError(
            "BLENDLIB-EXPORT-010",
            f"Material '{material.name}' needs a node-based external PNG texture.",
        )
    images = []
    for node in material.node_tree.nodes:
        if node.type == "TEX_IMAGE" and node.image is not None:
            images.append(node.image)
    if len(images) != 1:
        raise ExportError(
            "BLENDLIB-EXPORT-010",
            f"Material '{material.name}' must have exactly one base-color image node.",
        )
    image = images[0]
    if image.packed_file is not None:
        raise ExportError(
            "BLENDLIB-EXPORT-010",
            f"Material '{material.name}' texture is packed; use an external PNG source.",
        )
    filepath = blender.path.abspath(image.filepath)
    return Path(filepath).resolve()


def _path_slug(value: str) -> str:
    normalized = value.lower().replace("/", "_")
    return re.sub(r"[^a-z0-9._-]", "_", normalized)


def _build_descriptor(
    options: ExportOptions, action_names: Sequence[str], materials: dict[str, str]
) -> dict[str, Any]:
    descriptor: dict[str, Any] = {
        "format_version": 1,
        "profile": options.profile,
        "mesh": f"{options.namespace}:models3d/{options.model_id}.glb",
        "units_per_block": 1.0,
        "materials": {
            name: {
                "base_color": resource,
                "mode": "opaque",
                "emissive": False,
                "double_sided": False,
            }
            for name, resource in sorted(materials.items())
        },
        "extensions_used": [],
        "extensions_required": [],
        "extensions": {},
    }
    if action_names:
        states: dict[str, Any] = {}
        for action_name in action_names:
            logical_key = _path_slug(action_name)
            state_key = f"{options.namespace}:{logical_key}"
            if state_key in states:
                raise ExportError(
                    "BLENDLIB-EXPORT-008",
                    f"Action names collide after logical-key normalization: '{action_name}'.",
                )
            states[state_key] = {"clip": action_name, "loop": True, "speed": 1.0}
        descriptor["animation"] = {
            "initial_state": next(iter(states)),
            "states": states,
        }
    return descriptor


def validate_descriptor(
    descriptor: dict[str, Any], assets_root: Path, material_names: Sequence[str]
) -> None:
    if descriptor.get("format_version") != 1:
        raise ExportError("BLENDLIB-DESC-001", "Descriptor version must be exactly 1.")
    if descriptor.get("profile") not in {"blendlib:rigid_v1", "blendlib:skinned_v1"}:
        raise ExportError("BLENDLIB-DESC-001", "Descriptor profile is not supported by v1.")
    materials = descriptor.get("materials")
    if not isinstance(materials, dict) or tuple(sorted(materials)) != tuple(sorted(material_names)):
        raise ExportError(
            "BLENDLIB-MAT-003", "Descriptor materials do not exactly map the GLB material slots."
        )
    for material in materials.values():
        resource = material.get("base_color")
        if not isinstance(resource, str) or ":" not in resource:
            raise ExportError("BLENDLIB-DESC-001", "Descriptor base_color must be a resource ID.")
        namespace, path = resource.split(":", 1)
        if not RESOURCE_TOKEN.fullmatch(namespace) or not RESOURCE_TOKEN.fullmatch(path):
            raise ExportError("BLENDLIB-DESC-001", f"Unsafe texture resource ID: {resource}")
        if not path.endswith(".png"):
            raise ExportError(
                "BLENDLIB-DESC-001",
                f"Descriptor base_color must name an external PNG file: {resource}",
            )
        texture_path = assets_root / path
        if not texture_path.is_file():
            raise ExportError(
                "BLENDLIB-DESC-001", f"Descriptor texture was not copied: {texture_path}"
            )


def _bounded_regular_file(
    path: Path,
    maximum_bytes: int,
    code: str,
    description: str,
    allowed_roots: Sequence[Path] | None = None,
) -> tuple[Path, int]:
    """Resolve a regular file beneath an authorized root before reading it."""

    try:
        requested = Path(path)
        roots = tuple(Path(root).resolve(strict=True) for root in (allowed_roots or (requested.parent,)))
        resolved = requested.resolve(strict=True)
        if not resolved.is_file() or not any(resolved == root or resolved.is_relative_to(root) for root in roots):
            raise OSError("file is not beneath an authorized root")
        declared_size = resolved.stat().st_size
    except OSError as error:
        raise ExportError(code, f"{description} is not a safe regular file beneath its authorized root.") from error
    if declared_size < 0 or declared_size > maximum_bytes:
        raise ExportError(code, f"{description} exceeds its bounded size.")
    return resolved, declared_size


def _read_exact_bounded(stream: Any, size: int, code: str, message: str) -> bytes:
    payload = bytearray()
    while len(payload) < size:
        requested = min(IO_BUFFER_BYTES, size - len(payload))
        block = stream.read(requested)
        if not block or len(block) > requested:
            raise ExportError(code, message)
        payload.extend(block)
    return bytes(payload)


def _copy_bounded_stream(stream: Any, target: Any, declared_size: int, maximum_bytes: int) -> None:
    if declared_size < 0 or declared_size > maximum_bytes:
        raise ExportError("BLENDLIB-LIMIT-001", "PNG exceeds its bounded size.")
    total = 0
    while total < declared_size:
        requested = min(IO_BUFFER_BYTES, declared_size - total)
        block = stream.read(requested)
        if not block or len(block) > requested:
            raise ExportError("BLENDLIB-EXPORT-010", "PNG changed while being copied.")
        total += len(block)
        if total > maximum_bytes:
            raise ExportError("BLENDLIB-LIMIT-001", "PNG exceeds its bounded size.")
        target.write(block)
    if stream.read(1):
        raise ExportError("BLENDLIB-EXPORT-010", "PNG grew while being copied.")


def _copy_bounded_file(
    source: Path,
    target: Path,
    *,
    maximum_bytes: int,
    allowed_roots: Sequence[Path],
    output_root: Path,
) -> None:
    resolved_source, declared_size = _bounded_regular_file(
        source, maximum_bytes, "BLENDLIB-EXPORT-010", "PNG texture", allowed_roots
    )
    try:
        resolved_output_root = output_root.resolve(strict=True)
        resolved_parent = target.parent.resolve(strict=True)
        if resolved_parent != resolved_output_root and not resolved_parent.is_relative_to(resolved_output_root):
            raise OSError("texture target escapes output root")
        with resolved_source.open("rb") as source_stream, target.open("wb") as target_stream:
            _copy_bounded_stream(source_stream, target_stream, declared_size, maximum_bytes)
    except ExportError:
        try:
            target.unlink(missing_ok=True)
        except OSError:
            pass
        raise
    except OSError as error:
        try:
            target.unlink(missing_ok=True)
        except OSError:
            pass
        raise ExportError("BLENDLIB-EXPORT-010", "PNG could not be copied within its authorized roots and bounds.") from error


def read_glb(path: Path, *, allowed_roots: Sequence[Path] | None = None) -> tuple[dict[str, Any], bytes]:
    resolved, declared_size = _bounded_regular_file(
        path, MAX_GLB_BYTES, "BLENDLIB-LIMIT-001", "GLB", allowed_roots
    )
    if declared_size < 20:
        raise ExportError("BLENDLIB-GLB-001", "GLB is shorter than the mandatory header and JSON chunk.")
    chunks: list[tuple[int, bytes]] = []
    with resolved.open("rb") as stream:
        header = _read_exact_bounded(stream, 12, "BLENDLIB-GLB-001", "GLB changed while reading its header.")
        magic, version, internal_length = struct.unpack("<III", header)
        if magic != GLB_MAGIC or version != GLB_VERSION or internal_length != declared_size:
            raise ExportError("BLENDLIB-GLB-001", "Invalid GLB magic, version, or declared length.")
        offset = 12
        while offset < declared_size:
            if offset + 8 > declared_size:
                raise ExportError("BLENDLIB-GLB-001", "GLB chunk header exceeds declared length.")
            chunk_header = _read_exact_bounded(
                stream, 8, "BLENDLIB-GLB-001", "GLB changed while reading a chunk header."
            )
            chunk_length, chunk_type = struct.unpack("<II", chunk_header)
            offset += 8
            end = offset + chunk_length
            if chunk_length % 4 != 0 or end > declared_size:
                raise ExportError("BLENDLIB-GLB-001", "GLB chunk bounds are invalid.")
            chunks.append((chunk_type, _read_exact_bounded(
                stream, chunk_length, "BLENDLIB-GLB-001", "GLB changed while reading a chunk."
            )))
            offset = end
        if stream.read(1):
            raise ExportError("BLENDLIB-GLB-001", "GLB grew while being read.")
    if offset != declared_size or not chunks or chunks[0][0] != JSON_CHUNK_TYPE:
        raise ExportError("BLENDLIB-GLB-001", "GLB must begin with exactly one JSON chunk.")
    if len(chunks) > 2 or any(chunk_type not in {JSON_CHUNK_TYPE, BIN_CHUNK_TYPE} for chunk_type, _ in chunks):
        raise ExportError("BLENDLIB-GLB-001", "GLB contains unsupported chunk types.")
    if len(chunks) == 2 and chunks[1][0] != BIN_CHUNK_TYPE:
        raise ExportError("BLENDLIB-GLB-001", "GLB BIN chunk must follow JSON when present.")
    try:
        gltf = json.loads(chunks[0][1].decode("utf-8").rstrip(" \t\r\n\x00"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ExportError("BLENDLIB-GLB-001", "GLB JSON chunk is invalid UTF-8 JSON.") from error
    if not isinstance(gltf, dict):
        raise ExportError("BLENDLIB-GLB-001", "GLB JSON root must be an object.")
    return gltf, chunks[1][1] if len(chunks) == 2 else b""


def strip_runtime_images(gltf: dict[str, Any], binary: bytes) -> tuple[dict[str, Any], bytes]:
    """Drop image/texture records and compact the remaining accessor binary data."""

    clean = json.loads(json.dumps(gltf))
    clean.pop("images", None)
    clean.pop("textures", None)
    clean.pop("samplers", None)
    clean.pop("extensions", None)
    clean.pop("extensionsUsed", None)
    clean.pop("extensionsRequired", None)
    for material in clean.get("materials", []):
        material.pop("normalTexture", None)
        material.pop("occlusionTexture", None)
        material.pop("emissiveTexture", None)
        material.pop("extensions", None)
        pbr = material.get("pbrMetallicRoughness")
        if isinstance(pbr, dict):
            pbr.pop("baseColorTexture", None)
            pbr.pop("metallicRoughnessTexture", None)

    used_views = {
        accessor.get("bufferView")
        for accessor in clean.get("accessors", [])
        if isinstance(accessor, dict) and isinstance(accessor.get("bufferView"), int)
    }
    original_views = clean.get("bufferViews", [])
    if not isinstance(original_views, list):
        raise ExportError("BLENDLIB-GLB-001", "GLB bufferViews must be an array.")
    replacement_views: list[dict[str, Any]] = []
    mapping: dict[int, int] = {}
    compacted = bytearray()
    for old_index in sorted(used_views):
        if old_index < 0 or old_index >= len(original_views):
            raise ExportError("BLENDLIB-GLB-014", "Accessor references an invalid bufferView.")
        original = original_views[old_index]
        if not isinstance(original, dict):
            raise ExportError("BLENDLIB-GLB-014", "bufferView must be an object.")
        start = int(original.get("byteOffset", 0))
        length = int(original.get("byteLength", -1))
        if start < 0 or length < 0 or start + length > len(binary):
            raise ExportError("BLENDLIB-GLB-014", "bufferView is outside the GLB BIN chunk.")
        while len(compacted) % 4:
            compacted.append(0)
        replacement = {
            key: value
            for key, value in original.items()
            if key not in {"buffer", "byteOffset", "byteLength"}
        }
        replacement.update(
            {"buffer": 0, "byteOffset": len(compacted), "byteLength": length}
        )
        mapping[old_index] = len(replacement_views)
        replacement_views.append(replacement)
        compacted.extend(binary[start : start + length])
    for accessor in clean.get("accessors", []):
        if isinstance(accessor, dict) and "bufferView" in accessor:
            accessor["bufferView"] = mapping[accessor["bufferView"]]
    clean["bufferViews"] = replacement_views
    clean["buffers"] = [{"byteLength": len(compacted)}]
    return clean, bytes(compacted)


def write_glb(path: Path, gltf: dict[str, Any], binary: bytes) -> None:
    json_chunk = _canonical_json_bytes(gltf)
    json_chunk += b" " * ((-len(json_chunk)) % 4)
    binary_chunk = binary + (b"\x00" * ((-len(binary)) % 4))
    chunks = [struct.pack("<II", len(json_chunk), JSON_CHUNK_TYPE) + json_chunk]
    if binary_chunk:
        chunks.append(struct.pack("<II", len(binary_chunk), BIN_CHUNK_TYPE) + binary_chunk)
    payload = b"".join(chunks)
    path.write_bytes(struct.pack("<III", GLB_MAGIC, GLB_VERSION, 12 + len(payload)) + payload)


def validate_glb(
    path: Path,
    *,
    profile: str,
    expected_animation_names: Sequence[str],
    expected_bounds: dict[str, Sequence[float]],
    expected_material_names: Sequence[str],
    expected_source_node_names: Sequence[str],
) -> dict[str, Any]:
    gltf, binary = read_glb(path)
    asset = gltf.get("asset")
    if not isinstance(asset, dict) or asset.get("version") != "2.0":
        raise ExportError("BLENDLIB-GLB-001", "GLB asset.version must be '2.0'.")
    forbidden_top_level = {"images", "textures", "samplers", "cameras"}
    present = sorted(forbidden_top_level.intersection(gltf))
    if present:
        raise ExportError(
            "BLENDLIB-EXPORT-011", f"Runtime GLB still contains embedded texture/camera data: {present}."
        )
    if gltf.get("extensions") or gltf.get("extensionsUsed") or gltf.get("extensionsRequired"):
        raise ExportError("BLENDLIB-EXT-001", "P2 canonical GLB may not contain glTF extensions.")

    nodes = _expect_list(gltf, "nodes")
    if not nodes or len(nodes) > MAX_NODES:
        raise ExportError("BLENDLIB-LIMIT-001", "GLB node count is missing or exceeds 4096.")
    node_names = []
    for node in nodes:
        if not isinstance(node, dict) or not isinstance(node.get("name"), str) or not node["name"]:
            raise ExportError("BLENDLIB-GLB-001", "Every exported GLB node requires a non-empty name.")
        node_names.append(node["name"])
    duplicate_nodes = _duplicates(node_names)
    if duplicate_nodes:
        raise ExportError(
            "BLENDLIB-EXPORT-006", f"GLB contains duplicate node names: {', '.join(duplicate_nodes)}."
        )
    missing_nodes = sorted(set(expected_source_node_names) - set(node_names))
    if missing_nodes:
        raise ExportError(
            "BLENDLIB-EXPORT-006", f"GLB omitted expected source nodes: {', '.join(missing_nodes)}."
        )

    materials = _expect_list(gltf, "materials")
    material_names = []
    for material in materials:
        if not isinstance(material, dict) or not isinstance(material.get("name"), str):
            raise ExportError("BLENDLIB-MAT-003", "Every GLB material slot must have a name.")
        material_names.append(material["name"])
    if len(materials) > MAX_MATERIALS or _duplicates(material_names):
        raise ExportError("BLENDLIB-LIMIT-001", "GLB material slots exceed v1 limits or are duplicated.")
    if tuple(sorted(material_names)) != tuple(sorted(expected_material_names)):
        raise ExportError(
            "BLENDLIB-MAT-003",
            f"GLB material slots {material_names} do not match descriptor mappings {list(expected_material_names)}.",
        )

    meshes = _expect_list(gltf, "meshes")
    vertex_count = 0
    index_count = 0
    skinned_attribute_seen = False
    for mesh in meshes:
        if not isinstance(mesh, dict):
            raise ExportError("BLENDLIB-GLB-001", "GLB mesh must be an object.")
        primitives = _expect_list(mesh, "primitives")
        for primitive in primitives:
            if not isinstance(primitive, dict) or primitive.get("mode", 4) != 4:
                raise ExportError("BLENDLIB-GLB-001", "Only TRIANGLES primitives are accepted.")
            if "indices" not in primitive:
                raise ExportError("BLENDLIB-GLB-001", "TRIANGLES primitive requires an index accessor.")
            if primitive.get("targets"):
                raise ExportError("BLENDLIB-GLB-001", "Morph targets are not supported in v1.")
            attributes = primitive.get("attributes")
            if not isinstance(attributes, dict):
                raise ExportError("BLENDLIB-GLB-001", "Primitive attributes must be an object.")
            required = {"POSITION", "NORMAL", "TEXCOORD_0"}
            if not required.issubset(attributes):
                raise ExportError("BLENDLIB-GLB-001", "Primitive is missing POSITION, NORMAL, or TEXCOORD_0.")
            if "COLOR_0" in attributes or any(
                key.startswith("TEXCOORD_") and key != "TEXCOORD_0" for key in attributes
            ):
                raise ExportError("BLENDLIB-GLB-001", "Vertex colors and multiple UV sets are unsupported.")
            positions = _read_accessor(gltf, binary, attributes["POSITION"])
            normals = _read_accessor(gltf, binary, attributes["NORMAL"])
            texcoords = _read_accessor(gltf, binary, attributes["TEXCOORD_0"])
            indices = _read_accessor(gltf, binary, primitive["indices"])
            if positions["component_type"] != 5126 or positions["type"] != "VEC3":
                raise ExportError("BLENDLIB-GLB-014", "POSITION must use FLOAT VEC3.")
            if normals["component_type"] != 5126 or normals["type"] != "VEC3":
                raise ExportError("BLENDLIB-GLB-014", "NORMAL must use FLOAT VEC3.")
            if texcoords["type"] != "VEC2":
                raise ExportError("BLENDLIB-GLB-014", "TEXCOORD_0 must use VEC2.")
            if indices["component_type"] not in {5123, 5125} or indices["type"] != "SCALAR":
                raise ExportError("BLENDLIB-GLB-014", "Indices must use U16 or U32 scalar values.")
            if positions["count"] != normals["count"] or positions["count"] != texcoords["count"]:
                raise ExportError("BLENDLIB-GLB-014", "Vertex accessor counts disagree.")
            if indices["count"] % 3:
                raise ExportError("BLENDLIB-GLB-014", "Triangle index count must be divisible by three.")
            if any(index[0] < 0 or index[0] >= positions["count"] for index in indices["values"]):
                raise ExportError("BLENDLIB-GLB-014", "Index accessor references a vertex outside POSITION.")
            vertex_count += positions["count"]
            index_count += indices["count"]
            joints = attributes.get("JOINTS_0")
            weights = attributes.get("WEIGHTS_0")
            if (joints is None) != (weights is None):
                raise ExportError("BLENDLIB-GLB-014", "JOINTS_0 and WEIGHTS_0 must appear together.")
            if joints is not None:
                skinned_attribute_seen = True
                _validate_exported_weights(
                    _read_accessor(gltf, binary, joints),
                    _read_accessor(gltf, binary, weights),
                    positions["count"],
                )
    if vertex_count > MAX_VERTICES or index_count > MAX_INDICES:
        raise ExportError("BLENDLIB-LIMIT-001", "GLB geometry exceeds v1 vertex/index limits.")

    skins = gltf.get("skins", [])
    if not isinstance(skins, list):
        raise ExportError("BLENDLIB-GLB-001", "GLB skins must be an array.")
    if profile == "blendlib:rigid_v1":
        if skins or skinned_attribute_seen:
            raise ExportError("BLENDLIB-GLB-001", "Rigid profile cannot contain skin data.")
    elif not skins or not skinned_attribute_seen:
        raise ExportError("BLENDLIB-GLB-001", "Skinned profile requires skins and JOINTS_0/WEIGHTS_0.")
    for skin in skins:
        joints = skin.get("joints") if isinstance(skin, dict) else None
        if not isinstance(joints, list) or not joints or len(joints) > MAX_SKIN_JOINTS:
            raise ExportError("BLENDLIB-LIMIT-001", "Skin joint count is invalid or exceeds 512.")

    animation_names = _validate_animations(gltf)
    if tuple(sorted(animation_names)) != tuple(sorted(expected_animation_names)):
        raise ExportError(
            "BLENDLIB-ANIM-006",
            f"Exported animation names {animation_names} do not match expected {list(expected_animation_names)}.",
        )
    actual_bounds = _gltf_world_bounds(gltf, binary)
    _assert_bounds_close(actual_bounds, expected_bounds)
    return {
        "node_count": len(nodes),
        "vertex_count": vertex_count,
        "index_count": index_count,
        "material_names": sorted(material_names),
        "animation_names": sorted(animation_names),
        "world_bounds": actual_bounds,
        "embedded_runtime_images": False,
        "profile": profile,
    }


def _expect_list(document: dict[str, Any], field: str) -> list[Any]:
    value = document.get(field)
    if not isinstance(value, list):
        raise ExportError("BLENDLIB-GLB-001", f"GLB field '{field}' must be an array.")
    return value


_COMPONENTS: dict[int, tuple[str, int, bool]] = {
    5120: ("b", 1, True),
    5121: ("B", 1, False),
    5122: ("h", 2, True),
    5123: ("H", 2, False),
    5125: ("I", 4, False),
    5126: ("f", 4, True),
}
_TYPE_WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def _read_accessor(gltf: dict[str, Any], binary: bytes, index: Any) -> dict[str, Any]:
    if not isinstance(index, int):
        raise ExportError("BLENDLIB-GLB-014", "Accessor index must be an integer.")
    accessors = _expect_list(gltf, "accessors")
    views = _expect_list(gltf, "bufferViews")
    if index < 0 or index >= len(accessors) or not isinstance(accessors[index], dict):
        raise ExportError("BLENDLIB-GLB-014", "Accessor index is outside the accessor array.")
    accessor = accessors[index]
    if accessor.get("sparse") is not None:
        raise ExportError("BLENDLIB-GLB-014", "Sparse accessors are unsupported.")
    view_index = accessor.get("bufferView")
    if not isinstance(view_index, int) or view_index < 0 or view_index >= len(views):
        raise ExportError("BLENDLIB-GLB-014", "Accessor bufferView is invalid.")
    view = views[view_index]
    if not isinstance(view, dict):
        raise ExportError("BLENDLIB-GLB-014", "bufferView must be an object.")
    component_type = accessor.get("componentType")
    accessor_type = accessor.get("type")
    count = accessor.get("count")
    if (
        component_type not in _COMPONENTS
        or accessor_type not in _TYPE_WIDTH
        or not isinstance(count, int)
        or count < 0
    ):
        raise ExportError("BLENDLIB-GLB-014", "Accessor type, component type, or count is invalid.")
    format_char, component_size, _ = _COMPONENTS[component_type]
    width = _TYPE_WIDTH[accessor_type]
    element_size = component_size * width
    stride = int(view.get("byteStride", element_size))
    if stride < element_size or stride % component_size:
        raise ExportError("BLENDLIB-GLB-014", "Accessor byteStride is invalid.")
    start = int(view.get("byteOffset", 0)) + int(accessor.get("byteOffset", 0))
    last_end = start if count == 0 else start + stride * (count - 1) + element_size
    view_end = int(view.get("byteOffset", 0)) + int(view.get("byteLength", -1))
    if start < 0 or last_end > view_end or last_end > len(binary):
        raise ExportError("BLENDLIB-GLB-014", "Accessor lies outside the declared bufferView bounds.")
    values: list[tuple[float | int, ...]] = []
    unpack_format = "<" + format_char * width
    for offset in range(start, start + stride * count, stride):
        item = struct.unpack_from(unpack_format, binary, offset)
        if not all(math.isfinite(float(component)) for component in item):
            raise ExportError("BLENDLIB-GLB-014", "Accessor contains NaN or Infinity.")
        values.append(item)
    return {
        "component_type": component_type,
        "type": accessor_type,
        "count": count,
        "normalized": bool(accessor.get("normalized", False)),
        "values": values,
    }


def _validate_exported_weights(
    joints: dict[str, Any], weights: dict[str, Any], expected_count: int
) -> None:
    if joints["type"] != "VEC4" or weights["type"] != "VEC4":
        raise ExportError("BLENDLIB-GLB-014", "Skin joints and weights must be VEC4.")
    if joints["count"] != expected_count or weights["count"] != expected_count:
        raise ExportError("BLENDLIB-GLB-014", "Skin accessor counts must equal POSITION count.")
    for values in weights["values"]:
        numeric = _normalized_values(values, weights["component_type"], weights["normalized"])
        if any(value < -EPSILON for value in numeric) or not math.isclose(
            sum(numeric), 1.0, rel_tol=0.0, abs_tol=1.0e-4
        ):
            raise ExportError("BLENDLIB-GLB-014", "Exported skin weights are not normalized.")


def _normalized_values(values: Sequence[float | int], component_type: int, normalized: bool) -> list[float]:
    if not normalized or component_type == 5126:
        return [float(value) for value in values]
    _, size, signed = _COMPONENTS[component_type]
    maximum = float((1 << (8 * size - (1 if signed else 0))) - 1)
    return [max(-1.0, float(value) / maximum) if signed else float(value) / maximum for value in values]


def _validate_animations(gltf: dict[str, Any]) -> list[str]:
    animations = gltf.get("animations", [])
    if not isinstance(animations, list) or len(animations) > MAX_CLIPS:
        raise ExportError("BLENDLIB-LIMIT-001", "Animation clip count is invalid or exceeds 256.")
    names: list[str] = []
    for animation in animations:
        if not isinstance(animation, dict) or not isinstance(animation.get("name"), str):
            raise ExportError("BLENDLIB-ANIM-006", "Every animation clip requires a name.")
        names.append(animation["name"])
        samplers = animation.get("samplers", [])
        channels = animation.get("channels", [])
        if not isinstance(samplers, list) or not isinstance(channels, list):
            raise ExportError("BLENDLIB-ANIM-006", "Animation channels/samplers must be arrays.")
        for sampler in samplers:
            interpolation = sampler.get("interpolation", "LINEAR") if isinstance(sampler, dict) else None
            if interpolation not in {"LINEAR", "STEP"}:
                raise ExportError(
                    "BLENDLIB-ANIM-006", "CUBICSPLINE or another unsupported interpolation was exported."
                )
        for channel in channels:
            target = channel.get("target", {}) if isinstance(channel, dict) else {}
            if target.get("path") not in {"translation", "rotation", "scale"}:
                raise ExportError("BLENDLIB-ANIM-006", "Only node TRS animation channels are supported.")
    if _duplicates(names):
        raise ExportError("BLENDLIB-ANIM-006", "Animation clip names must be unique.")
    return names


def _gltf_world_bounds(gltf: dict[str, Any], binary: bytes) -> dict[str, list[float]]:
    nodes = _expect_list(gltf, "nodes")
    meshes = _expect_list(gltf, "meshes")
    parentless = set(range(len(nodes)))
    for node in nodes:
        for child in node.get("children", []) if isinstance(node, dict) else []:
            if not isinstance(child, int) or child < 0 or child >= len(nodes):
                raise ExportError("BLENDLIB-GLB-001", "Node child index is invalid.")
            parentless.discard(child)
    roots = _scene_roots(gltf, parentless)
    points: list[tuple[float, float, float]] = []
    visiting: set[int] = set()

    def walk(node_index: int, parent_matrix: tuple[float, ...]) -> None:
        if node_index in visiting:
            raise ExportError("BLENDLIB-SCENE-004", "GLB node hierarchy contains a cycle.")
        visiting.add(node_index)
        node = nodes[node_index]
        if not isinstance(node, dict):
            raise ExportError("BLENDLIB-GLB-001", "Node must be an object.")
        matrix = _matrix_multiply(parent_matrix, _node_matrix(node))
        mesh_index = node.get("mesh")
        if mesh_index is not None:
            if not isinstance(mesh_index, int) or mesh_index < 0 or mesh_index >= len(meshes):
                raise ExportError("BLENDLIB-GLB-001", "Node mesh index is invalid.")
            mesh = meshes[mesh_index]
            for primitive in _expect_list(mesh, "primitives"):
                attributes = primitive.get("attributes", {})
                positions = _read_accessor(gltf, binary, attributes["POSITION"])
                points.extend(
                    _transform_point(matrix, tuple(float(value) for value in point))
                    for point in positions["values"]
                )
        for child in node.get("children", []):
            walk(child, matrix)
        visiting.remove(node_index)

    for root in roots:
        walk(root, _identity_matrix())
    if not points:
        raise ExportError("BLENDLIB-GLB-014", "No POSITION data was available for bounds validation.")
    return {
        "min": [min(point[index] for point in points) for index in range(3)],
        "max": [max(point[index] for point in points) for index in range(3)],
    }


def _scene_roots(gltf: dict[str, Any], fallback: set[int]) -> list[int]:
    scenes = gltf.get("scenes", [])
    if isinstance(scenes, list) and scenes:
        scene_index = gltf.get("scene", 0)
        if not isinstance(scene_index, int) or scene_index < 0 or scene_index >= len(scenes):
            raise ExportError("BLENDLIB-GLB-001", "Default scene index is invalid.")
        roots = scenes[scene_index].get("nodes", [])
        if not isinstance(roots, list):
            raise ExportError("BLENDLIB-GLB-001", "Scene nodes must be an array.")
        return roots
    return sorted(fallback)


def _identity_matrix() -> tuple[float, ...]:
    return (1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)


def _node_matrix(node: dict[str, Any]) -> tuple[float, ...]:
    if "matrix" in node:
        values = node["matrix"]
        if not isinstance(values, list) or len(values) != 16:
            raise ExportError("BLENDLIB-GLB-001", "Node matrix must contain 16 values.")
        return tuple(float(value) for value in values)
    translation = _vector(node.get("translation", [0.0, 0.0, 0.0]), 3, "translation")
    rotation = _vector(node.get("rotation", [0.0, 0.0, 0.0, 1.0]), 4, "rotation")
    scale = _vector(node.get("scale", [1.0, 1.0, 1.0]), 3, "scale")
    x, y, z, w = rotation
    xx, yy, zz = x * x, y * y, z * z
    xy, xz, yz = x * y, x * z, y * z
    wx, wy, wz = w * x, w * y, w * z
    return (
        (1.0 - 2.0 * (yy + zz)) * scale[0],
        (2.0 * (xy + wz)) * scale[0],
        (2.0 * (xz - wy)) * scale[0],
        0.0,
        (2.0 * (xy - wz)) * scale[1],
        (1.0 - 2.0 * (xx + zz)) * scale[1],
        (2.0 * (yz + wx)) * scale[1],
        0.0,
        (2.0 * (xz + wy)) * scale[2],
        (2.0 * (yz - wx)) * scale[2],
        (1.0 - 2.0 * (xx + yy)) * scale[2],
        0.0,
        translation[0],
        translation[1],
        translation[2],
        1.0,
    )


def _vector(value: Any, count: int, label: str) -> tuple[float, ...]:
    if not isinstance(value, list) or len(value) != count:
        raise ExportError("BLENDLIB-GLB-001", f"Node {label} must have {count} values.")
    result = tuple(float(component) for component in value)
    if not all(math.isfinite(component) for component in result):
        raise ExportError("BLENDLIB-GLB-014", f"Node {label} contains NaN or Infinity.")
    return result


def _matrix_multiply(left: tuple[float, ...], right: tuple[float, ...]) -> tuple[float, ...]:
    return tuple(
        sum(left[row + inner * 4] * right[inner + column * 4] for inner in range(4))
        for column in range(4)
        for row in range(4)
    )


def _transform_point(matrix: tuple[float, ...], point: tuple[float, float, float]) -> tuple[float, float, float]:
    x, y, z = point
    transformed = (
        matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12],
        matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
        matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14],
    )
    if not all(math.isfinite(component) for component in transformed):
        raise ExportError("BLENDLIB-GLB-014", "World bounds calculation produced NaN or Infinity.")
    return transformed


def _assert_bounds_close(actual: dict[str, Sequence[float]], expected: dict[str, Sequence[float]]) -> None:
    for bound in ("min", "max"):
        if bound not in actual or bound not in expected or len(actual[bound]) != 3 or len(expected[bound]) != 3:
            raise ExportError("BLENDLIB-GLB-014", "Bounds must contain min/max three-vectors.")
        for index, (actual_value, expected_value) in enumerate(zip(actual[bound], expected[bound])):
            if not math.isclose(float(actual_value), float(expected_value), rel_tol=0.0, abs_tol=1.0e-4):
                raise ExportError(
                    "BLENDLIB-GLB-014",
                    f"Canonical bounds differ at {bound}[{index}]: {actual_value} != {expected_value}.",
                )


def normalized_structure_summary(
    gltf: dict[str, Any], descriptor: dict[str, Any], validation: dict[str, Any]
) -> dict[str, Any]:
    nodes = gltf.get("nodes", [])
    meshes = gltf.get("meshes", [])
    mesh_names = [mesh.get("name", f"mesh-{index}") for index, mesh in enumerate(meshes)]
    node_summary = []
    for node in nodes:
        children = [nodes[index].get("name") for index in node.get("children", [])]
        node_summary.append(
            {
                "name": node.get("name"),
                "mesh": mesh_names[node["mesh"]] if "mesh" in node else None,
                "skin": bool("skin" in node),
                "children": sorted(child for child in children if child is not None),
            }
        )
    primitive_summary = []
    for mesh in meshes:
        for primitive in mesh.get("primitives", []):
            primitive_summary.append(
                {
                    "mesh": mesh.get("name"),
                    "attributes": sorted(primitive.get("attributes", {}).keys()),
                    "mode": primitive.get("mode", 4),
                    "material": primitive.get("material"),
                }
            )
    animations = []
    for animation in gltf.get("animations", []):
        animations.append(
            {
                "name": animation.get("name"),
                "interpolations": sorted(
                    sampler.get("interpolation", "LINEAR") for sampler in animation.get("samplers", [])
                ),
                "paths": sorted(
                    channel.get("target", {}).get("path") for channel in animation.get("channels", [])
                ),
            }
        )
    return {
        "format": "blendlib-p2-normalized-structure-v1",
        "descriptor": descriptor,
        "glb": {
            "asset_version": gltf.get("asset", {}).get("version"),
            "node_count": validation["node_count"],
            "nodes": sorted(node_summary, key=lambda item: item["name"]),
            "primitives": sorted(
                primitive_summary, key=lambda item: (str(item["mesh"]), str(item["material"]))
            ),
            "material_names": validation["material_names"],
            "animation": sorted(animations, key=lambda item: item["name"]),
            "world_bounds": _rounded_bounds(validation["world_bounds"]),
            "embedded_runtime_images": validation["embedded_runtime_images"],
            "profile": validation["profile"],
        },
    }


def _rounded_bounds(bounds: dict[str, Sequence[float]]) -> dict[str, list[float]]:
    return {key: [round(float(value), 6) for value in values] for key, values in bounds.items()}


def _duplicates(values: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return sorted(duplicates)


def _canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(
    path: Path,
    *,
    maximum_bytes: int = MAX_SOURCE_BLEND_BYTES,
    allowed_roots: Sequence[Path] | None = None,
) -> str:
    resolved, declared_size = _bounded_regular_file(
        path, maximum_bytes, "BLENDLIB-LIMIT-001", "Hashed file", allowed_roots
    )
    with resolved.open("rb") as stream:
        return _sha256_bounded_stream(stream, declared_size, maximum_bytes)


def _sha256_bounded_stream(stream: BinaryIO, declared_size: int, maximum_bytes: int) -> str:
    """Hash a pre-statted file through fixed requests and reject size changes."""

    digest = hashlib.sha256()
    total = 0
    for block in iter(lambda: stream.read(IO_BUFFER_BYTES), b""):
        total += len(block)
        if total > declared_size or total > maximum_bytes:
            raise ExportError("BLENDLIB-LIMIT-001", "Hashed file grew or exceeded its bounded size.")
        digest.update(block)
    if total != declared_size:
        raise ExportError("BLENDLIB-LIMIT-001", "Hashed file shrank while being read.")
    return digest.hexdigest()


def _x5_toolchain() -> Any:
    """Import X5 only at Blender UI/runtime use sites, never at parser import time."""

    try:
        from . import blendlib_x5_toolchain as x5  # type: ignore
    except ImportError:
        import blendlib_x5_toolchain as x5  # type: ignore
    return x5


def register() -> None:
    """Register the Blender 5.x sidebar panel when installed as an extension."""

    blender = _require_blender()

    class BLENDLIB_OT_export_model(blender.types.Operator):
        bl_idname = "blendlib.export_model"
        bl_label = "Export BlendLib Model"
        bl_description = "Validate and export the selected collection as strict BlendLib v1 GLB"

        def execute(self, context: Any) -> set[str]:
            try:
                scene = context.scene
                options = ExportOptions(
                    blend_path=Path(blender.data.filepath).resolve(),
                    project_root=Path(scene.blendlib_project_root).expanduser().resolve(),
                    namespace=_require_resource_token(scene.blendlib_namespace, "namespace"),
                    model_id=_require_resource_token(scene.blendlib_model_id, "model id"),
                    profile=scene.blendlib_profile,
                    collection_name=scene.blendlib_collection.name if scene.blendlib_collection else None,
                    output_resource_root=scene.blendlib_output_resource_root,
                    report_path=None,
                )
                result = export_open_blend(options)
                self.report({"INFO"}, f"Exported {result['mesh_path']}")
                return {"FINISHED"}
            except ExportError as error:
                self.report({"ERROR"}, str(error))
                return {"CANCELLED"}

    class VIEW3D_PT_blendlib_export(blender.types.Panel):
        bl_label = "BlendLib v1 Export"
        bl_idname = "VIEW3D_PT_blendlib_export"
        bl_space_type = "VIEW_3D"
        bl_region_type = "UI"
        bl_category = "BlendLib"

        def draw(self, context: Any) -> None:
            layout = self.layout
            scene = context.scene
            layout.prop(scene, "blendlib_collection")
            layout.prop(scene, "blendlib_namespace")
            layout.prop(scene, "blendlib_model_id")
            layout.prop(scene, "blendlib_profile")
            layout.prop(scene, "blendlib_project_root")
            layout.prop(scene, "blendlib_output_resource_root")
            layout.operator(BLENDLIB_OT_export_model.bl_idname, icon="EXPORT")

    classes = (BLENDLIB_OT_export_model, VIEW3D_PT_blendlib_export)
    for cls in classes:
        blender.utils.register_class(cls)
    blender.types.Scene.blendlib_collection = blender.props.PointerProperty(type=blender.types.Collection)
    blender.types.Scene.blendlib_namespace = blender.props.StringProperty(name="Namespace", default="example")
    blender.types.Scene.blendlib_model_id = blender.props.StringProperty(name="Model ID", default="model")
    blender.types.Scene.blendlib_profile = blender.props.EnumProperty(
        name="Profile",
        items=(
            ("blendlib:rigid_v1", "Rigid v1", "Static or rigid node animation"),
            ("blendlib:skinned_v1", "Skinned v1", "Four-weight skeletal skinning"),
        ),
        default="blendlib:rigid_v1",
    )
    blender.types.Scene.blendlib_project_root = blender.props.StringProperty(
        name="Project Root", subtype="DIR_PATH"
    )
    blender.types.Scene.blendlib_output_resource_root = blender.props.StringProperty(
        name="Output Resource Root", default="src/main/resources"
    )
    globals()["_REGISTERED_CLASSES"] = classes
    _x5_toolchain().register_blender_ui(blender)


def unregister() -> None:
    blender = _require_blender()
    _x5_toolchain().unregister_blender_ui(blender)
    for property_name in (
        "blendlib_collection",
        "blendlib_namespace",
        "blendlib_model_id",
        "blendlib_profile",
        "blendlib_project_root",
        "blendlib_output_resource_root",
    ):
        if hasattr(blender.types.Scene, property_name):
            delattr(blender.types.Scene, property_name)
    for cls in reversed(globals().get("_REGISTERED_CLASSES", ())):
        blender.utils.unregister_class(cls)


if __name__ == "__main__":  # pragma: no cover - supported for direct debugging.
    try:
        report = run_cli(parse_blender_arguments(sys.argv))
        print(json.dumps(report, sort_keys=True))
    except ExportError as error:
        print(f"BLENDLIB_EXPORT_ERROR: {error}", file=sys.stderr)
        raise SystemExit(2) from error
