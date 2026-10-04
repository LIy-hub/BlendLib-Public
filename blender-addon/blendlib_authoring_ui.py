# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Explicit transient runtime authoring draft UI. Only Apply writes a Text datablock."""
import json
try:
    from . import blendlib_authoring_editor as editor
    from . import blendlib_authoring_sockets as sockets
except ImportError:
    import blendlib_authoring_editor as editor
    import blendlib_authoring_sockets as sockets

_CLASSES = ()
_LOAD_HANDLER = None
_EDIT_HANDLER = None
_UNDO_HANDLER = None
# Blender dynamic enum strings must outlive their callback invocation.
_STATE_ITEMS = []
_SOCKET_ITEMS = []
_MORPH_ITEMS = []
_TARGET_ITEMS = {}
_NODE_ITEMS = {}
_LOOP_ITEMS = {}
_NEXT_ITEMS = {}


def _morph(scene):
    return getattr(scene, 'blendlib_profile', None) == 'blendlib:skinned_morph_cpu_v1'


def _source(scene, exporter):
    collection = exporter._select_collection(scene.blendlib_collection.name if scene.blendlib_collection else None)
    objects, _ = exporter._collect_export_objects(collection)
    actions = exporter._cpu_morph_module().actions(exporter._cpu_morph_module().discover_bindings(objects)) if _morph(scene) else exporter._discover_action_objects(objects)
    facts = {action.name: (float(action.frame_range[0]), float(action.frame_range[1]),
             [(marker.name, marker.frame) for marker in action.pose_markers]) for action in actions}
    return actions, facts, scene.render.fps / scene.render.fps_base


def _socket_source(scene, exporter):
    return sockets.discover(scene, exporter)


def _reset_draft(draft):
    draft.active = False
    draft.source = None
    draft.source_content = ''
    draft.source_signature = ''
    draft.source_invalidated = False
    draft.events.clear()
    draft.nodes.clear()
    draft.targets.clear()
    draft.rules.clear()


