# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Genuine Blender socket-editor fixtures and strict Java pose-roundtrip evidence.

Run with Blender 5.1+:
  blender --background --python blender-addon/scripts/verify_socket_editor.py -- --project-root .

The Java BlenderSocketAuthoringLocomotionAcceptanceTest consumes these real GLBs.
Expected poses come from Blender's evaluated scene, never from the exported GLB.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import bpy
from mathutils import Matrix
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import blendlib_exporter as exporter
import blendlib_authoring_ui as ui
from create_canonical_fixtures import reset_scene, make_collection, make_external_png, make_material, make_triangle_mesh

NAMESPACE = 'blendlib_sockets:'
# The exporter's fixed Blender Z-up -> canonical glTF Y-up basis.
BASIS = Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1)))


def rejected(call, message=None):
    try:
        result = call()
    except (RuntimeError, ValueError, exporter.ExportError) as error:
        if message:
            assert message.lower() in str(error).lower(), str(error)
    else:
        assert result == {'CANCELLED'}, result


def rows(scene):
    found, signature = ui._socket_source(scene, exporter)
    assert found and signature
    assert all(set(row) >= {'path', 'kind', 'name', 'owner'} for row in found)
    assert len({row['path'] for row in found}) == len(found)
    return found


def discovered(scene, name, kind='OBJECT'):
    candidates = [row for row in rows(scene) if row['name'] == name and row['kind'] == kind]
    assert len(candidates) == 1, candidates
    return candidates[0]['path']


def begin(scene, mode='ADD', key=None):
    if mode == 'EDIT':
        scene.blendlib_authoring_socket = key
    assert bpy.ops.blendlib.authoring_socket_begin(mode=mode) == {'FINISHED'}
    draft = scene.blendlib_authoring_draft
    assert draft.active and draft.kind == 'SOCKET'
    return draft


def apply_socket(scene, key, node, mode='ADD'):
    draft = begin(scene, mode, key)
    draft.key, draft.socket_node = key, node
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    assert not draft.active
    return json.loads(scene.blendlib_runtime_authoring_text.as_string())


def discard(scene):
    assert bpy.ops.blendlib.authoring_discard() == {'FINISHED'}
    assert not scene.blendlib_authoring_draft.active


def configure(scene, collection, fixture, model, profile):
    scene.render.fps, scene.render.fps_base = 24, 1
    scene.blendlib_collection = collection
    scene.blendlib_namespace = NAMESPACE[:-1]
    scene.blendlib_model_id = model
    scene.blendlib_profile = profile
    scene.blendlib_project_root = '//../exported'
    scene.blendlib_output_resource_root = '.'
    scene.blendlib_runtime_authoring_enabled = False
    fixture.mkdir(parents=True, exist_ok=True)
    return exporter.ExportOptions(fixture/'source.blend', fixture.parent/'exported', NAMESPACE[:-1], model,
        profile, collection.name, '.', None, runtime_authoring_text=scene.blendlib_runtime_authoring_text.name)


def pose(matrix, *, bone=False):
    # Blender's glTF joint exporter retains bone-local axes: root joints receive
    # the Y-up correction on the left, while objects change both coordinate bases.
    # See io_scene_gltf2/blender/exp/animation/sampled/armature/sampler.py.
    canonical = BASIS @ matrix if bone else BASIS @ matrix @ BASIS.inverted()
    translation, rotation, scale = canonical.decompose()
    return {'translation': list(translation), 'rotation': [rotation.x, rotation.y, rotation.z, rotation.w],
            'scale': list(scale)}


def sampled_poses(scene, animated, clips, targets):
    original = animated.animation_data.action
    samples = []
    for state, action_name in clips:
        animated.animation_data.action = bpy.data.actions[action_name]
        for frame in (10, 16, 22, 28, 34):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            evaluated = animated.evaluated_get(bpy.context.evaluated_depsgraph_get())
            sockets = {}
            for key, target in targets.items():
                if isinstance(target, str):
                    matrix = evaluated.matrix_world @ evaluated.pose.bones[target].matrix
                else:
                    matrix = target.evaluated_get(bpy.context.evaluated_depsgraph_get()).matrix_world
                sockets[key] = pose(matrix, bone=isinstance(target, str))
            samples.append({'state': state, 'time_seconds': (frame - 10) / 24, 'sockets': sockets})
    animated.animation_data.action = original
    scene.frame_set(10)
    return samples


def output_bytes(result):
    keys = ('mesh_path', 'descriptor_path', 'locomotion_path')
    return {key: Path(result[key]).read_bytes() for key in keys if key in result}


