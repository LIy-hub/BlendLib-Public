# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded opt-in CPU morph authoring boundary; never used by strict-v1/X5.

Blender's mesh exporter supplies dense Basis-relative POSITION/NORMAL deltas.
Actions on Key datablocks are discovered with their actual slots and written as
complete LINEAR/STEP weight channels, independently of native TRS replacement.
"""
from __future__ import annotations
import dataclasses
import json
import math
import re
import struct

PROFILE = 'blendlib:skinned_morph_cpu_v1'
MAX_TARGETS = 8
MAX_NODES = 128
MAX_CONTROLS = 1024
MAX_PAIRS = 1_000_000
MAX_FLOATS = 32_000_000
KEY_PATH = re.compile(r'^key_blocks\[("(?:[^"\\]|\\.)*")\]\.value$')


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def bounded_name(value, maximum):
    # Java String.length counts UTF-16 units; mirror it for supplementary characters.
    return (isinstance(value, str) and bool(value.strip())
            and len(value.encode('utf-16-le')) // 2 <= maximum
            and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value))


def validate_controls(controls):
    if not isinstance(controls, dict) or not 1 <= len(controls) <= MAX_CONTROLS:
        raise ValueError('morph_controls must contain 1..1024 named controls')
    seen = set()
    for alias, control in controls.items():
        if not bounded_name(alias, 256) or not re.fullmatch(r'[a-z0-9._-]+:[a-z0-9._/-]+', alias):
            raise ValueError('morph control aliases must be namespaced resource IDs')
        if any(part in ('', '.', '..') for part in alias.split(':', 1)[1].split('/')):
            raise ValueError('morph control alias has unsafe path segments')
        if not isinstance(control, dict) or set(control) != {'node', 'target', 'min_weight', 'max_weight'}:
            raise ValueError('morph control requires exactly node, target, min_weight and max_weight')
        node, target = control['node'], control['target']
        if not bounded_name(node, 1024) or any(p in ('', '.', '..') for p in node.split('/')):
            raise ValueError('morph control node must be an exact exported node path')
        if not bounded_name(target, 128):
            raise ValueError('morph control target must be an exact nonblank targetName')
        low, high = control['min_weight'], control['max_weight']
        if not finite(low) or not finite(high) or not -2 <= low <= 0 <= high <= 2:
            raise ValueError('morph weight interval must be finite, include zero and fit [-2,2]')
        if (node, target) in seen:
            raise ValueError('exactly one morph control is required per node/target')
        seen.add((node, target))
    return {(c['node'], c['target']): (float(c['min_weight']), float(c['max_weight'])) for c in controls.values()}


@dataclasses.dataclass
class Binding:
    obj: object
    owner: object
    action: object
    slot: object
    curves: tuple
    is_key: bool


def _owner_bindings(obj, owner, is_key):
    data = owner.animation_data
    if data is None:
        return []
    if data.use_tweak_mode:
        raise ValueError(f'{owner.name}: NLA tweak mode is unsupported')
    if len(data.drivers):
        raise ValueError(f'{owner.name}: drivers are unsupported in the CPU morph source subset')
    if data.action_blend_type != 'REPLACE' or data.action_influence != 1:
        raise ValueError(f'{owner.name}: Action blending/influence is unsupported')
    if data.use_nla and any(not t.mute for t in data.nla_tracks):
        raise ValueError(f'{owner.name}: active NLA mixing is unsupported; use muted tracks only as Action storage')
    candidates = []
    if data.action is not None:
        candidates.append((data.action, data.action_slot))
    for track in data.nla_tracks:
        for strip in track.strips:
            if strip.action is not None:
                if strip.type != 'CLIP':
                    raise ValueError(f'{owner.name}: unsupported NLA strip type')
                candidates.append((strip.action, strip.action_slot))
    seen, result = set(), []
    for action, slot in candidates:
        if slot is None or slot.target_id_type != ('KEY' if is_key else 'OBJECT'):
            raise ValueError(f'{action.name}: Action must have an explicitly associated matching datablock slot')
        identity = (action.name, slot.handle)
        if identity in seen:
            continue
        seen.add(identity)
        if is_key and (len(action.layers) != 1 or len(action.layers[0].strips) != 1):
            raise ValueError(f'{action.name}: multiple Key Action layers/strips are unsupported')
        bags = [bag for layer in action.layers for strip in layer.strips for bag in strip.channelbags if bag.slot_handle == slot.handle]
        if is_key and len(bags) != 1:
            raise ValueError(f'{action.name}: associated Key Action slot must have exactly one channelbag')
        result.append(Binding(obj, owner, action, slot, tuple(c for bag in bags for c in bag.fcurves), is_key))
    return result


def discover_bindings(objects):
    result = []
    for obj in sorted(objects, key=lambda value: value.name):
        result.extend(_owner_bindings(obj, obj, False))
        if obj.type == 'MESH' and obj.data.shape_keys is not None:
            result.extend(_owner_bindings(obj, obj.data.shape_keys, True))
    return sorted(result, key=lambda b: (b.action.name, b.obj.name, b.is_key, b.slot.handle))


def actions(bindings):
    result = {b.action.name: b.action for b in bindings}
    if len(result) > 256:
        raise ValueError('exported Action count exceeds 256')
    return tuple(result[name] for name in sorted(result))


def validate_source(objects, controls):
    """Fail closed for unproved morph evaluation and topology semantics."""
    validate_controls(controls)
    meshes = {}
    source_pairs = source_floats = 0
    for obj in objects:
        if obj.type != 'MESH':
            continue
        if any(mod.type != 'ARMATURE' for mod in obj.modifiers):
            raise ValueError(f'{obj.name}: CPU morph export supports only one Armature modifier; apply other modifiers first')
        if len(obj.modifiers) != 1:
            raise ValueError(f'{obj.name}: CPU morph mesh requires exactly one Armature modifier')
        mod = obj.modifiers[0]
        if mod.object is None or mod.object.data.pose_position != 'POSE':
            raise ValueError(f'{obj.name}: Armature must evaluate in pose position')
        if (not mod.show_viewport or not mod.show_render or mod.use_deform_preserve_volume
                or not mod.use_vertex_groups or mod.use_bone_envelopes or mod.vertex_group or mod.use_multi_modifier):
            raise ValueError(f'{obj.name}: unsupported Armature modifier deformation settings')
        key = obj.data.shape_keys
        if key is None:
            continue
        if not 2 <= len(key.key_blocks) <= MAX_TARGETS+1:
            raise ValueError(f'{obj.name}: CPU morph meshes require 1..8 targets')
        blocks = list(key.key_blocks)
        if not key.use_relative or key.reference_key != blocks[0]:
            raise ValueError(f'{obj.name}: only relative-to-Basis shape keys are supported')
        if not 1 <= len(blocks)-1 <= MAX_TARGETS:
            raise ValueError(f'{obj.name}: CPU morph meshes require 1..8 targets')
        if obj.show_only_shape_key:
            raise ValueError(f'{obj.name}: show_only_shape_key is unsupported')
        names = [block.name for block in blocks[1:]]
        if len(set(names)) != len(names) or any(not name.strip() for name in names):
            raise ValueError(f'{obj.name}: shape key target names must be nonblank and unique')
        for block in blocks[1:]:
            if block.relative_key != blocks[0] or block.vertex_group or block.mute:
                raise ValueError(f'{obj.name}/{block.name}: chained keys, per-key masks and muted targets are unsupported')
            if len(block.data) != len(obj.data.vertices) or not finite(float(block.value)):
                raise ValueError(f'{obj.name}/{block.name}: target cardinality or default is invalid')
            if any(not all(math.isfinite(v) for v in point.co) for point in block.data):
                raise ValueError(f'{obj.name}/{block.name}: nonfinite target geometry')
        # glTF material/UV/normal splits cannot exceed one vertex per source
        # loop. This conservative preflight runs before invoking Blender export.
        source_pairs += len(obj.data.loops) * len(names)
        source_floats += len(obj.data.loops) * (12*len(names) + 24)
        if source_pairs > MAX_PAIRS or source_floats > MAX_FLOATS:
            raise ValueError('aggregate CPU morph source vertex-target/float budget exceeded')
        meshes[obj.name] = {'names': names, 'defaults': [float(block.value) for block in blocks[1:]], 'object': obj}
    if not meshes or len(meshes) > MAX_NODES:
        raise ValueError('CPU morph profile requires 1..128 morph mesh nodes')
    return meshes


def weight_plan(bindings, meshes, fps):
    if not finite(fps) or fps <= 0:
        raise ValueError('effective FPS must be finite and positive')
    clips, reasons = {}, []
    float_slots = 0
    for binding in bindings:
        if not binding.is_key:
            continue
        action = binding.action
        info = meshes.get(binding.obj.name)
        if info is None:
            raise ValueError('Key Action has no exported morph target mesh')
        curves = {}
        kinds = set()
        for curve in binding.curves:
            match = KEY_PATH.fullmatch(curve.data_path)
            if not match or json.loads(match.group(1)) not in info['names'] or curve.array_index != 0:
                raise ValueError(f'{action.name}: unsupported Key animation property {curve.data_path}')
            name = json.loads(match.group(1))
            if name in curves:
                raise ValueError(f'{action.name}: duplicate shape key FCurve')
            if curve.mute or not curve.is_valid or len(curve.modifiers) or curve.extrapolation != 'CONSTANT':
                raise ValueError(f'{action.name}: modified, muted, invalid or nonconstant-extrapolated Key FCurve')
            if len(curve.keyframe_points) * (len(info['names'])+1) * 2 > MAX_FLOATS:
                raise ValueError('CPU morph keyframe float budget exceeded')
            points = list(curve.keyframe_points)
            frames = [float(p.co.x) for p in points]
            if not frames or any(not all(math.isfinite(v) for v in (*p.co, *p.handle_left, *p.handle_right)) for p in points) or any(b <= a for a, b in zip(frames, frames[1:])):
                raise ValueError(f'{action.name}: Key curve requires finite strictly increasing keyframes')
            kinds.update(p.interpolation for p in points[:-1] or points)
            curves[name] = curve
        if not curves:
            continue
        start, end = map(float, action.frame_range)
        if not finite(start) or not finite(end) or not 0 < (end-start)/fps <= 600:
            raise ValueError(f'{action.name}: weight clip must have a positive bounded duration')
        exact = len(kinds) == 1 and kinds <= {'LINEAR', 'CONSTANT'}
        maximum_samples = sum(len(c.keyframe_points) for c in curves.values()) + 2
        if not exact:
            maximum_samples += max(0, math.floor(end)-math.ceil(start)+1)
        float_slots += maximum_samples * (len(info['names'])+1) * 2
        if float_slots > MAX_FLOATS:
            raise ValueError('aggregate CPU morph sampled-key float budget exceeded')
        if exact:
            frames = sorted({start, end, *(float(p.co.x) for c in curves.values() for p in c.keyframe_points)})
            interpolation = 'STEP' if kinds == {'CONSTANT'} else 'LINEAR'
        else:
            # One glTF weights sampler cannot exactly combine STEP and LINEAR
            # components. Such mixtures and Bezier use an explicitly reported bake.
            if kinds - {'LINEAR', 'CONSTANT', 'BEZIER'}:
                raise ValueError(f'{action.name}: unsupported Key interpolation')
            frames = sorted({start, end, *(float(f) for f in range(math.ceil(start), math.floor(end)+1)),
                             *(float(p.co.x) for c in curves.values() for p in c.keyframe_points)})
            interpolation = 'LINEAR'
            reasons.append({'action': action.name, 'node': binding.obj.name,
                'reason': 'Bezier or mixed weight interpolation requires sampled LINEAR fallback',
                'sample_cadence_frames': 1, 'sample_cadence_seconds': 1/fps})
        rows = [[float(curves[name].evaluate(frame)) if name in curves else info['defaults'][i]
                 for i, name in enumerate(info['names'])] for frame in frames]
        if any(not math.isfinite(v) for row in rows for v in row):
            raise ValueError(f'{action.name}: nonfinite evaluated morph weight')
        for row in rows:
            for name, value in zip(info['names'], row):
                block = binding.owner.key_blocks[name]
                if not block.slider_min <= value <= block.slider_max:
                    raise ValueError(f'{action.name}/{name}: animated morph weight exceeds the Blender slider range; expand the source slider explicitly')
        channel = {'node': binding.obj.name, 'path': 'weights', 'times': [(f-start)/fps for f in frames],
                   'interpolation': interpolation, 'values': rows}
        prior = clips.setdefault(action.name, [])
        if any(item['node'] == channel['node'] for item in prior):
            raise ValueError(f'{action.name}: multiple Action slots bind the same morph mesh')
        prior.append(channel)
    return clips, reasons


def append_weight_animations(gltf, binary, clips):
    """Replace only raw weight channels; retain every converted/fallback TRS channel."""
    output = bytearray(binary)
    def accessor(values):
        output.extend(b'\0' * (-len(output) % 4)); offset = len(output)
        output.extend(struct.pack('<'+'f'*len(values), *values))
        view = len(gltf.setdefault('bufferViews', []))
        gltf['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(output)-offset})
        index = len(gltf.setdefault('accessors', []))
        gltf['accessors'].append({'bufferView': view, 'componentType': 5126, 'count': len(values), 'type': 'SCALAR',
                                  'min': [min(values)], 'max': [max(values)]})
        return index
    animations = {}
    for old in gltf.get('animations', []):
        channels = [c for c in old['channels'] if c['target']['path'] != 'weights']
        if channels:
            if old['name'] in animations:
                raise ValueError('duplicate exported TRS Action names')
            animations[old['name']] = dict(old, channels=channels)
    node_indices = {n['name']: i for i, n in enumerate(gltf['nodes'])}
    for name, channels in sorted(clips.items()):
        animation = animations.setdefault(name, {'name': name, 'channels': [], 'samplers': []})
        for channel in channels:
            if channel['node'] not in node_indices:
                raise ValueError('morph animation node omitted by glTF exporter')
            sampler = len(animation['samplers'])
            animation['samplers'].append({'input': accessor(channel['times']),
                'output': accessor([v for row in channel['values'] for v in row]), 'interpolation': channel['interpolation']})
            animation['channels'].append({'sampler': sampler, 'target': {'node': node_indices[channel['node']], 'path': 'weights'}})
    # Remove unreferenced raw samplers, in particular cubic weight samplers.
    for animation in animations.values():
        used = sorted({c['sampler'] for c in animation['channels']})
        remap = {old: new for new, old in enumerate(used)}
        animation['samplers'] = [animation['samplers'][old] for old in used]
        for channel in animation['channels']:
            channel['sampler'] = remap[channel['sampler']]
    if animations:
        gltf['animations'] = [animations[name] for name in sorted(animations)]
    else:
        gltf.pop('animations', None)
    gltf['buffers'] = [{'byteLength': len(output)}]
    return bytes(output)


def validate_gltf(gltf, binary, controls, paths, read_accessor, source_meshes=None):
    """Validate cardinality/budgets before target decoding, then controls and curves."""
    intervals = validate_controls(controls)
    bindings, pairs, floats = {}, 0, 0
    meshes = gltf.get('meshes', [])
    accessors = gltf.get('accessors', [])
    def accessor_info(index):
        if type(index) is not int or not 0 <= index < len(accessors):
            raise ValueError('morph accessor index is invalid')
        accessor = accessors[index]
        if not isinstance(accessor, dict) or type(accessor.get('count')) is not int or accessor['count'] < 1:
            raise ValueError('morph accessor count is invalid')
        return accessor
    def declared_count(index):
        accessor = accessor_info(index)
        if 'sparse' in accessor or accessor.get('normalized', False) or accessor.get('componentType') != 5126 or accessor.get('type') != 'VEC3' or type(accessor.get('count')) is not int or accessor['count'] < 1:
            raise ValueError('morph base/target requires dense non-normalized FLOAT VEC3')
        return accessor['count']
    # Mirror the Java metadata-only preparation budget before decoding dense targets.
    uses = [0] * len(meshes)
    for node in gltf['nodes']:
        if 'mesh' in node:
            if type(node['mesh']) is not int or not 0 <= node['mesh'] < len(meshes):
                raise ValueError('invalid mesh reference')
            uses[node['mesh']] += 1
    floats = len(gltf['nodes']) * 96
    for mesh_index, mesh in enumerate(meshes):
        target_count = 0
        for primitive in mesh['primitives']:
            target_count = len(primitive.get('targets', []))
            count = declared_count(primitive['attributes']['POSITION'])
            floats += 40 * count + 12 * count * target_count + 80 * count * uses[mesh_index]
        if target_count:
            floats += target_count * (2 + 12 * uses[mesh_index])
    for skin_index, skin in enumerate(gltf.get('skins', [])):
        joints = len(skin['joints'])
        floats += 64 * joints
        for node in gltf['nodes']:
            if node.get('skin') == skin_index and 'mesh' in node:
                floats += 128 * joints * len(meshes[node['mesh']]['primitives'])
    for animation in gltf.get('animations', []):
        samplers = animation['samplers']
        for sampler in samplers:
            floats += accessor_info(sampler['input'])['count']
        for channel in animation['channels']:
            sampler = samplers[channel['sampler']]
            output = accessor_info(sampler['output'])
            width = {'SCALAR': 1, 'VEC3': 3, 'VEC4': 4}.get(output['type'])
            if width is None:
                raise ValueError('animation accessor shape is invalid')
            floats += 3 * output['count'] * width + accessor_info(sampler['input'])['count']
    if floats > MAX_FLOATS:
        raise ValueError('aggregate CPU morph float budget exceeded')
    for index, node in enumerate(gltf['nodes']):
        if 'mesh' not in node:
            if 'weights' in node:
                raise ValueError('weights require a morph mesh node')
            continue
        mesh = meshes[node['mesh']]
        primitives = mesh['primitives']
        names = mesh.get('extras', {}).get('targetNames')
        has_targets = any('targets' in p for p in primitives)
        if not has_targets:
            if 'weights' in node or 'weights' in mesh or names is not None:
                raise ValueError('morph defaults/names require morph targets')
            continue
        if index not in paths or 'skin' not in node:
            raise ValueError('morph nodes must be active and skinned')
        if not isinstance(names, list) or not 1 <= len(names) <= MAX_TARGETS or any(not bounded_name(n, 128) for n in names) or len(set(names)) != len(names):
            raise ValueError('mesh extras.targetNames must contain 1..8 unique nonblank ordered names')
        if source_meshes is not None:
            source = source_meshes.get(node['name'])
            if source is None or names != source['names']:
                raise ValueError('exported targetNames order differs from source Basis-relative keys')
        for primitive in primitives:
            targets = primitive.get('targets')
            if not isinstance(targets, list) or len(targets) != len(names):
                raise ValueError('material-split primitives must have identical target counts/order')
            count = declared_count(primitive['attributes']['POSITION'])
            if declared_count(primitive['attributes']['NORMAL']) != count:
                raise ValueError('base POSITION/NORMAL counts disagree')
            pairs += count * len(names)
            if pairs > MAX_PAIRS or floats > MAX_FLOATS:
                raise ValueError('aggregate CPU morph vertex-target/float budget exceeded')
            for target in targets:
                if not isinstance(target, dict) or set(target) != {'POSITION', 'NORMAL'}:
                    raise ValueError('dense morph targets require exactly POSITION and NORMAL; tangents are unsupported')
                if any(declared_count(target[semantic]) != count for semantic in ('POSITION', 'NORMAL')):
                    raise ValueError('target accessor count differs from base POSITION')
        defaults = node.get('weights', mesh.get('weights', [0.0]*len(names)))
        for owner in (node, mesh):
            if 'weights' in owner and (not isinstance(owner['weights'], list) or len(owner['weights']) != len(names) or any(not finite(v) for v in owner['weights'])):
                raise ValueError('morph defaults must be finite and match the target count')
        ranges = []
        for target, value in zip(names, defaults):
            key = (paths[index], target)
            if key not in intervals:
                raise ValueError('exactly one morph control is required for every node/target')
            low, high = intervals[key]
            if not low <= value <= high:
                raise ValueError('morph default lies outside its declared interval')
            ranges.append((low, high))
        bindings[index] = (names, defaults, ranges)
    if not 1 <= len(bindings) <= MAX_NODES:
        raise ValueError('CPU morph profile requires 1..128 morph nodes')
    expected = {(paths[index], name) for index, (names, _, _) in bindings.items() for name in names}
    if set(intervals) != expected:
        raise ValueError('morph controls must cover exactly the exported node/target pairs')
    # Decode only after the entire expanded scene has passed aggregate accounting.
    for skin in gltf.get('skins', []):
        matrices = read_accessor(gltf, binary, skin['inverseBindMatrices'])
        if any(row[3] != 0 or row[7] != 0 or row[11] != 0 or row[15] != 1 for row in matrices['values']):
            raise ValueError('CPU morph inverse-bind matrices require exact affine fourth row')
    for index, (_, _, ranges) in bindings.items():
        mesh = meshes[gltf['nodes'][index]['mesh']]
        for primitive in mesh['primitives']:
            base = read_accessor(gltf, binary, primitive['attributes']['NORMAL'])['values']
            delta_normals = [read_accessor(gltf, binary, target['NORMAL'])['values'] for target in primitive['targets']]
            for target in primitive['targets']:
                read_accessor(gltf, binary, target['POSITION'])
            for vertex, normal in enumerate(base):
                base_norm = math.hypot(*normal)
                delta_radius = sum(max(abs(lo), abs(hi))*math.hypot(*d[vertex]) for d, (lo, hi) in zip(delta_normals, ranges))
                lower = base_norm - delta_radius
                if not math.isfinite(delta_radius) or lower < 1e-5 + 1e-12 * (base_norm + delta_radius):
                    raise ValueError('source normal cannot be proved nondegenerate throughout all weight intervals')
    for animation in gltf.get('animations', []):
        seen = set()
        for channel in animation['channels']:
            if channel['target']['path'] != 'weights':
                continue
            node = channel['target'].get('node')
            if node not in bindings or node in seen:
                raise ValueError('weight channel node must be a unique morph binding in its clip')
            seen.add(node)
            sampler = animation['samplers'][channel['sampler']]
            if sampler.get('interpolation', 'LINEAR') not in {'LINEAR', 'STEP'}:
                raise ValueError('CPU morph weight interpolation must be LINEAR or STEP')
            times = read_accessor(gltf, binary, sampler['input'])
            output = read_accessor(gltf, binary, sampler['output'])
            values = [v[0] for v in times['values']]
            names, _, ranges = bindings[node]
            if times['component_type'] != 5126 or times['type'] != 'SCALAR' or output['component_type'] != 5126 or output['type'] != 'SCALAR' or times['normalized'] or output['normalized'] or len(values) < 2 or values[0] < 0 or any(b <= a for a, b in zip(values, values[1:])) or output['count'] != len(values)*len(names):
                raise ValueError('weight animation requires increasing FLOAT SCALAR times and key-major target values')
            for i, row in enumerate(output['values']):
                low, high = ranges[i % len(names)]
                if not low <= row[0] <= high:
                    raise ValueError('animated morph weight lies outside its declared interval')
    return {'morph_node_count': len(bindings), 'morph_control_count': len(intervals), 'vertex_target_pairs': pairs,
            'reserved_float_slots': floats}
