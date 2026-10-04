#!/usr/bin/env python3
"""Deterministically author the standard-IK demo GLBs and PNG; standard library only."""
from pathlib import Path
import json
import math
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/blendlib_runnable_examples'
NS = 'blendlib_runnable_examples:'
ANGLE, SCALE, HEIGHT = .35, 1.1, .55


def box(low, high):
    x,y,z = low; X,Y,Z = high
    faces = [((x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)), ((X,y,z),(x,y,z),(x,Y,z),(X,Y,z)),
             ((X,y,Z),(X,y,z),(X,Y,z),(X,Y,Z)), ((x,y,z),(x,y,Z),(x,Y,Z),(x,Y,z)),
             ((x,Y,Z),(X,Y,Z),(X,Y,z),(x,Y,z)), ((x,y,z),(X,y,z),(X,y,Z),(x,y,Z))]
    return [(a,b,c) for a,b,c,d in faces] + [(a,c,d) for a,b,c,d in faces]


def bind(p):
    x,y,z = p; c,s = math.cos(ANGLE), math.sin(ANGLE)
    return (SCALE*(c*x+s*z), HEIGHT+SCALE*y, SCALE*(-s*x+c*z))


class Glb:
    def __init__(self, name):
        self.binary = bytearray()
        self.model = {'asset': {'version': '2.0', 'generator': 'BlendLib generate_mechanical_arm.py'},
                      'scene': 0, 'scenes': [{'nodes': [0]}], 'nodes': [], 'meshes': [],
                      'materials': [{'name': 'MechanicalSurface', 'pbrMetallicRoughness':
                                     {'baseColorFactor': [1,1,1,1], 'metallicFactor': 0, 'roughnessFactor': 1}}],
                      'buffers': [{}], 'bufferViews': [], 'accessors': []}
        self.name = name

    def accessor(self, values, fmt, component, shape, target=None):
        while len(self.binary) % 4: self.binary.append(0)
        start = len(self.binary)
        for value in values: self.binary.extend(struct.pack('<'+fmt, *value))
        view = {'buffer': 0, 'byteOffset': start, 'byteLength': len(self.binary)-start}
        if target is not None: view['target'] = target
        self.model['bufferViews'].append(view)
        acc = {'bufferView': len(self.model['bufferViews'])-1, 'componentType': component,
               'count': len(values), 'type': shape}
        if shape in ('VEC3', 'SCALAR'):
            acc['min'] = [min(v[i] for v in values) for i in range(len(values[0]))]
            acc['max'] = [max(v[i] for v in values) for i in range(len(values[0]))]
        self.model['accessors'].append(acc)
        return len(self.model['accessors'])-1

    def geometry(self, parts, skinned=False):
        positions, normals, uv, joints = [], [], [], []
        for triangles, joint, color in parts:
            for triangle in triangles:
                a,b,c = [bind(p) for p in triangle] if skinned else triangle
                u,v = [b[i]-a[i] for i in range(3)], [c[i]-a[i] for i in range(3)]
                n = (u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
                length = math.sqrt(sum(x*x for x in n))
                positions.extend((a,b,c)); normals.extend([tuple(x/length for x in n)]*3)
                uv.extend([((color+.5)/4, .5)]*3); joints.extend([(joint,0,0,0)]*3)
        attributes = {'POSITION': self.accessor(positions, '3f', 5126, 'VEC3', 34962),
                      'NORMAL': self.accessor(normals, '3f', 5126, 'VEC3', 34962),
                      'TEXCOORD_0': self.accessor(uv, '2f', 5126, 'VEC2', 34962)}
        if skinned:
            attributes['JOINTS_0'] = self.accessor(joints, '4B', 5121, 'VEC4', 34962)
            attributes['WEIGHTS_0'] = self.accessor([(1.,0.,0.,0.)]*len(positions), '4f', 5126, 'VEC4', 34962)
        self.model['meshes'] = [{'name': self.name, 'primitives': [{'attributes': attributes,
            'indices': self.accessor([(i,) for i in range(len(positions))], 'H', 5123, 'SCALAR', 34963),
            'material': 0}]}]

    def save(self):
        self.model['buffers'][0]['byteLength'] = len(self.binary)
        encoded = json.dumps(self.model, separators=(',', ':')).encode()
        encoded += b' '*(-len(encoded)%4); self.binary.extend(b'\0'*(-len(self.binary)%4))
        payload = (struct.pack('<III', 0x46546c67, 2, 28+len(encoded)+len(self.binary))
                   + struct.pack('<II', len(encoded), 0x4e4f534a)+encoded
                   + struct.pack('<II', len(self.binary), 0x004e4942)+self.binary)
        (ROOT/'models3d'/f'{self.name}.glb').write_bytes(payload)


def descriptor(name, skinned=False):
    result = {'format_version': 1, 'profile': 'blendlib:skinned_v1' if skinned else 'blendlib:rigid_v1',
              'mesh': NS+f'models3d/{name}.glb', 'units_per_block': 1.0,
              'materials': {'MechanicalSurface': {'base_color': NS+'textures/mechanical_arm.png',
                'mode': 'opaque', 'double_sided': False, 'emissive': False}},
              'extensions': {}, 'extensions_required': [], 'extensions_used': []}
    if skinned:
        result['animation'] = {'initial_state': NS+'idle', 'states': {NS+'idle':
            {'clip': 'idle', 'loop': True, 'speed': 1.0, 'blend_seconds': 0.0}}}
        result['sockets'] = {NS+'ik_end': {'node': 'ArmScene/ArmMount/ArmShoulder/ArmElbow/ArmEnd'},
                             NS+'ik_origin': {'node': 'ArmScene'},
                             NS+'ik_mount': {'node': 'ArmScene/ArmMount'}}
    (ROOT/'blend_models'/f'{name}.json').write_text(json.dumps(result, indent=2)+'\n')


arm = Glb('mechanical_arm')
arm.model['nodes'] = [
    {'name': 'ArmScene', 'children': [1,5]},
    {'name': 'ArmMount', 'children': [2], 'translation': [0,HEIGHT,0],
     'rotation': [0,math.sin(ANGLE/2),0,math.cos(ANGLE/2)], 'scale': [SCALE]*3},
    {'name': 'ArmShoulder', 'children': [3]},
    {'name': 'ArmElbow', 'translation': [1.1,0,0], 'children': [4]},
    {'name': 'ArmEnd', 'translation': [.9,0,0]},
    {'name': 'ArmMesh', 'mesh': 0, 'skin': 0}]
# Rigidly weighted boxes make this an articulated mechanical arm, without rubber-like joints.
parts = [(box((.12,-.095,-.095),(.98,.095,.095)),0,1),
         (box((1.23,-.075,-.075),(1.88,.075,.075)),1,2)]
# The last housing sits behind the exact end pivot so its gold socket marker remains visible.
for x,joint in [(0,0),(1.1,1),(1.8,2)]:
    parts.append((box((x-.12,-.13,-.14),(x+.12,.13,.14)),joint,0))
    parts.append((box((x-.07,-.07,.14),(x+.07,.07,.165)),joint,3))
# Small fork attached to the end joint, leaving the exact endpoint visible between the prongs.
for z in [-.16,.12]: parts.append((box((1.87,-.035,z),(2.27,.035,z+.04)),2,0))
arm.geometry(parts, True)
c,s = math.cos(ANGLE)/SCALE, math.sin(ANGLE)/SCALE
binds = [(c,0,s,0, 0,1/SCALE,0,0, -s,0,c,0, -x,-HEIGHT/SCALE,0,1) for x in (0,1.1,2)]
arm.model['skins'] = [{'name':'MechanicalArmRig','joints':[2,3,4],
                       'inverseBindMatrices':arm.accessor(binds,'16f',5126,'MAT4')}]
times = arm.accessor([(0.,),(2.,),(4.,)], 'f',5126,'SCALAR')
rotations = [(0,math.sin(a/2),0,math.cos(a/2)) for a in (ANGLE,-.65,ANGLE)]
rotation = arm.accessor(rotations, '4f',5126,'VEC4')
scales = arm.accessor([(1.1,)*3,(1.25,)*3,(1.1,)*3], '3f',5126,'VEC3')
arm.model['animations'] = [{'name':'idle','channels':[
    {'sampler':0,'target':{'node':1,'path':'rotation'}},
    {'sampler':1,'target':{'node':1,'path':'scale'}}], 'samplers':[
        {'input':times,'output':rotation,'interpolation':'LINEAR'},
        {'input':times,'output':scales,'interpolation':'LINEAR'}]}]
arm.save(); descriptor('mechanical_arm', True)

# A cyan open cage surrounds the target; the smaller gold end marker stays visible inside it.
target = Glb('ik_target_marker'); target.model['nodes'] = [{'name':'TargetMarker','mesh':0}]
parts = []
for axis in range(3):
    for a in (-.15,.15):
        for b in (-.15,.15):
            low,high = [],[]; others = iter((a,b))
            for i in range(3):
                q = next(others) if i != axis else 0
                low.append(-.16 if i == axis else q-.009)
                high.append(.16 if i == axis else q+.009)
            parts.append((box(low,high),0,3))
target.geometry(parts); target.save(); descriptor('ik_target_marker')
end = Glb('ik_end_marker'); end.model['nodes'] = [{'name':'EndMarker','mesh':0}]
ring = [(0,.06,0),(.06,0,0),(0,-.06,0),(-.06,0,0)]
triangles = []
for i in range(4):
    a,b = ring[i],ring[(i+1)%4]
    triangles.extend([((0,0,.06),b,a),((0,0,-.06),a,b)])
end.geometry([(triangles,0,3)]); end.save(); descriptor('ik_end_marker')

# Four opaque color bands: steel, blue upper link, orange lower link, tintable marker white.
palette = [(76,89,103,255),(47,155,214,255),(243,161,52,255),(255,255,255,255)]
raw = b''.join(b'\0'+b''.join(bytes(palette[x//4]) for x in range(16)) for _ in range(4))
def chunk(kind, payload):
    return struct.pack('>I',len(payload))+kind+payload+struct.pack('>I',zlib.crc32(kind+payload)&0xffffffff)
png = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,4,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b'')
(ROOT/'textures/mechanical_arm.png').write_bytes(png)
print('Generated three-joint mechanical arm, target cage, end marker, strict descriptors and 16x4 RGBA atlas')
