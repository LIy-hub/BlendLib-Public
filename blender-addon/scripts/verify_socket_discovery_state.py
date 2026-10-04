# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Adversarial real-Blender discovery restoration and source identity checks."""
import argparse
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True
import bpy
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_exporter as exporter
import blendlib_authoring_sockets as sockets
import blendlib_authoring_ui as ui


def rejected(call, expected):
    try:
        result = call()
    except (ValueError, RuntimeError) as error:
        assert expected in str(error), str(error)
    else:
        assert result == {'CANCELLED'}, result


def snapshot(scene):
    values = []
    for obj in scene.objects:
        transforms = [(value.name, [tuple(getattr(value, field)) for field in sockets._TRANSFORM_FIELDS])
                      for value in [obj] + (list(obj.pose.bones) if obj.pose else [])]
        data = obj.animation_data
        animation = ([getattr(data, field) for field in sockets._ANIMATION_FIELDS],
                     [(track.mute, track.is_solo) for track in data.nla_tracks]) if data else None
        values.append((obj, transforms, animation, obj.select_get()))
    return (values, scene.frame_current, scene.frame_subframe,
            bpy.context.view_layer.active_layer_collection, bpy.context.view_layer.objects.active,
            [(text, text.as_string()) for text in bpy.data.texts],
            {key: len(getattr(bpy.data, key)) for key in ('objects', 'actions', 'materials', 'images', 'meshes', 'armatures')})


