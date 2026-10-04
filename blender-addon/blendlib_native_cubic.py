# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Opt-in exact native-FCurve subset for ``blendlib:skinned_cubic_v1``.

The ordinary strict-v1 exporter never imports or executes this module. Blender's
unsampled exporter is used for the scene, but its quaternion tangent conversion
normalizes control points. We therefore serialize eligible authored components
analytically, including rest-bone transforms and derivatives per *second*.
Anything not proved representable uses the existing sampled LINEAR exporter.
"""
from __future__ import annotations
import dataclasses
import json
import math
import re
import struct
from typing import Any, Sequence

PROFILE = "blendlib:skinned_cubic_v1"
BONE_PATH = re.compile(r'^pose\.bones\[("(?:[^"\\]|\\.)*")\]\.(location|rotation_quaternion|scale)$')
PATHS = {"location": "translation", "rotation_quaternion": "rotation", "scale": "scale"}
TOLERANCE = 1e-5
FLOAT_SAFETY_LIMIT = 3.4028234663852886e38 / 4
HEMISPHERE_MARGIN = 1e-5
MIN_SCALE = 1e-6


@dataclasses.dataclass
class NativePlan:
    native: bool
    clips: list[dict]
    reasons: list[dict]
    fps: float

    def report(self) -> dict:
        return {"profile": PROFILE, "mode": "native_fcurves" if self.native else "baked_linear_fallback",
                "exact_native_subset": self.native, "effective_fps": self.fps,
                "fallback_reasons": self.reasons,
                "channels": [{"clip": clip["name"], "node": channel["node"],
                              "path": channel["path"], "interpolation": channel["interpolation"],
                              "key_count": len(channel["times"])}
                             for clip in self.clips for channel in clip["channels"]] if self.native else [],
                "scope": "Native requires synchronized transform components and linear-in-time Bezier handles. "
                         "Unsupported sources bake the entire asset at scene-frame cadence; this fallback is approximate."}


def _near(a: float, b: float) -> bool:
    return abs(a - b) <= TOLERANCE * max(1.0, abs(a), abs(b))


def _uniform(values: Sequence[float]) -> bool:
    return len(values) == 3 and min(values) > 0 and all(_near(values[0], x) for x in values)


def _qmul(a: Sequence[float], b: Sequence[float]) -> tuple[float, ...]:
    w, x, y, z = a
    v, i, j, k = b
    return (w*v-x*i-y*j-z*k, w*i+x*v+y*k-z*j, w*j-x*k+y*v+z*i, w*k+x*j-y*i+z*v)


def _transform(obj: Any, bone: Any, prop: str):
    from mathutils import Matrix, Vector
    yup = Matrix(((1,0,0,0), (0,0,1,0), (0,-1,0,0), (0,0,0,1)))
    if bone is not None:
        data = bone.bone
        if not data.use_local_location or not data.use_inherit_rotation or data.inherit_scale != 'FULL':
            raise ValueError("nonstandard bone inheritance")
        transform = (bone.parent.bone.matrix_local.inverted() @ data.matrix_local
                     if bone.parent else yup @ data.matrix_local)
    else:
        if obj.parent_type != 'OBJECT' and obj.parent is not None:
            raise ValueError("bone or vertex object parenting")
        transform = obj.matrix_parent_inverse.copy() if obj.parent else Matrix.Identity(4)
    location, rotation, scale = transform.decompose()
    if not _uniform(scale):
        raise ValueError("nonuniform or negative parent/rest transform")
    reconstructed = Matrix.LocRotScale(location, rotation, scale)
    if any(abs(transform[row][col] - reconstructed[row][col]) > TOLERANCE
           for row in range(4) for col in range(4)):
        raise ValueError("sheared parent/rest transform")
    q = tuple(rotation)
    def convert(value, derivative=False):
        if prop == 'location':
            result = transform.to_3x3() @ Vector(value)
            if not derivative:
                result += location
            if bone is None:
                result = yup.to_3x3() @ result
            return tuple(result)
        if prop == 'scale':
            result = tuple(scale[0] * value[i] for i in range(3))
            return result if bone is not None else (result[0], result[2], result[1])
        result = _qmul(q, value)  # Never normalize derivatives or change quaternion signs.
        if bone is None:
            result = (result[0], result[1], result[3], -result[2])
        return (result[1], result[2], result[3], result[0])
    return convert


def _channel(obj: Any, bone: Any, prop: str, curves: list[Any], start: float, fps: float) -> dict:
    if bone is not None and bone.parent is not None and bone.bone.use_connect and prop == 'location':
        raise ValueError("connected child bone location is ignored by Blender and requires baking")
    width = 4 if prop == 'rotation_quaternion' else 3
    if sorted(c.array_index for c in curves) != list(range(width)):
        raise ValueError("all transform components must be keyed")
    curves = sorted(curves, key=lambda curve: curve.array_index)
    if any(len(curve.modifiers) or curve.mute or not curve.is_valid for curve in curves):
        raise ValueError("modified, muted, or invalid FCurve")
    if any(curve.extrapolation != 'CONSTANT' for curve in curves):
        raise ValueError("nonconstant FCurve extrapolation requires baking")
    keys = [list(curve.keyframe_points) for curve in curves]
    frames = [float(key.co.x) for key in keys[0]]
    if len(frames) < 2 or any(not math.isfinite(frame) for frame in frames) or any(b <= a for a, b in zip(frames, frames[1:])):
        raise ValueError("at least two finite strictly increasing keys are required")
    if any([float(key.co.x) for key in component] != frames for component in keys):
        raise ValueError("transform components have unequal key times")
    kinds = {key.interpolation for component in keys for key in component}
    if len(kinds) != 1 or next(iter(kinds)) not in {'BEZIER', 'LINEAR', 'CONSTANT'}:
        raise ValueError("mixed or unsupported interpolation")
    kind = next(iter(kinds))
    if prop == 'rotation_quaternion':
        target = bone if bone is not None else obj
        if target.rotation_mode != 'QUATERNION':
            raise ValueError("quaternion curves require quaternion rotation mode")
        if kind == 'LINEAR':
            raise ValueError("Blender component-linear quaternion is not glTF spherical-linear")
    values = [tuple(float(component[i].co.y) for component in keys) for i in range(len(frames))]
    if any(not math.isfinite(v) for row in values for v in row):
        raise ValueError("nonfinite transform values")
    if prop == 'rotation_quaternion' and any(not _near(sum(v*v for v in row), 1.0) for row in values):
        raise ValueError("quaternion key values must be unit length")
    incoming, outgoing = [], []
    for i, frame in enumerate(frames):
        left = tuple(float(component[i].handle_left.y) for component in keys)
        right = tuple(float(component[i].handle_right.y) for component in keys)
        if kind == 'BEZIER':
            if any(not math.isfinite(v) for component in keys for v in (*component[i].handle_left, *component[i].handle_right)):
                raise ValueError("nonfinite Bezier handles")
            if i and any(abs(float(component[i].handle_left.x) - (frame - (frame - frames[i-1])/3)) > TOLERANCE * max(1.0, frame-frames[i-1]) for component in keys):
                raise ValueError("Bezier incoming time handle is not one third of segment")
            if i + 1 < len(frames) and any(abs(float(component[i].handle_right.x) - (frame + (frames[i+1] - frame)/3)) > TOLERANCE * max(1.0, frames[i+1]-frame) for component in keys):
                raise ValueError("Bezier outgoing time handle is not one third of segment")
        incoming.append(tuple(3*(values[i][j]-left[j])*fps/(frame-frames[i-1]) if i else 0.0 for j in range(width)))
        outgoing.append(tuple(3*(right[j]-values[i][j])*fps/(frames[i+1]-frame) if i+1 < len(frames) else 0.0 for j in range(width)))
    convert = _transform(obj, bone, prop)
    converted = [convert(value) for value in values]
    tangents_in = [convert(value, True) for value in incoming]
    tangents_out = [convert(value, True) for value in outgoing]
    interpolation = {'BEZIER': 'CUBICSPLINE', 'LINEAR': 'LINEAR', 'CONSTANT': 'STEP'}[kind]
    times = [(frame-start)/fps for frame in frames]
    rows = [row for i, value in enumerate(converted) for row in (tangents_in[i], value, tangents_out[i])] if kind == 'BEZIER' else converted
    channel = {"node": bone.name if bone else obj.name, "path": PATHS[prop], "times": times,
               "interpolation": interpolation, "values": rows}
    validate_channel(channel)
    return channel


def validate_channel(channel: dict) -> None:
    """Conservative Bezier-control-hull guard, also used on emitted GLB data."""
    values, times, path = channel['values'], channel['times'], channel['path']
    cubic = channel['interpolation'] == 'CUBICSPLINE'
    if any(not math.isfinite(v) or abs(v) > FLOAT_SAFETY_LIMIT*4 for row in values for v in row):
        raise ValueError("animation output cannot be represented as finite float")
    if len(times) < 2 or any(not math.isfinite(t) or t < 0 for t in times) or any(b <= a for a,b in zip(times, times[1:])):
        raise ValueError("invalid native animation times")
    if len(values) != len(times) * (3 if cubic else 1):
        raise ValueError("invalid animation output cardinality")
    all_controls = []
    for i in range(len(times)-1):
        dt = times[i+1]-times[i]
        if cubic:
            first, last = values[3*i+1], values[3*(i+1)+1]
            controls = [first, tuple(a + dt*b/3 for a,b in zip(first, values[3*i+2])),
                        tuple(a - dt*b/3 for a,b in zip(last, values[3*(i+1)])), last]
        else:
            controls = [values[i], values[i+1]]
        all_controls.extend(controls)
        if any(not math.isfinite(v) or abs(v) > FLOAT_SAFETY_LIMIT for row in controls for v in row):
            raise ValueError("Bezier control exceeds finite float safety bound")
        if path == 'translation' and any(math.hypot(*row) > FLOAT_SAFETY_LIMIT for row in controls):
            raise ValueError("translation Bezier hull exceeds safe bounds")
        if path == 'scale' and any(min(row) < MIN_SCALE or not all(v == row[0] for v in row) for row in controls):
            raise ValueError("scale Bezier controls must be positive and exactly uniform")
        if path == 'rotation':
            for endpoint in (controls[0], controls[-1]):
                if abs(math.hypot(*endpoint) - 1) > 1e-4:
                    raise ValueError("quaternion key values must be unit length")
    if path == 'scale' and any(not all(v == row[0] for v in row) for row in values):
        raise ValueError("scale values and tangents must be exactly component-equal")
    if path == 'rotation' and cubic:
        first = values[1]
        direction = tuple(v/math.hypot(*first) for v in first)
        margin = HEMISPHERE_MARGIN + 1e-12 * max(math.hypot(*row) for row in all_controls)
        if any(sum(a*b for a,b in zip(direction, row)) < margin for row in all_controls):
            raise ValueError("quaternion controls lack a common positive hemisphere")


def analyze(objects: Sequence[Any], actions: Sequence[Any], fcurves, scene: Any) -> NativePlan:
    fps = float(scene.render.fps) / float(scene.render.fps_base)
    reasons, clips = [], []
    def fail(action, node, reason):
        reasons.append({"action": action, "node": node, "reason": reason})
    import bpy
    if bpy.app.version != (5, 1, 2):
        fail(None, None, "native coordinate conversion is verified only for Blender 5.1.2")
    if not math.isfinite(fps) or fps <= 0:
        fail(None, None, "effective FPS must be finite and positive")
        return NativePlan(False, [], reasons, fps)
    object_set = set(objects)
    for obj in objects:
        if obj.parent is not None and obj.parent not in object_set:
            fail(None, obj.name, "parent outside export collection requires baking")
        data = obj.animation_data
        if data is not None and len(data.drivers):
            fail(None, obj.name, "drivers require dependency-graph baking")
        if data is not None and data.use_nla and any(not track.mute for track in data.nla_tracks):
            fail(None, obj.name, "active NLA mixing requires baking")
        if data is not None and (data.action_blend_type != 'REPLACE' or data.action_influence != 1):
            fail(None, obj.name, "Action blending or influence requires baking")
        if obj.type == 'ARMATURE':
            for bone in obj.pose.bones:
                if len(bone.constraints):
                    fail(None, bone.name, "bone constraints require dependency-graph baking")
                if bone.bone.bbone_segments != 1:
                    fail(None, bone.name, "Bendy Bone deformation is outside the native subset")
                if not bone.bone.use_local_location or not bone.bone.use_inherit_rotation or bone.bone.inherit_scale != 'FULL':
                    fail(None, bone.name, "nonstandard bone inheritance requires baking")
        if any(abs(v) > TOLERANCE for v in obj.delta_location) or any(abs(v) > TOLERANCE for v in obj.delta_rotation_euler) or any(abs(v-1) > TOLERANCE for v in obj.delta_scale) or any(abs(a-b) > TOLERANCE for a,b in zip(obj.delta_rotation_quaternion, (1,0,0,0))):
            fail(None, obj.name, "delta transforms require baking")
    for action in actions:
        bound = []
        for obj in objects:
            data = obj.animation_data
            if data is not None and (data.action == action or any(strip.action == action for track in data.nla_tracks for strip in track.strips)):
                bound.append(obj)
        if len(bound) != 1:
            fail(action.name, None, "Action must bind to exactly one exported object")
            continue
        obj = bound[0]
        if len(action.layers) != 1 or len(action.layers[0].strips) != 1 or len(action.layers[0].strips[0].channelbags) != 1:
            fail(action.name, obj.name, "multiple Action layers, strips, or slots require baking")
            continue
        groups = {}
        for curve in fcurves(action):
            match = BONE_PATH.fullmatch(curve.data_path)
            if match and obj.type == 'ARMATURE':
                bone_name, prop = json.loads(match.group(1)), match.group(2)
                bone = obj.pose.bones.get(bone_name)
                if bone is None:
                    fail(action.name, obj.name, "FCurve references missing bone")
                    continue
            elif curve.data_path in PATHS:
                if obj.type == 'MESH':
                    fail(action.name, obj.name, "animated skinned-mesh object transforms require baking")
                    continue
                bone, prop = None, curve.data_path
            else:
                fail(action.name, obj.name, "unsupported native property: " + curve.data_path)
                continue
            groups.setdefault((bone.name if bone else '', prop), (bone, []))[1].append(curve)
        channels = []
        for (_, prop), (bone, curves) in sorted(groups.items()):
            try:
                channels.append(_channel(obj, bone, prop, curves, float(action.frame_range[0]), fps))
            except (ValueError, ZeroDivisionError) as error:
                fail(action.name, bone.name if bone else obj.name, str(error))
        clips.append({"name": action.name, "channels": channels})
    return NativePlan(not reasons, clips, reasons, fps)


def replace_animations(gltf: dict, binary: bytes, plan: NativePlan) -> bytes:
    """Write source keys/tangents, without resampling, into the exported scene."""
    if not plan.native:
        return binary
    node_indices = {node['name']: index for index, node in enumerate(gltf['nodes'])}
    output = bytearray(binary)
    def accessor(rows, kind):
        output.extend(b'\0' * (-len(output) % 4))
        offset = len(output)
        for row in rows:
            output.extend(struct.pack('<' + 'f'*len(row), *row))
        view = len(gltf['bufferViews'])
        gltf['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(output)-offset})
        index = len(gltf['accessors'])
        item = {'bufferView': view, 'componentType': 5126, 'count': len(rows), 'type': kind}
        if kind == 'SCALAR':
            item.update(min=[min(row[0] for row in rows)], max=[max(row[0] for row in rows)])
        gltf['accessors'].append(item)
        return index
    animations = []
    for clip in plan.clips:
        animation = {'name': clip['name'], 'channels': [], 'samplers': []}
        for channel in clip['channels']:
            if channel['node'] not in node_indices:
                raise ValueError("Native animation node omitted by glTF exporter: " + channel['node'])
            index = len(animation['samplers'])
            animation['samplers'].append({'input': accessor([(t,) for t in channel['times']], 'SCALAR'),
                'output': accessor(channel['values'], 'VEC4' if channel['path'] == 'rotation' else 'VEC3'),
                'interpolation': channel['interpolation']})
            animation['channels'].append({'sampler': index,
                'target': {'node': node_indices[channel['node']], 'path': channel['path']}})
        animations.append(animation)
    if animations:
        gltf['animations'] = animations
    else:
        gltf.pop('animations', None)
    gltf['buffers'] = [{'byteLength': len(output)}]
    return bytes(output)
