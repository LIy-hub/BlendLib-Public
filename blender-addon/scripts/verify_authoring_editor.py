# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Genuine Blender 5.1.2 state/event UI operator and export roundtrip acceptance."""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path
sys.dont_write_bytecode = True
import bpy
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_exporter as exporter
import blendlib_authoring_ui as ui


def rejected(call, message):
    try:
        result = call()
    except RuntimeError as error:  # Blender forwards an operator ERROR report.
        assert message in str(error), str(error)
    else:
        assert result == {'CANCELLED'}, result


def main(root):
    assert bpy.app.version >= (5, 1, 0)
    fixture = root/'test-assets/blender-authoring'
    bpy.ops.wm.open_mainfile(filepath=str(fixture/'source.blend'))
    exporter.register()
    scene = bpy.context.scene
    text = scene.blendlib_runtime_authoring_text
    original = text.as_string()
    config = json.loads(original)
    draft = scene.blendlib_authoring_draft
    assert not draft.active
    assert {a.name for a in ui._source(scene, exporter)[0]} == {'Idle', 'Walk', 'Attack'}
    unattached = bpy.data.actions.new('FakeUserOnly')
    unattached.use_fake_user = True
    assert unattached not in ui._source(scene, exporter)[0]
    camera = bpy.data.objects.new('IgnoredCamera', bpy.data.cameras.new('IgnoredCamera'))
    scene.blendlib_collection.objects.link(camera)
    camera.animation_data_create()
    camera.animation_data.action = bpy.data.actions['FakeUserOnly']
    assert unattached not in ui._source(scene, exporter)[0]
    assert bpy.types.VIEW3D_PT_blendlib_authoring.bl_parent_id == 'VIEW3D_PT_blendlib_export'

    def begin(key='blendlib_authoring:walk', mode='EDIT'):
        if mode == 'EDIT':
            scene.blendlib_authoring_state = key
        assert bpy.ops.blendlib.authoring_begin(mode=mode) == {'FINISHED'}
        assert draft.active

    def discard():
        assert bpy.ops.blendlib.authoring_discard() == {'FINISHED'}
        assert not draft.active

    # Loading/adding/removing/reordering/discarding only modifies a draft.
    begin()
    rejected(lambda: bpy.ops.blendlib.authoring_begin(mode='ADD'), 'current draft')
    assert bpy.ops.blendlib.authoring_event(operation='ADD') == {'FINISHED'}
    draft.events[1].marker, draft.events[1].event = 'Footstep', 'blendlib_authoring:second'
    assert bpy.ops.blendlib.authoring_event(operation='UP') == {'FINISHED'}
    assert draft.events[0].event == 'blendlib_authoring:second'
    assert bpy.ops.blendlib.authoring_event(operation='DOWN') == {'FINISHED'}
    assert bpy.ops.blendlib.authoring_event(operation='REMOVE') == {'FINISHED'}
    assert text.as_string() == original
    draft.speed = '1.5'
    discard()
    assert text.as_string() == original
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'draft first')

    # Validation failures cannot rewrite Text, other states or advanced sections.
    begin()
    draft.events[0].marker = 'Missing'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'exactly once')
    assert text.as_string() == original and draft.active
    draft.events[0].marker = 'Footstep'
    draft.loop = False
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'continuous loops')
    assert text.as_string() == original
    draft.loop = True
    draft.speed = 'NaN'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'finite number')
    assert text.as_string() == original
    discard()

    # Datablock rename is safe; switched identity, even identical bytes, is stale.
    begin()
    text.name = 'Renamed.runtime.json'
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    assert json.loads(text.as_string()) == config
    original = text.as_string()
    begin()
    other = bpy.data.texts.new('Unrelated.runtime.json')
    other.write(original)
    scene.blendlib_runtime_authoring_text = other
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Selected Text changed')
    assert text.as_string() == other.as_string() == original
    discard()
    scene.blendlib_runtime_authoring_text = text
    begin()
    text.write('\n')
    modified = text.as_string()
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Text contents changed')
    assert text.as_string() == modified
    discard()
    text.from_string(original)

    # Changing the collection/Action source is revalidated at Apply.
    begin()
    empty = bpy.data.collections.new('EmptyExport')
    saved_collection = scene.blendlib_collection
    scene.blendlib_collection = empty
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'no exportable objects')
    assert text.as_string() == original
    scene.blendlib_collection = saved_collection
    discard()

    # Existing state identity is immutable, including direct operator usage.
    begin()
    draft.key = 'blendlib_authoring:idle'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Renaming')
    assert text.as_string() == original
    discard()
    begin(mode='ADD')
    draft.key = 'blendlib_authoring:walk'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'already exists')
    discard()

    # Add-state preserves the initial state unless its checkbox is explicitly set.
    begin(mode='ADD')
    draft.key, draft.action = 'blendlib_authoring:run', bpy.data.actions['Walk']
    assert not draft.make_initial
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    assert json.loads(text.as_string())['animation']['initial_state'] == config['animation']['initial_state']
    begin('blendlib_authoring:run')
    draft.make_initial = True
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    assert json.loads(text.as_string())['animation']['initial_state'] == 'blendlib_authoring:run'
    text.from_string(original)

    # Build all three state mappings and events using the real operators. New Text
    # creation is explicit, leaves the old selected Text intact and keeps opt-out.
    scene.blendlib_runtime_authoring_enabled = False
    begin(mode='CREATE')
    draft.key, draft.action = 'blendlib_authoring:idle', bpy.data.actions['Idle']
    assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    created = scene.blendlib_runtime_authoring_text
    assert created != text and text.as_string() == original
    assert not scene.blendlib_runtime_authoring_enabled
    for key, action, marker, event, loop in [
            ('walk', 'Walk', 'Footstep', 'footstep', True),
            ('attack', 'Attack', 'Impact', 'impact', False)]:
        begin(mode='ADD')
        draft.key, draft.action, draft.loop = 'blendlib_authoring:'+key, bpy.data.actions[action], loop
        assert bpy.ops.blendlib.authoring_event(operation='ADD') == {'FINISHED'}
        draft.events[0].marker, draft.events[0].event = marker, 'blendlib_authoring:'+event
        assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
    authored = json.loads(created.as_string())
    # Advanced fields retain their existing Text-only authoring contract.
    authored['sockets'], authored['locomotion'] = config['sockets'], config['locomotion']
    authored['animation']['states']['blendlib_authoring:attack'].update(next='blendlib_authoring:idle', blend_seconds=.1)
    created.from_string(json.dumps(authored, indent=2))
    assert authored == config
    for state in ('idle', 'walk', 'attack'):
        begin('blendlib_authoring:'+state)
        assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
        assert json.loads(created.as_string()) == config
    created.name = 'Editor.runtime.json'
    scene.blendlib_runtime_authoring_enabled = True
    scene.blendlib_project_root = str(fixture/'exported')
    scene.blendlib_output_resource_root = '.'
    outputs = sorted((fixture/'exported').rglob('*'))
    goldens = {str(p.relative_to(fixture/'exported')): p.read_bytes() for p in outputs if p.is_file()}
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((fixture/'exported'/p).read_bytes() == data for p, data in goldens.items())
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((fixture/'exported'/p).read_bytes() == data for p, data in goldens.items())

    # Saved Text survives reopening; unapplied drafts are discarded on file load.
    begin()
    draft.speed = '1.23456789123456'
    saved = root/'build/authoring-editor/source.blend'
    saved.parent.mkdir(parents=True, exist_ok=True)
    # Copy source PNG beneath the reopened source's authorized texture root.
    shutil.copyfile(fixture/'albedo.png', saved.parent/'albedo.png')
    for image in bpy.data.images:
        if image.source == 'FILE' and image.filepath:
            image.filepath = str(saved.parent/'albedo.png')
    bpy.ops.wm.save_as_mainfile(filepath=str(saved), compress=True)
    bpy.ops.wm.open_mainfile(filepath=str(saved))
    scene = bpy.context.scene
    assert not scene.blendlib_authoring_draft.active
    assert json.loads(scene.blendlib_runtime_authoring_text.as_string()) == config
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((fixture/'exported'/p).read_bytes() == data for p, data in goldens.items())
    exporter.unregister()
    exporter.register()
    exporter.unregister()
    evidence = {'blender_version': bpy.app.version_string, 'build_hash': bpy.app.build_hash.decode(),
        'registered_ui_and_real_operators': True, 'source_text_rename_and_identity_guard': True,
        'changed_text_and_failed_validation_preserved': True, 'draft_discard_repeat_and_reload': True,
        'explicit_initial_and_new_text_opt_out': True, 'advanced_fields_preserved': True,
        'attached_actions_and_pose_markers': True, 'editor_export_matches_java_fixture': True,
        'two_run_byte_identical': True, 'saved_text_reopens_draft_discarded': True,
        'exported_sha256': {p: hashlib.sha256(data).hexdigest() for p, data in goldens.items()}}
    (root/'build/authoring-editor/verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print('STATE EDITOR BLENDER ACCEPTANCE PASSED '+json.dumps(evidence))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', required=True)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(Path(args.project_root).resolve())
