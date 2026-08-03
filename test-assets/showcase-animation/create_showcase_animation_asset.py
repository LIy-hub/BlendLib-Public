"""Create the BlendLib-owned P5 Showcase animation authoring source.

This Blender-only script creates a tiny skinned source with one active default
scene root and three deliberately different Actions: ``idle``, ``walk``, and
``attack``.  It is an authoring fixture; the BlendLib Java runtime never reads
the resulting ``.blend`` file.

Run with Blender 5.x:

    & 'D:\\Program Files\\Blender\\blender.exe' --background \
        --python test-assets\\showcase-animation\\create_showcase_animation_asset.py -- \
        --project-root .
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.dont_write_bytecode = True

import bpy


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
PROJECT_DIRECTORY = SCRIPT_DIRECTORY.parents[1]
ASSET_DIRECTORY = Path("test-assets/showcase-animation")
COLLECTION_NAME = "BlendLibShowcaseAnimation"
ROOT_NAME = "ShowcaseAnimationRoot"
ARMATURE_NAME = "ShowcaseAnimationArmature"
ROOT_BONE_NAME = "ShowcaseRootBone"
TIP_BONE_NAME = "ShowcaseTipBone"
MESH_NAME = "ShowcaseAnimatedMesh"
MATERIAL_NAME = "ShowcaseAnimationSurface"
NAMESPACE = "blendlib_showcase"
MODEL_ID = "showcase_animation/showcase_actor"
TIP_SOCKET_KEY = "blendlib_showcase:tip"
TIP_SOCKET_PATH = (
    "ShowcaseAnimationRoot/ShowcaseAnimationArmature/"
    "ShowcaseRootBone/ShowcaseTipBone"
)


def parse_args(argv: list[str]) -> argparse.Namespace:
    if "--" not in argv:
        raise SystemExit("Fixture arguments must follow Blender's '--' separator.")
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", required=True)
    return parser.parse_args(argv[argv.index("--") + 1 :])


def require_project_root(project_root: Path) -> Path:
    """Keep this fixture writer pinned to its checked-in BlendLib project."""

    resolved = project_root.resolve()
    if resolved != PROJECT_DIRECTORY:
        raise ValueError(
            "BLENDLIB-P5-FIXTURE-001: --project-root must be the checked-in BlendLib project "
            f"({PROJECT_DIRECTORY}), not {resolved}."
        )
    return resolved


def reset_scene() -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    # The tracked authoring source must not produce rolling .blend1 backups.
    bpy.context.preferences.filepaths.save_version = 0


def make_external_png(path: Path) -> Any:
    path.parent.mkdir(parents=True, exist_ok=True)
    image = bpy.data.images.new("ShowcaseAnimationPixels", width=2, height=2, alpha=True)
    # Amber and blue pixels make the source's external-texture identity obvious
    # without relying on an embedded GLB image at runtime.
    image.pixels = [
        0.96,
        0.48,
        0.10,
        1.0,
        0.12,
        0.58,
        0.92,
        1.0,
        0.12,
        0.58,
        0.92,
        1.0,
        0.96,
        0.48,
        0.10,
        1.0,
    ]
    image.filepath_raw = str(path)
    image.file_format = "PNG"
    image.save()
    bpy.data.images.remove(image)
    return bpy.data.images.load(str(path), check_existing=False)


def make_material(image: Any) -> Any:
    material = bpy.data.materials.new(MATERIAL_NAME)
    material.use_nodes = True
    nodes = material.node_tree.nodes
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    shader = nodes.new("ShaderNodeBsdfPrincipled")
    texture = nodes.new("ShaderNodeTexImage")
    texture.image = image
    material.node_tree.links.new(texture.outputs["Color"], shader.inputs["Base Color"])
    material.node_tree.links.new(shader.outputs["BSDF"], output.inputs["Surface"])
    return material


def make_skinned_mesh(collection: Any, armature: Any, material: Any) -> Any:
    mesh_data = bpy.data.meshes.new(f"{MESH_NAME}Data")
    # Blender +Z is up and -Y is forward. The P2 exporter owns the one canonical
    # boundary conversion to BlendLib's +Y-up, +Z-forward asset space.
    mesh_data.from_pydata(
        [
            (-0.25, -0.18, 0.00),
            (0.25, -0.18, 0.00),
            (-0.12, -0.18, 1.20),
            (0.12, -0.18, 1.20),
        ],
        [],
        [(0, 1, 2), (1, 3, 2)],
    )
    mesh_data.update()
    uv = mesh_data.uv_layers.new(name="UVMap")
    for loop, coordinate in zip(
        uv.data,
        ((0.0, 0.0), (1.0, 0.0), (0.0, 1.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)),
    ):
        loop.uv = coordinate
    mesh_data.materials.append(material)
    mesh = bpy.data.objects.new(MESH_NAME, mesh_data)
    mesh.parent = armature
    collection.objects.link(mesh)

    root_group = mesh.vertex_groups.new(name=ROOT_BONE_NAME)
    tip_group = mesh.vertex_groups.new(name=TIP_BONE_NAME)
    root_group.add([0, 1], 1.0, "REPLACE")
    tip_group.add([2, 3], 1.0, "REPLACE")
    modifier = mesh.modifiers.new("BlendLibShowcaseArmature", "ARMATURE")
    modifier.object = armature
    return mesh


def make_armature(collection: Any, root: Any) -> Any:
    armature_data = bpy.data.armatures.new(f"{ARMATURE_NAME}Data")
    armature = bpy.data.objects.new(ARMATURE_NAME, armature_data)
    armature.parent = root
    collection.objects.link(armature)
    bpy.context.view_layer.objects.active = armature
    armature.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    root_bone = armature_data.edit_bones.new(ROOT_BONE_NAME)
    root_bone.head = (0.0, 0.0, 0.0)
    root_bone.tail = (0.0, 0.0, 0.60)
    tip_bone = armature_data.edit_bones.new(TIP_BONE_NAME)
    tip_bone.head = (0.0, 0.0, 0.60)
    tip_bone.tail = (0.0, 0.0, 1.20)
    tip_bone.parent = root_bone
    tip_bone.use_connect = True
    bpy.ops.object.mode_set(mode="OBJECT")
    armature.animation_data_create()
    for pose_bone in armature.pose.bones:
        pose_bone.rotation_mode = "XYZ"
    return armature


def add_action(armature: Any, name: str, data_path: str, index: int, keys: list[tuple[float, float]]) -> Any:
    action = bpy.data.actions.new(name)
    action.use_fake_user = True
    armature.animation_data.action = action
    curve = action.fcurve_ensure_for_datablock(armature, data_path, index=index)
    curve.keyframe_points.add(len(keys))
    for point, (frame, value) in zip(curve.keyframe_points, keys):
        point.co = (frame, value)
        point.interpolation = "LINEAR"
    curve.update()
    return action


def make_actions(armature: Any) -> dict[str, Any]:
    tip_rotation = f'pose.bones["{TIP_BONE_NAME}"].rotation_euler'
    root_location = f'pose.bones["{ROOT_BONE_NAME}"].location'
    actions = {
        # A small, symmetric tip sway: this is intentionally unlike walking or
        # attack, both by channel target and by sampled values.
        "idle": add_action(
            armature,
            "idle",
            tip_rotation,
            1,
            [(1.0, 0.00), (12.0, 0.06), (24.0, 0.00)],
        ),
        # A root-bone lateral cadence: a different channel/path from idle.
        "walk": add_action(
            armature,
            "walk",
            root_location,
            0,
            [(1.0, 0.00), (7.0, 0.07), (13.0, 0.00), (19.0, -0.07), (24.0, 0.00)],
        ),
        # A non-symmetric swing with a different sampled signature from idle.
        "attack": add_action(
            armature,
            "attack",
            tip_rotation,
            1,
            [(1.0, 0.00), (5.0, -0.82), (11.0, 0.96), (18.0, 0.14), (24.0, 0.00)],
        ),
    }

    # P2 discovers Actions through the active action and NLA strips. Muted NLA
    # tracks retain all three authoring clips without mixing their poses in the
    # source scene. The existing P2 exporter still performs the actual Actions
    # mode glTF export and resampling.
    for name in ("idle", "walk", "attack"):
        # Blender 5 Action Slots belong to their assigned Action. Select the
        # action before binding its NLA strip; reusing idle's slot for another
        # Action is rejected by Blender and would make this source nonportable.
        armature.animation_data.action = actions[name]
        track = armature.animation_data.nla_tracks.new()
        strip = track.strips.new(f"Showcase{name.title()}Nla", 1, actions[name])
        action_slot = getattr(armature.animation_data, "action_slot", None)
        if action_slot is not None and hasattr(strip, "action_slot"):
            strip.action_slot = action_slot
        track.mute = True
    armature.animation_data.action = actions["idle"]
    return actions


def expected_contract() -> dict[str, Any]:
    return {
        "format": "blendlib-p5-showcase-animation-source-v1",
        "collection": COLLECTION_NAME,
        "namespace": NAMESPACE,
        "model_id": MODEL_ID,
        "profile": "blendlib:skinned_v1",
        "expected_default_scene_roots": [ROOT_NAME],
        "expected_animation_names": ["attack", "idle", "walk"],
        "expected_nodes": [ROOT_NAME, ARMATURE_NAME, ROOT_BONE_NAME, TIP_BONE_NAME, MESH_NAME],
        "source_texture": "textures/showcase_animation_surface.png",
        "expected_base_color": (
            "blendlib_showcase:textures/blendlib/showcase_animation/"
            "showcase_actor__showcaseanimationsurface.png"
        ),
        "descriptor_animation": {
            "initial_state": "blendlib_showcase:idle",
            "states": {
                "blendlib_showcase:idle": {
                    "clip": "idle",
                    "loop": True,
                    "speed": 1.0,
                    "blend_seconds": 0.12,
                },
                "blendlib_showcase:walk": {
                    "clip": "walk",
                    "loop": True,
                    "speed": 1.0,
                    "blend_seconds": 0.12,
                },
                "blendlib_showcase:attack": {
                    "clip": "attack",
                    "loop": False,
                    "speed": 1.0,
                    "next": "blendlib_showcase:idle",
                    "blend_seconds": 0.08,
                    "events": [
                        {
                            "time_seconds": 0.25,
                            "event": "blendlib_showcase:attack_whoosh",
                        }
                    ],
                },
            },
        },
        "descriptor_sockets": {
            TIP_SOCKET_KEY: {
                "node": TIP_SOCKET_PATH,
            },
        },
    }


def create_asset(project_root: Path) -> None:
    project_root = require_project_root(project_root)
    reset_scene()
    asset_root = project_root / ASSET_DIRECTORY
    texture = make_external_png(asset_root / "textures" / "showcase_animation_surface.png")
    material = make_material(texture)

    collection = bpy.data.collections.new(COLLECTION_NAME)
    bpy.context.scene.collection.children.link(collection)
    root = bpy.data.objects.new(ROOT_NAME, None)
    collection.objects.link(root)
    armature = make_armature(collection, root)
    make_skinned_mesh(collection, armature, material)
    make_actions(armature)

    bpy.context.scene.frame_start = 1
    bpy.context.scene.frame_end = 24
    bpy.context.scene.frame_set(1)
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.wm.save_as_mainfile(filepath=str(asset_root / "source.blend"), check_existing=False)
    (asset_root / "expected.json").write_text(
        json.dumps(expected_contract(), indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def main() -> None:
    args = parse_args(sys.argv)
    project_root = Path(args.project_root).resolve()
    create_asset(project_root)
    print("BLENDLIB_P5_SHOWCASE_ANIMATION_SOURCE_CREATED idle walk attack")


if __name__ == "__main__":
    main()
