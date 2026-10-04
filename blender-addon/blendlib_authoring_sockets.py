# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Private real-export discovery; no inferred Blender-to-glTF hierarchy."""
import json
from pathlib import Path
import tempfile


def _identity(value):
    return str(getattr(value, 'session_uid', '')) + ':' + str(value.as_pointer()) if value else ''


def source_signature(scene, exporter):
    """Ephemeral RNA identities; never persisted into canonical authoring JSON."""
    collection = exporter._select_collection(scene.blendlib_collection.name if scene.blendlib_collection else None)
    objects, _ = exporter._collect_export_objects(collection)
    signature = [_identity(scene), _identity(collection), scene.blendlib_profile, []]
    for obj in objects:
        bones = [(_identity(bone), bone.name, _identity(bone.parent)) for bone in obj.data.bones] if obj.type == 'ARMATURE' else []
        signature[3].append((_identity(obj), obj.name, obj.type, _identity(obj.data),
                             _identity(obj.parent), obj.parent_type, obj.parent_bone, bones))
    return json.dumps(signature, ensure_ascii=False)


_TRANSFORM_FIELDS = ('location', 'rotation_euler', 'rotation_quaternion', 'rotation_axis_angle', 'scale')
_ANIMATION_FIELDS = ('action', 'action_slot', 'use_nla', 'action_blend_type', 'action_extrapolation', 'action_influence')


def _capture_objects(objects):
    # glTF ACTIONS export resets unkeyed pose channels and may lose an active
    # Action with no slot. Discovery must restore them even after export failure.
    transforms, animations, tracks, shape_values = [], [], [], []
    for obj in objects:
        for value in [obj] + (list(obj.pose.bones) if obj.pose else []):
            transforms.append((value, {field: tuple(getattr(value, field)) for field in _TRANSFORM_FIELDS}))
        owners = [obj]
        if obj.type == 'MESH' and obj.data.shape_keys:
            owners.append(obj.data.shape_keys)
            shape_values.extend((key, key.value) for key in obj.data.shape_keys.key_blocks)
        for owner in owners:
            if owner.animation_data:
                data = owner.animation_data
                animations.append((data, {field: getattr(data, field) for field in _ANIMATION_FIELDS}))
                tracks.extend((track, track.mute, track.is_solo) for track in data.nla_tracks)
    return transforms, animations, tracks, shape_values


def _restore_objects(snapshot):
    transforms, animations, tracks, shape_values = snapshot
    for key, value in shape_values:
        key.value = value
    for data, values in animations:
        for field, value in values.items():
            if getattr(data, field) != value:
                setattr(data, field, value)
    for track, mute, solo in tracks:
        if track.mute != mute:
            track.mute = mute
        if track.is_solo != solo:
            track.is_solo = solo
    for value, fields in transforms:
        for field, raw in fields.items():
            if tuple(getattr(value, field)) != raw:
                setattr(value, field, raw)