def main(root):
    bpy.ops.wm.open_mainfile(filepath=str(root/'test-assets/blender-sockets/skinned/source.blend'))
    exporter.register()
    scene, rig = bpy.context.scene, bpy.data.objects['Rig']
    other = rig.animation_data.action.copy()
    track = rig.animation_data.nla_tracks.new()
    track.strips.new('OtherWave', 10, other)
    track.mute = True
    scene.frame_set(17, subframe=.375)
    rig.pose.bones['Arm'].location = (3, 4, 5)
    rig.pose.bones['Arm'].rotation_euler = (.4, .5, .6)
    for no_slot in (False, True):
        if no_slot:
            rig.animation_data.action_slot = None
        before = snapshot(scene)
        sockets.discover(scene, exporter)
        assert before == snapshot(scene), 'Successful discovery mutated source Action/pose/slot'
    # Bone RNA storage may be recycled; Edit Mode is an explicit stale boundary.
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    # Use whatever fixture bone socket is canonical, without hardcoding its path.
    config = json.loads(scene.blendlib_runtime_authoring_text.as_string())
    bone_key = next(key for key, value in config['sockets'].items() if value['node'].endswith('/Hand'))
    scene.blendlib_authoring_socket = bone_key
    assert bpy.ops.blendlib.authoring_socket_begin(mode='EDIT') == {'FINISHED'}
    content = scene.blendlib_runtime_authoring_text.as_string()
    bpy.ops.object.mode_set(mode='EDIT')
    bone = rig.data.edit_bones['Hand']
    head, tail, parent = bone.head.copy(), bone.tail.copy(), bone.parent
    rig.data.edit_bones.remove(bone)
    bone = rig.data.edit_bones.new('Hand')
    bone.head, bone.tail, bone.parent = head, tail, parent
    bpy.ops.object.mode_set(mode='OBJECT')
    assert scene.blendlib_authoring_draft.source_invalidated
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'discard and reload')
    assert content == scene.blendlib_runtime_authoring_text.as_string()
    bpy.ops.blendlib.authoring_discard()
    exporter.unregister()

    bpy.ops.wm.open_mainfile(filepath=str(root/'test-assets/blender-authoring/source.blend'))
    exporter.register()
    scene, body = bpy.context.scene, bpy.data.objects['Body']
    scene.frame_set(19, subframe=.25)
    body.animation_data.nla_tracks[0].is_solo = True
    before = snapshot(scene)
    sockets.discover(scene, exporter)
    assert before == snapshot(scene), 'Successful discovery changed NLA solo'
    # Fail inside actual exporter sampling after it has switched Actions.
    from io_scene_gltf2.blender.exp.animation import action
    original = action.gather_action_object_sampled
    calls = []

    def fail(*args, **kwargs):
        calls.append(True)
        if len(calls) >= 2:
            raise RuntimeError('Injected interrupted sampling')
        return original(*args, **kwargs)

    action.gather_action_object_sampled = fail
    try:
        rejected(lambda: sockets.discover(scene, exporter), 'Injected interrupted sampling')
    finally:
        action.gather_action_object_sampled = original
    assert len(calls) >= 2 and before == snapshot(scene), 'Failed discovery did not restore source state'
    body.animation_data.use_tweak_mode = True
    before = snapshot(scene)
    rejected(lambda: sockets.discover(scene, exporter), 'Exit NLA Tweak Mode')
    assert body.animation_data.use_tweak_mode and before == snapshot(scene)
    body.animation_data.use_tweak_mode = False
    # Same names/hierarchy with a replacement ID still require a fresh draft.
    scene.blendlib_authoring_socket = 'blendlib_authoring:hand'
    assert bpy.ops.blendlib.authoring_socket_begin(mode='EDIT') == {'FINISHED'}
    old = bpy.data.objects['Hand']
    parent, matrix, uid = old.parent, old.matrix_basis.copy(), old.session_uid
    bpy.data.objects.remove(old, do_unlink=True)
    replacement = bpy.data.objects.new('Hand', None)
    scene.blendlib_collection.objects.link(replacement)
    replacement.parent, replacement.matrix_basis = parent, matrix
    assert uid != replacement.session_uid
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'identity or node paths changed')
    bpy.ops.blendlib.authoring_discard()
    # Multi-scene upstream export may switch context and sample other frames.
    other_scene = bpy.data.scenes.new('Other')
    other_scene.collection.children.link(scene.blendlib_collection)
    other_scene.frame_set(25, subframe=.5)
    scene.frame_set(19, subframe=.25)
    before = snapshot(scene)
    other_before = snapshot(other_scene)
    sockets.discover(scene, exporter)
    assert bpy.context.scene == scene and before == snapshot(scene) and other_before == snapshot(other_scene)

    def fail_other(*args, **kwargs):
        if bpy.context.scene == other_scene:
            raise RuntimeError('Interrupted other-scene sampling')
        return original(*args, **kwargs)

    action.gather_action_object_sampled = fail_other
    try:
        rejected(lambda: sockets.discover(scene, exporter), 'Interrupted other-scene sampling')
    finally:
        action.gather_action_object_sampled = original
    assert bpy.context.scene == scene and before == snapshot(scene) and other_before == snapshot(other_scene)
    # A legal source name must not collide with the unselected enum placeholder.
    bpy.data.objects['Root'].name = '__NONE__'
    config = json.loads(scene.blendlib_runtime_authoring_text.as_string())
    config.pop('sockets')
    scene.blendlib_runtime_authoring_text.from_string(json.dumps(config))
    content = scene.blendlib_runtime_authoring_text.as_string()
    assert bpy.ops.blendlib.authoring_socket_begin(mode='ADD') == {'FINISHED'}
    assert scene.blendlib_authoring_draft.socket_node == '/'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Choose an exact discovered')
    assert content == scene.blendlib_runtime_authoring_text.as_string()
    bpy.ops.blendlib.authoring_discard()
    # Handler registration is bounded across reload/restart boundaries.
    exporter.unregister()
    exporter.register()
    assert bpy.app.handlers.depsgraph_update_post.count(ui._EDIT_HANDLER) == 1
    assert bpy.app.handlers.undo_post.count(ui._UNDO_HANDLER) == 1
    assert bpy.app.handlers.redo_post.count(ui._UNDO_HANDLER) == 1
    exporter.unregister()
    result = {'blender_version': bpy.app.version_string, 'multi_action_pose_and_null_slot_restored': True,
              'nla_solo_restored': True, 'interrupted_real_sampling_restored': True,
              'tweak_mode_rejected_before_export': True, 'same_name_replacement_id_rejected': True,
              'recreated_bone_draft_rejected': True, 'single_lifecycle_handlers': True, 'multi_scene_success_and_failure_restored': True,
              'placeholder_cannot_alias_legal_node_name': True}
    out = root/'build/socket-discovery-state/verification.json'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2)+'\n')
    print('SOCKET DISCOVERY STATE ACCEPTANCE PASSED '+json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', required=True)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(Path(args.project_root).resolve())
