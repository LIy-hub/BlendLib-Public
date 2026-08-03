# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Create the three canonical P2 source .blend fixtures and external PNGs.

Run through Blender, never through the BlendLib Java runtime:

    blender --background --python create_canonical_fixtures.py -- --project-root .
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

# The canonical source generator must not create Python bytecode beside itself.
sys.dont_write_bytecode = True

import bpy


def parse_args(argv: list[str]) -> argparse.Namespace:
    if "--" not in argv:
        raise SystemExit("Fixture arguments must follow Blender's '--' separator.")
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", required=True)
    return parser.parse_args(argv[argv.index("--") + 1 :])


def reset_scene() -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    # Canonical source fixtures have one committed .blend each; never emit .blend1 backups.
    bpy.context.preferences.filepaths.save_version = 0


def make_collection(name: str) -> Any:
    collection = bpy.data.collections.new("BlendLibExport")
    bpy.context.scene.collection.children.link(collection)
    return collection


def make_external_png(path: Path, rgba: tuple[float, float, float, float]) -> Any:
    path.parent.mkdir(parents=True, exist_ok=True)
    generated = bpy.data.images.new("BlendLibFixturePixels", width=2, height=2, alpha=True)
    generated.pixels = list(rgba) * 4
    generated.filepath_raw = str(path)
    generated.file_format = "PNG"
    generated.save()
    bpy.data.images.remove(generated)
    return bpy.data.images.load(str(path), check_existing=False)


def make_material(name: str, image: Any) -> Any:
    material = bpy.data.materials.new(name)
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


def make_triangle_mesh(
    collection: Any,
    name: str,
    vertices: list[tuple[float, float, float]],
    material: Any,
) -> Any:
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], [(0, 1, 2)])
    mesh.update()
    uv = mesh.uv_layers.new(name="UVMap")
    for loop, coordinate in zip(uv.data, ((0.0, 0.0), (1.0, 0.0), (0.0, 1.0))):
        loop.uv = coordinate
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)
    return obj


def add_filtered_camera_and_light(collection: Any, root: Any, prefix: str) -> None:
    camera = bpy.data.objects.new(f"{prefix}Camera", bpy.data.cameras.new(f"{prefix}CameraData"))
    light = bpy.data.objects.new(f"{prefix}Light", bpy.data.lights.new(f"{prefix}LightData", "POINT"))
    camera.parent = root
    light.parent = root
    collection.objects.link(camera)
    collection.objects.link(light)