def export_roundtrip(scene, options, fixture, expected, evidence):
    text = scene.blendlib_runtime_authoring_text
    authored = json.loads(text.as_string())
    scene.blendlib_runtime_authoring_enabled = True
    scene.frame_set(10)
    bpy.ops.wm.save_as_mainfile(filepath=str(options.blend_path), compress=True)
    (fixture/'runtime-authoring.json').write_text(text.as_string(), encoding='utf-8')
    first = exporter.export_open_blend(options)
    frozen = output_bytes(first)
    second = exporter.export_open_blend(options)
    assert frozen == output_bytes(second)
    descriptor = json.loads(frozen['descriptor_path'])
    assert descriptor['sockets'] == authored['sockets']
    assert all(set(value) == {'node'} for value in descriptor['sockets'].values())
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert frozen == output_bytes(first)

    # A transformed helper is the offset; strict v1 socket JSON remains node-only.
    invalid = json.loads(text.as_string())
    invalid['sockets'][next(iter(invalid['sockets']))]['offset'] = [1, 2, 3]
    text.from_string(json.dumps(invalid))
    rejected(lambda: exporter.export_open_blend(options), 'socket')
    assert frozen == output_bytes(first)
    text.from_string(json.dumps(authored, indent=2)+'\n')

    # Save with a dirty draft and reload. Only canonical Text persists.
    key = next(iter(authored['sockets']))
    draft = begin(scene, 'EDIT', key)
    draft.key = NAMESPACE+'must_not_persist'
    bpy.ops.wm.save_as_mainfile(filepath=str(options.blend_path), compress=True)
    bpy.ops.wm.open_mainfile(filepath=str(options.blend_path))
    scene = bpy.context.scene
    assert not scene.blendlib_authoring_draft.active
    assert json.loads(scene.blendlib_runtime_authoring_text.as_string()) == authored
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert frozen == output_bytes(first)
    for key, value in authored['sockets'].items():
        draft = begin(scene, 'EDIT', key)
        assert draft.socket_node == value['node']
        assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
        assert json.loads(scene.blendlib_runtime_authoring_text.as_string()) == authored
    expected['sockets'] = authored['sockets']
    expected['discovered_nodes'] = rows(scene)
    (fixture/'expected.json').write_text(json.dumps(expected, indent=2, sort_keys=True)+'\n', encoding='utf-8')
    evidence.update(two_run_byte_identical=True, sidebar_export=True, save_reload_discards_draft=True,
                    strict_node_only_socket_json=True, invalid_offset_preserves_outputs=True,
                    exported_sha256={key: hashlib.sha256(value).hexdigest() for key, value in frozen.items()})
    return scene