def discover(scene, exporter, *, morph_targets=False):
    """Return typed real GLB nodes and a source-identity/path snapshot.

    Explicit Load/Apply operations call this, never panel draw callbacks. Temporary
    output cannot overwrite resource files. Strict source validation forbids global
    duplicate object/bone names, making source-name attribution unambiguous.
    """
    blender = exporter._require_blender()
    if blender.context.mode != 'OBJECT':
        raise ValueError('Switch to Object Mode before discovering exported nodes / targets')
    if any(owner.animation_data and owner.animation_data.use_tweak_mode
           for obj in blender.data.objects
           for owner in ([obj, obj.data.shape_keys] if obj.type == 'MESH' and obj.data.shape_keys else [obj])):
        raise ValueError('Exit NLA Tweak Mode before discovering exported nodes / targets')
    collection = exporter._select_collection(scene.blendlib_collection.name if scene.blendlib_collection else None)
    objects, _ = exporter._collect_export_objects(collection)
    exporter._validate_source_objects(objects, scene.blendlib_profile)
    identity = source_signature(scene, exporter)
    morph_meshes = None
    if morph_targets:
        if scene.blendlib_profile != 'blendlib:skinned_morph_cpu_v1':
            raise ValueError('Morph controls require the CPU morph profile')
        if blender.app.version != (5, 1, 2):
            raise ValueError('CPU morph discovery is verified only for Blender 5.1.2')
        # Source topology/budget validation is independent of exact exported paths.
        morph_meshes = exporter._cpu_morph_module().validate_source(objects, {
            'discovery:probe': {'node': 'probe', 'target': 'probe', 'min_weight': -2, 'max_weight': 2}})
        identity = morph_signature(scene, exporter)
    source = {}
    for obj in objects:
        source[obj.name] = ('OBJECT', obj.name)
        if obj.type == 'ARMATURE':
            for bone in obj.data.bones:
                source[bone.name] = ('BONE', obj.name)
    # The unchanged strict glTF export may visit every scene. Preserve all of
    # their frames and view layers plus shared object/pose/animation state.
    window = blender.context.window
    window_scene, window_layer = window.scene, window.view_layer
    frames = [(item, item.frame_current, item.frame_subframe) for item in blender.data.scenes]
    layers = [(layer, layer.active_layer_collection, layer.objects.active,
               tuple(obj for obj in layer.objects if obj.select_get(view_layer=layer)))
              for item in blender.data.scenes for layer in item.view_layers]
    snapshot = _capture_objects(blender.data.objects)
    try:
        with tempfile.TemporaryDirectory(prefix='blendlib-socket-discovery-') as directory:
            path = Path(directory)/'nodes.glb'
            exporter._export_raw_glb(collection, path, runtime_authoring=True, cpu_morph=scene.blendlib_profile == 'blendlib:skinned_morph_cpu_v1')
            gltf, _ = exporter.read_glb(path, allowed_roots=(Path(directory),))
            paths = exporter._exported_node_paths(gltf)
    finally:
        window.scene, window.view_layer = window_scene, window_layer
        for item, frame, subframe in frames:
            item.frame_set(frame, subframe=subframe)
        _restore_objects(snapshot)
        for layer, active_collection, active_object, selected in layers:
            layer.active_layer_collection = active_collection
            layer.objects.active = active_object
            for obj in layer.objects:
                if obj.select_get(view_layer=layer) != (obj in selected):
                    obj.select_set(obj in selected, view_layer=layer)
    rows = []
    for index, path in paths.items():
        name = gltf['nodes'][index]['name']
        if name not in source:
            raise ValueError('Exporter produced an unattributed node; cannot safely select sockets')
        kind, owner = source[name]
        rows.append({'path': path, 'kind': kind, 'name': name, 'owner': owner})
    if set(source) != {row['name'] for row in rows}:
        raise ValueError('GLB omitted source nodes; socket discovery requires a supported complete export')
    rows.sort(key=lambda row: row['path'])
    if morph_targets:
        targets = []
        for index, path in paths.items():
            node = gltf['nodes'][index]
            info = morph_meshes.get(node['name'])
            if info is None:
                continue
            meshes, mesh_index = gltf.get('meshes', []), node.get('mesh')
            if type(mesh_index) is not int or not 0 <= mesh_index < len(meshes):
                raise ValueError('Exported morph node has no valid mesh')
            mesh = meshes[mesh_index]
            names = mesh.get('extras', {}).get('targetNames')
            if names != info['names'] or any(len(p.get('targets', [])) != len(names) for p in mesh['primitives']):
                raise ValueError('Exported morph targets differ from authored shape keys')
            targets.extend({'node': path, 'target': name, 'default': value}
                           for name, value in zip(names, info['defaults']))
        if len(targets) > 1024 or len(targets) != sum(len(m['names']) for m in morph_meshes.values()):
            raise ValueError('Export omitted morph targets or exceeds the control budget')
        if identity != morph_signature(scene, exporter):
            raise ValueError('Morph source changed during discovery; reload the draft')
        return targets, json.dumps([identity, targets], ensure_ascii=False, sort_keys=True)
    if identity != source_signature(scene, exporter):
        raise ValueError('Export source changed during discovery; reload the draft')
    return rows, json.dumps([identity, rows], ensure_ascii=False, sort_keys=True)


def morph_signature(scene, exporter):
    """Identity, ordering and defaults fence for explicit morph drafts."""
    collection = exporter._select_collection(scene.blendlib_collection.name if scene.blendlib_collection else None)
    objects, _ = exporter._collect_export_objects(collection)
    keys = []
    for obj in objects:
        if obj.type == 'MESH' and obj.data.shape_keys:
            key = obj.data.shape_keys
            keys.append((_identity(key), [(_identity(block), block.name, float(block.value),
                _identity(block.relative_key), block.mute, block.vertex_group) for block in key.key_blocks]))
    return json.dumps([source_signature(scene, exporter), keys], ensure_ascii=False)
