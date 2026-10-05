# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Real Blender batch-draft acceptance; uses disposable copies, no fixture mutation.

blender --background --python-exit-code 1 --python blender-addon/scripts/verify_morph_batch.py -- --project-root .
"""
import argparse
import copy
import json
from pathlib import Path
import sys
from unittest.mock import patch
sys.dont_write_bytecode = True
import bpy
sys.path[:0] = [str(Path(__file__).resolve().parents[1]), str(Path(__file__).resolve().parent)]
import blendlib_exporter as exporter
import blendlib_authoring_ui as ui
from verify_morph_editor import rejected


def main(root):
    assert bpy.app.version == (5, 1, 2)
    exporter.register()
    bpy.ops.wm.open_mainfile(filepath=str(root/'test-assets/blender-morph-authoring/source.blend'))
    scene = bpy.context.scene
    draft = scene.blendlib_authoring_draft
    text = scene.blendlib_runtime_authoring_text
    original = text.as_string()
    config = json.loads(original)
    mesh = bpy.data.objects['FaceBody']
    defaults = {k.name:k.value for k in mesh.data.shape_keys.key_blocks}
    unrelated = bpy.data.texts.new('Unrelated batch test')
    unrelated.write('{not JSON')

    def intact():
        assert defaults == {k.name:k.value for k in mesh.data.shape_keys.key_blocks}
        assert unrelated.as_string() == '{not JSON'

    def begin(mode='ADD'):
        assert bpy.ops.blendlib.authoring_morph_batch_begin(mode=mode) == {'FINISHED'}
        assert draft.active and draft.kind == 'MORPH_BATCH'
        intact()

    def discard():
        assert bpy.ops.blendlib.authoring_discard() == {'FINISHED'}
        assert not draft.active and not draft.batch_controls
        intact()

    def apply():
        assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
        assert not draft.active and not draft.batch_controls
        intact()

    # Existing complete Text is a true no-op, including its formatting.
    assert bpy.ops.blendlib.authoring_morph_batch_begin(mode='ADD') == {'FINISHED'}
    assert not draft.active and text.as_string() == original

    # Missing subset, editable aliases/ranges, all-or-nothing duplicate checks.
    partial = copy.deepcopy(config)
    del partial['morph_controls']['morph_editor:smile']
    del partial['morph_controls']['morph_editor:breath']
    text.from_string(json.dumps(partial))
    partial_bytes = text.as_string()
    begin()
    assert len(draft.batch_controls) == 2
    planned = [(r.key,r.node,r.target,r.min_weight,r.max_weight) for r in draft.batch_controls]
    rejected(lambda:bpy.ops.blendlib.authoring_morph_batch_begin(mode='ADD'), 'current draft')
    rejected(lambda:bpy.ops.blendlib.authoring_morph_begin(mode='ADD'), 'current draft')
    assert text.as_string() == partial_bytes
    discard()
    assert text.as_string() == partial_bytes
    begin()
    assert planned == [(r.key,r.node,r.target,r.min_weight,r.max_weight) for r in draft.batch_controls]
    draft.batch_controls[1].key = draft.batch_controls[0].key
    rejected(lambda:bpy.ops.blendlib.authoring_apply(), 'already exists')
    assert text.as_string() == partial_bytes and draft.active
    draft.batch_controls[1].key = 'custom:smile'
    draft.batch_controls[0].key = 'custom:breath'
    draft.batch_controls[0].max_weight = '0.1'
    rejected(lambda:bpy.ops.blendlib.authoring_apply(), 'default')
    assert text.as_string() == partial_bytes
    draft.batch_controls[0].max_weight = '1'
    apply()
    result = json.loads(text.as_string())
    for key,control in partial['morph_controls'].items():
        assert result['morph_controls'][key] == control
    assert len(result['morph_controls']) == 4

    # CREATE is explicit and leaves the selected old Text intact.
    selected = text.as_string()
    scene.blendlib_runtime_authoring_text = unrelated
    enabled = scene.blendlib_runtime_authoring_enabled
    begin('CREATE')
    assert len(draft.batch_controls) == 4
    discard()
    assert scene.blendlib_runtime_authoring_text == unrelated
    begin('CREATE')
    apply()
    created = scene.blendlib_runtime_authoring_text
    assert created not in (text, unrelated)
    assert scene.blendlib_runtime_authoring_enabled == enabled
    assert text.as_string() == selected and not bpy.data.actions
    assert len(json.loads(created.as_string())['morph_controls']) == 4
    scene.blendlib_runtime_authoring_text = text

    # Two real meshes with repeated target names, Unicode and non-unit defaults.
    duplicate = mesh.copy()
    duplicate.data = mesh.data.copy()
    duplicate.name = 'OtherFace'
    scene.blendlib_collection.objects.link(duplicate)
    duplicate.data.shape_keys.key_blocks['Smile'].name = '笑顔'
    duplicate.data.shape_keys.key_blocks['Squeeze'].slider_min = -2
    duplicate.data.shape_keys.key_blocks['Squeeze'].value = -1.5
    duplicate.data.shape_keys.key_blocks['Breath'].slider_max = 2
    duplicate.data.shape_keys.key_blocks['Breath'].value = 1.5
    duplicate_defaults = {k.name:k.value for k in duplicate.data.shape_keys.key_blocks}
    begin()
    assert len(draft.batch_controls) == 4
    assert all(row.node.endswith('/OtherFace') for row in draft.batch_controls)
    assert next(row for row in draft.batch_controls if row.target == 'Squeeze').min_weight == '-1.5'
    assert next(row for row in draft.batch_controls if row.target == 'Breath').max_weight == '1.5'
    apply()
    assert len(json.loads(text.as_string())['morph_controls']) == 8
    assert duplicate_defaults == {k.name:k.value for k in duplicate.data.shape_keys.key_blocks}
    bpy.data.objects.remove(duplicate, do_unlink=True)
    text.from_string(selected)

    # Identity, content, undo, source changes and profile changes all fence Apply.
    for conflict in ('selection','content','default','undo','profile','edit_mode'):
        text.from_string(partial_bytes)
        begin()
        if conflict == 'selection': scene.blendlib_runtime_authoring_text = created
        if conflict == 'content': text.write('\n')
        if conflict == 'default': mesh.data.shape_keys.key_blocks['Breath'].value = .25
        if conflict == 'undo': ui._UNDO_HANDLER(None)
        if conflict == 'profile': scene.blendlib_profile = 'blendlib:skinned_v1'
        if conflict == 'edit_mode':
            bpy.context.view_layer.objects.active = mesh
            bpy.ops.object.mode_set(mode='EDIT')
            bpy.context.view_layer.update()
            bpy.ops.object.mode_set(mode='OBJECT')
            assert draft.source_invalidated
        before = text.as_string()
        rejected(lambda:bpy.ops.blendlib.authoring_apply())
        assert text.as_string() == before and draft.active
        scene.blendlib_runtime_authoring_text = text
        scene.blendlib_profile = 'blendlib:skinned_morph_cpu_v1'
        mesh.data.shape_keys.key_blocks['Breath'].value = defaults['Breath']
        discard()

    # Existing invalid binding/range needs explicit repair rather than replacement.
    bad = copy.deepcopy(partial)
    bad['morph_controls']['morph_editor:squeeze']['target'] = 'Gone'
    text.from_string(json.dumps(bad))
    rejected(lambda:bpy.ops.blendlib.authoring_morph_batch_begin(mode='ADD'), 'morph_editor:squeeze')
    assert not draft.active and json.loads(text.as_string()) == bad

    # Discovery failure leaves canonical Text and defaults untouched.
    text.from_string(partial_bytes)
    def interrupted(*args, **kwargs):
        mesh.data.shape_keys.key_blocks['Breath'].value = .66
        raise RuntimeError('injected batch discovery interruption')
    with patch.object(exporter, '_export_raw_glb', side_effect=interrupted):
        rejected(lambda:bpy.ops.blendlib.authoring_morph_batch_begin(mode='ADD'), 'interruption')
    assert not draft.active and text.as_string() == partial_bytes
    intact()

    # Real export still reads only canonical Text; complete batch reopens safely.
    text.from_string(original)
    output = root/'build/morph-batch-acceptance'
    output.mkdir(parents=True, exist_ok=True)
    scene.blendlib_project_root = str(output/'exported')
    scene.blendlib_output_resource_root = '.'
    scene.blendlib_runtime_authoring_enabled = True
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    golden = {p.relative_to(output/'exported'):p.read_bytes() for p in (output/'exported').rglob('*') if p.is_file()}
    begin('CREATE')
    draft.batch_controls[0].max_weight = '0.0'
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((output/'exported'/p).read_bytes() == value for p,value in golden.items())
    saved = output/'batch-reopen.blend'
    # Source texture paths must remain valid after saving elsewhere.
    for image in bpy.data.images:
        if image.source == 'FILE' and image.filepath:
            image.filepath = bpy.path.abspath(image.filepath)
    bpy.ops.wm.save_as_mainfile(filepath=str(saved), compress=True)
    assert draft.active
    bpy.ops.wm.open_mainfile(filepath=str(saved))
    scene = bpy.context.scene
    draft = scene.blendlib_authoring_draft
    assert not draft.active and not draft.batch_controls
    assert scene.blendlib_runtime_authoring_text.as_string() == original

    # Animated Text retains every state/event/socket/locomotion value.
    bpy.ops.wm.open_mainfile(filepath=str(root/'test-assets/cpu-morph/source.blend'))
    scene = bpy.context.scene
    draft = scene.blendlib_authoring_draft
    text = scene.blendlib_runtime_authoring_text
    mesh = bpy.data.objects['FaceBody']
    defaults = {k.name:k.value for k in mesh.data.shape_keys.key_blocks}
    animated = json.loads(text.as_string())
    animated['locomotion'] = {'schema_version':1, 'default':'cpu_morph:nod', 'minimum_interval_ticks':3,
        'rules':[{'animation':'cpu_morph:breath','conditions':[{'input':'speed','enter_min':.12345678912345678,'exit_min':.01}]}]}
    del animated['morph_controls']['cpu_morph:smile']
    text.from_string(json.dumps(animated))
    unrelated = bpy.data.texts.new('Animated unrelated')
    unrelated.write('{not JSON')
    begin()
    assert len(draft.batch_controls) == 1
    draft.batch_controls[0].key = 'cpu_morph:smile'
    apply()
    result = json.loads(text.as_string())
    del result['morph_controls']['cpu_morph:smile']
    assert result == animated
    exporter.unregister()
    exporter.register()
    exporter.unregister()
    evidence = {'blender_version':bpy.app.version_string,'build_hash':bpy.app.build_hash.decode(),
        'real_glb_discovery_and_registered_operators':True,'multiple_meshes_unicode_and_expanded_default_ranges':True,'atomic_editable_missing_only_batch':True,
        'deterministic_redraft_and_complete_noop':True,'existing_text_controls_and_defaults_preserved':True,
        'create_apply_discard_and_repeated_begin':True,'conflict_failure_does_not_write_text':True,
        'selection_content_default_profile_edit_and_undo_fences':True,'failed_discovery_restores_defaults':True,
        'unapplied_batch_ignored_by_export':True,'reopen_clears_transient_batch':True,
        'animated_states_events_sockets_locomotion_preserved':True,'interactive_visual_verification':False}
    (output/'verification.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print('MORPH BATCH BLENDER ACCEPTANCE PASSED '+json.dumps(evidence))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', type=Path, required=True)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(args.project_root.resolve())
