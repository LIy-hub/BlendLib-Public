# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Explicit runtime authoring v1 compiler. Never reads legacy X5 metadata.

Pure Python on purpose: Blender provides Action markers, effective FPS and actual
exported clip bounds/node paths; Java still consumes only existing strict assets.
"""
from __future__ import annotations

import json
import math
import re
from typing import Any

MAX_TEXT_BYTES = 1024 * 1024
RESOURCE_ID = re.compile(r"[a-z0-9._-]+:[a-z0-9._/-]+\Z")
INPUT_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}\Z")


def _object(value: Any, allowed: set[str], required: set[str], label: str) -> dict:
    if not isinstance(value, dict) or set(value) - allowed or required - set(value):
        raise ValueError(f"{label}: expected object with required {sorted(required)} and only {sorted(allowed)}")
    return value


def _name(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 2048:
        raise ValueError(f"{label}: expected nonblank string of at most 2048 characters")
    return value


def _resource(value: Any, label: str) -> str:
    value = _name(value, label)
    if not RESOURCE_ID.fullmatch(value) or any(p in ('', '.', '..') for p in value.split(':', 1)[1].split('/')):
        raise ValueError(f"{label}: expected namespaced resource ID with safe path segments")
    return value


def _number(value: Any, label: str, low: float | None = None, high: float | None = None) -> float:
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f"{label}: expected finite number")
    if low is not None and value < low or high is not None and value > high:
        raise ValueError(f"{label}: number outside supported bounds")
    return float(value)


def _array(value: Any, limit: int, label: str) -> list:
    if not isinstance(value, list) or len(value) > limit:
        raise ValueError(f"{label}: expected array with at most {limit} entries")
    return value


def parse(text: str) -> dict:
    """Bounded strict JSON; duplicate keys/NaN/Infinity are never accepted."""
    if len(text) > MAX_TEXT_BYTES or len(text.encode('utf-8')) > MAX_TEXT_BYTES:
        raise ValueError('runtime authoring Text exceeds 1 MiB')

    def unique(pairs: list) -> dict:
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('runtime authoring contains a duplicate JSON key')
            result[key] = value
        return result

    def constant(_: str) -> None:
        raise ValueError('runtime authoring contains a non-finite JSON constant')

    try:
        result = json.loads(text, object_pairs_hook=unique, parse_constant=constant)
    except (json.JSONDecodeError, RecursionError) as error:
        raise ValueError('runtime authoring Text is not valid bounded JSON') from error
    root = _object(result, {'schema_version', 'animation', 'sockets', 'locomotion'}, {'schema_version', 'animation'}, 'root')
    if type(root['schema_version']) is not int or root['schema_version'] != 1:
        raise ValueError('runtime authoring schema_version must be integer 1')
    return root


def compile_authoring(config: dict, actions: dict, clips: dict, node_paths: set[str], fps: float) -> tuple[dict, dict | None]:
    """Compile authoring JSON against exact source Actions and exported GLB facts.

    actions maps name to (start_frame, end_frame, [(marker_name, frame), ...]);
    clips maps name to (first_sample_seconds, last_sample_seconds).
    """
    return _compile_authoring(config, actions, clips, node_paths, fps)


def validate_source(config: dict, actions: dict, fps: float) -> None:
    """Validate authoring against source Actions, without claiming GLB verification.

    The editor shares every schema/state/event/rule check with the exporter.
    Actual exported clip membership, sample bounds and socket paths remain export
    checks because source-only editing cannot establish those facts.
    """
    _compile_authoring(config, actions, None, None, fps)


def _compile_authoring(config: dict, actions: dict, clips: dict | None,
                       node_paths: set[str] | None, fps: float) -> tuple[dict, dict | None]:
    fps = _number(fps, 'effective FPS')
    if fps <= 0:
        raise ValueError('effective FPS must be positive')
    animation = _object(config['animation'], {'initial_state', 'states'}, {'initial_state', 'states'}, 'animation')
    states = animation['states']
    if not isinstance(states, dict) or not 1 <= len(states) <= 256:
        raise ValueError('states: expected 1..256 states')
    output = {}
    total_events = 0
    for key, raw in states.items():
        _resource(key, 'state key')
        state = _object(raw, {'clip', 'loop', 'speed', 'next', 'blend_seconds', 'events'}, {'clip', 'loop', 'speed'}, 'state')
        clip = _name(state['clip'], 'clip')
        if clip not in actions or (clips is not None and clip not in clips):
            raise ValueError(f"clip '{clip}' is not an attached Action exported to the GLB")
        if type(state['loop']) is not bool:
            raise ValueError('loop must be boolean')
        speed = _number(state['speed'], 'speed', 0, 64)
        if speed == 0:
            raise ValueError('speed must be positive')
        item = {'clip': clip, 'loop': state['loop'], 'speed': speed}
        if 'next' in state:
            target = _resource(state['next'], 'next')
            if target not in states:
                raise ValueError('next must name an authored state')
            item['next'] = target
        if 'blend_seconds' in state:
            item['blend_seconds'] = _number(state['blend_seconds'], 'blend_seconds', 0)
        start, end, markers = actions[clip]
        duration = (end - start) / fps
        first, last = clips[clip] if clips is not None else (0., duration)
        if not math.isfinite(duration) or duration <= 0 or duration > 600 or abs(first) > 1e-6 or not math.isclose(last, duration, abs_tol=1e-5, rel_tol=1e-6):
            raise ValueError(f"clip '{clip}' must export its full Action range at time zero (duration <=600s)")
        if 'events' in state:
            source_events = _array(state['events'], 4096, 'events')
            total_events += len(source_events)
            if total_events > 16384:
                raise ValueError('descriptor visual-event total exceeds 16384')
            events = []
            for raw_event in source_events:
                event = _object(raw_event, {'marker', 'event'}, {'marker', 'event'}, 'event')
                marker = _name(event['marker'], 'marker')
                matches = [frame for name, frame in markers if name == marker]
                if len(matches) != 1:
                    raise ValueError(f"marker '{marker}' must occur exactly once in Action '{clip}'")
                frame = _number(matches[0], 'marker frame', start, end)
                # Clamp only floating point end-rounding to the actual runtime duration.
                time = min((frame - start) / fps, last)
                events.append({'time_seconds': time, 'event': _resource(event['event'], 'event key')})
            item['events'] = sorted(events, key=lambda value: value['time_seconds'])
        output[key] = item
    initial = _resource(animation['initial_state'], 'initial_state')
    if initial not in output:
        raise ValueError('initial_state must name an authored state')
    descriptor = {'animation': {'initial_state': initial, 'states': output}}
    if 'sockets' in config:
        sockets = config['sockets']
        if not isinstance(sockets, dict) or len(sockets) > 512:
            raise ValueError('sockets: expected object with at most 512 entries')
        descriptor['sockets'] = {}
        for key, raw in sockets.items():
            _resource(key, 'socket key')
            value = _object(raw, {'node'}, {'node'}, 'socket')
            node = _name(value['node'], 'socket node')
            if (node_paths is not None and node not in node_paths) or node.startswith('/') or node.endswith('/') or '//' in node:
                raise ValueError(f"socket '{key}' must name an exact full exported node path")
            descriptor['sockets'][key] = {'node': node}
    rules = validate_locomotion(config['locomotion'], output) if 'locomotion' in config else None
    return descriptor, rules


def validate_locomotion(value: Any, states: dict) -> dict:
    """Mirror the existing strict runtime sidecar, including semantic constraints."""
    root = _object(value, {'schema_version', 'default', 'minimum_interval_ticks', 'rules'}, {'schema_version', 'default', 'rules'}, 'locomotion')
    if type(root['schema_version']) is not int or root['schema_version'] != 1:
        raise ValueError('locomotion schema_version must be integer 1')

    def target(key: Any) -> str:
        key = _resource(key, 'locomotion target')
        if key not in states or not states[key]['loop'] or 'next' in states[key]:
            raise ValueError('locomotion targets must be authored continuous loops without next')
        return key

    target(root['default'])
    interval = root.get('minimum_interval_ticks', 0)
    if type(interval) is not int or not 0 <= interval <= 200:
        raise ValueError('minimum_interval_ticks must be integer 0..200')
    inputs = {}
    for raw in _array(root['rules'], 32, 'rules'):
        rule = _object(raw, {'animation', 'conditions'}, {'animation', 'conditions'}, 'rule')
        target(rule['animation'])
        for raw_condition in _array(rule['conditions'], 8, 'conditions'):
            if not isinstance(raw_condition, dict):
                raise ValueError('condition must be an object')
            fields = ({'input', 'equals'} if 'equals' in raw_condition else
                      {'input', 'enter_min', 'exit_min'} if 'enter_min' in raw_condition or 'exit_min' in raw_condition else
                      {'input', 'enter_max', 'exit_max'})
            condition = _object(raw_condition, fields, fields, 'condition')
            if not isinstance(condition['input'], str) or not INPUT_NAME.fullmatch(condition['input']):
                raise ValueError('input must be an ASCII identifier of at most 64 characters')
            input_type = 'boolean' if 'equals' in condition else 'number'
            prior = inputs.setdefault(condition['input'], input_type)
            if prior != input_type or len(inputs) > 32:
                raise ValueError('locomotion requires at most 32 uniquely typed inputs')
            if 'equals' in condition:
                if type(condition['equals']) is not bool:
                    raise ValueError('equals must be boolean')
            else:
                suffix = 'min' if 'enter_min' in condition else 'max'
                enter = _number(condition['enter_' + suffix], 'enter threshold')
                leave = _number(condition['exit_' + suffix], 'exit threshold')
                # Serialize finite doubles rather than arbitrarily long integer tokens.
                condition['enter_' + suffix] = enter
                condition['exit_' + suffix] = leave
                if (suffix == 'min' and leave > enter) or (suffix == 'max' and enter > leave):
                    raise ValueError('invalid locomotion hysteresis threshold order')
    encoded = json.dumps(root, ensure_ascii=False, sort_keys=True, indent=2).encode('utf-8') + b'\n'
    if len(encoded) > 64 * 1024:
        raise ValueError('locomotion sidecar exceeds runtime 64 KiB limit')
    return root
