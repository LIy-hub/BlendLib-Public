# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Real Blender 5.1+ fixture creation and two-run authoring acceptance.

blender --background --python blender-addon/scripts/verify_runtime_authoring.py -- --project-root .
"""
from __future__ import annotations
import argparse
import dataclasses
import hashlib
import json
import sys
import tempfile
from pathlib import Path
sys.dont_write_bytecode = True
import bpy
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from blendlib_exporter import ExportError, ExportOptions, export_open_blend, register, unregister
from create_canonical_fixtures import reset_scene, make_collection, make_external_png, make_material, make_triangle_mesh


def configuration():
    ns = 'blendlib_authoring:'
    return {
        'schema_version': 1,
        'animation': {'initial_state': ns+'idle', 'states': {
            ns+'idle': {'clip': 'Idle', 'loop': True, 'speed': 1},
            ns+'walk': {'clip': 'Walk', 'loop': True, 'speed': 1,
                        'events': [{'marker': 'Footstep', 'event': ns+'footstep'}]},
            ns+'attack': {'clip': 'Attack', 'loop': False, 'speed': 1,
                         'next': ns+'idle', 'blend_seconds': .1,
                         'events': [{'marker': 'Impact', 'event': ns+'impact'}]}}},
        'sockets': {ns+'hand': {'node': 'Root/Hand'}},
        'locomotion': {'schema_version': 1, 'default': ns+'idle', 'minimum_interval_ticks': 0,
            'rules': [{'animation': ns+'walk', 'conditions': [
                {'input': 'grounded', 'equals': True},
                {'input': 'speed', 'enter_min': .1, 'exit_min': .05}]}]}}


def main(root):
    assert bpy.app.version >= (5, 1, 0), 'Real Blender 5.1+ is required'
    fixture = root / 'test-assets/blender-authoring'
    fixture.mkdir(parents=True, exist_ok=True)
    reset_scene()
    collection = make_collection('BlendLibExport')
    scene = bpy.context.scene
    scene.render.fps = 24
    scene.render.fps_base = 1
    scene.frame_start, scene.frame_end = 1, 100
    origin = bpy.data.objects.new('Root', None)
    collection.objects.link(origin)
    hand = bpy.data.objects.new('Hand', None)
    collection.objects.link(hand)
    hand.parent, hand.location = origin, (1, 0, 1)
    image = make_external_png(fixture / 'albedo.png', (.2, .65, .9, 1))
    material = make_material('BodyMaterial', image)
    body = make_triangle_mesh(collection, 'Body', [(0, 0, 0), (.5, 0, 0), (0, 0, .5)], material)
    body.parent = origin
    body.animation_data_create()
    for name, distance, marker, frame in [('Idle', 0.01, None, 0), ('Walk', 1, 'Footstep', 22), ('Attack', 2, 'Impact', 16)]:
        body.animation_data.action = None
        for tick, x in [(10, 0), (34, distance)]:
            body.location = (x, 0, 0)
            body.keyframe_insert(data_path='location', frame=tick)
        action = body.animation_data.action
        action.name = name
        for layer in action.layers:
            for strip in layer.strips:
                for bag in strip.channelbags:
                    for curve in bag.fcurves:
                        for key in curve.keyframe_points:
                            key.interpolation = 'LINEAR'
        if marker:
            action.pose_markers.new(marker).frame = frame
        track = body.animation_data.nla_tracks.new()
        track.name = name
        track.strips.new(name, 10, action)
        track.mute = True
    body.animation_data.action = bpy.data.actions['Idle']
    scene.frame_set(10)
    register()
    scene.blendlib_collection = collection
    scene.blendlib_namespace = 'blendlib_authoring'
    scene.blendlib_model_id = 'actor'
    scene.blendlib_project_root = '//exported'
    scene.blendlib_output_resource_root = '.'
    text = bpy.data.texts.new('BlendLib.runtime.json')
    text.write(json.dumps(configuration(), indent=2))
    scene.blendlib_runtime_authoring_text = text
    scene.blendlib_runtime_authoring_enabled = True
    source = fixture / 'source.blend'
    image.filepath = '//albedo.png'
    bpy.ops.wm.save_as_mainfile(filepath=str(source), compress=True)
    (fixture / 'runtime-authoring.json').write_text(text.as_string()+'\n')
    options = ExportOptions(source, fixture / 'exported', 'blendlib_authoring', 'actor',
        'blendlib:rigid_v1', collection.name, '.', None, runtime_authoring_text=text.name)
    first = export_open_blend(options)
    first_bytes = {key: Path(first[key]).read_bytes() for key in ('mesh_path', 'descriptor_path', 'locomotion_path')}
    second = export_open_blend(options)
    assert first['normalized_structure'] == second['normalized_structure']
    assert all(first_bytes[key] == Path(second[key]).read_bytes() for key in first_bytes)
    descriptor = json.loads(first_bytes['descriptor_path'])
    assert descriptor['animation']['states']['blendlib_authoring:walk']['events'][0]['time_seconds'] == .5
    assert descriptor['animation']['states']['blendlib_authoring:attack']['events'][0]['time_seconds'] == .25
    assert descriptor['sockets'] == {'blendlib_authoring:hand': {'node': 'Root/Hand'}}
    # The real sidebar operator also executes the opted-in export successfully.
    assert bpy.ops.blendlib.export_model() == {'FINISHED'}
    # Invalid marker cannot overwrite a previously successful asset.
    invalid = configuration()
    invalid['animation']['states']['blendlib_authoring:walk']['events'][0]['marker'] = 'Missing'
    text.clear(); text.write(json.dumps(invalid))
    try:
        export_open_blend(options)
        raise AssertionError('invalid marker was accepted')
    except ExportError as error:
        assert error.code == 'BLENDLIB-AUTHOR-001'
    assert all(first_bytes[key] == Path(first[key]).read_bytes() for key in first_bytes)
    text.clear(); text.write(json.dumps(configuration(), indent=2))
    # Removing optional rules must not leave an earlier sidecar silently active.
    no_rules = configuration(); del no_rules['locomotion']
    text.clear(); text.write(json.dumps(no_rules))
    try:
        export_open_blend(options)
        raise AssertionError('stale sidecar was accepted')
    except ExportError as error:
        assert error.code == 'BLENDLIB-AUTHOR-001'
    assert all(first_bytes[key] == Path(first[key]).read_bytes() for key in first_bytes)
    # Use actual glTF export at fractional FPS, not just a pure conversion test.
    text.clear(); text.write(json.dumps(configuration(), indent=2))
    scene.render.fps_base = 1.001
    fractional = export_open_blend(dataclasses.replace(options, project_root=root/'build/authoring-fractional'))
    fractional_descriptor = json.loads(Path(fractional['descriptor_path']).read_text())
    fractional_event = fractional_descriptor['animation']['states']['blendlib_authoring:walk']['events'][0]['time_seconds']
    assert abs(fractional_event - .5005) < 1e-6
    scene.render.fps_base = 1
    # Sidecar directory/file symlinks cannot escape or alias another output.
    text.clear(); text.write(json.dumps(configuration(), indent=2))
    with tempfile.TemporaryDirectory(prefix='blendlib-authoring-') as temporary:
        temp = Path(temporary)
        project, outside = temp/'project', temp/'outside'
        target_dir = project/'assets/blendlib_authoring/blend_animation_rules'
        target_dir.parent.mkdir(parents=True)
        outside.mkdir()
        target = outside/'actor.json'; target.write_text('sentinel')
        target_dir.symlink_to(outside, target_is_directory=True)
        try:
            export_open_blend(dataclasses.replace(options, project_root=project))
            raise AssertionError('escaping sidecar symlink was accepted')
        except ExportError as error:
            assert error.code in {'BLENDLIB-CLI-002', 'BLENDLIB-AUTHOR-001'}
        assert target.read_text() == 'sentinel'
        assert not (project/'assets/blendlib_authoring/models3d').exists()
    # Unselected malformed Text and enabled sidebar settings never implicitly alter CLI defaults.
    text.clear(); text.write('{invalid')
    automatic = export_open_blend(dataclasses.replace(options, project_root=root/'build/authoring-default', runtime_authoring_text=None))
    auto = json.loads(Path(automatic['descriptor_path']).read_text())
    assert set(auto['animation']['states']) == {'blendlib_authoring:attack', 'blendlib_authoring:idle', 'blendlib_authoring:walk'}
    assert all(state['loop'] and state['speed'] == 1 and 'events' not in state for state in auto['animation']['states'].values())
    assert 'sockets' not in auto and 'locomotion_path' not in automatic
    text.clear(); text.write(json.dumps(configuration(), indent=2))
    # X5's historical frozen staging options predate the explicit authoring field.
    # Exercise that real strict-export seam without weakening its POSIX atomic gate.
    import blendlib_x5_toolchain as x5
    try:
        x5._freeze_export_options(options)
        raise AssertionError('X5 silently ignored explicit runtime authoring')
    except x5.X5ToolingError as error:
        assert error.code == 'BLENDLIB-AUTHOR-001'
    frozen = x5._freeze_export_options(dataclasses.replace(options,
        project_root=root/'build/authoring-x5-frozen', output_resource_root='src/main/resources', runtime_authoring_text=None))
    frozen = dataclasses.replace(frozen, texture_source_roots=(source.parent,))
    legacy_result = export_open_blend(frozen)
    legacy_descriptor = json.loads(Path(legacy_result['descriptor_path']).read_text())
    assert all(state['loop'] for state in legacy_descriptor['animation']['states'].values())
    assert 'runtime_authoring' not in legacy_result
    unregister()
    register()
    unregister()
    evidence = {'blender_version': bpy.app.version_string, 'build_hash': bpy.app.build_hash.decode(),
        'two_run_byte_identical': True, 'sidebar_operator': True, 'invalid_marker_preserves_outputs': True,
        'default_export_unchanged': True, 'stale_sidecar_rejected': True, 'sidecar_symlink_preserves_target': True, 'real_fractional_fps': True, 'legacy_x5_frozen_options': True,
        'sha256': {key: hashlib.sha256(value).hexdigest() for key, value in first_bytes.items()}}
    (fixture/'verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print('RUNTIME AUTHORING BLENDER ACCEPTANCE PASSED '+json.dumps(evidence))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-root', required=True)
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    main(Path(args.project_root).resolve())
