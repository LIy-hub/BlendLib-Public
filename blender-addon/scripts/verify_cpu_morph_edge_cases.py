# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Real Blender CPU-morph negative/source/fallback/editor regressions."""
import argparse
import dataclasses
import json
import sys
from pathlib import Path
sys.dont_write_bytecode=True
import bpy
sys.path[:0]=[str(Path(__file__).resolve().parents[1]),str(Path(__file__).resolve().parent)]
import blendlib_exporter as exporter
from create_cpu_morph_fixture import oracle

parser=argparse.ArgumentParser();parser.add_argument('--project-root',required=True,type=Path)
args=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);root=args.project_root.resolve()
fixture=root/'test-assets/cpu-morph';source=fixture/'source.blend';evidence=[]
exporter.register()


def load():
    bpy.ops.wm.open_mainfile(filepath=str(source))
    return bpy.data.objects['MorphRig'],bpy.data.objects['FaceBody'],bpy.context.scene


def options(case):
    return exporter.ExportOptions(source,root/'build/cpu-morph-edge-cases'/case,'cpu_morph','face_actor',
        'blendlib:skinned_morph_cpu_v1','BlendLibExport','resources',None,runtime_authoring_text='BlendLib.runtime.json')


def reject(case,change,fragment):
    rig,mesh,scene=load();change(rig,mesh,scene)
    try:
        exporter.export_open_blend(options(case))
        raise AssertionError('expected rejection: '+case)
    except exporter.ExportError as error:
        assert fragment.lower() in str(error).lower(),(case,error)
        evidence.append({'case':case,'rejected':True,'code':error.code,'reason':error.message})


reject('absolute_keys',lambda r,m,s:setattr(m.data.shape_keys,'use_relative',False),'relative-to-Basis')
reject('chained_keys',lambda r,m,s:setattr(m.data.shape_keys.key_blocks['Smile'],'relative_key',m.data.shape_keys.key_blocks['Blink']),'chained')
reject('masked_key',lambda r,m,s:setattr(m.data.shape_keys.key_blocks['Blink'],'vertex_group','Face'),'per-key masks')
reject('muted_target',lambda r,m,s:setattr(m.data.shape_keys.key_blocks['Blink'],'mute',True),'muted')
reject('key_driver',lambda r,m,s:m.data.shape_keys.key_blocks['Blink'].driver_add('value'),'drivers')
reject('object_driver',lambda r,m,s:r.driver_add('location',0),'drivers')
reject('active_nla',lambda r,m,s:setattr(m.data.shape_keys.animation_data.nla_tracks[0],'mute',False),'NLA')
reject('key_action_influence',lambda r,m,s:setattr(m.data.shape_keys.animation_data,'action_influence',.5),'influence')
reject('subdivision_modifier',lambda r,m,s:m.modifiers.new('Unsafe','SUBSURF'),'only one Armature')
reject('triangulate_modifier',lambda r,m,s:m.modifiers.new('Unproved','TRIANGULATE'),'only one Armature')
reject('armature_rest_display',lambda r,m,s:setattr(r.data,'pose_position','REST'),'pose position')
reject('preserve_volume',lambda r,m,s:setattr(m.modifiers[0],'use_deform_preserve_volume',True),'deformation')
reject('armature_mask',lambda r,m,s:setattr(m.modifiers[0],'vertex_group','Face'),'deformation')
reject('armature_envelopes',lambda r,m,s:setattr(m.modifiers[0],'use_bone_envelopes',True),'deformation')
reject('target_limit',lambda r,m,s:[m.shape_key_add(name='Extra'+str(i)) for i in range(6)],'1..8')
reject('key_extrapolation',lambda r,m,s:setattr(next(iter(exporter._action_fcurves(bpy.data.actions['Smile']))),'extrapolation','LINEAR'),'extrapolat')
reject('key_noise',lambda r,m,s:next(iter(exporter._action_fcurves(bpy.data.actions['Smile']))).modifiers.new('NOISE'),'modified')
reject('default_out_of_range',lambda r,m,s:(setattr(m.data.shape_keys.key_blocks['Blink'],'slider_max',2),setattr(m.data.shape_keys.key_blocks['Blink'],'value',1.2)),'default')
reject('key_out_of_range',lambda r,m,s:setattr(next(iter(exporter._action_fcurves(bpy.data.actions['Smile']))).keyframe_points[1].co,'y',1.1),'animated morph weight')

# Bezier weights deliberately bake, while approved native cubic TRS remain cubic.
rig,mesh,scene=load()
for curve in exporter._action_fcurves(bpy.data.actions['Smile']):
    for key in curve.keyframe_points:key.interpolation='BEZIER';key.handle_left_type='AUTO';key.handle_right_type='AUTO'
result=exporter.export_open_blend(options('bezier_weights'));gltf,binary=exporter.read_glb(Path(result['mesh_path']))
assert result['cpu_morph']['weight_mode']=='sampled_linear_fallback'
assert result['cpu_morph']['fallback_reasons'][0]['sample_cadence_frames']==1
assert result['native_cubic']['mode']=='native_fcurves'
assert all(s['interpolation']=='CUBICSPLINE' for a in gltf['animations'] if a['name']=='Nod' for s in a['samplers'])
sampledir=root/'build/cpu-morph-edge-cases/bezier_weights';probe=oracle(rig,mesh,gltf,binary,sampledir,24)
assert probe['maximum_weight_error']<.01 and probe['maximum_skinned_position_error']<.001,probe
evidence.append({'case':'bezier_weights','cpu_morph':result['cpu_morph'],'oracle':probe,'approximate':True})

