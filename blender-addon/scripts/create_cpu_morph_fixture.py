# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reproducible first-party face/breath actor, real Blender oracle and preview.

blender --background --python-exit-code 1 --python blender-addon/scripts/create_cpu_morph_fixture.py -- --project-root .
"""
from __future__ import annotations
import argparse
import dataclasses
import json
import math
import sys
import struct
from pathlib import Path
sys.dont_write_bytecode = True
import bpy
from mathutils import Matrix, Quaternion, Vector
sys.path[:0] = [str(Path(__file__).resolve().parents[1]), str(Path(__file__).resolve().parent)]
import blendlib_exporter as exporter
from create_canonical_fixtures import reset_scene, make_collection, make_external_png, make_material

YUP = Matrix(((1,0,0,0),(0,0,1,0),(0,-1,0,0),(0,0,0,1)))
MESH_PATH = 'MorphRoot/MorphRig/FaceBody'


def actor_mesh(collection, rig, materials):
    vertices, faces, face_materials, parts = [], [], [], {}
    def box(name, center, size, material, bone):
        begin = len(vertices)
        vertices.extend(tuple(center[c] + sign[c]*size[c]/2 for c in range(3)) for sign in
            ((-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)))
        faces.extend(tuple(begin+i for i in face) for face in ((0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)))
        face_materials.extend([material]*6)
        parts[name] = {'indices': list(range(begin,begin+8)), 'center': center, 'bone': bone}
    box('torso',(0,0,.70),(.72,.44,.85),0,'Base')
    box('head',(0,0,1.48),(.91,.49,.68),0,'Face')
    box('left_eye',(-.23,-.261,1.57),(.16,.075,.17),1,'Face')
    box('right_eye',(.23,-.261,1.57),(.16,.075,.17),1,'Face')
    box('mouth_center',(0,-.270,1.31),(.24,.066,.045),1,'Face')
    box('mouth_left',(-.175,-.270,1.32),(.13,.065,.046),1,'Face')
    box('mouth_right',(.175,-.270,1.32),(.13,.065,.046),1,'Face')
    box('left_arm',(-.49,0,.78),(.20,.29,.64),0,'Base')
    box('right_arm',(.49,0,.78),(.20,.29,.64),0,'Base')
    box('left_foot',(-.22,-.065,.15),(.26,.48,.23),1,'Base')
    box('right_foot',(.22,-.065,.15),(.26,.48,.23),1,'Base')
    mesh = bpy.data.meshes.new('FaceBodyData');mesh.from_pydata(vertices,[],faces);mesh.update()
    for material in materials: mesh.materials.append(material)
    uv = mesh.uv_layers.new(name='UVMap')
    for polygon,material in zip(mesh.polygons,face_materials):
        polygon.material_index = material
        for loop,coordinate in zip(polygon.loop_indices,((0,0),(1,0),(1,1),(0,1))):uv.data[loop].uv=coordinate
    obj=bpy.data.objects.new('FaceBody',mesh);collection.objects.link(obj);obj.parent=rig
    for bone in ('Base','Face'):
        group=obj.vertex_groups.new(name=bone)
        group.add([i for part in parts.values() if part['bone']==bone for i in part['indices']],1,'REPLACE')
    # Upper chest vertices blend two actual joints, exercising morph-before-LBS
    # even when Nod supplies only bones and Breath remains at its nonzero default.
    shoulder_vertices=[i for i in parts['torso']['indices'] if vertices[i][2]>.70]
    obj.vertex_groups['Base'].add(shoulder_vertices,.8,'REPLACE')
    obj.vertex_groups['Face'].add(shoulder_vertices,.2,'REPLACE')
    modifier=obj.modifiers.new('Skin','ARMATURE');modifier.object=rig
    basis=obj.shape_key_add(name='Basis')
    blink=obj.shape_key_add(name='Blink');smile=obj.shape_key_add(name='Smile');breath=obj.shape_key_add(name='Breath')
    smile.slider_min=-1;breath.slider_min=-.5
    for name in ('left_eye','right_eye'):
        part=parts[name]
        for i in part['indices']:blink.data[i].co.z=part['center'][2]+(basis.data[i].co.z-part['center'][2])*.12
    for name,direction in (('mouth_left',-1),('mouth_right',1)):
        part=parts[name];center=Vector(part['center'])
        rotation=Matrix.Rotation(-direction*.20,3,'Y')
        for i in part['indices']:smile.data[i].co=center+rotation@(basis.data[i].co-center)+Vector((0,0,.035))
    for i in parts['torso']['indices']:
        point=breath.data[i].co
        point.x*=1.11
        # A gentle varying front depth gives nonzero normal deltas for the normal oracle.
        point.y*=1.15 + .06*(point.z-.70)
    breath.value=.15
    return obj


def stash(owner,action):
    data=owner.animation_data
    slot=data.action_slot
    track=data.nla_tracks.new();track.name='Stored '+action.name
    strip=track.strips.new(action.name,int(action.frame_range[0]),action);strip.action_slot=slot
    track.mute=True;data.action=None


def create_actions(rig,mesh):
    scene=bpy.context.scene
    for bone in rig.pose.bones:bone.rotation_mode='QUATERNION'
    for frame,angle in ((10,0),(22,.40),(46,0)):
        rig.pose.bones['Face'].rotation_quaternion=Quaternion((1,0,0),angle)
        rig.pose.bones['Face'].keyframe_insert('rotation_quaternion',frame=frame)
    nod=rig.animation_data.action;nod.name='Nod';nod.pose_markers.new('Apex').frame=22
    for curve in exporter._action_fcurves(nod):
        points=curve.keyframe_points
        for i,key in enumerate(points):
            key.interpolation='BEZIER';key.handle_left_type='FREE';key.handle_right_type='FREE'
            previous=points[i-1].co.x if i else key.co.x-12
            following=points[i+1].co.x if i+1<len(points) else key.co.x+24
            key.handle_left=(key.co.x-(key.co.x-previous)/3,key.co.y)
            key.handle_right=(key.co.x+(following-key.co.x)/3,key.co.y)
    keys=mesh.data.shape_keys
    for name,kind,values in (
        ('Blink','CONSTANT',((10,0),(16,1),(19,0),(46,0))),
        ('Smile','LINEAR',((10,0),(22,.85),(34,-.35),(46,0))),
        ('Breath','LINEAR',((10,.15),(22,.8),(34,-.25),(46,.15)))):
        block=keys.key_blocks[name]
        for frame,value in values:block.value=value;block.keyframe_insert('value',frame=frame)
        action=keys.animation_data.action;action.name=name
        for curve in exporter._action_fcurves(action):
            for key in curve.keyframe_points:key.interpolation=kind
        stash(keys,action)
    for name,value in (('Blink',0),('Smile',0),('Breath',.15)):keys.key_blocks[name].value=value
    scene.frame_set(10)
    return nod


def sample_channel(gltf,binary,animation,channel,seconds,target_count=1):
    sampler=animation['samplers'][channel['sampler']]
    times=[r[0] for r in exporter._read_accessor(gltf,binary,sampler['input'])['values']]
    values=exporter._read_accessor(gltf,binary,sampler['output'])['values']
    if channel['target']['path']=='weights':
        flat=[r[0] for r in values];values=[flat[i:i+target_count] for i in range(0,len(flat),target_count)]
    i=max(0,min(len(times)-2,next((i-1 for i,t in enumerate(times) if t>seconds),len(times)-2)))
    dt=times[i+1]-times[i];u=max(0,min(1,(seconds-times[i])/dt))
    kind=sampler.get('interpolation','LINEAR')
    if kind=='STEP':return values[-1] if seconds>=times[-1] else values[i]
    if kind=='LINEAR':return [a+(b-a)*u for a,b in zip(values[i],values[i+1])]
    p0,m0,m1,p1=values[3*i+1],values[3*i+2],values[3*(i+1)],values[3*(i+1)+1]
    result=[(2*u**3-3*u*u+1)*a+(u**3-2*u*u+u)*dt*b+(u**3-u*u)*dt*c+(-2*u**3+3*u*u)*d for a,b,c,d in zip(p0,m0,m1,p1)]
    if channel['target']['path']=='rotation':result=[v/math.hypot(*result) for v in result]
    return result


def sampled_world(gltf,binary,animation,seconds):
    local={i:{'translation':n.get('translation',[0,0,0]),'rotation':n.get('rotation',[0,0,0,1]),'scale':n.get('scale',[1,1,1])} for i,n in enumerate(gltf['nodes'])}
    for channel in animation['channels']:
        if channel['target']['path']=='weights':continue
        local[channel['target']['node']][channel['target']['path']]=sample_channel(gltf,binary,animation,channel,seconds)
    world={}
    def walk(index,parent):
        n=local[index];q=n['rotation']
        world[index]=parent@Matrix.LocRotScale(Vector(n['translation']),Quaternion((q[3],q[0],q[1],q[2])),Vector(n['scale']))
        for child in gltf['nodes'][index].get('children',[]):walk(child,world[index])
    for index in gltf['scenes'][gltf.get('scene',0)]['nodes']:walk(index,Matrix.Identity(4))
    return world


def oracle(rig,mesh,gltf,binary,fixture,fps):
    nodes=gltf['nodes'];node_id=next(i for i,n in enumerate(nodes) if n['name']==mesh.name);node=nodes[node_id]
    gltf_mesh=gltf['meshes'][node['mesh']];names=gltf_mesh['extras']['targetNames'];defaults=node.get('weights',gltf_mesh.get('weights',[0]*len(names)))
    skin=gltf['skins'][node['skin']]
    inverses=[Matrix(tuple(tuple(raw[c*4+r] for c in range(4)) for r in range(4))) for raw in exporter._read_accessor(gltf,binary,skin['inverseBindMatrices'])['values']]
    maps=[]
    for pi,primitive in enumerate(gltf_mesh['primitives']):
        positions=exporter._read_accessor(gltf,binary,primitive['attributes']['POSITION'])['values']
        indices=[]
        for p in positions:
            point=Vector((p[0],-p[2],p[1]))
            index=min(range(len(mesh.data.vertices)),key=lambda i:(mesh.data.shape_keys.reference_key.data[i].co-point).length)
            assert (mesh.data.shape_keys.reference_key.data[index].co-point).length<1e-6
            indices.append(index)
        maps.append({'primitive':pi,'source_vertex_indices':indices,'vertex_count':len(indices)})
    actions={a.name:a for a in bpy.data.actions};keys=mesh.data.shape_keys
    output=[];maximum_position=0;maximum_weight=0;maximum_matrix=0
    for animation in gltf['animations']:
        clip=animation['name'];samples=[]
        rig.animation_data.action=None;keys.animation_data.action=None
        for bone in rig.pose.bones:bone.matrix_basis=Matrix.Identity(4)
        for name,value in zip(names,defaults):keys.key_blocks[name].value=value
        owner=rig if clip=='Nod' else keys
        owner.animation_data.action=actions[clip]
        owner.animation_data.action_slot=next(slot for slot in actions[clip].slots if slot.target_id_type==('OBJECT' if clip=='Nod' else 'KEY'))
        for frame in (10,10.25,13,15.9,16,17.5,19,21.75,22,27.5,33.75,34,39.25,45.8,46):
            source_seconds=(frame-10)/fps
            # glTF time accessors are FLOAT. Probe authored key boundaries at
            # their representable runtime time, so STEP does not compare the
            # unrounded source instant with the opposite side of a rounded jump.
            seconds=struct.unpack('<f',struct.pack('<f',source_seconds))[0]
            bpy.context.scene.frame_set(math.floor(frame),subframe=frame%1)
            depsgraph=bpy.context.evaluated_depsgraph_get();evaluated=mesh.evaluated_get(depsgraph);evaluated_mesh=evaluated.to_mesh()
            actual_positions=[YUP@evaluated.matrix_world@v.co for v in evaluated_mesh.vertices]
            actual_weights=[float(keys.key_blocks[name].value) for name in names]
            expected_weights=list(defaults)
            for channel in animation['channels']:
                if channel['target']['path']=='weights':expected_weights=sample_channel(gltf,binary,animation,channel,seconds,len(names))
            maximum_weight=max(maximum_weight,max(abs(a-b) for a,b in zip(actual_weights,expected_weights)))
            world=sampled_world(gltf,binary,animation,seconds)
            bones={}
            for bone in rig.pose.bones:
                expected=YUP@rig.matrix_world@bone.matrix;index=next(i for i,n in enumerate(nodes) if n['name']==bone.name)
                bones[bone.name]=[float(expected[r][c]) for r in range(4) for c in range(4)]
                maximum_matrix=max(maximum_matrix,max(abs(world[index][r][c]-expected[r][c]) for r in range(4) for c in range(4)))
            primitives=[]
            for pi,primitive in enumerate(gltf_mesh['primitives']):
                attr=primitive['attributes'];read=lambda index:exporter._read_accessor(gltf,binary,index)['values']
                positions,normals=read(attr['POSITION']),read(attr['NORMAL'])
                joints,weights=read(attr['JOINTS_0']),read(attr['WEIGHTS_0'])
                dp=[read(t['POSITION']) for t in primitive['targets']];dn=[read(t['NORMAL']) for t in primitive['targets']]
                predicted_positions=[];normal_oracle=[]
                for vi,(point,normal,js,ws) in enumerate(zip(positions,normals,joints,weights)):
                    p=Vector([point[c]+sum(w*d[vi][c] for w,d in zip(expected_weights,dp)) for c in range(3)])
                    n=Vector([normal[c]+sum(w*d[vi][c] for w,d in zip(expected_weights,dn)) for c in range(3)])
                    out_p=Vector((0,0,0));out_n=Vector((0,0,0))
                    for j,w in zip(js,ws):
                        if not w:continue
                        transform=world[skin['joints'][j]]@inverses[j]
                        out_p+=(transform@p)*w
                        out_n+=(transform.to_3x3().inverted().transposed()@n)*w
                    out_n.normalize();predicted_positions.append(out_p);normal_oracle.append(list(out_n))
                actual=[list(actual_positions[i]) for i in maps[pi]['source_vertex_indices']]
                maximum_position=max(maximum_position,max((p-Vector(a)).length for p,a in zip(predicted_positions,actual)))
                primitives.append({'primitive':pi,'positions':actual,'normals':normal_oracle})
            evaluated.to_mesh_clear()
            samples.append({'frame':frame,'seconds':seconds,'source_seconds':source_seconds,'weights':{MESH_PATH:actual_weights},
                'bone_world_matrices':bones,'meshes':{'FaceBody':primitives}})
        output.append({'clip':clip,'samples':samples})
    rig.animation_data.action=actions['Nod'];rig.animation_data.action_slot=actions['Nod'].slots[0];keys.animation_data.action=None
    for name,value in zip(names,defaults):keys.key_blocks[name].value=value
    bpy.context.scene.frame_set(10)
    document={'blender_version':bpy.app.version_string,'matrix_layout':'row-major','coordinates':'glTF Y-up world',
        'geometry_order':'exported primitive POSITION accessor','position_evidence':'Actual Blender depsgraph-evaluated skinned vertices',
        'normal_evidence':'Independent glTF target-delta then inverse-transpose skin math; not Blender geometric normals',
        'time_evidence':'seconds are glTF FLOAT-representable probe times; source_seconds records the unrounded Blender instant, including STEP boundaries',
        'mesh_node_path':MESH_PATH,'target_names':names,'default_weights':defaults,
        'meshes':{'FaceBody':maps},'clips':output}
    (fixture/'oracle-samples.json').write_text(json.dumps(document,separators=(',', ':'),sort_keys=True)+'\n')
    return {'sample_count':sum(len(c['samples']) for c in output),'maximum_skinned_position_error':maximum_position,
            'maximum_weight_error':maximum_weight,'maximum_bone_world_matrix_error':maximum_matrix}


def render_preview(fixture,scene,mesh):
    scene.frame_set(19)
    mesh.data.shape_keys.key_blocks['Smile'].value=.85
    camera=bpy.data.objects.new('PreviewCamera',bpy.data.cameras.new('PreviewCamera'));scene.collection.objects.link(camera)
    camera.location=(3,-7,3.1);target=Vector((0,0,.92));camera.rotation_euler=(target-camera.location).to_track_quat('-Z','Y').to_euler()
    camera.data.type='ORTHO';camera.data.ortho_scale=2.65;scene.camera=camera
    light=bpy.data.objects.new('PreviewLight',bpy.data.lights.new('PreviewLight','AREA'));scene.collection.objects.link(light)
    light.location=(0,-3,5);light.data.energy=700;light.data.size=4
    light.rotation_euler=(target-light.location).to_track_quat('-Z','Y').to_euler()
    scene.world=bpy.data.worlds.new('PreviewWorld');scene.world.color=(.16,.18,.22)
    label_material=bpy.data.materials.new('PreviewLabel');label_material.use_nodes=True
    nodes=label_material.node_tree.nodes;nodes.clear()
    emission=nodes.new('ShaderNodeEmission');emission.inputs['Color'].default_value=(.8,.86,.88,1)
    output=nodes.new('ShaderNodeOutputMaterial');label_material.node_tree.links.new(emission.outputs[0],output.inputs['Surface'])
    for body,y,size in (('BLENDER SOURCE PREVIEW',-1.16,.072),('CPU morph fixture / Minecraft visual check pending',-1.245,.040)):
        font=bpy.data.curves.new('PreviewLabel','FONT');font.body=body;font.size=size;font.align_x='CENTER'
        label=bpy.data.objects.new('PreviewLabel',font);scene.collection.objects.link(label)
        label.parent=camera;label.location=(0,y,-2);label.data.materials.append(label_material)
    scene.render.engine='CYCLES';scene.cycles.samples=24
    scene.render.resolution_x=720;scene.render.resolution_y=720;scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG';scene.render.filepath=str(fixture/'preview-blender.png')
    bpy.ops.render.render(write_still=True)


def create(root):
    reset_scene();fixture=root/'test-assets/cpu-morph';fixture.mkdir(parents=True,exist_ok=True)
    scene=bpy.context.scene;scene.render.fps=24;scene.render.fps_base=1;scene.frame_start=10;scene.frame_end=46
    collection=make_collection('BlendLibExport')
    origin=bpy.data.objects.new('MorphRoot',None);collection.objects.link(origin)
    rig=bpy.data.objects.new('MorphRig',bpy.data.armatures.new('MorphRigData'));collection.objects.link(rig);rig.parent=origin
    bpy.context.view_layer.objects.active=rig;rig.select_set(True);bpy.ops.object.mode_set(mode='EDIT')
    base=rig.data.edit_bones.new('Base');base.head=(0,0,0);base.tail=(0,0,1.05)
    face=rig.data.edit_bones.new('Face');face.parent=base;face.head=(0,0,1.12);face.tail=(.06,.025,1.8);face.roll=.13
    bpy.ops.object.mode_set(mode='OBJECT')
    surface=make_external_png(fixture/'surface.png',(.12,.67,.72,1));detail=make_external_png(fixture/'detail.png',(.025,.05,.10,1))
    materials=[make_material('MorphSurface',surface),make_material('FaceDetails',detail)]
    mesh=actor_mesh(collection,rig,materials);create_actions(rig,mesh)
    config={'schema_version':1,'animation':{'initial_state':'cpu_morph:nod','states':{
        'cpu_morph:'+name.lower():{'clip':name,'loop':True,'speed':1} for name in ('Nod','Blink','Smile','Breath')}},
        'sockets':{'cpu_morph:face':{'node':'MorphRoot/MorphRig/Base/Face'}},
        'morph_controls':{'cpu_morph:'+name.lower():{'node':MESH_PATH,'target':name,'min_weight':low,'max_weight':high}
            for name,low,high in (('Blink',0,1),('Smile',-1,1),('Breath',-.5,1))}}
    config['animation']['states']['cpu_morph:nod']['events']=[{'marker':'Apex','event':'cpu_morph:nod_apex'}]
    text=bpy.data.texts.new('BlendLib.runtime.json');text.write(json.dumps(config,indent=2))
    exporter.register();scene.blendlib_collection=collection;scene.blendlib_namespace='cpu_morph';scene.blendlib_model_id='face_actor'
    scene.blendlib_profile='blendlib:skinned_morph_cpu_v1';scene.blendlib_project_root='//exported';scene.blendlib_output_resource_root='.'
    scene.blendlib_runtime_authoring_enabled=True;scene.blendlib_runtime_authoring_text=text
    surface.filepath='//surface.png';detail.filepath='//detail.png';source=fixture/'source.blend'
    bpy.ops.wm.save_as_mainfile(filepath=str(source),compress=True)
    options=exporter.ExportOptions(source,fixture/'exported','cpu_morph','face_actor','blendlib:skinned_morph_cpu_v1',collection.name,'.',None,runtime_authoring_text=text.name)
    first=exporter.export_open_blend(options)
    original={key:Path(first[key]).read_bytes() for key in ('mesh_path','descriptor_path')}
    second=exporter.export_open_blend(options)
    assert all(original[key]==Path(second[key]).read_bytes() for key in original),'nondeterministic export'
    gltf,binary=exporter.read_glb(Path(first['mesh_path']))
    assert len(gltf['meshes'][0]['primitives'])==2
    assert [c['name'] for c in gltf['animations']]==['Blink','Breath','Nod','Smile']
    assert all(c['target']['path']=='weights' for a in gltf['animations'] if a['name']!='Nod' for c in a['channels'])
    assert all(s['interpolation']=='CUBICSPLINE' for a in gltf['animations'] if a['name']=='Nod' for s in a['samplers'])
    evidence=oracle(rig,mesh,gltf,binary,fixture,24)
    assert evidence['maximum_skinned_position_error']<3e-5,evidence
    assert evidence['maximum_weight_error']<1e-6,evidence
    assert evidence['maximum_bone_world_matrix_error']<3e-5,evidence
    # Source export remains reproducible after independent evaluation of every clip.
    third=exporter.export_open_blend(options)
    assert all(original[key]==Path(third[key]).read_bytes() for key in original)
    evidence.update(blender_version=bpy.app.version_string,build_hash=bpy.app.build_hash.decode(),profile=options.profile,
        deterministic_glb_and_descriptor=True,native_cubic=first['native_cubic'],cpu_morph=first['cpu_morph'],
        preview='Actual Blender render; no native Minecraft visual acceptance is claimed')
    (fixture/'oracle.json').write_text(json.dumps(evidence,indent=2,sort_keys=True)+'\n')
    (fixture/'runtime-authoring.json').write_text(json.dumps(config,indent=2)+'\n')
    render_preview(fixture,scene,mesh);exporter.unregister()
    print('BLENDLIB_CPU_MORPH_OK '+json.dumps(evidence))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--project-root',type=Path,required=True)
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);create(args.project_root.resolve())
