# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Roundtrip contracts for the explicit state/event editor."""
import copy
import json
from pathlib import Path
import sys
import unittest
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_authoring_editor as editor


class AuthoringEditorTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((Path(__file__).resolve().parents[2]/'test-assets/blender-authoring/runtime-authoring.json').read_text())
        self.actions = {name: (10., 34., [('Footstep', 22), ('Impact', 16)]) for name in ('Idle', 'Walk', 'Attack')}

    def apply(self, **overrides):
        args = dict(mode='EDIT', key='blendlib_authoring:walk', clip='Walk', loop=True,
                    speed='1', events=self.config['animation']['states']['blendlib_authoring:walk']['events'],
                    make_initial=False, actions=self.actions, fps=24.)
        args.update(overrides)
        return editor.apply(json.dumps(self.config), **args)

    def test_noop_preserves_full_config_and_absence(self):
        self.assertEqual(self.config, json.loads(self.apply()))
        self.assertEqual(self.config, json.loads(self.apply(key='blendlib_authoring:idle', clip='Idle', events=[])))
        self.config['animation']['states']['blendlib_authoring:idle']['events'] = []
        self.assertEqual(self.config, json.loads(self.apply(key='blendlib_authoring:idle', clip='Idle', events=[])))

    def test_advanced_fields_and_precise_numbers_remain(self):
        attack = self.config['animation']['states']['blendlib_authoring:attack']
        attack['speed'] = 1.23456789123456
        attack['blend_seconds'] = 0.123456789123456
        self.config['locomotion']['rules'][0]['conditions'][1]['enter_min'] = 10**180
        original = copy.deepcopy(self.config)
        output = json.loads(self.apply(key='blendlib_authoring:attack', clip='Attack', loop=False,
            speed=json.dumps(attack['speed']), events=attack['events']))
        self.assertEqual(original, output)
        self.assertEqual(original, self.config)

    def test_edit_only_selected_state_fields(self):
        output = json.loads(self.apply(speed='1.5'))
        wanted = copy.deepcopy(self.config)
        wanted['animation']['states']['blendlib_authoring:walk']['speed'] = 1.5
        self.assertEqual(wanted, output)

    def test_add_requires_unique_key_and_initial_is_explicit(self):
        output = json.loads(self.apply(mode='ADD', key='blendlib_authoring:run'))
        self.assertEqual('blendlib_authoring:idle', output['animation']['initial_state'])
        output = json.loads(self.apply(mode='ADD', key='blendlib_authoring:run', make_initial=True))
        self.assertEqual('blendlib_authoring:run', output['animation']['initial_state'])
        with self.assertRaises(ValueError): self.apply(mode='ADD')
        with self.assertRaises(ValueError): self.apply(key='blendlib_authoring:missing')

    def test_create_is_independent_of_existing_source(self):
        output = json.loads(editor.apply('{broken old Text', mode='CREATE', key='demo:idle',
            clip='Idle', loop=True, speed='1', events=[], make_initial=False, actions=self.actions, fps=24))
        self.assertEqual({'schema_version': 1, 'animation': {'initial_state': 'demo:idle',
            'states': {'demo:idle': {'clip': 'Idle', 'loop': True, 'speed': 1}}}}, output)

    def test_invalid_fields_and_rule_reference_constraints(self):
        for patch in [dict(speed='NaN'), dict(speed='true'), dict(speed='"1"'), dict(speed='0'),
                      dict(speed='65'), dict(speed='1e500'), dict(speed='1,2'), dict(clip='Absent'),
                      dict(loop=False), dict(key='wrong'), dict(events=[{'marker': 'Absent', 'event': 'x:e'}]),
                      dict(events=[{'marker': 'Footstep', 'event': 'bad'}])]:
            with self.subTest(patch=patch), self.assertRaises(ValueError): self.apply(**patch)
        self.config['animation']['states']['blendlib_authoring:walk']['future_field'] = 1
        with self.assertRaises(ValueError): self.apply()

    def test_source_checks_do_not_claim_exported_paths_or_bounds(self):
        editor.load(json.dumps(self.config), self.actions, 24.)
        with self.assertRaises(ValueError):
            editor.authoring.compile_authoring(self.config, self.actions,
                {key: (0., 1.) for key in self.actions}, {'Wrong/Path'}, 24.)
        with self.assertRaises(ValueError):
            editor.authoring.compile_authoring(self.config, self.actions,
                {key: (0., 2.) for key in self.actions}, {'Root/Hand'}, 24.)

    def test_events_keep_order_and_limits(self):
        events = [{'marker': 'Footstep', 'event': 'x:b'}, {'marker': 'Footstep', 'event': 'x:a'}]
        self.assertEqual(events, json.loads(self.apply(events=events))['animation']['states']['blendlib_authoring:walk']['events'])
        with self.assertRaises(ValueError): self.apply(events=events*2049)
        self.assertEqual([], json.loads(self.apply(events=[]))['animation']['states']['blendlib_authoring:walk']['events'])


if __name__ == '__main__':
    unittest.main()
