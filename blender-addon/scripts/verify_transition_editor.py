# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Real Blender state/transition operators produce strict runtime timing fixtures."""
from __future__ import annotations
import argparse
import hashlib
import json
import sys
from pathlib import Path
sys.dont_write_bytecode = True
import bpy
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import blendlib_exporter as exporter
import blendlib_authoring_ui as ui
from create_canonical_fixtures import reset_scene, make_collection, make_external_png, make_material, make_triangle_mesh
from verify_authoring_editor import rejected


def main(root):
    assert bpy.app.version >= (5, 1, 0)
    fixture = root/'test-assets/blender-transitions'
    fixture.mkdir(parents=True, exist_ok=True)
    reset_scene()
    collection = make_collection('BlendLibExport')
    scene = bpy.context.scene
    scene.render.fps, scene.render.fps_base = 24, 1
    origin = bpy.data.objects.new('Root', None)
    collection.objects.link(origin)
    hand = bpy.data.objects.new('Hand', None)
    collection.objects.link(hand)
    hand.parent, hand.location = origin, (1, 0, 1)
    image = make_external_png(fixture/'albedo.png', (.35, .8, .4, 1))
    body = make_triangle_mesh(collection, 'Body', [(0, 0, 0), (.5, 0, 0), (0, 0, .5)], make_material('BodyMaterial', image))
    body.parent = origin
    body.animation_data_create()
    for name, distance, marker in [('Idle', .01, None), ('Walk', 1, 'Footstep'), ('Run', 2, 'Footstep'), ('Attack', 3, 'Impact')]:
        body.animation_data.action = None
        for frame, x in [(10, 0), (34, distance)]:
            body.location = (x, 0, 0)
            body.keyframe_insert(data_path='location', frame=frame)
        action = body.animation_data.action
        action.name = name
        for layer in action.layers:
            for strip in layer.strips:
                for bag in strip.channelbags:
                    for curve in bag.fcurves:
                        for key in curve.keyframe_points: key.interpolation = 'LINEAR'
        if marker: action.pose_markers.new(marker).frame = 22
        track = body.animation_data.nla_tracks.new()
        track.strips.new(name, 10, action)
        track.mute = True
    body.animation_data.action = bpy.data.actions['Idle']
    scene.frame_set(10)
    exporter.register()
    scene.blendlib_collection, scene.blendlib_namespace = collection, 'blendlib_transitions'
    scene.blendlib_model_id, scene.blendlib_project_root = 'actor', '//exported'
    scene.blendlib_output_resource_root = '.'
    scene.blendlib_runtime_authoring_enabled = False
    draft = scene.blendlib_authoring_draft
    def begin(key=None, mode='EDIT'):
        if key: scene.blendlib_authoring_state = 'blendlib_transitions:'+key
        assert bpy.ops.blendlib.authoring_begin(mode=mode) == {'FINISHED'}
        assert draft.active and draft.kind == 'STATE'

    def apply():
        assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
        assert not draft.active

    def discard():
        assert bpy.ops.blendlib.authoring_discard() == {'FINISHED'}
        assert not draft.active

    def apply_bad(message):
        before = scene.blendlib_runtime_authoring_text.as_string()
        rejected(lambda: bpy.ops.blendlib.authoring_apply(), message)
        assert draft.active and scene.blendlib_runtime_authoring_text.as_string() == before

    # Every persisted field is authored with the actual UI operators, no preset JSON.
    for i, (key, action, loop, next_key, blend, speed) in enumerate([
            ('idle', 'Idle', True, '/', '0.4', '1'),
            ('walk', 'Walk', True, '/', '0', '1'),
            ('run', 'Run', True, '/', None, '1'),
            ('attack', 'Attack', False, 'idle', '0.2', '2'),
            ('hold', 'Attack', False, '/', None, '1'),
            ('self', 'Walk', False, '//SELF', None, '1'),
            ('cycle_a', 'Walk', False, '/', None, '1'),
            ('cycle_b', 'Run', False, 'cycle_a', None, '1'),
            ('loop_next', 'Run', True, 'attack', None, '1'),
            ('precise', 'Idle', False, '/', '0.12345678912345678', '1')]):
        begin(mode='CREATE' if i == 0 else 'ADD')
        assert draft.next_state == '/' and not draft.use_blend
        draft.key, draft.action, draft.loop = 'blendlib_transitions:'+key, bpy.data.actions[action], loop
        draft.next_state = next_key if next_key.startswith('/') else 'blendlib_transitions:'+next_key
        draft.use_blend, draft.blend_seconds, draft.speed = blend is not None, blend or '0', speed
        if action != 'Idle':
            assert bpy.ops.blendlib.authoring_event(operation='ADD') == {'FINISHED'}
            draft.events[0].marker = 'Impact' if action == 'Attack' else 'Footstep'
            draft.events[0].event = 'blendlib_transitions:'+('impact' if action == 'Attack' else 'footstep')
        apply()
    begin('cycle_a')
    draft.next_state = 'blendlib_transitions:cycle_b'
    apply()
    text = scene.blendlib_runtime_authoring_text
    # Source-discovered socket, with the real temporary GLB path picker.
    assert bpy.ops.blendlib.authoring_socket_begin(mode='ADD') == {'FINISHED'}
    draft.key, draft.socket_node = 'blendlib_transitions:hand', 'Root/Hand'
    apply()
    assert bpy.ops.blendlib.authoring_rules_begin() == {'FINISHED'}
    draft.default_loop = 'blendlib_transitions:idle'
    for key, enter, exit in [('run', '2', '1.5'), ('walk', '0.1', '0.05')]:
        assert bpy.ops.blendlib.authoring_rules_row(target='RULE', operation='ADD') == {'FINISHED'}
        rule = draft.rules[draft.rule_index]
        rule.animation = 'blendlib_transitions:'+key
        assert bpy.ops.blendlib.authoring_rules_row(target='CONDITION', operation='ADD') == {'FINISHED'}
        condition = rule.conditions[0]
        condition.kind, condition.input_name = 'MIN', 'speed'
        condition.enter, condition.exit = enter, exit
    apply()
    assert not scene.blendlib_runtime_authoring_enabled
    canonical = text.as_string()
    config = json.loads(canonical)
    # Precise blend values pass actual StringProperty RNA without float rounding.
    for key in config['animation']['states']:
        begin(key.split(':')[1])
        if key.endswith(':precise'): assert draft.blend_seconds == '0.12345678912345678'
        if key.endswith(':self'): assert draft.next_state == '//SELF'
        apply()
        assert json.loads(text.as_string()) == config
    canonical = text.as_string()
    begin('attack')
    items = [row[0] for row in ui._NEXT_ITEMS[draft.as_pointer()]]
    assert '/' in items and '//SELF' in items and 'blendlib_transitions:idle' in items
    assert 'blendlib_transitions:attack' not in items
    rejected(lambda: bpy.ops.blendlib.authoring_begin(mode='ADD'), 'current draft')
    rejected(lambda: bpy.ops.blendlib.authoring_rules_begin(), 'current draft')
    draft.next_state, draft.use_blend = '/', False
    discard()
    assert text.as_string() == canonical
    begin('attack')
    for bad in ('NaN', 'true', 'null', '-1', '.2', '1e500', '9'*5000):
        draft.blend_seconds = bad
        apply_bad('number')
    draft.blend_seconds = '0.2'
    apply()
    assert json.loads(text.as_string()) == config
    # Deliberately clear/re-add next and blend; no empty/null placeholder is serialized.
    begin('attack')
    draft.next_state, draft.use_blend = '/', False
    apply()
    state = json.loads(text.as_string())['animation']['states']['blendlib_transitions:attack']
    assert 'next' not in state and 'blend_seconds' not in state
    begin('attack')
    draft.next_state, draft.use_blend, draft.blend_seconds = 'blendlib_transitions:idle', True, '0.2'
    apply()
    assert json.loads(text.as_string()) == config
    # Rule references are checked atomically after a state edit, for default and rules.
    for key in ('idle', 'walk', 'run'):
        begin(key)
        draft.next_state = '//SELF'
        apply_bad('continuous loops')
        draft.next_state, draft.loop = '/', False
        apply_bad('continuous loops')
        discard()
    # No implicit remapping after a target rename or deletion in canonical Text.
    for operation in ('rename', 'delete'):
        begin('attack')
        changed = json.loads(canonical)
        target = changed['animation']['states'].pop('blendlib_transitions:idle')
        if operation == 'rename': changed['animation']['states']['blendlib_transitions:renamed'] = target
        text.from_string(json.dumps(changed))
        assert draft.next_state == 'blendlib_transitions:idle'
        apply_bad('Text contents changed')
        discard()
        text.from_string(canonical)
    begin('attack')
    text.name = 'TransitionEditor.runtime.json'
    apply()
    assert json.loads(text.as_string()) == config
    begin('attack')
    other = bpy.data.texts.new('Identical.transitions.json')
    other.write(text.as_string())
    scene.blendlib_runtime_authoring_text = other
    apply_bad('Selected Text changed')
    discard()
    scene.blendlib_runtime_authoring_text = text
    begin('attack')
    draft.key = 'blendlib_transitions:renamed'
    apply_bad('Renaming')
    discard()
    # CREATE ignores the selected old Text, including its target choices. Self
    # remains deliberate across edits to the new key, never an enum index alias.
    begin(mode='CREATE')
    assert draft.next_state == '/'
    draft.key, draft.loop, draft.next_state = 'blendlib_transitions:new_self', False, '//SELF'
    draft.key = 'blendlib_transitions:renamed_self'
    apply()
    created = scene.blendlib_runtime_authoring_text
    assert set(json.loads(created.as_string())['animation']['states']) == {'blendlib_transitions:renamed_self'}
    assert json.loads(created.as_string())['animation']['states']['blendlib_transitions:renamed_self']['next'] == 'blendlib_transitions:renamed_self'
    assert text.as_string() == canonical
    scene.blendlib_runtime_authoring_text = text
    # An existing default-key state must stay selectable during Add, even before
    # the new draft's default key has been changed to a unique name.
    begin(mode='CREATE')
    assert draft.key == 'blendlib_transitions:new_state'
    apply()
    begin(mode='ADD')
    draft.key = 'blendlib_transitions:second'
    draft.next_state = 'blendlib_transitions:new_state'
    draft.loop = False
    apply()
    temp = json.loads(scene.blendlib_runtime_authoring_text.as_string())
    assert temp['animation']['states']['blendlib_transitions:second']['next'] == 'blendlib_transitions:new_state'
    scene.blendlib_runtime_authoring_text = text
    # Export always consumes applied Text, including when invalid draft edits exist.
    image.filepath = '//albedo.png'
    scene.blendlib_runtime_authoring_enabled = True
    source = fixture/'source.blend'
    bpy.ops.wm.save_as_mainfile(filepath=str(source), compress=True)
    (fixture/'runtime-authoring.json').write_text(text.as_string())
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    goldens = {str(p.relative_to(fixture/'exported')): p.read_bytes() for p in (fixture/'exported').rglob('*') if p.is_file()}
    begin('attack')
    draft.blend_seconds = '-1'
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((fixture/'exported'/p).read_bytes() == value for p,value in goldens.items())
    bpy.ops.wm.save_as_mainfile(filepath=str(source), compress=True)
    bpy.ops.wm.open_mainfile(filepath=str(source))
    scene, draft = bpy.context.scene, bpy.context.scene.blendlib_authoring_draft
    assert not draft.active
    assert json.loads(scene.blendlib_runtime_authoring_text.as_string()) == config
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((fixture/'exported'/p).read_bytes() == value for p,value in goldens.items())
    begin('attack')
    exporter.unregister()
    exporter.register()
    assert not scene.blendlib_authoring_draft.active
    bpy.ops.wm.save_as_mainfile(filepath=str(source), compress=True)
    exporter.unregister()
    evidence = {'blender_version': bpy.app.version_string, 'build_hash': bpy.app.build_hash.decode(),
        'all_fields_created_through_state_event_socket_rule_operators': True,
        'explicit_none_self_and_existing_target_choices': True, 'precision_absent_zero_preserved': True,
        'loop_next_and_positive_duration_cycles_follow_existing_contract': True,
        'rule_target_next_and_loop_invalidations_rejected_atomically': True,
        'renamed_deleted_target_and_text_identity_guards': True,
        'repeat_discard_error_recovery_save_reload_restart': True,
        'export_ignores_unapplied_draft': True, 'two_run_and_reload_byte_identical': True,
        'exported_sha256': {p: hashlib.sha256(value).hexdigest() for p,value in goldens.items()}}
    (fixture/'verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print('TRANSITION EDITOR BLENDER ACCEPTANCE PASSED '+json.dumps(evidence))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', required=True)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(Path(args.project_root).resolve())