def register(blender, exporter):
    global _CLASSES, _LOAD_HANDLER, _EDIT_HANDLER, _UNDO_HANDLER
    props = blender.props
    transient = {'SKIP_SAVE'}

    def state_items(self, context):
        global _STATE_ITEMS
        result = []
        text = context.scene.blendlib_runtime_authoring_text if context else None
        if text:
            try:
                config = editor.authoring.parse(text.as_string(), allow_morph_controls=_morph(context.scene))
                states = config['animation']['states']
                if isinstance(states, dict) and len(states) <= 256:
                    result = [(key, key, 'Load this state into an explicit draft') for key in states]
            except (ValueError, KeyError, TypeError):
                pass
        _STATE_ITEMS = result or [('__NONE__', 'No valid states', '')]
        return _STATE_ITEMS

    def socket_items(self, context):
        global _SOCKET_ITEMS
        result = []
        text = context.scene.blendlib_runtime_authoring_text if context else None
        if text:
            try:
                values = editor.authoring.parse(text.as_string(), allow_morph_controls=_morph(context.scene)).get('sockets', {})
                if isinstance(values, dict) and len(values) <= 512:
                    result = [(key, key, 'Load this socket into an explicit draft') for key in values]
            except (ValueError, KeyError, TypeError):
                pass
        _SOCKET_ITEMS = result or [('__NONE__', 'No sockets', '')]
        return _SOCKET_ITEMS

    def morph_items(self, context):
        global _MORPH_ITEMS
        result = []
        text = context.scene.blendlib_runtime_authoring_text if context else None
        if text and _morph(context.scene):
            try:
                values = editor.authoring.parse(text.as_string(), allow_morph_controls=True).get('morph_controls', {})
                result = [(key, key, 'Load this control into an explicit draft') for key in values]
            except (ValueError, KeyError, TypeError):
                pass
        _MORPH_ITEMS = result or [('__NONE__', 'No morph controls', '')]
        return _MORPH_ITEMS

    def target_items(self, context):
        _TARGET_ITEMS[self.as_pointer()] = [('/', 'Choose exported mesh / target', '')] + [
            (json.dumps([row.path, row.name], ensure_ascii=False), row.path + ' / ' + row.name,
             'Authored default: ' + row.default_weight) for row in self.targets]
        return _TARGET_ITEMS[self.as_pointer()]

    def node_items(self, context):
        key = self.as_pointer()
        _NODE_ITEMS[key] = [('/', 'Choose exported object or bone', '')] + [
            (row.path, ('Bone: ' + row.owner + ' / ' + row.name) if row.kind == 'BONE'
             else 'Object: ' + row.name, row.path) for row in self.nodes]
        return _NODE_ITEMS[key]

    def loop_items(self, context):
        # Read the loaded snapshot, never silently retarget a draft after Text edits.
        result = [('/', 'Choose continuous loop state', '')]
        if context:
            try:
                config = editor.authoring.parse(context.scene.blendlib_authoring_draft.source_content, allow_morph_controls=_morph(context.scene))
                result += [(key, key, 'Authored loop without next')
                           for key, state in config['animation']['states'].items()
                           if state.get('loop') is True and 'next' not in state]
            except (ValueError, KeyError, TypeError, AttributeError):
                pass
        _LOOP_ITEMS[self.as_pointer()] = result
        return result

    def next_items(self, context):
        # Snapshot-backed IDs cannot silently retarget when canonical Text changes.
        # The explicit self sentinel also survives editing a new state's key.
        result = [('/', 'None', 'Non-loop holds its end pose; loop keeps repeating'),
                  ('//SELF', 'This State', 'Non-loop restarts this state on completion')]
        if self.mode != 'CREATE':
            try:
                config = editor.authoring.parse(self.source_content, allow_morph_controls=_morph(context.scene))
                result += [(key, key, 'Enter this state when a non-loop finishes')
                           for key in config['animation']['states']
                           if self.mode != 'EDIT' or key != self.loaded_key]
            except (ValueError, KeyError, TypeError):
                pass
        _NEXT_ITEMS[self.as_pointer()] = result
        return result

    def action_poll(self, action):
        try:
            return action in _source(blender.context.scene, exporter)[0]
        except (exporter.ExportError, ValueError, OverflowError):
            return False

    class BLENDLIB_PG_authoring_event(blender.types.PropertyGroup):
        marker: props.StringProperty(name='Action Marker', options=transient)
        event: props.StringProperty(name='Event Key', options=transient)

    class BLENDLIB_PG_authoring_node(blender.types.PropertyGroup):
        path: props.StringProperty(options=transient)
        kind: props.StringProperty(options=transient)
        name: props.StringProperty(options=transient)
        owner: props.StringProperty(options=transient)
        default_weight: props.StringProperty(options=transient)

    class BLENDLIB_PG_authoring_condition(blender.types.PropertyGroup):
        input_name: props.StringProperty(name='Input Name', options=transient)
        kind: props.EnumProperty(name='Condition Type', items=[
            ('BOOL', 'Boolean', 'Exact true / false match'),
            ('MIN', 'Number >=', 'Enter at or above enter; stay at or above exit'),
            ('MAX', 'Number <=', 'Enter at or below enter; stay at or below exit')], options=transient)
        equals: props.BoolProperty(name='Equals', default=True, options=transient)
        enter: props.StringProperty(name='Enter Threshold', default='0', options=transient,
            description='Finite JSON number; text preserves double precision')
        exit: props.StringProperty(name='Exit Threshold', default='0', options=transient,
            description='Finite JSON number; >= requires exit <= enter; <= requires enter <= exit')

    class BLENDLIB_PG_authoring_rule(blender.types.PropertyGroup):
        animation: props.EnumProperty(name='Target Loop', items=loop_items, options=transient)
        conditions: props.CollectionProperty(type=BLENDLIB_PG_authoring_condition, options=transient)
        condition_index: props.IntProperty(default=0, options=transient)

    class BLENDLIB_PG_authoring_draft(blender.types.PropertyGroup):
        default_loop: props.EnumProperty(name='Default Loop', items=loop_items, options=transient)
        minimum_interval: props.IntProperty(name='Minimum Interval (ticks)', default=0, min=0, max=200, options=transient)
        rules: props.CollectionProperty(type=BLENDLIB_PG_authoring_rule, options=transient)
        rule_index: props.IntProperty(default=0, options=transient)
        active: props.BoolProperty(default=False, options=transient)
        kind: props.StringProperty(default='STATE', options=transient)
        mode: props.StringProperty(options=transient)
        source_signature: props.StringProperty(options=transient)
        source_invalidated: props.BoolProperty(default=False, options=transient)
        nodes: props.CollectionProperty(type=BLENDLIB_PG_authoring_node, options=transient)
        targets: props.CollectionProperty(type=BLENDLIB_PG_authoring_node, options=transient)
        morph_target: props.EnumProperty(name='Exported Mesh / Target', items=target_items, options=transient)
        min_weight: props.StringProperty(name='Minimum Weight', default='0', options=transient)
        max_weight: props.StringProperty(name='Maximum Weight', default='1', options=transient)
        socket_node: props.EnumProperty(name='Exported Node', items=node_items, options=transient)
        source: props.PointerProperty(type=blender.types.Text, options=transient)
        source_content: props.StringProperty(options=transient)
        key: props.StringProperty(name='State Key', options=transient)
        loaded_key: props.StringProperty(options=transient)
        action: props.PointerProperty(name='Attached Action', type=blender.types.Action, poll=action_poll, options=transient)
        loop: props.BoolProperty(name='Loop', default=True, options=transient)
        speed: props.StringProperty(name='Speed', default='1', options=transient,
            description='Finite JSON number in (0, 64]; kept as text to preserve double precision')
        next_state: props.EnumProperty(name='Next State', items=next_items, options=transient)
        use_blend: props.BoolProperty(name='Set Blend In', default=False, options=transient,
            description='Store an explicit blend duration; unchecked omits the field (default zero)')
        blend_seconds: props.StringProperty(name='Blend In (seconds)', default='0', options=transient,
            description='Finite JSON number >= 0; cross-fade duration when entering this state')
        make_initial: props.BoolProperty(name='Make Initial State', default=False, options=transient,
            description='Explicitly choose this state as the initial state on Apply')
        events: props.CollectionProperty(type=BLENDLIB_PG_authoring_event, options=transient)
        event_index: props.IntProperty(default=0, options=transient)

    class BLENDLIB_OT_authoring_begin(blender.types.Operator):
        bl_idname = 'blendlib.authoring_begin'
        bl_label = 'Start State Draft'
        bl_description = 'Load a state or start a new draft; never writes the Text'
        mode: props.EnumProperty(items=[('EDIT', 'Load State', ''), ('ADD', 'Add State', ''), ('CREATE', 'New Text', '')])

        def execute(self, context):
            scene = context.scene
            draft = scene.blendlib_authoring_draft
            if draft.active:
                self.report({'ERROR'}, 'Apply or discard the current draft first')
                return {'CANCELLED'}
            try:
                actions, facts, fps = _source(scene, exporter)
                if not actions:
                    raise ValueError('Attach an Action to an exported object or NLA strip first')
                text = scene.blendlib_runtime_authoring_text
                content = text.as_string() if text else ''
                config = editor.load(content, facts, fps, allow_morph_controls=_morph(scene)) if self.mode != 'CREATE' else None
                key = scene.blendlib_authoring_state if self.mode == 'EDIT' else scene.blendlib_namespace + ':new_state'
                state = config['animation']['states'][key] if self.mode == 'EDIT' else {'clip': actions[0].name, 'loop': True, 'speed': 1}
                draft.source = text
                draft.source_content = content
                draft.source_invalidated = False
                draft.kind = 'STATE'
                draft.nodes.clear()
                draft.source_signature = ''
                draft.mode = self.mode
                draft.key = key
                draft.loaded_key = key
                draft.action = blender.data.actions.get(state['clip'])
                draft.loop = state['loop']
                draft.speed = json.dumps(state['speed'])
                draft.next_state = '//SELF' if state.get('next') == key else state.get('next', '/')
                draft.use_blend = 'blend_seconds' in state
                draft.blend_seconds = json.dumps(state.get('blend_seconds', 0))
                draft.make_initial = self.mode == 'CREATE'
                draft.events.clear()
                for raw in state.get('events', []):
                    row = draft.events.add()
                    row.marker, row.event = raw['marker'], raw['event']
                draft.event_index = 0
                draft.active = True
                return {'FINISHED'}
            except (ValueError, KeyError, TypeError, OverflowError, exporter.ExportError) as error:
                self.report({'ERROR'}, str(error)[:300])
                return {'CANCELLED'}

    class BLENDLIB_OT_authoring_socket_begin(blender.types.Operator):
        bl_idname = 'blendlib.authoring_socket_begin'
        bl_label = 'Start Socket Draft'
        bl_description = 'Discover actual exported nodes in temporary output; never writes Text or resources'
        mode: props.EnumProperty(items=[('EDIT', 'Load Socket', ''), ('ADD', 'Add Socket', '')])

        def execute(self, context):
            scene, draft = context.scene, context.scene.blendlib_authoring_draft
            if draft.active:
                self.report({'ERROR'}, 'Apply or discard the current draft first')
                return {'CANCELLED'}
            try:
                text = scene.blendlib_runtime_authoring_text
                if text is None or text.library is not None:
                    raise ValueError('Select a local editable Text datablock')
                content = text.as_string()
                _, facts, fps = _source(scene, exporter)
                config = editor.load(content, facts, fps, allow_morph_controls=_morph(scene))
                key = scene.blendlib_authoring_socket if self.mode == 'EDIT' else scene.blendlib_namespace + ':new_socket'
                node = config.get('sockets', {})[key]['node'] if self.mode == 'EDIT' else '/'
                rows, signature = _socket_source(scene, exporter)
                draft.nodes.clear()
                for raw in rows:
                    row = draft.nodes.add()
                    row.path, row.kind, row.name, row.owner = raw['path'], raw['kind'], raw['name'], raw['owner']
                draft.source_invalidated = False
                draft.kind, draft.mode = 'SOCKET', self.mode
                draft.source, draft.source_content = text, content
                draft.key = draft.loaded_key = key
                draft.source_signature = signature
                draft.socket_node = node if any(row['path'] == node for row in rows) else '/'
                draft.events.clear()
                draft.active = True
                return {'FINISHED'}
            except (ValueError, KeyError, TypeError, OverflowError, OSError, RuntimeError, exporter.ExportError) as error:
                self.report({'ERROR'}, str(error)[:300])
                return {'CANCELLED'}

    class BLENDLIB_OT_authoring_morph_begin(blender.types.Operator):
        bl_idname = 'blendlib.authoring_morph_begin'
        bl_label = 'Start Morph Control Draft'
        bl_description = 'Discover exact CPU mesh targets in temporary output; never changes Text or shape-key defaults'
        mode: props.EnumProperty(items=[('EDIT', 'Load Control', ''), ('ADD', 'Add Control', ''), ('CREATE', 'New Morph Text', '')])

        def execute(self, context):
            scene, draft = context.scene, context.scene.blendlib_authoring_draft
            if draft.active:
                self.report({'ERROR'}, 'Apply or discard the current draft first')
                return {'CANCELLED'}
            try:
                if not _morph(scene):
                    raise ValueError('Select the CPU morph profile first')
                text = scene.blendlib_runtime_authoring_text
                if self.mode != 'CREATE' and (text is None or text.library is not None):
                    raise ValueError('Select a local editable Text datablock')
                content = text.as_string() if text else ''
                _, facts, fps = _source(scene, exporter)
                config = editor.load(content, facts, fps, allow_morph_controls=True) if self.mode != 'CREATE' else {}
                key = scene.blendlib_authoring_morph if self.mode == 'EDIT' else scene.blendlib_namespace + ':new_control'
                control = config.get('morph_controls', {})[key] if self.mode == 'EDIT' else None
                rows, signature = sockets.discover(scene, exporter, morph_targets=True)
                draft.targets.clear()
                for raw in rows:
                    row = draft.targets.add()
                    row.path, row.name, row.default_weight = raw['node'], raw['target'], json.dumps(raw['default'])
                draft.source, draft.source_content = text, content
                draft.source_signature, draft.source_invalidated = signature, False
                draft.kind, draft.mode = 'MORPH', self.mode
                draft.key = draft.loaded_key = key
                pair = (control['node'], control['target']) if control else None
                draft.morph_target = json.dumps(list(pair), ensure_ascii=False) if pair and any(
                    (raw['node'], raw['target']) == pair for raw in rows) else '/'
                draft.min_weight = json.dumps(control['min_weight']) if control else '0'
                draft.max_weight = json.dumps(control['max_weight']) if control else '1'
                draft.events.clear()
                draft.nodes.clear()
                draft.rules.clear()
                draft.active = True
                return {'FINISHED'}
            except (ValueError, KeyError, TypeError, OverflowError, OSError, RuntimeError, exporter.ExportError) as error:
                self.report({'ERROR'}, str(error)[:300])
                return {'CANCELLED'}

    class BLENDLIB_OT_authoring_rules_begin(blender.types.Operator):
        bl_idname = 'blendlib.authoring_rules_begin'
        bl_label = 'Load / Add Locomotion Rules'
        bl_description = 'Load existing rules or start an empty draft; only Apply writes Text'

        def execute(self, context):
            scene, draft = context.scene, context.scene.blendlib_authoring_draft
            if draft.active:
                self.report({'ERROR'}, 'Apply or discard the current draft first')
                return {'CANCELLED'}
            try:
                text = scene.blendlib_runtime_authoring_text
                if text is None or text.library is not None:
                    raise ValueError('Select a local editable Text datablock')
                content = text.as_string()
                _, facts, fps = _source(scene, exporter)
                config = editor.load(content, facts, fps, allow_morph_controls=_morph(scene))
                rules = config.get('locomotion', {})
                draft.source, draft.source_content = text, content
                draft.kind, draft.mode = 'RULES', 'RULES'
                draft.source_signature, draft.source_invalidated = '', False
                draft.key = draft.loaded_key = ''
                draft.events.clear()
                draft.nodes.clear()
                draft.rules.clear()
                draft.default_loop = rules.get('default', '/')
                draft.minimum_interval = rules.get('minimum_interval_ticks', 0)
                for raw in rules.get('rules', []):
                    rule = draft.rules.add()
                    rule.animation = raw['animation']
                    for condition in raw['conditions']:
                        row = rule.conditions.add()
                        row.input_name = condition['input']
                        row.kind = 'BOOL' if 'equals' in condition else 'MIN' if 'enter_min' in condition else 'MAX'
                        if row.kind == 'BOOL':
                            row.equals = condition['equals']
                        else:
                            row.enter = json.dumps(condition['enter_' + row.kind.lower()])
                            row.exit = json.dumps(condition['exit_' + row.kind.lower()])
                    rule.condition_index = 0
                draft.rule_index = 0
                draft.active = True
                return {'FINISHED'}
            except (ValueError, KeyError, TypeError, OverflowError, exporter.ExportError) as error:
                self.report({'ERROR'}, str(error)[:300])
                return {'CANCELLED'}

    class BLENDLIB_OT_authoring_rules_row(blender.types.Operator):
        bl_idname = 'blendlib.authoring_rules_row'
        bl_label = 'Edit Locomotion Draft'
        target: props.EnumProperty(items=[('RULE', 'Rule', ''), ('CONDITION', 'Condition', '')])
        operation: props.EnumProperty(items=[('ADD', 'Add', ''), ('REMOVE', 'Remove', ''), ('UP', 'Up', ''), ('DOWN', 'Down', '')])

        def execute(self, context):
            draft = context.scene.blendlib_authoring_draft
            if not draft.active or draft.kind != 'RULES':
                return {'CANCELLED'}
            owner, name, index_name, limit = draft, 'rules', 'rule_index', 32
            if self.target == 'CONDITION':
                if not 0 <= draft.rule_index < len(draft.rules):
                    return {'CANCELLED'}
                owner, name, index_name, limit = draft.rules[draft.rule_index], 'conditions', 'condition_index', 8
            rows, index = getattr(owner, name), getattr(owner, index_name)
            size = len(rows)
            if self.operation == 'ADD':
                if size >= limit:
                    self.report({'ERROR'}, 'At most ' + str(limit) + ' ' + name + ' are supported')
                    return {'CANCELLED'}
                row = rows.add()
                if self.target == 'RULE':
                    row.animation = '/'
                setattr(owner, index_name, size)
            elif 0 <= index < size:
                if self.operation == 'REMOVE':
                    rows.remove(index)
                    setattr(owner, index_name, max(0, min(index, size - 2)))
                else:
                    target = index + (-1 if self.operation == 'UP' else 1)
                    if 0 <= target < size:
                        rows.move(index, target)
                        setattr(owner, index_name, target)
            return {'FINISHED'}

    class BLENDLIB_OT_authoring_discard(blender.types.Operator):
        bl_idname = 'blendlib.authoring_discard'
        bl_label = 'Discard Draft'
        bl_description = 'Discard only this unsaved draft; leave every Text unchanged'

        def execute(self, context):
            draft = context.scene.blendlib_authoring_draft
            _reset_draft(draft)
            return {'FINISHED'}

    class BLENDLIB_OT_authoring_event(blender.types.Operator):
        bl_idname = 'blendlib.authoring_event'
        bl_label = 'Edit Event Draft'
        operation: props.EnumProperty(items=[('ADD', 'Add', ''), ('REMOVE', 'Remove', ''), ('UP', 'Up', ''), ('DOWN', 'Down', '')])

        def execute(self, context):
            draft = context.scene.blendlib_authoring_draft
            if not draft.active or draft.kind != 'STATE':
                return {'CANCELLED'}
            index, size = draft.event_index, len(draft.events)
            if self.operation == 'ADD':
                if size >= 4096:
                    self.report({'ERROR'}, 'A state supports at most 4096 events')
                    return {'CANCELLED'}
                row = draft.events.add()
                row.event = context.scene.blendlib_namespace + ':event'
                draft.event_index = size
            elif 0 <= index < size:
                if self.operation == 'REMOVE':
                    draft.events.remove(index)
                    draft.event_index = max(0, min(index, size - 2))
                else:
                    target = index + (-1 if self.operation == 'UP' else 1)
                    if 0 <= target < size:
                        draft.events.move(index, target)
                        draft.event_index = target
            return {'FINISHED'}

    class BLENDLIB_OT_authoring_apply(blender.types.Operator):
        bl_idname = 'blendlib.authoring_apply'
        bl_label = 'Apply Draft to Text'
        bl_description = 'Validate source references, then explicitly save this draft to Text (no resource export)'
        bl_options = {'UNDO'}

        def execute(self, context):
            scene = context.scene
            draft = scene.blendlib_authoring_draft
            try:
                if not draft.active:
                    raise ValueError('Load or create a draft first')
                if draft.mode == 'EDIT' and draft.key != draft.loaded_key:
                    raise ValueError('Renaming an existing ' + draft.kind.lower() + ' is not supported; reload the draft')
                text = scene.blendlib_runtime_authoring_text
                # Pointer identity deliberately permits rename but rejects a switched
                # datablock, including another Text with byte-identical content.
                if text != draft.source:
                    raise ValueError('Selected Text changed; discard and reload the draft')
                content = text.as_string() if text else ''
                if content != draft.source_content:
                    raise ValueError('Text contents changed; discard and reload the draft')
                if draft.mode != 'CREATE' and (text is None or text.library is not None):
                    raise ValueError('Select a local editable Text datablock')
                actions, facts, fps = _source(scene, exporter)
                if draft.kind == 'MORPH':
                    if not _morph(scene):
                        raise ValueError('Morph controls require the CPU morph profile; discard and reload')
                    if draft.source_invalidated:
                        raise ValueError('Scene edit or undo changed source identity; discard and reload the draft')
                    rows, signature = sockets.discover(scene, exporter, morph_targets=True)
                    if signature != draft.source_signature:
                        raise ValueError('Export source or morph targets changed; discard and reload the draft')
                    if draft.morph_target == '/':
                        raise ValueError('Choose an exact exported mesh / target')
                    node, target = json.loads(draft.morph_target)
                    replacement = editor.apply_morph(content, mode=draft.mode, key=draft.key,
                        node=node, target=target, min_weight=draft.min_weight, max_weight=draft.max_weight,
                        targets=rows, actions=facts, fps=fps)
                elif draft.kind == 'SOCKET':
                    if draft.source_invalidated:
                        raise ValueError('Armature Edit Mode or undo changed source identity; discard and reload the draft')
                    rows, signature = _socket_source(scene, exporter)
                    if signature != draft.source_signature:
                        raise ValueError('Export source identity or node paths changed; discard and reload the draft')
                    replacement = editor.apply_socket(content, mode=draft.mode, key=draft.key,
                        node=draft.socket_node, node_paths={row['path'] for row in rows}, actions=facts, fps=fps, allow_morph_controls=_morph(scene))
                elif draft.kind == 'RULES':
                    replacement = editor.apply_locomotion(content, default=draft.default_loop,
                        interval=draft.minimum_interval, rules=[{'animation': rule.animation,
                            'conditions': [editor.condition(kind=row.kind, input_name=row.input_name,
                                equals=row.equals, enter=row.enter, exit=row.exit) for row in rule.conditions]}
                            for rule in draft.rules], actions=facts, fps=fps, allow_morph_controls=_morph(scene))
                elif draft.kind == 'STATE':
                    replacement = editor.apply(content, mode=draft.mode, key=draft.key,
                        clip=draft.action.name if draft.action else '', loop=draft.loop,
                        speed=draft.speed, events=[{'marker': row.marker, 'event': row.event} for row in draft.events],
                        make_initial=draft.make_initial, actions=facts, fps=fps, allow_morph_controls=_morph(scene),
                        next_state=None if draft.next_state == '/' else draft.key if draft.next_state == '//SELF' else draft.next_state,
                        blend_seconds=draft.blend_seconds if draft.use_blend else None)
                else:
                    raise ValueError('Unknown authoring draft kind; discard and reload')
                if draft.mode == 'CREATE':
                    text = blender.data.texts.new('BlendLib.runtime.json')
                    text.write(replacement)
                    scene.blendlib_runtime_authoring_text = text
                else:
                    text.from_string(replacement)
                _reset_draft(draft)
                self.report({'INFO'}, 'Applied to Text; save the .blend to persist. Export still validates GLB bounds and socket paths')
                return {'FINISHED'}
            except (ValueError, TypeError, KeyError, OverflowError, OSError, RuntimeError, exporter.ExportError) as error:
                self.report({'ERROR'}, str(error)[:300])
                return {'CANCELLED'}

    class BLENDLIB_UL_authoring_events(blender.types.UIList):
        def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
            layout.label(text=item.marker or '(choose Action marker)')
            layout.label(text=item.event or '(event key)')

    class BLENDLIB_UL_authoring_rules(blender.types.UIList):
        def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
            layout.label(text=str(index + 1) + '. ' + (item.animation if item.animation != '/' else '(choose loop)'))
            layout.label(text=str(len(item.conditions)) + ' conditions')

    class BLENDLIB_UL_authoring_conditions(blender.types.UIList):
        def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
            label = ('== ' + str(item.equals)) if item.kind == 'BOOL' else ('>= ' if item.kind == 'MIN' else '<= ') + item.enter
            layout.label(text=(item.input_name or '(input name)') + ' ' + label)

    def draw_row_controls(layout, target):
        row = layout.row(align=True)
        for operation, icon in [('ADD', 'ADD'), ('REMOVE', 'REMOVE'), ('UP', 'TRIA_UP'), ('DOWN', 'TRIA_DOWN')]:
            op = row.operator('blendlib.authoring_rules_row', text='', icon=icon)
            op.target, op.operation = target, operation

    def draw_rules(layout, draft):
        layout.prop(draft, 'default_loop')
        layout.prop(draft, 'minimum_interval')
        layout.label(text='Priority: first matching rule wins (top to bottom)')
        layout.template_list('BLENDLIB_UL_authoring_rules', '', draft, 'rules', draft, 'rule_index', rows=3)
        draw_row_controls(layout, 'RULE')
        if 0 <= draft.rule_index < len(draft.rules):
            rule = draft.rules[draft.rule_index]
            layout.prop(rule, 'animation')
            layout.label(text='All conditions must match; empty = unconditional')
            layout.template_list('BLENDLIB_UL_authoring_conditions', '', rule, 'conditions', rule, 'condition_index', rows=3)
            draw_row_controls(layout, 'CONDITION')
            if 0 <= rule.condition_index < len(rule.conditions):
                row = rule.conditions[rule.condition_index]
                layout.prop(row, 'input_name')
                layout.prop(row, 'kind')
                if row.kind == 'BOOL':
                    layout.prop(row, 'equals')
                else:
                    layout.prop(row, 'enter')
                    layout.prop(row, 'exit')
                    layout.label(text='Exit <= Enter' if row.kind == 'MIN' else 'Enter <= Exit')
        layout.label(text='Use one consistent type per input name')
        layout.label(text='No matching rule: default loop')
        row = layout.row(align=True)
        row.operator('blendlib.authoring_apply', text='Apply Rules to Text')
        row.operator('blendlib.authoring_discard', text='Discard')

    class VIEW3D_PT_blendlib_authoring(blender.types.Panel):
        bl_label = 'Runtime Authoring Editor'
        bl_idname = 'VIEW3D_PT_blendlib_authoring'
        bl_space_type = 'VIEW_3D'
        bl_region_type = 'UI'
        bl_category = 'BlendLib'
        bl_parent_id = 'VIEW3D_PT_blendlib_export'

        def draw(self, context):
            layout, scene = self.layout, context.scene
            draft = scene.blendlib_authoring_draft
            layout.label(text='Text is canonical; drafts do not export')
            if not scene.blendlib_runtime_authoring_enabled:
                layout.label(text='Enable Runtime Animation Authoring to export', icon='INFO')
            if not draft.active:
                if _morph(scene):
                    if scene.blendlib_runtime_authoring_text:
                        layout.prop(scene, 'blendlib_authoring_morph', text='Morph Control')
                        row = layout.row(align=True)
                        row.operator('blendlib.authoring_morph_begin', text='Load Control').mode = 'EDIT'
                        row.operator('blendlib.authoring_morph_begin', text='Add Control').mode = 'ADD'
                    layout.operator('blendlib.authoring_morph_begin', text='Start Morph-only Text').mode = 'CREATE'
                    layout.label(text='Discover exact mesh / shape key; no live preview')
                if scene.blendlib_runtime_authoring_text:
                    layout.prop(scene, 'blendlib_authoring_state', text='State')
                    row = layout.row(align=True)
                    row.operator('blendlib.authoring_begin', text='Load State').mode = 'EDIT'
                    row.operator('blendlib.authoring_begin', text='Add State').mode = 'ADD'
                layout.operator('blendlib.authoring_begin', text='Start New Text').mode = 'CREATE'
                if scene.blendlib_runtime_authoring_text:
                    layout.prop(scene, 'blendlib_authoring_socket', text='Socket')
                    row = layout.row(align=True)
                    row.operator('blendlib.authoring_socket_begin', text='Load Socket').mode = 'EDIT'
                    row.operator('blendlib.authoring_socket_begin', text='Add Socket').mode = 'ADD'
                    layout.label(text='Socket nodes are discovered by a temporary export')
                    layout.operator('blendlib.authoring_rules_begin', text='Load / Add Locomotion Rules')
                return
            layout.label(text='New Text draft' if draft.mode == 'CREATE' else 'Draft for ' + (draft.source.name if draft.source else '(missing Text)'))
            if draft.kind == 'RULES':
                draw_rules(layout, draft)
                return
            key_row = layout.row()
            key_row.enabled = draft.mode != 'EDIT'
            key_row.prop(draft, 'key', text='Control Alias' if draft.kind == 'MORPH' else 'State Key' if draft.kind == 'STATE' else 'Socket Key')
            if draft.kind == 'MORPH':
                if draft.source_invalidated:
                    layout.label(text='Scene edited: discard and reload this draft', icon='ERROR')
                layout.prop(draft, 'morph_target')
                layout.prop(draft, 'min_weight')
                layout.prop(draft, 'max_weight')
                layout.label(text='Interval includes zero and default; within [-2, 2]')
                layout.label(text='Add one control for every exported target before export')
                layout.label(text='Authored shape-key values are never changed')
                row = layout.row(align=True)
                row.operator('blendlib.authoring_apply', text='Apply Morph Control to Text')
                row.operator('blendlib.authoring_discard', text='Discard')
                return
            if draft.kind == 'SOCKET':
                if draft.source_invalidated:
                    layout.label(text='Scene edited: discard and reload this draft', icon='ERROR')
                layout.prop(draft, 'socket_node')
                layout.label(text='Path: ' + draft.socket_node)
                layout.label(text='Offset / rotation: transform an exported Empty')
                layout.label(text='Create or move helpers before loading this draft')
                row = layout.row(align=True)
                row.operator('blendlib.authoring_apply', text='Apply Socket to Text')
                row.operator('blendlib.authoring_discard', text='Discard')
                return
            layout.prop(draft, 'action')
            row = layout.row(align=True)
            row.prop(draft, 'loop')
            row.prop(draft, 'speed')
            if draft.mode == 'CREATE':
                layout.label(text='This first state will be initial')
            else:
                layout.prop(draft, 'make_initial')
            layout.template_list('BLENDLIB_UL_authoring_events', '', draft, 'events', draft, 'event_index', rows=3)
            row = layout.row(align=True)
            for operation, icon in [('ADD', 'ADD'), ('REMOVE', 'REMOVE'), ('UP', 'TRIA_UP'), ('DOWN', 'TRIA_DOWN')]:
                row.operator('blendlib.authoring_event', text='', icon=icon).operation = operation
            if 0 <= draft.event_index < len(draft.events):
                event = draft.events[draft.event_index]
                if draft.action:
                    layout.prop_search(event, 'marker', draft.action, 'pose_markers')
                else:
                    layout.prop(event, 'marker')
                layout.prop(event, 'event')
            layout.prop(draft, 'next_state')
            if draft.loop:
                layout.label(text='Loop repeats; Next is not followed', icon='INFO')
            else:
                layout.label(text='No Next: hold end pose; otherwise enter Next')
            layout.prop(draft, 'use_blend')
            if draft.use_blend:
                layout.prop(draft, 'blend_seconds')
            else:
                layout.label(text='Blend In default: 0 seconds (field absent)')
            layout.label(text='Blend In applies when entering this state')
            layout.label(text='Locomotion targets require Loop and no Next')
            row = layout.row(align=True)
            row.operator('blendlib.authoring_apply', text='Create New Text' if draft.mode == 'CREATE' else 'Apply to Selected Text')
            row.operator('blendlib.authoring_discard', text='Discard')

    _CLASSES = (BLENDLIB_PG_authoring_event, BLENDLIB_PG_authoring_node,
                BLENDLIB_PG_authoring_condition, BLENDLIB_PG_authoring_rule, BLENDLIB_PG_authoring_draft,
                BLENDLIB_OT_authoring_begin, BLENDLIB_OT_authoring_morph_begin, BLENDLIB_OT_authoring_socket_begin, BLENDLIB_OT_authoring_discard,
                BLENDLIB_OT_authoring_event, BLENDLIB_OT_authoring_rules_begin, BLENDLIB_OT_authoring_rules_row, BLENDLIB_OT_authoring_apply,
                BLENDLIB_UL_authoring_events, BLENDLIB_UL_authoring_rules, BLENDLIB_UL_authoring_conditions, VIEW3D_PT_blendlib_authoring)
    for cls in _CLASSES:
        blender.utils.register_class(cls)
    blender.types.Scene.blendlib_authoring_state = props.EnumProperty(name='State', items=state_items, options=transient)
    blender.types.Scene.blendlib_authoring_morph = props.EnumProperty(name='Morph Control', items=morph_items, options=transient)
    blender.types.Scene.blendlib_authoring_socket = props.EnumProperty(name='Socket', items=socket_items, options=transient)
    blender.types.Scene.blendlib_authoring_draft = props.PointerProperty(type=BLENDLIB_PG_authoring_draft, options=transient)

    # SKIP_SAVE does not suppress Scene ID-property serialization in Blender.
    # Clear only working drafts at explicit lifecycle boundaries; saving an open
    # file does not discard current edits and export still reads only the Text.
    @blender.app.handlers.persistent
    def clear_loaded_drafts(_):
        _clear_drafts(blender)

    @blender.app.handlers.persistent
    def invalidate_bone_edits(scene, _depsgraph):
        # Bone RNA addresses can be reused after delete/recreate with identical
        # names/transforms. Seeing Edit Mode is the conservative identity boundary;
        # a pointer or retained Bone wrapper alone cannot detect that replacement.
        if any(obj.type in {'ARMATURE', 'MESH'} and obj.mode == 'EDIT' for obj in blender.data.objects):
            for owner_scene in blender.data.scenes:
                draft = owner_scene.blendlib_authoring_draft
                if draft.active and draft.kind in {'SOCKET', 'MORPH'}:
                    draft.source_invalidated = True

    @blender.app.handlers.persistent
    def invalidate_undo(_):
        for scene in blender.data.scenes:
            draft = scene.blendlib_authoring_draft
            if draft.active and draft.kind in {'SOCKET', 'MORPH'}:
                draft.source_invalidated = True

    _EDIT_HANDLER, _UNDO_HANDLER = invalidate_bone_edits, invalidate_undo
    blender.app.handlers.depsgraph_update_post.append(_EDIT_HANDLER)
    blender.app.handlers.undo_post.append(_UNDO_HANDLER)
    blender.app.handlers.redo_post.append(_UNDO_HANDLER)
    _LOAD_HANDLER = clear_loaded_drafts
    blender.app.handlers.load_post.append(_LOAD_HANDLER)
    _clear_drafts(blender)