def rigid(root):
    fixture = root/'test-assets/blender-sockets/rigid'
    fixture.mkdir(parents=True, exist_ok=True)
    source = root/'test-assets/blender-authoring'
    bpy.ops.wm.open_mainfile(filepath=str(source/'source.blend'))
    bpy.context.preferences.filepaths.save_version = 0
    exporter.register()
    scene = bpy.context.scene
    text = scene.blendlib_runtime_authoring_text
    original_config = json.loads(text.as_string())
    collection = scene.blendlib_collection
    shutil.copyfile(source/'albedo.png', fixture/'albedo.png')
    for image in bpy.data.images:
        if image.source == 'FILE' and image.filepath:
            image.filepath = str(fixture/'albedo.png')
    root_node, body = bpy.data.objects['Root'], bpy.data.objects['Body']
    root_node.location = (2, -1, 3)
    root_node.rotation_euler = (0, 0, math.pi / 2)
    root_node.scale = (2, 2, 2)
    pivot = bpy.data.objects.new('Pivot', None)
    collection.objects.link(pivot)
    pivot.parent, pivot.location = root_node, (1, 2, .5)
    pivot.rotation_euler, pivot.scale = (math.pi / 2, 0, 0), (.5, .5, .5)
    body.parent = pivot
    helper = bpy.data.objects.new('Grip', None)
    collection.objects.link(helper)
    helper.parent, helper.location = body, (.25, -.5, .75)
    helper.rotation_euler, helper.scale = (0, math.pi / 2, 0), (1.25, 1.25, 1.25)
    options = configure(scene, collection, fixture, 'rigid', 'blendlib:rigid_v1')
    # Discovery needs a saved source, just like the real strict exporter.
    bpy.ops.wm.save_as_mainfile(filepath=str(options.blend_path), compress=True)
    for image in bpy.data.images:
        if image.source == 'FILE' and image.filepath:
            image.filepath = '//albedo.png'
    source_rows = rows(scene)
    helper_path = discovered(scene, 'Grip')
    assert helper_path == 'Root/Pivot/Body/Grip'
    assert all(row['kind'] == 'OBJECT' for row in source_rows)
    evidence = {'nested_rotated_uniform_scaled_parents': True, 'transformed_empty_helper': True}

    # Starting, selection, discard, and invalid key/duplicate key never rewrite Text.
    original = text.as_string()
    draft = begin(scene)
    draft.key, draft.socket_node = NAMESPACE+'temporary', helper_path
    rejected(lambda: bpy.ops.blendlib.authoring_begin(mode='ADD'), 'current draft')
    rejected(lambda: bpy.ops.blendlib.authoring_socket_begin(mode='ADD'), 'current draft')
    assert text.as_string() == original
    discard(scene)
    assert text.as_string() == original and not scene.blendlib_runtime_authoring_enabled
    draft = begin(scene)
    draft.key, draft.socket_node = 'INVALID key', helper_path
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'resource')
    assert text.as_string() == original and draft.active
    draft.key = 'blendlib_authoring:hand'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'already exists')
    assert text.as_string() == original
    discard(scene)
    added = apply_socket(scene, NAMESPACE+'held_item', helper_path)
    assert added['animation'] == original_config['animation']
    assert added['locomotion'] == original_config['locomotion']
    assert added['sockets']['blendlib_authoring:hand'] == original_config['sockets']['blendlib_authoring:hand']
    assert not scene.blendlib_runtime_authoring_enabled

    # Existing key identity and Text pointer/content conflicts are guarded.
    original = text.as_string()
    draft = begin(scene, 'EDIT', NAMESPACE+'held_item')
    draft.key = NAMESPACE+'renamed'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Renaming')
    assert text.as_string() == original
    discard(scene)
    begin(scene, 'EDIT', NAMESPACE+'held_item')
    other = bpy.data.texts.new('Other.runtime.json')
    other.from_string(original)
    scene.blendlib_runtime_authoring_text = other
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Selected Text changed')
    assert other.as_string() == text.as_string() == original
    discard(scene)
    scene.blendlib_runtime_authoring_text = text
    begin(scene, 'EDIT', NAMESPACE+'held_item')
    text.write('\n')
    external = text.as_string()
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Text contents changed')
    assert text.as_string() == external
    discard(scene)
    text.from_string(original)

    # A changed name/hierarchy invalidates the active draft. Exact refreshed paths
    # can repair an already-authored missing reference without hand-writing JSON.
    begin(scene, 'EDIT', NAMESPACE+'held_item')
    helper.name = 'RenamedGrip'
    rejected(lambda: bpy.ops.blendlib.authoring_apply())
    assert text.as_string() == original
    discard(scene)
    renamed = discovered(scene, 'RenamedGrip')
    assert renamed != helper_path
    apply_socket(scene, NAMESPACE+'held_item', renamed, mode='EDIT')
    before_reparent = text.as_string()
    begin(scene, 'EDIT', NAMESPACE+'held_item')
    helper.parent = pivot
    rejected(lambda: bpy.ops.blendlib.authoring_apply())
    assert text.as_string() == before_reparent
    discard(scene)
    reparented = discovered(scene, 'RenamedGrip')
    assert reparented != renamed
    apply_socket(scene, NAMESPACE+'held_item', reparented, mode='EDIT')
    helper.name, helper.parent = 'Grip', body
    apply_socket(scene, NAMESPACE+'held_item', discovered(scene, 'Grip'), mode='EDIT')
    evidence.update(draft_discard_and_validation_non_mutating=True, source_text_identity_guard=True,
                    preserves_states_events_locomotion_and_other_sockets=True, explicit_opt_in_preserved=True,
                    renamed_and_reparented_nodes_repaired=True)

    expected = {'model': NAMESPACE+'rigid', 'profile': 'blendlib:rigid_v1',
        'oracle': 'Evaluated Blender object world matrices conjugated into glTF Y-up; not GLB-derived',
        'samples': sampled_poses(scene, body, [('blendlib_authoring:'+name.lower(), name)
            for name in ('Idle', 'Walk', 'Attack')],
            {NAMESPACE+'held_item': helper, 'blendlib_authoring:hand': bpy.data.objects['Hand']})}
    export_roundtrip(scene, options, fixture, expected, evidence)
    exporter.unregister()
    return evidence


