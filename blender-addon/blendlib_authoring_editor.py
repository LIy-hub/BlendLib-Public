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
except ImportError:
    import blendlib_runtime_authoring as authoring


def load(text: str, actions: dict, fps: float) -> dict:
    config = authoring.parse(text)
    authoring.validate_source(copy.deepcopy(config), actions, fps)
    return config


def apply(text: str, *, mode: str, key: str, clip: str, loop: bool,
          speed: str, events: list, make_initial: bool, actions: dict, fps: float) -> str:
    """Return validated replacement bytes; callers own identity/conflict checks."""
    if mode not in {'EDIT', 'ADD', 'CREATE'}:
        raise ValueError('Unknown authoring draft mode')
    config = ({'schema_version': 1, 'animation': {'initial_state': key, 'states': {}}}
              if mode == 'CREATE' else authoring.parse(text))
    animation = config['animation']
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
    # Preserve absence vs an explicitly empty event list on a no-op edit.
    if events or 'events' in state:
        state['events'] = copy.deepcopy(events)
    states[key] = state
    if make_initial:
        animation['initial_state'] = key
    authoring.validate_source(copy.deepcopy(config), actions, fps)
    result = json.dumps(config, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    authoring.parse(result)  # Enforce canonical Text size after expansion as well.
    return result


def apply_socket(text: str, *, mode: str, key: str, node: str,
                 node_paths: set[str], actions: dict, fps: float) -> str:
    """Patch one node-only socket through the same strict source compiler."""
    if mode not in {'ADD', 'EDIT'}:
        raise ValueError('Unknown socket draft mode')
    config = load(text, actions, fps)
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
    authoring.parse(result)
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
                     actions: dict, fps: float) -> str:
    """Patch only locomotion, preserving source numeric tokens and optional absence."""
    config = load(text, actions, fps)
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
    authoring.parse(result)
    return result
