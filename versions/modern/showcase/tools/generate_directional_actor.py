#!/usr/bin/env python3
"""Author phase-aligned unequal-duration loops on the local two-slot actor fixture."""
from pathlib import Path
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
    binary.extend(b'\x00' * (-len(binary) % 4))
    offset = len(binary)
    binary.extend(struct.pack('<' + 'f' * len(values), *values))
    model['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(values) * 4})
    result = {'bufferView': len(model['bufferViews']) - 1, 'componentType': 5126,
              'count': len(values) // width, 'type': kind}
    if width == 1:
        result.update(min=[min(values)], max=[max(values)])
    model['accessors'].append(result)
    return len(model['accessors']) - 1


root = next(i for i, node in enumerate(model['nodes']) if node['name'] == 'ShowcaseRootBone')
tip = next(i for i, node in enumerate(model['nodes']) if node['name'] == 'ShowcaseTipBone')
# Keep the independent original upper attack. Replace idle/walk only in this derivative.
model['animations'] = [clip for clip in model['animations'] if clip['name'] == 'attack']
# All loops have identical contact/bob phase conventions, not inferred by the runtime.
# Max root excursion remains below the original .07-unit attachment envelope budget.
loops = [('idle', 2.0, .005, .003, .04, .5),
         ('forward', 1.0, .025, .012, .22, 1.5),
         ('left', .5, .045, .020, -.35, 2.0),
         ('back', 1.5, -.025, .010, -.22, .75),
         ('right', 2.5, -.045, .015, .35, 1.25)]
for name, duration, x_amplitude, y_amplitude, angle, speed in loops:
    phases = [i / 32 for i in range(33)]
    times = [duration * phase for phase in phases]
    # Exact repeated endpoint prevents a tiny numerical seam in authored loop channels.
    cycles = [2 * math.pi * phase if phase < 1 else 0 for phase in phases]
    positions = [value for cycle in cycles for value in
                 (x_amplitude * math.sin(cycle), y_amplitude * (1 - math.cos(cycle)), 0)]
    rotations = [value for cycle in cycles for value in
                 (0, 0, math.sin(angle * math.sin(cycle) / 2), math.cos(angle * math.sin(cycle) / 2))]
    time_index = accessor(times, 1, 'SCALAR')
    position_index = accessor(positions, 3, 'VEC3')
    rotation_index = accessor(rotations, 4, 'VEC4')
    model['animations'].append({'name': name, 'channels': [
        {'sampler': 0, 'target': {'node': root, 'path': 'translation'}},
        {'sampler': 1, 'target': {'node': tip, 'path': 'rotation'}}],
        'samplers': [{'input': time_index, 'output': position_index, 'interpolation': 'LINEAR'},
                     {'input': time_index, 'output': rotation_index, 'interpolation': 'LINEAR'}]})
model['buffers'][0]['byteLength'] = len(binary)
model['asset']['generator'] = 'BlendLib generate_directional_actor.py (repository-local derivative)'
encoded = json.dumps(model, separators=(',', ':')).encode()
encoded += b' ' * (-len(encoded) % 4)
binary.extend(b'\x00' * (-len(binary) % 4))
result = (struct.pack('<III', 0x46546c67, 2, 28 + len(encoded) + len(binary))
          + struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
          + struct.pack('<II', len(binary), 0x004e4942) + binary)
(ROOT / 'models3d/directional_actor.glb').write_bytes(result)
descriptor = json.loads((ROOT / 'blend_models/appearance_actor.json').read_text())
descriptor['animation']['states'].pop('blendlib_runnable_examples:walk', None)
descriptor['mesh'] = 'blendlib_runnable_examples:models3d/directional_actor.glb'
for name, duration, _, _, _, speed in loops:
    # There are deliberately no footstep markers: synchronized layer-event observations
    # are not a gait/contact-event deduplication system.
    descriptor['animation']['states']['blendlib_runnable_examples:' + name] = {
        'blend_seconds': 0.0, 'clip': name, 'loop': True, 'speed': speed}
(ROOT / 'blend_models/directional_actor.json').write_text(json.dumps(descriptor, indent=2) + '\n')
print('Generated directional_actor.glb and descriptor: five phase-aligned unequal-duration directions')
