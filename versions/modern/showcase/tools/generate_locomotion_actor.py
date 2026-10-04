#!/usr/bin/env python3
"""Add an authored run loop to the local appearance actor, preserving its original clips."""
from pathlib import Path
import copy
import json
import math
import struct

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/blendlib_runnable_examples'
data = (ROOT / 'models3d/appearance_actor.glb').read_bytes()
length, kind = struct.unpack_from('<II', data, 12)
assert kind == 0x4e4f534a
model = json.loads(data[20:20 + length])
bin_length, bin_kind = struct.unpack_from('<II', data, 20 + length)
assert bin_kind == 0x004e4942
binary = bytearray(data[28 + length:28 + length + bin_length])


def accessor(values, width, kind):
    while len(binary) % 4:
        binary.append(0)
    offset = len(binary)
    binary.extend(struct.pack('<' + 'f' * len(values), *values))
    model['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(values) * 4})
    result = {'bufferView': len(model['bufferViews']) - 1, 'componentType': 5126,
              'count': len(values) // width, 'type': kind}
    if width == 1:
        result.update(min=[min(values)], max=[max(values)])
    model['accessors'].append(result)
    return len(model['accessors']) - 1


# A half-second run cycle has a distinct fore/aft translation and tip rotation. Its
# root-translation radius never exceeds the original .07 actor/attachment bound.
times = [i / 48 for i in range(25)]
positions = [v for t in times for v in (.045 * math.sin(t * 4 * math.pi),
                                      .020 * (1 - math.cos(t * 8 * math.pi)), 0)]
rotations = [v for t in times for v in (0, 0, math.sin(.20 * math.sin(t * 4 * math.pi)),
                                      math.cos(.20 * math.sin(t * 4 * math.pi)))]
time_index = accessor(times, 1, 'SCALAR')
position_index = accessor(positions, 3, 'VEC3')
rotation_index = accessor(rotations, 4, 'VEC4')
root = next(i for i, n in enumerate(model['nodes']) if n['name'] == 'ShowcaseRootBone')
tip = next(i for i, n in enumerate(model['nodes']) if n['name'] == 'ShowcaseTipBone')
model['animations'].append({'name': 'run', 'channels': [
    {'sampler': 0, 'target': {'node': root, 'path': 'translation'}},
    {'sampler': 1, 'target': {'node': tip, 'path': 'rotation'}}],
    'samplers': [{'input': time_index, 'output': position_index, 'interpolation': 'LINEAR'},
                 {'input': time_index, 'output': rotation_index, 'interpolation': 'LINEAR'}]})
model['buffers'][0]['byteLength'] = len(binary)
model['asset']['generator'] = 'BlendLib generate_locomotion_actor.py (repository-local derivative)'
encoded = json.dumps(model, separators=(',', ':')).encode()
encoded += b' ' * (-len(encoded) % 4)
binary.extend(b'\x00' * (-len(binary) % 4))
result = (struct.pack('<III', 0x46546c67, 2, 28 + len(encoded) + len(binary))
          + struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
          + struct.pack('<II', len(binary), 0x004e4942) + binary)
(ROOT / 'models3d/locomotion_actor.glb').write_bytes(result)
descriptor = json.loads((ROOT / 'blend_models/appearance_actor.json').read_text())
descriptor['mesh'] = 'blendlib_runnable_examples:models3d/locomotion_actor.glb'
descriptor['animation']['states']['blendlib_runnable_examples:run'] = {
    'blend_seconds': 0.12, 'clip': 'run', 'loop': True, 'speed': 1.0}
(ROOT / 'blend_models/locomotion_actor.json').write_text(json.dumps(descriptor, indent=2) + '\n')
print('Generated locomotion_actor.glb and descriptor with actual idle/walk/run loops')
