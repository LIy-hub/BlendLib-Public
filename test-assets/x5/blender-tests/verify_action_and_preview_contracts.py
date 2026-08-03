"""Real Blender X5 Action-source and viewport-preview contract verifier."""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

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


def _reset_project(name: str) -> Path:
    project = REPOSITORY_ROOT / "build" / "x5-r5-blender-contracts" / name
    if project.exists():
        shutil.rmtree(project)
    project.mkdir(parents=True)
    return project


def verify_unrelated_fake_user_action() -> None:
    project = _reset_project("static-unrelated-action")
    options = _options("static", project, "fixtures/static_unrelated", "blendlib:rigid_v1")
    bpy.ops.wm.open_mainfile(filepath=str(options.blend_path))
    unrelated = bpy.data.actions.new("UnrelatedFakeUserAction")
    unrelated.use_fake_user = True

    result = x5.x5_export_open_blend(options)
    sidecar = json.loads((project / result["authoring_sidecar"]).read_text(encoding="utf-8"))
    gltf, _ = legacy.read_glb(project / result["mesh"])
    clips = sidecar["mapping"]["action_animation_clips"]
    animations = gltf.get("animations", [])
    assert clips == [], clips
    assert animations == [], animations
    print("BLENDLIB_X5_UNRELATED_FAKE_USER_ACTION_EXCLUDED")


def verify_bound_and_nla_actions() -> None:
    for fixture, model_id, profile in (
        ("rigid", "fixtures/rigid_action_contract", "blendlib:rigid_v1"),
        ("skinned", "fixtures/skinned_nla_contract", "blendlib:skinned_v1"),
    ):
        project = _reset_project(f"{fixture}-actions")
        options = _options(fixture, project, model_id, profile)
        bpy.ops.wm.open_mainfile(filepath=str(options.blend_path))
        collection = legacy._select_collection(options.collection_name)
        objects, _ = legacy._collect_export_objects(collection)
        expected = legacy._discover_actions(objects)
        assert expected, fixture
        if fixture == "skinned":
            owner = next(obj for obj in objects if obj.animation_data and obj.animation_data.action)
            animation_data = owner.animation_data
            action = animation_data.action
            animation_data.action = None
            track = animation_data.nla_tracks.new()
            track.name = "BlendLibContractNLA"
            track.strips.new(action.name, int(action.frame_range[0]), action)
            expected = legacy._discover_actions(objects)
            assert action.name in expected, expected

        result = x5.x5_export_open_blend(options)
        sidecar = json.loads((project / result["authoring_sidecar"]).read_text(encoding="utf-8"))
        gltf, _ = legacy.read_glb(project / result["mesh"])
        sidecar_names = tuple(item["clip"] for item in sidecar["mapping"]["action_animation_clips"])
        glb_names = tuple(sorted(item["name"] for item in gltf.get("animations", [])))
        assert sidecar_names == tuple(sorted(expected)), (fixture, sidecar_names, expected)
        assert glb_names == tuple(sorted(expected)), (fixture, glb_names, expected)
    print("BLENDLIB_X5_BOUND_AND_NLA_ACTIONS_PASS rigid skinned")


def verify_preview_state() -> None:
    project = _reset_project("preview")
    options = _options("skinned", project, "fixtures/skinned_preview", "blendlib:skinned_v1")
    bpy.ops.wm.open_mainfile(filepath=str(options.blend_path))
    legacy.register()
    x5.register_blender_ui(bpy)
    try:
        scene = bpy.context.scene
        scene.blendlib_project_root = str(project)
        scene.blendlib_namespace = options.namespace
        scene.blendlib_model_id = options.model_id
        scene.blendlib_profile = options.profile
        scene.blendlib_collection = bpy.data.collections[options.collection_name]
        scene.blendlib_output_resource_root = options.output_resource_root
        armature = next(obj for obj in scene.blendlib_collection.all_objects if obj.type == "ARMATURE")
        mesh = next(obj for obj in scene.blendlib_collection.all_objects if obj.type == "MESH")
        socket = bpy.data.objects.new("DebugSocket", None)
        socket.parent = armature
        scene.blendlib_collection.objects.link(socket)
        for obj in scene.blendlib_collection.all_objects:
            obj.select_set(False)
        armature.show_in_front = False
        armature.data.show_names = False
        mesh.show_wire = False
        original_range = (scene.frame_start, scene.frame_end)

        scene.blendlib_x5_preview_model = True
        scene.blendlib_x5_preview_bones = True
        scene.blendlib_x5_preview_sockets = True
        scene.blendlib_x5_preview_normals = True
        scene.blendlib_x5_preview_materials = True
        scene.blendlib_x5_preview_timeline = True
        assert bpy.ops.blendlib.x5_preview() == {"FINISHED"}
        state = json.loads(scene.blendlib_x5_preview_state)
        assert state["active"], state
        assert state["selected_objects"] >= 2, state
        assert armature.show_in_front and armature.data.show_names
        assert socket.show_in_front and socket.show_name
        assert mesh.show_wire
        assert state["timeline_range"] == [scene.frame_start, scene.frame_end], state
        assert state["timeline_range"], state

        for name in (
            "blendlib_x5_preview_model",
            "blendlib_x5_preview_bones",
            "blendlib_x5_preview_sockets",
            "blendlib_x5_preview_normals",
            "blendlib_x5_preview_materials",
            "blendlib_x5_preview_timeline",
        ):
            setattr(scene, name, False)
        assert bpy.ops.blendlib.x5_preview() == {"FINISHED"}
        assert not armature.show_in_front and not armature.data.show_names
        assert not socket.show_in_front and not socket.show_name
        assert not mesh.show_wire
        assert (scene.frame_start, scene.frame_end) == original_range
    finally:
        x5.unregister_blender_ui(bpy)
        legacy.unregister()
    assert not hasattr(bpy.types.Scene, "blendlib_x5_preview_state")
    print("BLENDLIB_X5_HEADLESS_PREVIEW_STATE_PASS model bones sockets normals materials timeline")


def main() -> None:
    if "--" not in sys.argv:
        raise SystemExit("use -- --case <action-negative|action-positive|preview>")
    arguments = sys.argv[sys.argv.index("--") + 1 :]
    if arguments == ["--case", "action-negative"]:
        verify_unrelated_fake_user_action()
    elif arguments == ["--case", "action-positive"]:
        verify_bound_and_nla_actions()
    elif arguments == ["--case", "preview"]:
        verify_preview_state()
    else:
        raise SystemExit("unknown case")


if __name__ == "__main__":
    main()
