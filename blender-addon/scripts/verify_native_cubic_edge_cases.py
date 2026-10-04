# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Real Blender regressions for rejected native-curve eligibility assumptions."""
import argparse
import json
import sys
from pathlib import Path
sys.dont_write_bytecode=True
import bpy
sys.path[:0]=[str(Path(__file__).resolve().parents[1]),str(Path(__file__).resolve().parent)]
import blendlib_exporter as exporter
from create_native_cubic_fixture import oracle

parser=argparse.ArgumentParser();parser.add_argument('--project-root',type=Path,required=True)
args=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);root=args.project_root.resolve()
source=root/'test-assets/native-cubic/source.blend';results=[]
for case in ('connected_location','linear_extrapolation'):
    bpy.ops.wm.open_mainfile(filepath=str(source))
    rig=bpy.data.objects['CubicRig'];scene=bpy.context.scene
    if case=='connected_location':
        bpy.context.view_layer.objects.active=rig;rig.select_set(True)
        bpy.ops.object.mode_set(mode='EDIT');rig.data.edit_bones['Tip'].use_connect=True;bpy.ops.object.mode_set(mode='OBJECT')
        for frame,value in ((10,0),(22,.5),(46,0)):
            rig.pose.bones['Tip'].location=(value,0,0)
            rig.pose.bones['Tip'].keyframe_insert('location',frame=frame)
        for curve in exporter._action_fcurves(rig.animation_data.action):
            if curve.data_path!='pose.bones["Tip"].location':continue
            keys=curve.keyframe_points
            for i,key in enumerate(keys):
                key.interpolation='BEZIER';key.handle_left_type='FREE';key.handle_right_type='FREE'
                key.handle_left=(key.co.x-(key.co.x-keys[i-1].co.x)/3 if i else key.co.x-4,key.co.y)
                key.handle_right=(key.co.x+(keys[i+1].co.x-key.co.x)/3 if i+1<len(keys) else key.co.x+8,key.co.y)
        expected_reason='connected child bone location'
    else:
        for curve in exporter._action_fcurves(rig.animation_data.action):
            if curve.data_path!='pose.bones["Base"].location':continue
            curve.keyframe_points.remove(curve.keyframe_points[-1]);curve.extrapolation='LINEAR'
            for key in curve.keyframe_points:key.interpolation='LINEAR'
        expected_reason='nonconstant FCurve extrapolation'
    scene.frame_set(10)
    options=exporter.ExportOptions(source,root/'build/native-cubic-edge-cases'/case,'native_cubic','eased_actor',
            'blendlib:skinned_cubic_v1','BlendLibExport','resources',None,runtime_authoring_text='BlendLib.runtime.json')
    result=exporter.export_open_blend(options);report=result['native_cubic']
    assert not report['exact_native_subset'] and report['mode']=='baked_linear_fallback',report
    assert any(expected_reason in reason['reason'] for reason in report['fallback_reasons']),report
    gltf,binary=exporter.read_glb(Path(result['mesh_path']))
    assert all(s.get('interpolation','LINEAR') in ('LINEAR','STEP') for animation in gltf.get('animations',[]) for s in animation['samplers'])
    _,error=oracle(rig,[bpy.data.objects[n] for n in ('Body','Head','Arm')],gltf,binary,24)
    assert error<.02,(case,error) # Baking is explicitly approximate between scene frames.
    results.append({'case':case,'native':False,'fallback':report,'maximum_baked_oracle_error':error})
output=root/'build/native-cubic-edge-cases/evidence.json';output.write_text(json.dumps(results,indent=2)+'\n')
print('BLENDLIB_NATIVE_CUBIC_EDGE_CASES_OK '+json.dumps(results))
