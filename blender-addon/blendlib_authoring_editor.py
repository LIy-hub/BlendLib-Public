# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Lossless state-level edits to the canonical strict authoring Text.

No bpy, second parser, or exporter. Validation runs on a copy because the common
compiler normalizes locomotion numbers for runtime output, not source editing.
"""
from __future__ import annotations
import copy
import json
try:
    from . import blendlib_runtime_authoring as authoring
    from . import blendlib_cpu_morph as morph
except ImportError:
    import blendlib_runtime_authoring as authoring
    import blendlib_cpu_morph as morph


_UNCHANGED = object()


def load(text: str, actions: dict, fps: float, *, allow_morph_controls: bool = False) -> dict:
    config = authoring.parse(text, allow_morph_controls=allow_morph_controls)
    authoring.validate_source(copy.deepcopy(config), actions, fps)
    return config


def apply(text: str, *, mode: str, key: str, clip: str, loop: bool,
          speed: str, events: list, make_initial: bool, actions: dict, fps: float,
          next_state=_UNCHANGED, blend_seconds=_UNCHANGED, allow_morph_controls: bool = False) -> str:
    """Return validated replacement bytes; callers own identity/conflict checks."""
    if mode not in {'EDIT', 'ADD', 'CREATE'}:
        raise ValueError('Unknown authoring draft mode')
    config = ({'schema_version': 1, 'animation': {'initial_state': key, 'states': {}}}
              if mode == 'CREATE' else authoring.parse(text, allow_morph_controls=allow_morph_controls))
    animation = config.setdefault('animation', {'initial_state': key, 'states': {}})
    states = animation['states']
    if mode == 'EDIT' and key not in states:
        raise ValueError('The loaded state no longer exists; reload the draft')
    if mode != 'EDIT' and key in states:
        raise ValueError('State key already exists; load it to edit instead')
    # A JSON numeric token keeps double precision across an unchanged UI roundtrip.
    try:
        number = json.loads(speed)
    except (ValueError, RecursionError) as error:
        raise ValueError('Speed must be a finite JSON number in (0, 64]') from error
    state = copy.deepcopy(states[key]) if mode == 'EDIT' else {}
    state.update(clip=clip, loop=loop, speed=number)
    # None explicitly removes an optional field. Unspecified parameters preserve
    # older editor callers; the sidebar always supplies its deliberate selection.
    if next_state is not _UNCHANGED:
        if next_state is None:
            state.pop('next', None)
        else:
            state['next'] = next_state
    if blend_seconds is not _UNCHANGED:
        if blend_seconds is None:
            state.pop('blend_seconds', None)
        else:
            try:
                state['blend_seconds'] = json.loads(blend_seconds)
            except (ValueError, RecursionError) as error:
                raise ValueError('Blend In must be a finite JSON number >= 0') from error
    # Preserve absence vs an explicitly empty event list on a no-op edit.
    if events or 'events' in state:
        state['events'] = copy.deepcopy(events)
    states[key] = state
    if make_initial:
        animation['initial_state'] = key
    authoring.validate_source(copy.deepcopy(config), actions, fps)
    result = json.dumps(config, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    authoring.parse(result, allow_morph_controls=allow_morph_controls)  # Enforce canonical Text size after expansion as well.
    return result


def apply_socket(text: str, *, mode: str, key: str, node: str,
                 node_paths: set[str], actions: dict, fps: float, allow_morph_controls: bool = False) -> str:
    """Patch one node-only socket through the same strict source compiler."""
    if mode not in {'ADD', 'EDIT'}:
        raise ValueError('Unknown socket draft mode')
    config = load(text, actions, fps, allow_morph_controls=allow_morph_controls)
    sockets = config.setdefault('sockets', {})
    if not isinstance(sockets, dict):
        raise ValueError('sockets must be an object')
    if mode == 'EDIT' and key not in sockets:
        raise ValueError('The loaded socket no longer exists; reload the draft')
    if mode == 'ADD' and key in sockets:
        raise ValueError('Socket key already exists; load it to edit instead')
    if node not in node_paths:
        raise ValueError('Choose an exact discovered exported node')
    sockets[key] = {'node': node}
    authoring.validate_source(copy.deepcopy(config), actions, fps, node_paths=node_paths)
    result = json.dumps(config, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    authoring.parse(result, allow_morph_controls=allow_morph_controls)
    return result


def condition(*, kind: str, input_name: str, equals: bool, enter: str, exit: str) -> dict:
    """Map one explicitly typed UI row to an existing strict condition shape."""
    if kind == 'BOOL':
        return {'input': input_name, 'equals': equals}
    if kind not in {'MIN', 'MAX'}:
        raise ValueError('Choose Boolean, Number >= or Number <= for each condition')
    try:
        numbers = [json.loads(token) for token in (enter, exit)]
    except (ValueError, RecursionError) as error:
        raise ValueError('Enter and exit thresholds must be finite JSON numbers') from error
    suffix = kind.lower()
    return {'input': input_name, 'enter_' + suffix: numbers[0], 'exit_' + suffix: numbers[1]}


def apply_locomotion(text: str, *, default: str, interval: int, rules: list,
                     actions: dict, fps: float, allow_morph_controls: bool = False) -> str:
    """Patch only locomotion, preserving source numeric tokens and optional absence."""
    config = load(text, actions, fps, allow_morph_controls=allow_morph_controls)
    prior = config.get('locomotion', {})
    locomotion = {'schema_version': 1, 'default': default, 'rules': copy.deepcopy(rules)}
    # Zero is the existing runtime default; no-op editing must not add an absent key.
    if 'minimum_interval_ticks' in prior or interval != 0:
        locomotion['minimum_interval_ticks'] = interval
    # Validate even an omitted zero (e.g. False compares equal to 0 in Python).
    if type(interval) is not int or not 0 <= interval <= 200:
        raise ValueError('minimum_interval_ticks must be integer 0..200')
    config['locomotion'] = locomotion
    authoring.validate_source(copy.deepcopy(config), actions, fps)
    result = json.dumps(config, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    authoring.parse(result, allow_morph_controls=allow_morph_controls)
    return result


def apply_morph(text: str, *, mode: str, key: str, node: str, target: str,
                min_weight: str, max_weight: str, targets: list, actions: dict, fps: float) -> str:
    """Patch one CPU control using exact export discovery; never change geometry."""
    if mode not in {'CREATE', 'ADD', 'EDIT'}:
        raise ValueError('Unknown morph draft mode')
    config = {'schema_version': 1} if mode == 'CREATE' else load(text, actions, fps, allow_morph_controls=True)
    controls = config.setdefault('morph_controls', {})
    if mode == 'EDIT' and key not in controls:
        raise ValueError('The loaded control no longer exists; reload the draft')
    if mode != 'EDIT' and key in controls:
        raise ValueError('Control alias already exists; load it to edit instead')
    matches = [row for row in targets if (row['node'], row['target']) == (node, target)]
    if len(matches) != 1:
        raise ValueError('Choose an exact discovered exported mesh and shape-key target')
    try:
        low, high = json.loads(min_weight), json.loads(max_weight)
    except (ValueError, RecursionError) as error:
        raise ValueError('Declared weights must be finite JSON numbers') from error
    controls[key] = {'node': node, 'target': target, 'min_weight': low, 'max_weight': high}
    # Reuse CPU validation before numeric comparisons; bool is not a weight.
    morph.validate_controls(controls)
    authoring.validate_source(copy.deepcopy(config), actions, fps)
    if not low <= matches[0]['default'] <= high:
        raise ValueError('Declared interval must contain the authored shape-key default')
    result = json.dumps(config, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    authoring.parse(result, allow_morph_controls=True)
    return result