def skinned(root):
    fixture = root/'test-assets/blender-sockets/skinned'
    fixture.mkdir(parents=True, exist_ok=True)
    reset_scene()
    collection = make_collection('BlendLibExport')
    scene = bpy.context.scene
    scene.frame_start, scene.frame_end = 10, 34
    root_node = bpy.data.objects.new('SkinRoot', None)
    collection.objects.link(root_node)
    # Keep the skin fixture inside existing strict source/bind-pose limits.
    # Nested transformed ancestry is covered separately by the rigid fixture.
    armature_data = bpy.data.armatures.new('RigData')
    armature = bpy.data.objects.new('Rig', armature_data)
    collection.objects.link(armature)
    armature.parent = root_node
    bpy.context.view_layer.objects.active = armature
    armature.select_set(True)
    bpy.ops.object.mode_set(mode='EDIT')
    root_bone = armature_data.edit_bones.new('Arm')
    root_bone.head, root_bone.tail = (0, 0, 0), (0, 1, 0)
    hand_bone = armature_data.edit_bones.new('Hand')
    hand_bone.head, hand_bone.tail, hand_bone.parent = (0, 1, 0), (0, 2, 0), root_bone
    bpy.ops.object.mode_set(mode='OBJECT')
    image = make_external_png(fixture/'albedo.png', (.8, .3, .2, 1))
    body = make_triangle_mesh(collection, 'Body', [(0, 0, 0), (.5, 0, 0), (0, 1, .5)], make_material('SkinMaterial', image))
    body.parent = armature
    body.vertex_groups.new(name='Hand').add([0, 1, 2], 1, 'REPLACE')
    body.modifiers.new('Skin', 'ARMATURE').object = armature
    object_hand = bpy.data.objects.new('ObjectHand', None)
    collection.objects.link(object_hand)
    object_hand.parent, object_hand.location = armature, (.5, .5, .5)
    object_hand.rotation_euler, object_hand.scale = (0, 0, math.pi/4), (.75, .75, .75)
    bone = armature.pose.bones['Hand']
    bone.rotation_mode = 'XYZ'
    for frame, distance, rotation in ((10, 0, 0), (34, .5, math.pi/2)):
        bone.location, bone.rotation_euler = (distance, 0, 0), (0, 0, rotation)
        bone.keyframe_insert(data_path='location', frame=frame)
        bone.keyframe_insert(data_path='rotation_euler', frame=frame)
    armature.animation_data.action.name = 'Wave'
    for layer in armature.animation_data.action.layers:
        for strip in layer.strips:
            for bag in strip.channelbags:
                for curve in bag.fcurves:
                    for key in curve.keyframe_points:
                        key.interpolation = 'LINEAR'
    scene.frame_set(10)
    exporter.register()
    text = bpy.data.texts.new('Sockets.runtime.json')
    text.from_string(json.dumps({'schema_version': 1, 'animation': {'initial_state': NAMESPACE+'wave',
        'states': {NAMESPACE+'wave': {'clip': 'Wave', 'loop': True, 'speed': 1}}}}, indent=2))
    scene.blendlib_runtime_authoring_text = text
    options = configure(scene, collection, fixture, 'skinned', 'blendlib:skinned_v1')
    bpy.ops.wm.save_as_mainfile(filepath=str(options.blend_path), compress=True)
    image.filepath = '//albedo.png'
    found = rows(scene)
    hand_path = discovered(scene, 'Hand', 'BONE')
    object_path = discovered(scene, 'ObjectHand', 'OBJECT')
    assert hand_path != object_path
    assert any(row['kind'] == 'BONE' and row['owner'] == 'Rig' for row in found)
    apply_socket(scene, NAMESPACE+'hand_bone', hand_path)
    apply_socket(scene, NAMESPACE+'hand_object', object_path)
    before = text.as_string()

    # Blender objects and armature bones can share names, but strict v1 forbids
    # that ambiguity. Discovery and Apply must preserve the previous Text.
    begin(scene, 'EDIT', NAMESPACE+'hand_bone')
    object_hand.name = 'Hand'
    rejected(lambda: ui._socket_source(scene, exporter), 'Duplicate')
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Duplicate')
    assert text.as_string() == before
    discard(scene)
    object_hand.name = 'ObjectHand'
    assert discovered(scene, 'Hand', 'BONE') == hand_path
    assert discovered(scene, 'ObjectHand', 'OBJECT') == object_path
    evidence = {'bone_and_object_targets_distinguished': True,
        'bone_paths_from_actual_exporter': True, 'duplicate_object_bone_name_rejected': True}
    expected = {'model': NAMESPACE+'skinned', 'profile': 'blendlib:skinned_v1',
        'oracle': 'Evaluated Blender matrices: C*boneWorld for joints, C*objectWorld*C^-1 for objects (glTF Y-up); not GLB-derived',
        'samples': sampled_poses(scene, armature, [(NAMESPACE+'wave', 'Wave')],
            {NAMESPACE+'hand_bone': 'Hand', NAMESPACE+'hand_object': object_hand})}
    export_roundtrip(scene, options, fixture, expected, evidence)
    exporter.unregister()
    return evidence


def main(root):
    assert bpy.app.version >= (5, 1, 0), 'Real Blender 5.1+ is required'
    evidence = {'blender_version': bpy.app.version_string, 'build_hash': bpy.app.build_hash.decode(),
                'rigid': rigid(root), 'skinned': skinned(root)}
    (root/'test-assets/blender-sockets/verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print('SOCKET EDITOR BLENDER ACCEPTANCE PASSED '+json.dumps(evidence))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', required=True)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(Path(args.project_root).resolve())