# An ineligible TRS curve must not lose any Key Actions during whole-TRS fallback.
rig,mesh,scene=load();curve=next(iter(exporter._action_fcurves(bpy.data.actions['Nod'])))
curve.keyframe_points[0].handle_right.x=11
result=exporter.export_open_blend(options('trs_fallback'));gltf,binary=exporter.read_glb(Path(result['mesh_path']))
assert result['native_cubic']['mode']=='baked_linear_fallback'
assert [a['name'] for a in gltf['animations']]==['Blink','Breath','Nod','Smile']
assert all(c['target']['path']=='weights' for a in gltf['animations'] if a['name']!='Nod' for c in a['channels'])
probe=oracle(rig,mesh,gltf,binary,root/'build/cpu-morph-edge-cases/trs_fallback',24)
assert probe['maximum_weight_error']<1e-6 and probe['maximum_skinned_position_error']<.004,probe
evidence.append({'case':'trs_fallback_preserves_weight_only_clips','native_cubic':result['native_cubic'],'oracle':probe})

# Fractional FPS must use effective seconds for both direct weight and native TRS keys.
rig,mesh,scene=load();scene.render.fps_base=1.001
result=exporter.export_open_blend(options('fractional_fps'));gltf,binary=exporter.read_glb(Path(result['mesh_path']))
probe=oracle(rig,mesh,gltf,binary,root/'build/cpu-morph-edge-cases/fractional_fps',24/float(scene.render.fps_base))
assert probe['maximum_weight_error']<1e-6 and probe['maximum_skinned_position_error']<3e-5,probe
evidence.append({'case':'fractional_fps','oracle':probe})

# Action slots associated with a Key datablock must coexist with object slots.
rig,mesh,scene=load();keys=mesh.data.shape_keys;nod=bpy.data.actions['Nod']
keys.animation_data.action=nod
slot=nod.slots.new(id_type='KEY',name='FaceKeys');keys.animation_data.action_slot=slot
for frame,value in ((10,0),(22,.6),(46,0)):
    keys.key_blocks['Smile'].value=value;keys.key_blocks['Smile'].keyframe_insert('value',frame=frame)
for layer in nod.layers:
    for strip in layer.strips:
        for bag in strip.channelbags:
            if bag.slot_handle==slot.handle:
                for curve in bag.fcurves:
                    for key in curve.keyframe_points:key.interpolation='LINEAR'
scene.frame_set(10)
result=exporter.export_open_blend(options('combined_slots'));gltf,binary=exporter.read_glb(Path(result['mesh_path']))
nod_clip=next(a for a in gltf['animations'] if a['name']=='Nod')
assert {'rotation','weights'} <= {c['target']['path'] for c in nod_clip['channels']},nod_clip
assert result['native_cubic']['mode']=='baked_linear_fallback'
evidence.append({'case':'associated_object_and_key_slots','channel_paths':sorted({c['target']['path'] for c in nod_clip['channels']}),'native_cubic':result['native_cubic']})

# New profile works through the existing sidebar, state editor and socket discovery.
rig,mesh,scene=load();scene.blendlib_project_root=str(root/'build/cpu-morph-edge-cases/sidebar')
assert bpy.ops.blendlib.export_model()=={'FINISHED'}
scene.blendlib_authoring_state='cpu_morph:smile'
assert bpy.ops.blendlib.authoring_begin(mode='EDIT')=={'FINISHED'}
assert scene.blendlib_authoring_draft.action==bpy.data.actions['Smile']
original=json.loads(scene.blendlib_runtime_authoring_text.as_string())
assert bpy.ops.blendlib.authoring_apply()=={'FINISHED'}
assert json.loads(scene.blendlib_runtime_authoring_text.as_string())==original
import blendlib_authoring_sockets as sockets
before=[key.value for key in mesh.data.shape_keys.key_blocks]
rows,_=sockets.discover(scene,exporter)
assert any(row['path']=='MorphRoot/MorphRig/Base/Face' for row in rows)
assert before==[key.value for key in mesh.data.shape_keys.key_blocks]
evidence.append({'case':'sidebar_state_editor_and_socket_discovery','weight_only_action_edit':True,'defaults_restored':True})

# A manual-only actor needs no dummy clip or state.
rig,mesh,scene=load();rig.animation_data_clear();mesh.data.shape_keys.animation_data_clear()
config=json.loads(bpy.data.texts['BlendLib.runtime.json'].as_string());del config['animation']
bpy.data.texts['BlendLib.runtime.json'].from_string(json.dumps(config))
result=exporter.export_open_blend(options('manual_only'));gltf,_=exporter.read_glb(Path(result['mesh_path']))
assert 'animations' not in gltf
assert 'animation' not in json.loads(Path(result['descriptor_path']).read_text())
evidence.append({'case':'manual_only_without_dummy_clip','animations':0})

# Strict modes still reject the new Text field, and X5 still rejects the profile.
rig,mesh,scene=load()
try:
    exporter.export_open_blend(dataclasses.replace(options('strict'),profile='blendlib:skinned_v1'))
    raise AssertionError('strict Text parser accepted morph_controls')
except exporter.ExportError as error:assert error.code=='BLENDLIB-AUTHOR-001'
import blendlib_x5_toolchain as x5
try:
    x5._freeze_export_options(dataclasses.replace(options('x5'),runtime_authoring_text=None))
    raise AssertionError('X5 accepted the CPU morph profile')
except x5.X5ToolingError as error:assert error.code=='BLENDLIB-X5-PROFILE-001'
evidence.append({'case':'strict_authoring_rejects_morph_controls','rejected':True})
exporter.unregister()
(fixture/'edge-cases.json').write_text(json.dumps({'blender_version':bpy.app.version_string,'cases':evidence},indent=2,sort_keys=True)+'\n')
print('BLENDLIB_CPU_MORPH_EDGE_CASES_OK '+json.dumps({'cases':len(evidence)}))
