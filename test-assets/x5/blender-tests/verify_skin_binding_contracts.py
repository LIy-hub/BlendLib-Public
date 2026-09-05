"""Real Blender X5 runtime skin-binding and frozen-report regressions."""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path
from typing import Callable

sys.dont_write_bytecode = True

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
ADDON_ROOT = REPOSITORY_ROOT / "blender-addon"
if str(ADDON_ROOT) not in sys.path:
    sys.path.insert(0, str(ADDON_ROOT))

import bpy  # type: ignore  # noqa: E402

import blendlib_exporter as legacy  # noqa: E402
import blendlib_x5_toolchain as x5  # noqa: E402


def _options(fixture: str, project: Path, model_id: str, profile: str) -> legacy.ExportOptions:
    return legacy.ExportOptions(
        blend_path=REPOSITORY_ROOT / "test-assets" / fixture / "source.blend",
        project_root=project,
        namespace="blendlib_showcase",
        model_id=model_id,
        profile=profile,
        collection_name="BlendLibExport",
        output_resource_root="src/main/resources",
        report_path=None,
    )


def _fresh_project(name: str, *, create: bool) -> Path:
    project = REPOSITORY_ROOT / "build" / "x5-r11-skin-binding" / name
    if project.exists():
        shutil.rmtree(project)
    if create:
        project.mkdir(parents=True)
    return project


def _open_objects(options: legacy.ExportOptions) -> tuple[object, list[object]]:
    bpy.ops.wm.open_mainfile(filepath=str(options.blend_path))
    collection = legacy._select_collection(options.collection_name)
    objects, _ = legacy._collect_export_objects(collection)
    return collection, list(objects)


def verify_rigid_decorative_groups() -> None:
    project = _fresh_project("rigid-decorative", create=True)
    options = _options("static", project, "fixtures/rigid_decorative", "blendlib:rigid_v1")
    _, objects = _open_objects(options)
    mesh = next(obj for obj in objects if obj.type == "MESH")
    vertices = [vertex.index for vertex in mesh.data.vertices]
    assert len(vertices) >= 2, vertices
    mesh.vertex_groups.new(name="decor_half").add([vertices[0]], 0.5, "REPLACE")
    mesh.vertex_groups.new(name="decor_full").add([vertices[1]], 1.0, "REPLACE")

    preflight = x5.preflight_blender(options)
    assert preflight.ok, preflight.report()
    mesh_snapshot = next(item for item in preflight.snapshot["objects"] if item.get("type") == "MESH")
    assert mesh_snapshot["skin_binding"]["armature_modifiers"] == (), mesh_snapshot
    assert not any(item.code.startswith("BLENDLIB-X5-WEIGHT-") for item in preflight.diagnostics)

    result = x5.x5_export_open_blend(options)
    report = json.loads((project / result["authoring_report"]).read_text(encoding="utf-8"))
    assert report["counts"]["vertex_weight_records"] == 0, report
    assert report["diagnostics"] == report["performance_warnings"], report
    assert len(report["diagnostics"]) == len({
        json.dumps(item, sort_keys=True) for item in report["diagnostics"]
    }), report
    assert not any(item["code"].startswith("BLENDLIB-X5-WEIGHT-") for item in report["diagnostics"])
    print(f"BLENDLIB_X5_RIGID_DECORATIVE_GROUPS_PASS {project}")


