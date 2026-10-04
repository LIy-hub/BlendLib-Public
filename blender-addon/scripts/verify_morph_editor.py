# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Real Blender 5.1.2 morph draft acceptance and editable face/squeeze fixture.

blender --background --python-exit-code 1 --python blender-addon/scripts/verify_morph_editor.py -- --project-root .

Generates test-assets/blender-morph-authoring/source.blend and complete exports.
No Blender mocks, version bypasses, synthetic GLB, or runtime-renderer claims.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import sys
from unittest.mock import patch

sys.dont_write_bytecode = True
import bpy
sys.path[:0] = [str(Path(__file__).resolve().parents[1]), str(Path(__file__).resolve().parent)]
import blendlib_exporter as exporter
import blendlib_authoring_ui as ui
import blendlib_authoring_sockets as discovery
from create_canonical_fixtures import reset_scene, make_collection, make_external_png, make_material
from create_cpu_morph_fixture import actor_mesh

PROFILE = 'blendlib:skinned_morph_cpu_v1'
MESH_PATH = 'MorphRoot/MorphRig/FaceBody'


def rejected(call, message=None):
    try:
        result = call()
    except RuntimeError as error:
        if message:
            assert message in str(error), str(error)
    else:
        assert result == {'CANCELLED'}, result


def create_scene(fixture):
    reset_scene()
    fixture.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    scene.render.fps, scene.render.fps_base = 24, 1
    collection = make_collection('BlendLibExport')
    origin = bpy.data.objects.new('MorphRoot', None)
    collection.objects.link(origin)
    rig = bpy.data.objects.new('MorphRig', bpy.data.armatures.new('MorphRigData'))
    collection.objects.link(rig)
    rig.parent = origin
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode='EDIT')
    base = rig.data.edit_bones.new('Base')
    base.head, base.tail = (0, 0, 0), (0, 0, 1.05)
    face = rig.data.edit_bones.new('Face')
    face.parent, face.head, face.tail = base, (0, 0, 1.12), (0, 0, 1.8)
    bpy.ops.object.mode_set(mode='OBJECT')
    surface = make_external_png(fixture/'surface.png', (.12, .67, .72, 1))
    detail = make_external_png(fixture/'detail.png', (.025, .05, .10, 1))
    mesh = actor_mesh(collection, rig, [make_material('MorphSurface', surface),
                                     make_material('FaceDetails', detail)])
    squeeze = mesh.shape_key_add(name='Squeeze')
    squeeze.slider_min, squeeze.slider_max = -1, 1
    for point in squeeze.data:
        point.co.x *= .7
        point.co.z *= 1.08
    exporter.register()
    scene.blendlib_collection = collection
    scene.blendlib_namespace, scene.blendlib_model_id = 'morph_editor', 'face_squeeze'
    scene.blendlib_profile = PROFILE
    scene.blendlib_project_root, scene.blendlib_output_resource_root = '//exported', '.'
    scene.blendlib_runtime_authoring_enabled = False
    for image, name in ((surface, 'surface.png'), (detail, 'detail.png')):
        image.filepath = '//'+name
    # A saved source gives real discovery/export a stable, portable texture root.
    bpy.ops.wm.save_as_mainfile(filepath=str(fixture/'source.blend'), compress=True)
    bpy.context.view_layer.objects.active = mesh
    rig.select_set(False)
    mesh.select_set(True)
    return scene, mesh, rig


