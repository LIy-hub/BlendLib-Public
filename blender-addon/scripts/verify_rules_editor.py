# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Real Blender operators create ordered idle/walk/run rules and strict Java goldens."""
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
    fixture = root/'test-assets/blender-rules'
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
    scene.blendlib_collection, scene.blendlib_namespace = collection, 'blendlib_rules'
    scene.blendlib_model_id, scene.blendlib_project_root = 'actor', '//exported'
    scene.blendlib_output_resource_root = '.'
    scene.blendlib_runtime_authoring_enabled = False
    draft = scene.blendlib_authoring_draft
    # Genuine state/event UI first, then the new rule UI. No preset rule JSON.
    for i, name in enumerate(('Idle', 'Walk', 'Run', 'Attack')):
        assert bpy.ops.blendlib.authoring_begin(mode='CREATE' if i == 0 else 'ADD') == {'FINISHED'}
        draft.key, draft.action, draft.loop = 'blendlib_rules:'+name.lower(), bpy.data.actions[name], name != 'Attack'
        if name != 'Idle':
            assert bpy.ops.blendlib.authoring_event(operation='ADD') == {'FINISHED'}
            draft.events[0].marker = 'Impact' if name == 'Attack' else 'Footstep'
            draft.events[0].event = 'blendlib_rules:'+('impact' if name == 'Attack' else 'footstep')
        assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    text = scene.blendlib_runtime_authoring_text
    config = json.loads(text.as_string())
    config['sockets'] = {'blendlib_rules:hand': {'node': 'Root/Hand'}}
    config['animation']['states']['blendlib_rules:attack'].update(next='blendlib_rules:idle', blend_seconds=.123456789123456)
    text.from_string(json.dumps(config, indent=2))
    preserved = json.loads(text.as_string())

    def begin():
        assert bpy.ops.blendlib.authoring_rules_begin() == {'FINISHED'}
        assert draft.active and draft.kind == 'RULES'

    def discard():
        assert bpy.ops.blendlib.authoring_discard() == {'FINISHED'}
        assert not draft.active and not draft.rules

    def row(target, operation):
        assert bpy.ops.blendlib.authoring_rules_row(target=target, operation=operation) == {'FINISHED'}

    def check_rejected(call, message):
        before = text.as_string()
        rejected(call, message)
        assert text.as_string() == before and draft.active

    def apply_bad(message): check_rejected(lambda: bpy.ops.blendlib.authoring_apply(), message)

    begin()
    assert draft.default_loop == '/' and not draft.rules
    apply_bad('resource ID')
    check_rejected(lambda: bpy.ops.blendlib.authoring_rules_begin(), 'current draft')
    check_rejected(lambda: bpy.ops.blendlib.authoring_begin(mode='ADD'), 'current draft')
    check_rejected(lambda: bpy.ops.blendlib.authoring_socket_begin(mode='ADD'), 'current draft')
    draft.default_loop = 'blendlib_rules:idle'
    draft.minimum_interval = 4
    # Add Walk before Run, then deliberately move Run up to higher priority.
    for name, conditions in [('walk', [('grounded', 'BOOL', True, '', ''), ('speed', 'MIN', True, '0.1', '0.05')]),
                             ('run', [('grounded', 'BOOL', True, '', ''), ('speed', 'MIN', True, '2.0', '1.5'), ('slope', 'MAX', True, '0.5', '0.75')])]:
        row('RULE', 'ADD')
        rule = draft.rules[draft.rule_index]
        assert rule.animation == '/'
        rule.animation = 'blendlib_rules:'+name
        for input_name, kind, equals, enter, exit in conditions:
            row('CONDITION', 'ADD')
            condition = rule.conditions[rule.condition_index]
            condition.input_name, condition.kind, condition.equals = input_name, kind, equals
            condition.enter, condition.exit = enter, exit
    row('RULE', 'UP')
    assert [x.animation for x in draft.rules] == ['blendlib_rules:run', 'blendlib_rules:walk']
    row('CONDITION', 'UP')
    row('CONDITION', 'DOWN')
    row('CONDITION', 'ADD')
    row('CONDITION', 'REMOVE')
    row('RULE', 'ADD')
    row('RULE', 'REMOVE')
    assert json.loads(text.as_string()) == preserved
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    assert not scene.blendlib_runtime_authoring_enabled
    config = json.loads(text.as_string())
    assert {k:v for k,v in config.items() if k != 'locomotion'} == preserved
    expected_rules = {'schema_version': 1, 'default': 'blendlib_rules:idle', 'minimum_interval_ticks': 4, 'rules': [
        {'animation': 'blendlib_rules:run', 'conditions': [
            {'input': 'grounded', 'equals': True}, {'input': 'speed', 'enter_min': 2., 'exit_min': 1.5},
            {'input': 'slope', 'enter_max': .5, 'exit_max': .75}]},
        {'animation': 'blendlib_rules:walk', 'conditions': [
            {'input': 'grounded', 'equals': True}, {'input': 'speed', 'enter_min': .1, 'exit_min': .05}]}]}
    assert config['locomotion'] == expected_rules
    original = text.as_string()
    # Real enum choices exclude non-loop/next states; no fallback or wrong-type rewrite.
    begin()
    keys = [x[0] for x in ui._LOOP_ITEMS[draft.as_pointer()]]
    assert 'blendlib_rules:attack' not in keys
    rule = draft.rules[0]
    condition = rule.conditions[1]
    for bad in ('NaN', 'true', '"1"', '1e500', '9'*5000):
        condition.enter = bad
        apply_bad('number')
    condition.enter, condition.exit = '2.0', '3.0'
    apply_bad('hysteresis')
    condition.exit = '1.5'
    rule.conditions[0].input_name = 'speed'
    apply_bad('uniquely typed')
    rule.conditions[0].input_name = 'grounded'
    rule.conditions[2].enter, rule.conditions[2].exit = '0.8', '0.75'
    apply_bad('hysteresis')
    rule.conditions[2].enter = '0.5'
    row('CONDITION', 'ADD')
    rule.conditions[-1].input_name = 'not valid'
    apply_bad('ASCII identifier')
    row('CONDITION', 'REMOVE')
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    assert json.loads(text.as_string()) == config
    # No-op preservation includes original precision and absent interval, tested in real RNA.
    precise = json.loads(original)
    del precise['locomotion']['minimum_interval_ticks']
    precise['locomotion']['rules'][0]['conditions'][1].update(enter_min=10**180, exit_min=.12345678912345678)
    text.from_string(json.dumps(precise))
    begin()
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    assert json.loads(text.as_string()) == precise
    text.from_string(original)
    begin()
    text.name = 'Renamed.rules.json'
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    original = text.as_string()
    begin()
    other = bpy.data.texts.new('Identical.rules.json')
    other.write(original)
    scene.blendlib_runtime_authoring_text = other
    apply_bad('Selected Text changed')
    assert other.as_string() == original
    discard()
    scene.blendlib_runtime_authoring_text = text
    begin()
    text.write('\n')
    apply_bad('Text contents changed')
    discard()
    text.from_string(original)
    begin()
    empty = bpy.data.collections.new('EmptyExport')
    scene.blendlib_collection = empty
    apply_bad('no exportable objects')
    scene.blendlib_collection = collection
    discard()
    # A state draft also prevents rules loading, and Discard leaves canonical data intact.
    scene.blendlib_authoring_state = 'blendlib_rules:idle'
    assert bpy.ops.blendlib.authoring_begin(mode='EDIT') == {'FINISHED'}
    check_rejected(lambda: bpy.ops.blendlib.authoring_rules_begin(), 'current draft')
    discard()
    assert json.loads(text.as_string()) == config
    # Persist applied Text only. Export with an open, invalid rule draft uses canonical Text.
    image.filepath = '//albedo.png'
    scene.blendlib_runtime_authoring_enabled = True
    source = fixture/'source.blend'
    bpy.ops.wm.save_as_mainfile(filepath=str(source), compress=True)
    (fixture/'runtime-authoring.json').write_text(text.as_string())
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    goldens = {str(p.relative_to(fixture/'exported')): p.read_bytes() for p in (fixture/'exported').rglob('*') if p.is_file()}
    begin()
    draft.default_loop = '/'
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((fixture/'exported'/p).read_bytes() == value for p, value in goldens.items())
    bpy.ops.wm.save_as_mainfile(filepath=str(source), compress=True)
    assert draft.active
    bpy.ops.wm.open_mainfile(filepath=str(source))
    scene = bpy.context.scene
    draft = scene.blendlib_authoring_draft
    assert not draft.active and not draft.rules
    assert json.loads(scene.blendlib_runtime_authoring_text.as_string()) == config
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((fixture/'exported'/p).read_bytes() == value for p, value in goldens.items())
    # Restart cleanup and repeated registration do not leave a second handler or draft.
    assert bpy.ops.blendlib.authoring_rules_begin() == {'FINISHED'}
    exporter.unregister()
    exporter.register()
    assert not scene.blendlib_authoring_draft.active
    bpy.ops.wm.save_as_mainfile(filepath=str(source), compress=True)
    exporter.unregister()
    evidence = {'blender_version': bpy.app.version_string, 'build_hash': bpy.app.build_hash.decode(),
        'real_state_event_rule_operators': True, 'explicit_priority_reorder': True,
        'typed_min_max_hysteresis_validation': True, 'lossless_precise_numbers_absent_interval': True,
        'default_has_no_implicit_fallback': True, 'text_identity_content_and_cross_editor_guards': True,
        'failed_apply_retains_recoverable_draft': True, 'unrelated_fields_preserved': True,
        'save_reload_restart_clears_draft': True, 'export_ignores_unapplied_draft': True,
        'two_run_and_reload_byte_identical': True,
        'exported_sha256': {p: hashlib.sha256(value).hexdigest() for p,value in goldens.items()}}
    (fixture/'verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print('RULE EDITOR BLENDER ACCEPTANCE PASSED '+json.dumps(evidence))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', required=True)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(Path(args.project_root).resolve())
