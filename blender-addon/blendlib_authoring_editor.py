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