def verify_legal_skinned_binding() -> None:
    project = _fresh_project("legal-skinned", create=True)
    options = _options("skinned", project, "fixtures/legal_skinned", "blendlib:skinned_v1")
    _, objects = _open_objects(options)
    preflight = x5.preflight_blender(options)
    assert preflight.ok, preflight.report()
    mesh_snapshot = next(item for item in preflight.snapshot["objects"] if item.get("type") == "MESH")
    binding = mesh_snapshot["skin_binding"]
    assert binding["profile"] == "blendlib:skinned_v1", binding
    assert len(binding["armature_modifiers"]) == 1, binding
    modifier = binding["armature_modifiers"][0]
    assert modifier["target_exported"] and modifier["target_type"] == "ARMATURE", modifier
    assert modifier["target_bones"], modifier
    assert all(assignments for assignments in binding["vertex_group_assignments"]), binding

    result = x5.x5_export_open_blend(options)
    report = json.loads((project / result["authoring_report"]).read_text(encoding="utf-8"))
    runtime_vertices = result["strict_v1_validation"]["vertex_count"]
    assert runtime_vertices > 0, result
    assert report["counts"]["vertex_weight_records"] == runtime_vertices, report
    print(f"BLENDLIB_X5_LEGAL_SKINNED_BINDING_PASS {project}")


def _verify_skinned_decorative_group(name: str, weight: float) -> None:
    project = _fresh_project(name, create=True)
    options = _options("skinned", project, f"fixtures/{name}", "blendlib:skinned_v1")
    _, objects = _open_objects(options)
    mesh = next(obj for obj in objects if obj.type == "MESH")
    decorative = mesh.vertex_groups.new(name=f"Decorative_{name}")
    decorative.add([vertex.index for vertex in mesh.data.vertices], weight, "REPLACE")

    preflight = x5.preflight_blender(options)
    assert preflight.ok, preflight.report()
    result = x5.x5_export_open_blend(options)
    report = json.loads((project / result["authoring_report"]).read_text(encoding="utf-8"))
    assert report["counts"]["vertex_weight_records"] == result["strict_v1_validation"]["vertex_count"], report
    assert result["strict_v1_validation"]["vertex_count"] == 3, result
    assert not any(item["code"].startswith("BLENDLIB-X5-WEIGHT-") for item in report["diagnostics"]), report
    print(f"BLENDLIB_X5_SKINNED_DECORATIVE_GROUP_PASS name={name} weight={weight}")


def verify_skinned_decorative_groups_and_shared_armature() -> None:
    _verify_skinned_decorative_group("skinned-decorative-positive", 0.75)
    _verify_skinned_decorative_group("skinned-decorative-zero", 0.0)

    project = _fresh_project("shared-armature", create=True)
    options = _options("skinned", project, "fixtures/shared_armature", "blendlib:skinned_v1")
    collection, objects = _open_objects(options)
    mesh = next(obj for obj in objects if obj.type == "MESH")
    duplicate = mesh.copy()
    duplicate.data = mesh.data.copy()
    duplicate.name = "SkinnedMeshSharedArmature"
    duplicate.parent = mesh.parent
    collection.objects.link(duplicate)

    preflight = x5.preflight_blender(options)
    assert preflight.ok, preflight.report()
    result = x5.x5_export_open_blend(options)
    report = json.loads((project / result["authoring_report"]).read_text(encoding="utf-8"))
    assert result["strict_v1_validation"]["vertex_count"] == 6, result
    assert report["counts"]["vertex_weight_records"] == 6, report
    print("BLENDLIB_X5_SHARED_ARMATURE_PASS meshes=2 vertices=6")


def _assert_invalid(
    name: str,
    expected_code: str,
    mutate: Callable[[object, list[object]], None],
    *,
    profile: str = "blendlib:skinned_v1",
) -> None:
    project = _fresh_project(name, create=False)
    options = _options("skinned", project, f"fixtures/{name}", profile)
    collection, objects = _open_objects(options)
    mutate(collection, objects)

    original_validate = legacy._validate_source_objects
    try:
        legacy._validate_source_objects = lambda *unused: (_ for _ in ()).throw(
            AssertionError("binding error reached private legacy validation")
        )
        preflight = x5.preflight_blender(options)
    finally:
        legacy._validate_source_objects = original_validate

    codes = [item.code for item in preflight.diagnostics]
    assert expected_code in codes, preflight.report()
    assert "BLENDLIB-X5-PREFLIGHT-LEGACY" not in codes, preflight.report()
    assert not preflight.ok
    try:
        x5.build_authoring_sidecar(preflight.snapshot)
    except x5.X5ToolingError as error:
        assert error.code == expected_code or expected_code in codes, error
    else:
        raise AssertionError("invalid binding built an authoring sidecar")

    original_exporter = x5._legacy_exporter
    try:
        x5._legacy_exporter = lambda: (_ for _ in ()).throw(
            AssertionError("binding error reached private staging exporter")
        )
        try:
            x5._prepare_x5_export(options, preflight=preflight)
        except x5.X5ToolingError as error:
            assert error.code == "BLENDLIB-X5-PREFLIGHT-001", error
        else:
            raise AssertionError("invalid binding reached staging")
    finally:
        x5._legacy_exporter = original_exporter
    assert not project.exists(), project
    assert not list(project.parent.glob(".blendlib-x5-export-*"))


