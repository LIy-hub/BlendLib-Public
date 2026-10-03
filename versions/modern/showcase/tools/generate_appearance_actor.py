#!/usr/bin/env python3
"""Derive a two-slot skinned example without changing the original Showcase copy."""
from pathlib import Path
import copy
import json
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

# A small side badge shares the original quad's normals, UVs, skin weights and indices.
# Its own positions create actual separate geometry, not an unused descriptor entry.
primitive = copy.deepcopy(model['meshes'][0]['primitives'][0])
position = model['accessors'][primitive['attributes']['POSITION']]
view = model['bufferViews'][position['bufferView']]
points = [struct.unpack_from('<3f', binary, view.get('byteOffset', 0) + i * 12)
          for i in range(position['count'])]
points = [(0.39 + x * 0.48, 0.52 + y * 0.24, z + 0.025) for x, y, z in points]
while len(binary) % 4:
    binary.append(0)
offset = len(binary)
for point in points:
    binary.extend(struct.pack('<3f', *point))
model['bufferViews'].append({'buffer': 0, 'byteOffset': offset,
                             'byteLength': len(points) * 12, 'target': 34962})
model['accessors'].append({'bufferView': len(model['bufferViews']) - 1,
                          'componentType': 5126, 'count': len(points), 'type': 'VEC3',
                          'min': [min(p[i] for p in points) for i in range(3)],
                          'max': [max(p[i] for p in points) for i in range(3)]})
primitive['attributes']['POSITION'] = len(model['accessors']) - 1
primitive['material'] = len(model['materials'])
accessory = copy.deepcopy(model['materials'][0])
accessory['name'] = 'ExampleAccessory'
model['materials'].append(accessory)
model['meshes'][0]['primitives'].append(primitive)
model['buffers'][0]['byteLength'] = len(binary)
model['asset']['generator'] = 'BlendLib generate_appearance_actor.py (repository-local derivative)'
encoded = json.dumps(model, separators=(',', ':')).encode()
encoded += b' ' * (-len(encoded) % 4)
binary.extend(b'\x00' * (-len(binary) % 4))
result = (struct.pack('<III', 0x46546c67, 2, 28 + len(encoded) + len(binary))
          + struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
          + struct.pack('<II', len(binary), 0x004e4942) + binary)
(ROOT / 'models3d').mkdir(exist_ok=True)
(ROOT / 'models3d/appearance_actor.glb').write_bytes(result)
descriptor = json.loads((ROOT / 'blend_models/actor.json').read_text())
descriptor['mesh'] = 'blendlib_runnable_examples:models3d/appearance_actor.glb'
descriptor['materials']['ExampleAccessory'] = copy.deepcopy(descriptor['materials']['ShowcaseAnimationSurface'])
(ROOT / 'blend_models/appearance_actor.json').write_text(json.dumps(descriptor, indent=2) + '\n')
print('Generated appearance_actor.glb and descriptor with two rendered material slots')