def _clear_drafts(blender):
    for scene in blender.data.scenes:
        if hasattr(scene, 'blendlib_authoring_draft'):
            draft = scene.blendlib_authoring_draft
            _reset_draft(draft)


def unregister(blender):
    global _CLASSES, _STATE_ITEMS, _SOCKET_ITEMS, _LOAD_HANDLER, _EDIT_HANDLER, _UNDO_HANDLER
    if _LOAD_HANDLER in blender.app.handlers.load_post:
        blender.app.handlers.load_post.remove(_LOAD_HANDLER)
    _LOAD_HANDLER = None
    for handlers, handler in ((blender.app.handlers.depsgraph_update_post, _EDIT_HANDLER),
                              (blender.app.handlers.undo_post, _UNDO_HANDLER),
                              (blender.app.handlers.redo_post, _UNDO_HANDLER)):
        if handler in handlers:
            handlers.remove(handler)
    _EDIT_HANDLER = _UNDO_HANDLER = None
    _clear_drafts(blender)
    for name in ('blendlib_authoring_state', 'blendlib_authoring_morph', 'blendlib_authoring_socket', 'blendlib_authoring_draft'):
        if hasattr(blender.types.Scene, name):
            delattr(blender.types.Scene, name)
    for cls in reversed(_CLASSES):
        blender.utils.unregister_class(cls)
    _CLASSES = ()
    _STATE_ITEMS = []
    _SOCKET_ITEMS = []
    _MORPH_ITEMS.clear()
    _TARGET_ITEMS.clear()
    _NODE_ITEMS.clear()
    _LOOP_ITEMS.clear()
    _NEXT_ITEMS.clear()