def verify_invalid_bindings() -> None:
    def mesh_and_modifier(objects: list[object]) -> tuple[object, object]:
        mesh = next(obj for obj in objects if obj.type == "MESH")
        modifier = next(item for item in mesh.modifiers if item.type == "ARMATURE")
        return mesh, modifier

    _assert_invalid(
        "unbound-armature",
        "BLENDLIB-X5-ARMATURE-005",
        lambda unused_collection, objects: setattr(mesh_and_modifier(objects)[1], "object", None),
    )

    def add_modifier(unused_collection: object, objects: list[object]) -> None:
        mesh, modifier = mesh_and_modifier(objects)
        extra = mesh.modifiers.new(name="SecondArmature", type="ARMATURE")
        extra.object = modifier.object

    _assert_invalid("multiple-armatures", "BLENDLIB-X5-ARMATURE-004", add_modifier)

    def wrong_target(collection: object, objects: list[object]) -> None:
        unused_mesh, modifier = mesh_and_modifier(objects)
        wrong_data = bpy.data.armatures.new("WrongArmatureTargetData")
        wrong = bpy.data.objects.new("WrongArmatureTarget", wrong_data)
        modifier.object = wrong

    _assert_invalid("wrong-target", "BLENDLIB-X5-ARMATURE-005", wrong_target)

    def non_bone_groups(unused_collection: object, objects: list[object]) -> None:
        mesh, unused_modifier = mesh_and_modifier(objects)
        for index, group in enumerate(mesh.vertex_groups):
            group.name = f"DecorativeOnly{index}"

    _assert_invalid("non-bone-groups", "BLENDLIB-X5-WEIGHT-001", non_bone_groups)

    def non_normalized(unused_collection: object, objects: list[object]) -> None:
        mesh, modifier = mesh_and_modifier(objects)
        bone_names = {bone.name for bone in modifier.object.data.bones}
        bone_group = next(group for group in mesh.vertex_groups if group.name in bone_names)
        bone_group.add([vertex.index for vertex in mesh.data.vertices], 0.75, "REPLACE")

    _assert_invalid("non-normalized-bone-groups", "BLENDLIB-X5-WEIGHT-002", non_normalized)

    def more_than_four_bone_groups(unused_collection: object, objects: list[object]) -> None:
        mesh, modifier = mesh_and_modifier(objects)
        armature = modifier.object
        existing_bone_names = [bone.name for bone in armature.data.bones]
        assert len(existing_bone_names) == 1, existing_bone_names
        bpy.context.view_layer.objects.active = armature
        armature.select_set(True)
        bpy.ops.object.mode_set(mode="EDIT")
        try:
            for index in range(4):
                bone = armature.data.edit_bones.new(f"AttackBone{index}")
                bone.head = (0.0, 0.0, float(index + 1))
                bone.tail = (0.0, 0.0, float(index + 2))
        finally:
            bpy.ops.object.mode_set(mode="OBJECT")
        all_bones = [bone.name for bone in armature.data.bones]
        assert len(all_bones) == 5, all_bones
        groups = {group.name: group for group in mesh.vertex_groups}
        for bone_name in all_bones:
            group = groups.get(bone_name) or mesh.vertex_groups.new(name=bone_name)
            group.add([vertex.index for vertex in mesh.data.vertices], 0.2, "REPLACE")

    _assert_invalid("more-than-four-bone-groups", "BLENDLIB-X5-WEIGHT-001", more_than_four_bone_groups)

    _assert_invalid(
        "rigid-armature",
        "BLENDLIB-X5-ARMATURE-004",
        lambda unused_collection, unused_objects: None,
        profile="blendlib:rigid_v1",
    )

    project = _fresh_project("profile-mismatch", create=False)
    options = _options("skinned", project, "fixtures/profile_mismatch", "blendlib:skinned_v1")
    collection, objects = _open_objects(options)
    snapshot = x5._snapshot_from_blender(bpy, collection, objects, options)
    mesh_snapshot = next(item for item in snapshot["objects"] if item.get("type") == "MESH")
    mesh_snapshot["skin_binding"]["profile"] = "blendlib:rigid_v1"
    mismatch = x5.preflight_snapshot(snapshot)
    assert "BLENDLIB-X5-PROFILE-002" in [item.code for item in mismatch.diagnostics], mismatch.report()
    assert not project.exists(), project

    project = _fresh_project("batch-ui-unbound", create=False)
    options = _options("skinned", project, "fixtures/batch_ui_unbound", "blendlib:skinned_v1")
    unused_collection, objects = _open_objects(options)
    unused_mesh, modifier = mesh_and_modifier(objects)
    modifier.object = None
    original_validate = legacy._validate_source_objects
    try:
        legacy._validate_source_objects = lambda *unused: (_ for _ in ()).throw(
            AssertionError("batch/UI binding error reached private legacy validation")
        )
        try:
            x5.x5_batch_export_open_blend(
                options,
                [x5.BatchExportItem(
                    "blendlib_showcase",
                    "fixtures/batch_ui_unbound",
                    "blendlib:skinned_v1",
                    "BlendLibExport",
                )],
            )
        except x5.X5ToolingError as error:
            assert error.code == "BLENDLIB-X5-BATCH-004", error
        else:
            raise AssertionError("invalid batch binding reached staging")

        legacy.register()
        x5.register_blender_ui(bpy)
        try:
            scene = bpy.context.scene
            scene.blendlib_project_root = str(project)
            scene.blendlib_namespace = options.namespace
            scene.blendlib_model_id = options.model_id
            scene.blendlib_profile = options.profile
            scene.blendlib_collection = bpy.data.collections["BlendLibExport"]
            scene.blendlib_output_resource_root = options.output_resource_root
            try:
                operator_result = bpy.ops.blendlib.x5_preflight()
            except RuntimeError as error:
                assert "BLENDLIB-X5-ARMATURE-005" in str(error), error
            else:
                assert operator_result == {"CANCELLED"}, operator_result
            assert scene.blendlib_x5_last_status in {"BLOCKED", "ERROR"}
        finally:
            x5.unregister_blender_ui(bpy)
            legacy.unregister()
    finally:
        legacy._validate_source_objects = original_validate
    assert not project.exists(), project

    print("BLENDLIB_X5_INVALID_BINDINGS_PASS unbound multiple wrong-target non-bone non-normalized more-than-four rigid profile-mismatch batch ui")


def main() -> None:
    if "--" not in sys.argv:
        raise SystemExit("use -- --case <rigid|skinned|skinned-edge|invalid>")
    arguments = sys.argv[sys.argv.index("--") + 1 :]
    if arguments == ["--case", "rigid"]:
        verify_rigid_decorative_groups()
    elif arguments == ["--case", "skinned"]:
        verify_legal_skinned_binding()
    elif arguments == ["--case", "skinned-edge"]:
        verify_skinned_decorative_groups_and_shared_armature()
    elif arguments == ["--case", "invalid"]:
        verify_invalid_bindings()
    else:
        raise SystemExit("unknown case")


if __name__ == "__main__":
    main()