def main(root):
    assert bpy.app.version == (5, 1, 2), 'Use the verified official Blender 5.1.2 toolchain'
    fixture = root/'test-assets/blender-morph-authoring'
    scene, mesh, rig = create_scene(fixture)
    draft = scene.blendlib_authoring_draft
    defaults = {key.name: key.value for key in mesh.data.shape_keys.key_blocks}
    assert abs(defaults['Breath']-.15) < 1e-7
    assert not bpy.data.actions and not ui._source(scene, exporter)[0]

    def unchanged_defaults():
        assert {key.name: key.value for key in mesh.data.shape_keys.key_blocks} == defaults

    def begin(mode='EDIT', key='morph_editor:squeeze'):
        if mode == 'EDIT':
            scene.blendlib_authoring_morph = key
        assert bpy.ops.blendlib.authoring_morph_begin(mode=mode) == {'FINISHED'}
        assert draft.active and draft.kind == 'MORPH' and draft.mode == mode
        unchanged_defaults()

    def select(target='Squeeze', low='-1', high='1', key=None):
        draft.morph_target = json.dumps([MESH_PATH, target], ensure_ascii=False)
        draft.min_weight, draft.max_weight = low, high
        if key:
            draft.key = key

    def discard():
        assert bpy.ops.blendlib.authoring_discard() == {'FINISHED'}
        assert not draft.active
        unchanged_defaults()

    def apply():
        assert bpy.ops.blendlib.authoring_apply() == {'FINISHED'}
        assert not draft.active
        unchanged_defaults()

    # The real GLB discoverer supplies exact node/target pairs, preserves defaults,
    # and does not create authoring Text or a synthetic animation Action.
    rows, _ = discovery.discover(scene, exporter, morph_targets=True)
    assert {(row['node'], row['target']) for row in rows} == {
        (MESH_PATH, name) for name in ('Blink', 'Smile', 'Breath', 'Squeeze')}
    assert not scene.blendlib_runtime_authoring_text and not bpy.data.actions
    unchanged_defaults()

    begin(mode='CREATE')
    select(key='morph_editor:squeeze')
    rejected(lambda: bpy.ops.blendlib.authoring_morph_begin(mode='CREATE'), 'current draft')
    discard()
    assert not scene.blendlib_runtime_authoring_text
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'draft first')

    # Explicit CREATE replaces the selection with a new Text, leaving even invalid
    # old Text intact and preserving the runtime-authoring opt-in checkbox.
    old = bpy.data.texts.new('Unrelated.txt')
    old.write('{not authoring JSON')
    scene.blendlib_runtime_authoring_text = old
    begin(mode='CREATE')
    select(key='morph_editor:squeeze')
    apply()
    text = scene.blendlib_runtime_authoring_text
    assert text != old and old.as_string() == '{not authoring JSON'
    assert not scene.blendlib_runtime_authoring_enabled
    assert set(json.loads(text.as_string())) == {'schema_version', 'morph_controls'}
    assert not bpy.data.actions
    for target, low, high in (('Blink', '0', '1'), ('Smile', '-1', '1'), ('Breath', '-0.5', '1')):
        begin(mode='ADD')
        select(target, low, high, 'morph_editor:'+target.lower())
        apply()
    original = text.as_string()
    config = json.loads(original)

    # Inject a failure at the real exporter boundary after a shape-key mutation.
    # Blender, its scene and the discovery restoration path remain genuine.
    def interrupted_export(*args, **kwargs):
        mesh.data.shape_keys.key_blocks['Breath'].value = .66
        raise RuntimeError('injected glTF interruption')

    scene.blendlib_authoring_morph = 'morph_editor:squeeze'
    with patch.object(exporter, '_export_raw_glb', side_effect=interrupted_export):
        rejected(lambda: bpy.ops.blendlib.authoring_morph_begin(mode='EDIT'), 'injected glTF interruption')
    assert not draft.active and text.as_string() == original
    unchanged_defaults()

    # Invalid ranges/defaults, duplicate aliases/bindings and immutable edit keys
    # fail without changing canonical Text. The draft remains available to fix.
    begin()
    draft.max_weight = 'NaN'
    rejected(lambda: bpy.ops.blendlib.authoring_apply())
    assert draft.active and text.as_string() == original
    draft.max_weight = '2.01'
    rejected(lambda: bpy.ops.blendlib.authoring_apply())
    assert text.as_string() == original
    discard()
    begin(key='morph_editor:breath')
    draft.max_weight = '0.1'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'default')
    assert text.as_string() == original
    discard()
    begin(mode='ADD')
    select(key='morph_editor:squeeze')
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'already exists')
    draft.key = 'morph_editor:duplicate'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'one morph control')
    assert text.as_string() == original
    discard()
    begin()
    draft.key = 'morph_editor:renamed'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'Renaming')
    assert text.as_string() == original
    discard()

    begin()
    draft.min_weight = '-2'
    discard()
    assert text.as_string() == original
    begin()
    text.name = 'FaceSqueeze.runtime.json'
    apply()
    assert json.loads(text.as_string()) == config
    original = text.as_string()
    begin()
    other = bpy.data.texts.new('IdenticalButDifferent.runtime.json')
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

    # A different collection or Mesh datablock with identical names and content
    # cannot reuse a stale discovery snapshot. Neither can a renamed target,
    # changed shape-key default, or profile switch.
    begin()
    collection = scene.blendlib_collection
    other_collection = bpy.data.collections.new('AnotherExport')
    scene.collection.children.link(other_collection)
    for obj in collection.objects:
        other_collection.objects.link(obj)
    scene.blendlib_collection = other_collection
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'changed')
    assert text.as_string() == original
    scene.blendlib_collection = collection
    discard()
    bpy.data.collections.remove(other_collection)

    begin()
    data = mesh.data
    replacement = data.copy()
    mesh.data = replacement
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'changed')
    assert text.as_string() == original
    mesh.data = data
    discard()
    bpy.data.meshes.remove(replacement)

    begin()
    mesh.data.shape_keys.key_blocks['Squeeze'].name = 'Squash'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'changed')
    assert text.as_string() == original
    mesh.data.shape_keys.key_blocks['Squash'].name = 'Squeeze'
    discard()

    begin()
    mesh.data.shape_keys.key_blocks['Breath'].value = .25
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'changed')
    assert text.as_string() == original
    mesh.data.shape_keys.key_blocks['Breath'].value = defaults['Breath']
    discard()

    begin()
    scene.blendlib_profile = 'blendlib:skinned_v1'
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'CPU morph profile')
    assert text.as_string() == original
    scene.blendlib_profile = PROFILE
    discard()
    scene.blendlib_profile = 'blendlib:skinned_v1'
    rejected(lambda: bpy.ops.blendlib.authoring_morph_begin(mode='CREATE'), 'CPU morph profile')
    assert not draft.active
    scene.blendlib_profile = PROFILE

    # Geometry editing and undo invalidate, even when names later return to the
    # same values. The shared handler is additionally checked with an active draft.
    begin()
    bpy.context.view_layer.objects.active = mesh
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.context.view_layer.update()
    bpy.ops.object.mode_set(mode='OBJECT')
    assert draft.source_invalidated
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'changed source identity')
    assert text.as_string() == original
    discard()
    begin()
    ui._UNDO_HANDLER(None)
    rejected(lambda: bpy.ops.blendlib.authoring_apply(), 'changed source identity')
    assert text.as_string() == original
    discard()

    # Export and repeat exports use saved Text only; the unapplied interval is
    # ignored. Save/reopen clears the transient draft and preserves actual defaults.
    scene.blendlib_runtime_authoring_enabled = True
    text.name = 'BlendLib.runtime.json'
    bpy.ops.wm.save_as_mainfile(filepath=str(fixture/'source.blend'), compress=True)
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    exported = fixture/'exported'
    golden = {str(p.relative_to(exported)): p.read_bytes() for p in exported.rglob('*') if p.is_file()}
    descriptor = json.loads((exported/'assets/morph_editor/blend_models/face_squeeze.json').read_text())
    assert descriptor['morph_controls'] == config['morph_controls']
    assert 'animation' not in descriptor and 'locomotion' not in descriptor
    gltf, _ = exporter.read_glb(exported/'assets/morph_editor/models3d/face_squeeze.glb')
    assert not gltf.get('animations')
    gltf_mesh = gltf['meshes'][0]
    assert gltf_mesh['extras']['targetNames'] == ['Blink', 'Smile', 'Breath', 'Squeeze']
    assert abs(gltf_mesh['weights'][2]-.15) < 1e-7
    begin()
    draft.max_weight = '0.12345678912345678'
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((exported/p).read_bytes() == data for p, data in golden.items())
    saved = root/'build/morph-editor-reopen/source.blend'
    saved.parent.mkdir(parents=True, exist_ok=True)
    for name in ('surface.png', 'detail.png'):
        shutil.copyfile(fixture/name, saved.parent/name)
    for image in bpy.data.images:
        if image.source == 'FILE' and image.filepath:
            image.filepath = str(saved.parent/Path(image.filepath).name)
    scene.blendlib_project_root = str(exported)
    bpy.ops.wm.save_as_mainfile(filepath=str(saved), compress=True)
    assert draft.active, 'Saving must not silently discard the current working draft'
    bpy.ops.wm.open_mainfile(filepath=str(saved))
    scene = bpy.context.scene
    draft = scene.blendlib_authoring_draft
    mesh = bpy.data.objects['FaceBody']
    assert not draft.active
    assert json.loads(scene.blendlib_runtime_authoring_text.as_string()) == config
    unchanged_defaults()
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    assert all((exported/p).read_bytes() == data for p, data in golden.items())
    (fixture/'runtime-authoring.json').write_text(json.dumps(config, indent=2)+'\n')

    # Also edit the animated CPU fixture: marker events, exact sockets, states and
    # advanced locomotion values survive an actual operator roundtrip unchanged.
    bpy.ops.wm.open_mainfile(filepath=str(root/'test-assets/cpu-morph/source.blend'))
    scene = bpy.context.scene
    draft = scene.blendlib_authoring_draft
    mesh = bpy.data.objects['FaceBody']
    defaults = {key.name: key.value for key in mesh.data.shape_keys.key_blocks}
    text = scene.blendlib_runtime_authoring_text
    animated = json.loads(text.as_string())
    animated['locomotion'] = {'schema_version': 1, 'default': 'cpu_morph:nod',
        'minimum_interval_ticks': 3, 'rules': [{'animation': 'cpu_morph:breath',
        'conditions': [{'input': 'speed', 'enter_min': .12345678912345678, 'exit_min': .01}]}]}
    text.from_string(json.dumps(animated, indent=2))
    begin(key='cpu_morph:smile')
    apply()
    assert json.loads(text.as_string()) == animated
    begin(key='cpu_morph:smile')
    draft.min_weight = '-0.75'
    apply()
    expected = copy.deepcopy(animated)
    expected['morph_controls']['cpu_morph:smile']['min_weight'] = -.75
    assert json.loads(text.as_string()) == expected
    exporter.unregister()
    exporter.register()
    exporter.unregister()

    evidence = {
        'blender_version': bpy.app.version_string,
        'build_hash': bpy.app.build_hash.decode(),
        'real_registered_operators_and_glb_discovery': True,
        'manual_only_create_without_actions_or_animation': True,
        'explicit_create_add_edit_apply_and_discard': True,
        'invalid_ranges_and_defaults_do_not_write_text': True,
        'duplicate_alias_and_target_rejected': True,
        'text_rename_allowed_identity_and_content_conflicts_rejected': True,
        'collection_mesh_target_default_and_profile_fences': True,
        'edit_mode_and_undo_handler_fences': True,
        'nonzero_shape_key_default_preserved': True,
        'failed_discovery_restores_shape_values_and_preserves_text': True,
        'states_events_sockets_and_locomotion_preserved': True,
        'export_ignores_unapplied_draft': True,
        'saved_text_reopens_transient_draft_cleared': True,
        'repeat_and_reopened_export_byte_identical': True,
        'interactive_visual_verification': False,
        'exported_sha256': {p: hashlib.sha256(data).hexdigest() for p, data in sorted(golden.items())},
    }
    (fixture/'verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print('MORPH EDITOR BLENDER ACCEPTANCE PASSED '+json.dumps(evidence))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', required=True, type=Path)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(args.project_root.resolve())
