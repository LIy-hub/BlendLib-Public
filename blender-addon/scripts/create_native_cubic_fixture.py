# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate and verify the authored format-2 skinned cubic sample in real Blender.

blender --background --python blender-addon/scripts/create_native_cubic_fixture.py -- --project-root .
Includes a non-key depsgraph skinning oracle, fractional-FPS and fallback checks.
"""
from __future__ import annotations
import argparse
import dataclasses
import json
import math
import sys
from pathlib import Path
sys.dont_write_bytecode = True
import bpy
from mathutils import Matrix, Quaternion, Vector
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import blendlib_exporter as exporter
from create_canonical_fixtures import reset_scene, make_collection, make_external_png, make_material


def box(collection, armature, name, center, size, material, bone):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    obj = bpy.context.object
    obj.name = name
    for owner in list(obj.users_collection): owner.objects.unlink(obj)
    collection.objects.link(obj)
    obj.scale = size
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    obj.parent = armature
    obj.data.materials.append(material)
    group = obj.vertex_groups.new(name=bone)
    group.add(list(range(len(obj.data.vertices))), 1, 'REPLACE')
    modifier = obj.modifiers.new('Skin', 'ARMATURE'); modifier.object = armature
    return obj


def create(root):
    reset_scene()
    fixture = root/'test-assets/native-cubic'; fixture.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    scene.render.fps, scene.render.fps_base = 24, 1
    scene.frame_start, scene.frame_end = 10, 46
    collection = make_collection('BlendLibExport')
    origin = bpy.data.objects.new('CubicRoot', None); collection.objects.link(origin)
    rig = bpy.data.objects.new('CubicRig', bpy.data.armatures.new('CubicRigData'))
    collection.objects.link(rig); rig.parent = origin
    bpy.context.view_layer.objects.active = rig; rig.select_set(True)
    bpy.ops.object.mode_set(mode='EDIT')
    base = rig.data.edit_bones.new('Base'); base.head = (0,0,0); base.tail = (0,0,.8)
    tip = rig.data.edit_bones.new('Tip'); tip.parent = base; tip.head=(0,0,.8); tip.tail=(.18,.12,1.8); tip.roll=.35
    bpy.ops.object.mode_set(mode='OBJECT')
    image = make_external_png(fixture/'albedo.png', (.12,.55,.95,1))
    material = make_material('CubicSurface', image)
    meshes = [box(collection,rig,'Body',(0,0,.55),(.6,.38,.95),material,'Base'),
              box(collection,rig,'Head',(.08,.05,1.43),(.75,.48,.6),material,'Tip'),
              box(collection,rig,'Arm',(.55,0,1.06),(.64,.22,.22),material,'Tip')]
    # A nontrivial rest-bone orientation and uneven 12/24-frame segments prevent
    # axis/rest or tangent-unit mistakes from hiding behind an identity fixture.
    for bone in rig.pose.bones: bone.rotation_mode='QUATERNION'
    keys=[(10,(0,0,0),1,0),(22,(.4,.12,.2),1.08,.85),(46,(0,0,0),1,0)]
    for frame, location, scale, angle in keys:
        rig.pose.bones['Base'].location=location
        rig.pose.bones['Base'].scale=(scale,)*3
        rig.pose.bones['Tip'].rotation_quaternion=Quaternion((0,0,1),angle)
        rig.pose.bones['Base'].keyframe_insert('location',frame=frame)
        rig.pose.bones['Base'].keyframe_insert('scale',frame=frame)
        rig.pose.bones['Tip'].keyframe_insert('rotation_quaternion',frame=frame)
    action = rig.animation_data.action; action.name='EaseWave'
    action.pose_markers.new('Apex').frame=22
    for curve in exporter._action_fcurves(action):
        points = curve.keyframe_points
        for i,key in enumerate(points):
            key.interpolation='BEZIER'; key.handle_left_type='FREE'; key.handle_right_type='FREE'
            prior = points[i-1].co.x if i else key.co.x-12
            following = points[i+1].co.x if i+1<len(points) else key.co.x+24
            left_value = key.co.y - .20*(key.co.y-points[i-1].co.y) if i else key.co.y
            right_value = key.co.y + .08*(points[i+1].co.y-key.co.y) if i+1<len(points) else key.co.y
            key.handle_left=(key.co.x-(key.co.x-prior)/3,left_value)
            key.handle_right=(key.co.x+(following-key.co.x)/3,right_value)
    config={'schema_version':1,
        'animation':{'initial_state':'native_cubic:wave','states':{
            'native_cubic:wave':{'clip':'EaseWave','loop':True,'speed':1,
                               'events':[{'marker':'Apex','event':'native_cubic:apex'}]},
            'native_cubic:once':{'clip':'EaseWave','loop':False,'speed':1,'next':'native_cubic:wave','blend_seconds':.1}}},
        'sockets':{'native_cubic:tip':{'node':'CubicRoot/CubicRig/Base/Tip'}}}
    text=bpy.data.texts.new('BlendLib.runtime.json'); text.write(json.dumps(config,indent=2))
    exporter.register()
    scene.blendlib_collection=collection
    scene.blendlib_namespace='native_cubic';scene.blendlib_model_id='eased_actor'
    scene.blendlib_profile='blendlib:skinned_cubic_v1'
    scene.blendlib_project_root='//exported';scene.blendlib_output_resource_root='.'
    scene.blendlib_runtime_authoring_enabled=True;scene.blendlib_runtime_authoring_text=text
    scene.frame_set(10)
    source=fixture/'source.blend'; image.filepath='//albedo.png'
    bpy.ops.wm.save_as_mainfile(filepath=str(source),compress=True)
    options=exporter.ExportOptions(source,fixture/'exported','native_cubic','eased_actor',
        'blendlib:skinned_cubic_v1',collection.name,'.',None,runtime_authoring_text=text.name)
    first=exporter.export_open_blend(options)
    expected_bytes={key:Path(first[key]).read_bytes() for key in ('mesh_path','descriptor_path')}
    second=exporter.export_open_blend(options)
    assert all(expected_bytes[key]==Path(second[key]).read_bytes() for key in expected_bytes)
    assert first['native_cubic']['mode']=='native_fcurves',first['native_cubic']
    gltf,binary=exporter.read_glb(Path(first['mesh_path']))
    assert all(s['interpolation']=='CUBICSPLINE' for s in gltf['animations'][0]['samplers'])
    assert all(exporter._read_accessor(gltf,binary,s['input'])['count']==3 for s in gltf['animations'][0]['samplers'])
    descriptor=json.loads(expected_bytes['descriptor_path'])
    assert descriptor['format_version']==2
    assert descriptor['sockets']==config['sockets']
    assert descriptor['animation']['states']['native_cubic:wave']['events'][0]['time_seconds']==.5
    assert any(any(abs(v)>1e-4 for v in row) for sampler in gltf['animations'][0]['samplers']
               for i,row in enumerate(exporter._read_accessor(gltf,binary,sampler['output'])['values']) if i%3!=1)
    probes, maximum = oracle(rig,meshes,gltf,binary,24, fixture/'oracle-samples.json')
    assert maximum < 3e-5,maximum
    # Slow-in / slow-out must visibly disagree with straight interpolation.
    base_translation=next(c for c in gltf['animations'][0]['channels'] if gltf['nodes'][c['target']['node']]['name']=='Base' and c['target']['path']=='translation')
    cubic=sample(gltf,binary,base_translation,.125)
    start=sample(gltf,binary,base_translation,0);end=sample(gltf,binary,base_translation,.5)
    easing_difference=max(abs(cubic[i]-(start[i]+.25*(end[i]-start[i]))) for i in range(3))
    assert easing_difference>.02
    # Fractional FPS requires both input seconds and inverse-scaled tangents.
    scene.render.fps_base=1.001; scene.frame_set(10)
    fractional=exporter.export_open_blend(dataclasses.replace(options,project_root=root/'build/native-cubic-fractional'))
    fractional_gltf,fractional_bin=exporter.read_glb(Path(fractional['mesh_path']))
    _,fractional_error=oracle(rig,meshes,fractional_gltf,fractional_bin,24/float(scene.render.fps_base))
    assert fractional_error<3e-5,fractional_error
    fraction_descriptor=json.loads(Path(fractional['descriptor_path']).read_text())
    fractional_event=fraction_descriptor['animation']['states']['native_cubic:wave']['events'][0]['time_seconds']
    assert abs(fractional_event-.5005)<1e-6
    scene.render.fps_base=1
    # A true non-third Bezier time handle must be declared approximate/baked.
    curve=next(iter(exporter._action_fcurves(action)));old=curve.keyframe_points[0].handle_right.copy()
    curve.keyframe_points[0].handle_right.x=12
    scene.frame_set(10)
    fallback=exporter.export_open_blend(dataclasses.replace(options,project_root=root/'build/native-cubic-fallback'))
    fallback_gltf,_=exporter.read_glb(Path(fallback['mesh_path']))
    assert fallback['native_cubic']['mode']=='baked_linear_fallback'
    assert any('one third' in r['reason'] for r in fallback['native_cubic']['fallback_reasons'])
    assert all(s['interpolation'] in {'LINEAR','STEP'} for clip in fallback_gltf['animations'] for s in clip['samplers'])
    curve.keyframe_points[0].handle_right=old
    eligibility_checks = eligibility_tests(rig, action, collection, scene)
    # Native LINEAR and STEP translations retain source key timing too.
    translation_curves=[c for c in exporter._action_fcurves(action) if c.data_path.endswith('.location')]
    for kind,expected in [('LINEAR','LINEAR'),('CONSTANT','STEP')]:
        for item in translation_curves:
            for key in item.keyframe_points:key.interpolation=kind
        scene.frame_set(10)
        mixed=exporter.export_open_blend(dataclasses.replace(options,project_root=root/('build/native-cubic-'+kind.lower())))
        mixed_gltf,mixed_binary=exporter.read_glb(Path(mixed['mesh_path']))
        assert mixed['native_cubic']['mode']=='native_fcurves'
        channel=next(c for c in mixed_gltf['animations'][0]['channels'] if c['target']['path']=='translation')
        assert mixed_gltf['animations'][0]['samplers'][channel['sampler']]['interpolation']==expected
        _,mixed_error=oracle(rig,meshes,mixed_gltf,mixed_binary,24)
        assert mixed_error<3e-5,(kind,mixed_error)
    for item in translation_curves:
        for key in item.keyframe_points:key.interpolation='BEZIER'
    # Existing strict-v1 stays sampled and never emits format 2 or cubic.
    scene.frame_set(10)
    strict=exporter.export_open_blend(dataclasses.replace(options,profile='blendlib:skinned_v1',project_root=root/'build/native-cubic-strict'))
    strict_gltf,_=exporter.read_glb(Path(strict['mesh_path']))
    assert json.loads(Path(strict['descriptor_path']).read_text())['format_version']==1
    assert 'native_cubic' not in strict
    assert all(s['interpolation'] in {'LINEAR','STEP'} for clip in strict_gltf['animations'] for s in clip['samplers'])
    # Exercise existing authoring sidebar export with the new explicit profile.
    scene.frame_set(10); assert bpy.ops.blendlib.export_model()=={'FINISHED'}
    evidence={'blender_version':bpy.app.version_string,'build_hash':bpy.app.build_hash.decode(),
        'profile':options.profile,'format_version':2,'native':first['native_cubic'],
        'deterministic_glb_and_descriptor':True,'sample_count':len(probes),
        'maximum_skin_position_error':maximum,'fractional_fps_maximum_error':fractional_error,
        'fractional_fps_event_seconds':fractional_event,'quarter_segment_easing_difference':easing_difference,
        'fallback':fallback['native_cubic'],'eligibility_checks':eligibility_checks,'native_linear_step_oracles':True,'strict_v1_preserved':True,'sidebar_export':True,'samples':probes}
    (fixture/'oracle.json').write_text(json.dumps(evidence,indent=2,sort_keys=True)+'\n')
    (fixture/'runtime-authoring.json').write_text(json.dumps(config,indent=2)+'\n')
    # Screenshot-like render of three sampled poses to make the easing source inspectable.
    render_preview(fixture,collection,scene)
    exporter.unregister()
    print('BLENDLIB_NATIVE_CUBIC_OK '+json.dumps({key:evidence[key] for key in ('maximum_skin_position_error','fractional_fps_maximum_error','quarter_segment_easing_difference')}))


def eligibility_tests(rig,action,collection,scene):
    module=exporter._native_cubic_module()
    objects,_=exporter._collect_export_objects(collection)
    passed=[]
    def expect(label):
        plan=module.analyze(objects,(action,),exporter._action_fcurves,scene)
        assert not plan.native,(label,plan.report())
        passed.append({'case':label,'reasons':plan.reasons})
    curves=list(exporter._action_fcurves(action))
    first=curves[0];key=first.keyframe_points[1]
    old=key.co.x;key.co.x+=.25;expect('unequal_component_key_times');key.co.x=old
    modifier=first.modifiers.new('NOISE');expect('FCurve_modifier');first.modifiers.remove(modifier)
    first.mute=True;expect('muted_FCurve');first.mute=False
    old=rig.animation_data.action_influence;rig.animation_data.action_influence=.5
    expect('Action_influence');rig.animation_data.action_influence=old
    track=rig.animation_data.nla_tracks.new();strip=track.strips.new('Mixing',10,action)
    expect('active_NLA_mixing');rig.animation_data.nla_tracks.remove(track)
    constraint=rig.pose.bones['Tip'].constraints.new('COPY_LOCATION')
    expect('bone_constraint');rig.pose.bones['Tip'].constraints.remove(constraint)
    bone=rig.data.bones['Tip'];old=bone.inherit_scale;bone.inherit_scale='NONE'
    expect('nonstandard_bone_inheritance');bone.inherit_scale=old
    scale=next(c for c in curves if c.data_path.endswith('.scale'))
    key=scale.keyframe_points[0];old=key.handle_right.y;key.handle_right.y=-2
    expect('nonpositive_or_nonuniform_scale_hull');key.handle_right.y=old
    rotation=[c for c in curves if c.data_path.endswith('.rotation_quaternion')]
    old_values=[c.keyframe_points[-1].co.y for c in rotation]
    for c in rotation:c.keyframe_points[-1].co.y=-c.keyframe_points[-1].co.y
    expect('quaternion_hemisphere_crossing')
    for c,old in zip(rotation,old_values):c.keyframe_points[-1].co.y=old
    for c in rotation:
        for key in c.keyframe_points:key.interpolation='LINEAR'
    expect('component_linear_quaternion')
    for c in rotation:
        for key in c.keyframe_points:key.interpolation='BEZIER'
    constraint=rig.constraints.new('COPY_LOCATION')
    try:
        exporter._validate_source_objects(objects,'blendlib:skinned_cubic_v1')
        raise AssertionError('object constraint must remain a rejection')
    except exporter.ExportError as error:
        assert error.code=='BLENDLIB-EXPORT-003'
        passed.append({'case':'object_constraint','rejection_code':error.code})
    rig.constraints.remove(constraint)
    assert module.analyze(objects,(action,),exporter._action_fcurves,scene).native
    return passed


def sample(gltf,binary,channel,time):
    sampler=gltf['animations'][0]['samplers'][channel['sampler']]
    times=[x[0] for x in exporter._read_accessor(gltf,binary,sampler['input'])['values']]
    data=exporter._read_accessor(gltf,binary,sampler['output'])['values']
    index=max(0,min(len(times)-2,next((i-1 for i,t in enumerate(times) if t>time),len(times)-2)))
    dt=times[index+1]-times[index];u=max(0,min(1,(time-times[index])/dt))
    if sampler['interpolation']=='STEP':
        return data[-1] if time>=times[-1] else data[index]
    if sampler['interpolation']=='LINEAR':
        return tuple(a+(b-a)*u for a,b in zip(data[index],data[index+1]))
    p0,m0,m1,p1=data[index*3+1],data[index*3+2],data[(index+1)*3],data[(index+1)*3+1]
    value=tuple((2*u**3-3*u*u+1)*a+(u**3-2*u*u+u)*dt*b+(-2*u**3+3*u*u)*d+(u**3-u*u)*dt*c for a,b,c,d in zip(p0,m0,m1,p1))
    if channel['target']['path']=='rotation':
        norm=math.hypot(*value);value=tuple(v/norm for v in value)
    return value


def oracle(rig,meshes,gltf,binary,fps,save_samples=None):
    nodes=gltf['nodes']; animation=gltf['animations'][0]
    yup=Matrix(((1,0,0,0),(0,0,1,0),(0,-1,0,0),(0,0,0,1)))
    probes=[];maximum=0
    independent_samples=[];mesh_mappings={}
    for frame in (10,10.25,11.5,13,16,19.25,21.9,22,24.5,28,34,39.75,45.9,46):
        time=(frame-10)/fps
        bpy.context.scene.frame_set(math.floor(frame),subframe=frame%1)
        local={i:dict(translation=n.get('translation',[0,0,0]),rotation=n.get('rotation',[0,0,0,1]),scale=n.get('scale',[1,1,1])) for i,n in enumerate(nodes)}
        for channel in animation['channels']:local[channel['target']['node']][channel['target']['path']]=sample(gltf,binary,channel,time)
        world={}
        def walk(i,parent):
            n=local[i];q=n['rotation']
            world[i]=parent @ Matrix.LocRotScale(Vector(n['translation']),Quaternion((q[3],q[0],q[1],q[2])),Vector(n['scale']))
            for child in nodes[i].get('children',[]):walk(child,world[i])
        for i in gltf['scenes'][gltf.get('scene',0)]['nodes']:walk(i,Matrix.Identity(4))
        names={node['name']:i for i,node in enumerate(nodes)}
        max_pose=0
        expected_bones={}
        expected_skin={}
        for bone in rig.pose.bones:
            expected=yup @ rig.matrix_world @ bone.matrix
            expected_bones[bone.name]=[float(expected[r][c]) for r in range(4) for c in range(4)]
            error=max(abs(expected[r][c]-world[names[bone.name]][r][c]) for r in range(4) for c in range(4))
            maximum=max(maximum,error);max_pose=max(max_pose,error)
        # Compare actual evaluated skinned vertices with exported bind vertices,
        # inverse binds and sampled bone worlds (one exact weight per vertex).
        depsgraph=bpy.context.evaluated_depsgraph_get();max_skin=0
        for obj in meshes:
            node=nodes[names[obj.name]];skin=gltf['skins'][node['skin']]
            inverse=exporter._read_accessor(gltf,binary,skin['inverseBindMatrices'])['values']
            primitive=gltf['meshes'][node['mesh']]['primitives'][0];attr=primitive['attributes']
            positions=exporter._read_accessor(gltf,binary,attr['POSITION'])['values']
            if obj.name not in mesh_mappings:
                source_indices=[]
                for point in positions:
                    # Geometry was applied before skinning; match only rest source
                    # positions, without using any animated/exported bone matrix.
                    bind=Vector((point[0],-point[2],point[1]))
                    index=min(range(len(obj.data.vertices)),key=lambda i:(obj.data.vertices[i].co-bind).length)
                    assert (obj.data.vertices[index].co-bind).length<1e-6
                    source_indices.append(index)
                mesh_mappings[obj.name]={'primitive':0,'source_vertex_indices':source_indices,'vertex_count':len(positions)}
            joints=exporter._read_accessor(gltf,binary,attr['JOINTS_0'])['values']
            predicted=[]
            for point,joint in zip(positions,joints):
                raw=inverse[joint[0]];inv=Matrix(tuple(tuple(raw[c*4+r] for c in range(4)) for r in range(4)))
                predicted.append(world[skin['joints'][joint[0]]] @ inv @ Vector(point))
            evaluated=obj.evaluated_get(depsgraph);mesh=evaluated.to_mesh()
            expected=[yup @ evaluated.matrix_world @ vertex.co for vertex in mesh.vertices]
            expected_skin[obj.name]=[list(expected[i]) for i in mesh_mappings[obj.name]['source_vertex_indices']]
            error=max((a-Vector(b)).length for a,b in zip(predicted,expected_skin[obj.name]))
            evaluated.to_mesh_clear();max_skin=max(max_skin,error);maximum=max(maximum,error)
        probes.append({'frame':frame,'seconds':time,'maximum_pose_matrix_error':max_pose,'maximum_skinned_vertex_error':max_skin})
        independent_samples.append({'frame':frame,'seconds':time,'bone_world_matrices':expected_bones,'skinned_world_positions':expected_skin})
    if save_samples is not None:
        save_samples.write_text(json.dumps({'blender_version':bpy.app.version_string,'clip':'EaseWave',
            'matrix_layout':'row-major','coordinates':'glTF Y-up world','geometry_order':'exported primitive POSITION accessor',
            'meshes':mesh_mappings,'samples':independent_samples},indent=2,sort_keys=True)+'\n')
    return probes,maximum


def render_preview(fixture,collection,scene):
    # Render using Blender, not an invented image, from the actual authored scene.
    scene.frame_set(19)
    camera=bpy.data.objects.new('PreviewCamera',bpy.data.cameras.new('PreviewCamera'))
    scene.collection.objects.link(camera);camera.location=(3,-6,3)
    target=Vector((.1,0,.8));camera.rotation_euler=(target-camera.location).to_track_quat('-Z','Y').to_euler()
    camera.data.type='ORTHO';camera.data.ortho_scale=3.1;scene.camera=camera
    light=bpy.data.objects.new('PreviewLight',bpy.data.lights.new('PreviewLight','AREA'))
    scene.collection.objects.link(light);light.location=(1,-3,5);light.data.energy=650;light.data.size=5
    light.rotation_euler=(target-light.location).to_track_quat('-Z','Y').to_euler()
    scene.world=bpy.data.worlds.new('PreviewWorld');scene.world.color=(.15,.15,.15)
    scene.render.engine='BLENDER_EEVEE_NEXT' if bpy.app.version<(4,2,0) else 'CYCLES'
    scene.cycles.samples=16
    scene.render.resolution_x=640;scene.render.resolution_y=640;scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG';scene.render.filepath=str(fixture/'preview.png')
    bpy.ops.render.render(write_still=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--project-root',required=True)
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);create(Path(args.project_root).resolve())
