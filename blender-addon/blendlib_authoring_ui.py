# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Explicit transient state/event draft UI. Only Apply writes a Text datablock."""
import json
try:
    from . import blendlib_authoring_editor as editor
except ImportError:
    import blendlib_authoring_editor as editor

_CLASSES = ()
_LOAD_HANDLER = None
# Blender dynamic enum strings must outlive their callback invocation.
_STATE_ITEMS = []


def _source(scene, exporter):
    collection = exporter._select_collection(scene.blendlib_collection.name if scene.blendlib_collection else None)
    objects, _ = exporter._collect_export_objects(collection)
    actions = exporter._discover_action_objects(objects)
    facts = {action.name: (float(action.frame_range[0]), float(action.frame_range[1]),
             [(marker.name, marker.frame) for marker in action.pose_markers]) for action in actions}
    return actions, facts, scene.render.fps / scene.render.fps_base


def register(blender, exporter):
    global _CLASSES, _LOAD_HANDLER
    props = blender.props
    transient = {'SKIP_SAVE'}

    def state_items(self, context):
        global _STATE_ITEMS
        result = []
        text = context.scene.blendlib_runtime_authoring_text if context else None
        if text:
            try:
                config = editor.authoring.parse(text.as_string())
                states = config['animation']['states']
                if isinstance(states, dict) and len(states) <= 256:
                    result = [(key, key, 'Load this state into an explicit draft') for key in states]
            except (ValueError, KeyError, TypeError):
                pass
        _STATE_ITEMS = result or [('__NONE__', 'No valid states', '')]
        return _STATE_ITEMS

    def action_poll(self, action):
        try:
            return action in _source(blender.context.scene, exporter)[0]
        except (exporter.ExportError, ValueError, OverflowError):
            return False

    class BLENDLIB_PG_authoring_event(blender.types.PropertyGroup):
        marker: props.StringProperty(name='Action Marker', options=transient)
        event: props.StringProperty(name='Event Key', options=transient)

    class BLENDLIB_PG_authoring_draft(blender.types.PropertyGroup):
        active: props.BoolProperty(default=False, options=transient)
        mode: props.StringProperty(options=transient)
        source: props.PointerProperty(type=blender.types.Text, options=transient)
        source_content: props.StringProperty(options=transient)
        key: props.StringProperty(name='State Key', options=transient)
        loaded_key: props.StringProperty(options=transient)
        action: props.PointerProperty(name='Attached Action', type=blender.types.Action, poll=action_poll, options=transient)
        loop: props.BoolProperty(name='Loop', default=True, options=transient)
        speed: props.StringProperty(name='Speed', default='1', options=transient,
            description='Finite JSON number in (0, 64]; kept as text to preserve double precision')
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
                config = editor.load(content, facts, fps) if self.mode != 'CREATE' else None
                key = scene.blendlib_authoring_state if self.mode == 'EDIT' else scene.blendlib_namespace + ':new_state'
                state = config['animation']['states'][key] if self.mode == 'EDIT' else {'clip': actions[0].name, 'loop': True, 'speed': 1}
                draft.source = text
                draft.source_content = content
                draft.mode = self.mode
                draft.key = key
                draft.loaded_key = key
                draft.action = blender.data.actions.get(state['clip'])
                draft.loop = state['loop']
                draft.speed = json.dumps(state['speed'])
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

    class BLENDLIB_OT_authoring_discard(blender.types.Operator):
        bl_idname = 'blendlib.authoring_discard'
        bl_label = 'Discard Draft'
        bl_description = 'Discard only this unsaved draft; leave every Text unchanged'

        def execute(self, context):
            draft = context.scene.blendlib_authoring_draft
            draft.active = False
            draft.source = None
            draft.source_content = ''
            draft.events.clear()
            return {'FINISHED'}

    class BLENDLIB_OT_authoring_event(blender.types.Operator):
        bl_idname = 'blendlib.authoring_event'
        bl_label = 'Edit Event Draft'
        operation: props.EnumProperty(items=[('ADD', 'Add', ''), ('REMOVE', 'Remove', ''), ('UP', 'Up', ''), ('DOWN', 'Down', '')])

        def execute(self, context):
            draft = context.scene.blendlib_authoring_draft
            if not draft.active:
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
        bl_description = 'Validate source references, then explicitly save this state to the selected Text (no export)'
        bl_options = {'UNDO'}

        def execute(self, context):
            scene = context.scene
            draft = scene.blendlib_authoring_draft
            try:
                if not draft.active:
                    raise ValueError('Load or create a draft first')
                if draft.mode == 'EDIT' and draft.key != draft.loaded_key:
                    raise ValueError('Renaming an existing state is not supported; reload the draft')
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
                replacement = editor.apply(content, mode=draft.mode, key=draft.key,
                    clip=draft.action.name if draft.action else '', loop=draft.loop,
                    speed=draft.speed, events=[{'marker': row.marker, 'event': row.event} for row in draft.events],
                    make_initial=draft.make_initial, actions=facts, fps=fps)
                if draft.mode == 'CREATE':
                    text = blender.data.texts.new('BlendLib.runtime.json')
                    text.write(replacement)
                    scene.blendlib_runtime_authoring_text = text
                else:
                    text.from_string(replacement)
                draft.active = False
                draft.source = None
                draft.source_content = ''
                draft.events.clear()
                self.report({'INFO'}, 'Applied to Text; save the .blend to persist. Export still validates GLB bounds and socket paths')
                return {'FINISHED'}
            except (ValueError, TypeError, KeyError, OverflowError, exporter.ExportError) as error:
                self.report({'ERROR'}, str(error)[:300])
                return {'CANCELLED'}

    class BLENDLIB_UL_authoring_events(blender.types.UIList):
        def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
            layout.label(text=item.marker or '(choose Action marker)')
            layout.label(text=item.event or '(event key)')

    class VIEW3D_PT_blendlib_authoring(blender.types.Panel):
        bl_label = 'Runtime State / Event Editor'
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
                if scene.blendlib_runtime_authoring_text:
                    layout.prop(scene, 'blendlib_authoring_state', text='State')
                    row = layout.row(align=True)
                    row.operator('blendlib.authoring_begin', text='Load State').mode = 'EDIT'
                    row.operator('blendlib.authoring_begin', text='Add State').mode = 'ADD'
                layout.operator('blendlib.authoring_begin', text='Start New Text').mode = 'CREATE'
                return
            layout.label(text='New Text draft' if draft.mode == 'CREATE' else 'Draft for ' + (draft.source.name if draft.source else '(missing Text)'))
            key_row = layout.row()
            key_row.enabled = draft.mode != 'EDIT'
            key_row.prop(draft, 'key')
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
            layout.label(text='Next/blend, sockets and rules stay in Text')
            row = layout.row(align=True)
            row.operator('blendlib.authoring_apply', text='Create New Text' if draft.mode == 'CREATE' else 'Apply to Selected Text')
            row.operator('blendlib.authoring_discard', text='Discard')

    _CLASSES = (BLENDLIB_PG_authoring_event, BLENDLIB_PG_authoring_draft,
                BLENDLIB_OT_authoring_begin, BLENDLIB_OT_authoring_discard,
                BLENDLIB_OT_authoring_event, BLENDLIB_OT_authoring_apply,
                BLENDLIB_UL_authoring_events, VIEW3D_PT_blendlib_authoring)
    for cls in _CLASSES:
        blender.utils.register_class(cls)
    blender.types.Scene.blendlib_authoring_state = props.EnumProperty(name='State', items=state_items, options=transient)
    blender.types.Scene.blendlib_authoring_draft = props.PointerProperty(type=BLENDLIB_PG_authoring_draft, options=transient)

    # SKIP_SAVE does not suppress Scene ID-property serialization in Blender.
    # Clear only working drafts at explicit lifecycle boundaries; saving an open
    # file does not discard current edits and export still reads only the Text.
    @blender.app.handlers.persistent
    def clear_loaded_drafts(_):
        _clear_drafts(blender)

    _LOAD_HANDLER = clear_loaded_drafts
    blender.app.handlers.load_post.append(_LOAD_HANDLER)
    _clear_drafts(blender)


def _clear_drafts(blender):
    for scene in blender.data.scenes:
        if hasattr(scene, 'blendlib_authoring_draft'):
            draft = scene.blendlib_authoring_draft
            draft.active = False
            draft.source = None
            draft.source_content = ''
            draft.events.clear()


def unregister(blender):
    global _CLASSES, _STATE_ITEMS, _LOAD_HANDLER
    if _LOAD_HANDLER in blender.app.handlers.load_post:
        blender.app.handlers.load_post.remove(_LOAD_HANDLER)
    _LOAD_HANDLER = None
    _clear_drafts(blender)
    for name in ('blendlib_authoring_state', 'blendlib_authoring_draft'):
        if hasattr(blender.types.Scene, name):
            delattr(blender.types.Scene, name)
    for cls in reversed(_CLASSES):
        blender.utils.unregister_class(cls)
    _CLASSES = ()
    _STATE_ITEMS = []
