#!/usr/bin/env python3
"""Author a 3D wand using the original demo wand's repository-local rig and clips."""
from pathlib import Path
import copy
import json
import math
import struct

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/blendlib_runnable_examples'
REPOSITORY = Path(__file__).resolve().parents[4]
source = REPOSITORY / 'blendlib-showcase/src/main/resources/assets/blendlib_showcase/models3d/showcase_animation/showcase_actor.glb'
data = source.read_bytes()
length, kind = struct.unpack_from('<II', data, 12)
assert kind == 0x4e4f534a
model = json.loads(data[20:20 + length])
bin_length, bin_kind = struct.unpack_from('<II', data, 20 + length)
assert bin_kind == 0x004e4942
binary = bytearray(data[28 + length:28 + length + bin_length])


def accessor(values, fmt, component, shape, target):
    while len(binary) % 4:
        binary.append(0)
    offset = len(binary)
    for value in values:
        binary.extend(struct.pack('<' + fmt, *value))
    model['bufferViews'].append({'buffer': 0, 'byteOffset': offset,
                                'byteLength': len(binary) - offset, 'target': target})
    result = {'bufferView': len(model['bufferViews']) - 1, 'componentType': component,
              'count': len(values), 'type': shape}
    if shape == 'VEC3':
        result['min'] = [min(v[i] for v in values) for i in range(3)]
        result['max'] = [max(v[i] for v in values) for i in range(3)]
    model['accessors'].append(result)
    return len(model['accessors']) - 1


def primitive(triangles, material, tip=False):
    positions, normals = [], []
    for a, b, c in triangles:
        u, v = [b[i] - a[i] for i in range(3)], [c[i] - a[i] for i in range(3)]
        n = [u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0]]
        length = math.sqrt(sum(x*x for x in n))
        positions.extend([a, b, c]); normals.extend([tuple(x/length for x in n)] * 3)
    count = len(positions)
    weights = [(0., 1., 0., 0.) if tip else
               (1. - min(1., max(0., p[1] / 1.2)), min(1., max(0., p[1] / 1.2)), 0., 0.)
               for p in positions]
    return {'attributes': {
        'POSITION': accessor(positions, '3f', 5126, 'VEC3', 34962),
        'NORMAL': accessor(normals, '3f', 5126, 'VEC3', 34962),
        'TEXCOORD_0': accessor([(0., 0.), (1., 0.), (.5, 1.)] * (count//3), '2f', 5126, 'VEC2', 34962),
        'JOINTS_0': accessor([(0, 1, 0, 0)] * count, '4B', 5121, 'VEC4', 34962),
        'WEIGHTS_0': accessor(weights, '4f', 5126, 'VEC4', 34962)},
        'indices': accessor([(i,) for i in range(count)], 'H', 5123, 'SCALAR', 34963),
        'material': material}


# Slender square shaft, with outward-wound faces. Coordinates are source rig model units.
x, z, low, high = .055, .055, 0., 1.2
faces = [
    [(-x,low,z),(x,low,z),(x,high,z),(-x,high,z)],
    [(x,low,-z),(-x,low,-z),(-x,high,-z),(x,high,-z)],
    [(x,low,z),(x,low,-z),(x,high,-z),(x,high,z)],
    [(-x,low,-z),(-x,low,z),(-x,high,z),(-x,high,-z)],
    [(-x,high,z),(x,high,z),(x,high,-z),(-x,high,-z)],
    [(-x,low,-z),(x,low,-z),(x,low,z),(-x,low,z)],
]
shaft = [(f[0], f[1], f[2]) for f in faces] + [(f[0], f[2], f[3]) for f in faces]
# A separate eight-triangle crystal at the tip; the small gap makes hiding unambiguous.
ring = [(0.,1.4,.16),(.16,1.4,0.),(0.,1.4,-.16),(-.16,1.4,0.)]
crystal = []
for i in range(4):
    a,b = ring[i],ring[(i+1)%4]
    crystal.extend([((0.,1.60,0.),a,b),((0.,1.21,0.),b,a)])
model['meshes'][0]['primitives'] = [primitive(shaft, 0), primitive(crystal, 1, True)]
material = copy.deepcopy(model['materials'][0]); material['name'] = 'WandBody'
accessory = copy.deepcopy(material); accessory['name'] = 'WandAccessory'
model['materials'] = [material, accessory]
model['buffers'][0]['byteLength'] = len(binary)
model['asset']['generator'] = 'BlendLib generate_appearance_wand.py (authored shaft and tip crystal; original rig/clips)'
encoded = json.dumps(model, separators=(',', ':')).encode()
encoded += b' ' * (-len(encoded) % 4)
binary.extend(b'\x00' * (-len(binary) % 4))
result = (struct.pack('<III', 0x46546c67, 2, 28 + len(encoded) + len(binary))
          + struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
          + struct.pack('<II', len(binary), 0x004e4942) + binary)
(ROOT / 'models3d/appearance_wand.glb').write_bytes(result)
wand = json.loads((ROOT / 'blend_models/wand.json').read_text())
base = wand['materials']['ShowcaseAnimationSurface']
wand['materials'] = {'WandBody': copy.deepcopy(base), 'WandAccessory': copy.deepcopy(base)}
wand['mesh'] = 'blendlib_runnable_examples:models3d/appearance_wand.glb'
(ROOT / 'blend_models/appearance_wand.json').write_text(json.dumps(wand, indent=2) + '\n')
print('Generated authored 3D wand shaft and separate tip crystal with original rig/animations')