def save_fixture(blend_path: Path, expected_path: Path, expected: dict[str, Any]) -> None:
    blend_path.parent.mkdir(parents=True, exist_ok=True)
    bpy.context.scene.frame_start = 1
    bpy.context.scene.frame_end = 20
    bpy.context.scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=str(blend_path), check_existing=False)
    expected_path.write_text(json.dumps(expected, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def create_static(root: Path) -> None:
    reset_scene()
    collection = make_collection("BlendLibExport")
    image = make_external_png(root / "test-assets" / "static" / "textures" / "static_surface.png", (0.2, 0.7, 1.0, 1.0))
    material = make_material("StaticSurface", image)
    scene_root = bpy.data.objects.new("StaticRoot", None)
    collection.objects.link(scene_root)
    mesh = make_triangle_mesh(
        collection,
        "StaticMesh",
        [(-0.5, -2.0, 0.0), (0.5, 0.0, 0.0), (0.0, 0.0, 1.0)],
        material,
    )
    mesh.parent = scene_root
    add_filtered_camera_and_light(collection, scene_root, "Static")
    save_fixture(
        root / "test-assets" / "static" / "source.blend",
        root / "test-assets" / "static" / "expected.json",
        {
            "fixture": "static",
            "collection": "BlendLibExport",
            "namespace": "blendlib_showcase",
            "model_id": "fixtures/static_model",
            "profile": "blendlib:rigid_v1",
            "expected_animation_names": [],
            "coordinate_probe": {
                "blender": [0.0, -2.0, 0.0],
                "canonical": [0.0, 0.0, 2.0],
            },
            "expected_canonical_bounds": {
                "min": [-0.5, 0.0, 0.0],
                "max": [0.5, 1.0, 2.0],
            },
            "source_texture": "textures/static_surface.png",
            "expected_base_color": "blendlib_showcase:textures/blendlib/fixtures_static_model__staticsurface.png",
        },
    )


def create_rigid(root: Path) -> None:
    reset_scene()
    collection = make_collection("BlendLibExport")
    image = make_external_png(root / "test-assets" / "rigid" / "textures" / "rigid_surface.png", (1.0, 0.45, 0.1, 1.0))
    material = make_material("RigidSurface", image)
    scene_root = bpy.data.objects.new("RigidRoot", None)
    collection.objects.link(scene_root)
    base = make_triangle_mesh(
        collection,
        "RigidBase",
        [(-0.5, -1.0, 0.0), (0.5, 0.0, 0.0), (0.0, 0.0, 0.75)],
        material,
    )
    base.parent = scene_root
    arm = make_triangle_mesh(
        collection,
        "RigidArm",
        [(0.0, -0.3, 0.0), (0.4, -0.3, 0.0), (0.0, -0.3, 0.6)],
        material,
    )
    arm.parent = scene_root
    arm.location = (0.0, 0.0, 1.0)
    arm.animation_data_create()
    action = bpy.data.actions.new("rigid_pulse")
    arm.animation_data.action = action
    curve = action.fcurve_ensure_for_datablock(arm, "location", index=0)
    curve.keyframe_points.add(2)
    curve.keyframe_points[0].co = (1.0, 0.0)
    curve.keyframe_points[1].co = (20.0, 0.25)
    for point in curve.keyframe_points:
        point.interpolation = "BEZIER"
    track = arm.animation_data.nla_tracks.new()
    strip = track.strips.new("RigidPulseNla", 1, action)
    strip.action_slot = arm.animation_data.action_slot
    track.mute = True
    add_filtered_camera_and_light(collection, scene_root, "Rigid")
    save_fixture(
        root / "test-assets" / "rigid" / "source.blend",
        root / "test-assets" / "rigid" / "expected.json",
        {
            "fixture": "rigid",
            "collection": "BlendLibExport",
            "namespace": "blendlib_showcase",
            "model_id": "fixtures/rigid_model",
            "profile": "blendlib:rigid_v1",
            "expected_animation_names": ["rigid_pulse"],
            "animation_source": "NLA action with BEZIER source keys; exporter must resample to LINEAR or STEP",
            "source_texture": "textures/rigid_surface.png",
            "expected_base_color": "blendlib_showcase:textures/blendlib/fixtures_rigid_model__rigidsurface.png",
        },
    )


def create_skinned(root: Path) -> None:
    reset_scene()
    collection = make_collection("BlendLibExport")
    image = make_external_png(root / "test-assets" / "skinned" / "textures" / "skinned_surface.png", (0.55, 0.2, 0.9, 1.0))
    material = make_material("SkinnedSurface", image)
    scene_root = bpy.data.objects.new("SkinnedRoot", None)
    collection.objects.link(scene_root)

    armature_data = bpy.data.armatures.new("SkinnedArmatureData")
    armature = bpy.data.objects.new("SkinnedArmature", armature_data)
    armature.parent = scene_root
    collection.objects.link(armature)
    bpy.context.view_layer.objects.active = armature
    armature.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    bone = armature_data.edit_bones.new("SkinnedBone")
    bone.head = (0.0, 0.0, 0.0)
    bone.tail = (0.0, 0.0, 1.0)
    bpy.ops.object.mode_set(mode="OBJECT")

    mesh = make_triangle_mesh(
        collection,
        "SkinnedMesh",
        [(-0.4, -1.0, 0.0), (0.4, 0.0, 0.0), (0.0, 0.0, 1.0)],
        material,
    )
    mesh.parent = armature
    group = mesh.vertex_groups.new(name="SkinnedBone")
    group.add([vertex.index for vertex in mesh.data.vertices], 1.0, "REPLACE")
    modifier = mesh.modifiers.new("BlendLibArmature", "ARMATURE")
    modifier.object = armature

    armature.animation_data_create()
    action = bpy.data.actions.new("skinned_wave")
    armature.animation_data.action = action
    armature.pose.bones["SkinnedBone"].rotation_mode = "XYZ"
    curve = action.fcurve_ensure_for_datablock(
        armature, 'pose.bones["SkinnedBone"].rotation_euler', index=2
    )
    curve.keyframe_points.add(2)
    curve.keyframe_points[0].co = (1.0, 0.0)
    curve.keyframe_points[1].co = (20.0, 0.4)
    for point in curve.keyframe_points:
        point.interpolation = "BEZIER"
    add_filtered_camera_and_light(collection, scene_root, "Skinned")
    save_fixture(
        root / "test-assets" / "skinned" / "source.blend",
        root / "test-assets" / "skinned" / "expected.json",
        {
            "fixture": "skinned",
            "collection": "BlendLibExport",
            "namespace": "blendlib_showcase",
            "model_id": "fixtures/skinned_model",
            "profile": "blendlib:skinned_v1",
            "expected_animation_names": ["skinned_wave"],
            "source_texture": "textures/skinned_surface.png",
            "expected_base_color": "blendlib_showcase:textures/blendlib/fixtures_skinned_model__skinnedsurface.png",
            "skin": "one armature bone with exactly one normalized weight per vertex",
        },
    )


def main() -> None:
    args = parse_args(sys.argv)
    root = Path(args.project_root).resolve()
    create_static(root)
    create_rigid(root)
    create_skinned(root)
    print("BLENDLIB_FIXTURES_CREATED static rigid skinned")


if __name__ == "__main__":
    main()
