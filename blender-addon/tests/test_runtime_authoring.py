# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Pure compiler contracts; these do not substitute real Blender acceptance."""
import copy
import json
from pathlib import Path
import sys
import unittest
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_runtime_authoring as authoring

ROOT = Path(__file__).resolve().parents[2]

class RuntimeAuthoringTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((ROOT/'test-assets/blender-authoring/runtime-authoring.json').read_text())
        self.actions = {name: (10., 34., [('Footstep', 22), ('Impact', 16)]) for name in ('Idle', 'Walk', 'Attack')}
        self.clips = {name: (0., 1.) for name in self.actions}

    def compile(self):
        return authoring.compile_authoring(authoring.parse(json.dumps(self.config)), self.actions, self.clips, {'Root', 'Root/Hand', 'Root/Body'}, 24.)

    def test_success_exact_mapping_and_events(self):
        descriptor, rules = self.compile()
        states = descriptor['animation']['states']
        self.assertEqual(.5, states['blendlib_authoring:walk']['events'][0]['time_seconds'])
        self.assertEqual(.25, states['blendlib_authoring:attack']['events'][0]['time_seconds'])
        self.assertEqual('blendlib_authoring:idle', states['blendlib_authoring:attack']['next'])
        self.assertEqual('Root/Hand', descriptor['sockets']['blendlib_authoring:hand']['node'])
        self.assertEqual(self.config['locomotion'], rules)
        self.assertNotIn('locomotion', descriptor)

    def test_strict_json(self):
        for raw in ['{"schema_version":1,"schema_version":1}', '{"schema_version":NaN}', '{"schema_version":Infinity}', '{bad', '['*2000+']'*2000, ' '*1048577]:
            with self.subTest(raw=raw[:50]), self.assertRaises(ValueError):
                authoring.parse(raw)

    def test_invalid_contracts(self):
        cases = [
            ('schema_version', True), ('schema_version', 1.0), ('schema_version', 2), ('old_x5_field', {}),
            ('animation.initial_state', 'blendlib_authoring:missing'),
            ('animation.states.blendlib_authoring:walk.clip', 'Missing'),
            ('animation.states.blendlib_authoring:walk.loop', 1),
            ('animation.states.blendlib_authoring:walk.speed', 0),
            ('animation.states.blendlib_authoring:walk.speed', 65),
            ('animation.states.blendlib_authoring:walk.speed', True),
            ('animation.states.blendlib_authoring:walk.blend_seconds', -1),
            ('animation.states.blendlib_authoring:walk.next', 'blendlib_authoring:missing'),
            ('animation.states.blendlib_authoring:walk.unknown', 0),
            ('animation.states.blendlib_authoring:walk.events', [{'time_seconds': .5, 'event': 'x:e'}]),
            ('animation.states.blendlib_authoring:walk.events', [{'marker': 'Missing', 'event': 'x:e'}]),
            ('animation.states.blendlib_authoring:walk.events', [{'marker': 'Footstep', 'event': 'x:../e'}]),
            ('sockets.blendlib_authoring:hand.node', 'Hand'),
            ('sockets.blendlib_authoring:hand.offset', [1, 2, 3]),
            ('locomotion.default', 'blendlib_authoring:attack'),
            ('locomotion.default', 'blendlib_authoring:missing'),
            ('locomotion.minimum_interval_ticks', 201),
            ('locomotion.minimum_interval_ticks', 1.0),
            ('locomotion.minimum_interval_ticks', True),
            ('locomotion.schema_version', 2),
            ('locomotion.rules', [{'animation': 'blendlib_authoring:walk', 'conditions': [{'input': 'bad name', 'equals': True}]}]),
            ('locomotion.rules', [{'animation': 'blendlib_authoring:walk', 'conditions': [{'input': 'speed', 'enter_min': 1, 'exit_min': 2}]}]),
            ('locomotion.rules', [{'animation': 'blendlib_authoring:walk', 'conditions': [{'input': 'speed', 'enter_max': 2, 'exit_max': 1}]}]),
            ('locomotion.rules', [{'animation': 'blendlib_authoring:walk', 'conditions': [{'input': 'grounded', 'equals': 1}]}]),
            ('locomotion.rules', [{'animation': 'blendlib_authoring:walk', 'conditions': [{'input': 'grounded', 'equals': True, 'enter_min': 0}]}]),
        ]
        original = copy.deepcopy(self.config)
        for path, value in cases:
            with self.subTest(path=path, value=value):
                self.config = copy.deepcopy(original)
                node = self.config
                parts = path.split('.')
                for part in parts[:-1]:
                    node = node[part]
                node[parts[-1]] = value
                with self.assertRaises(ValueError): self.compile()

    def test_actual_export_bounds_and_markers(self):
        for action in [(10., 34., [('Footstep', 9)]), (10., 34., [('Footstep', 35)]),
                       (10., 34., [('Footstep', 22), ('Footstep', 22)])]:
            self.actions['Walk'] = action
            with self.assertRaises(ValueError): self.compile()
        self.actions['Walk'] = (10., 34., [('Footstep', 34)])
        self.assertEqual(1., self.compile()[0]['animation']['states']['blendlib_authoring:walk']['events'][0]['time_seconds'])
        for bounds in [(.1, 1), (0, 2), (0, 0)]:
            self.clips['Walk'] = bounds
            with self.assertRaises(ValueError): self.compile()

    def test_non_integer_effective_fps(self):
        fps = 24/1.001
        clips = {name: (0, 24/fps) for name in self.actions}
        result = authoring.compile_authoring(self.config, self.actions, clips, {'Root/Hand'}, fps)
        self.assertAlmostEqual(.5005, result[0]['animation']['states']['blendlib_authoring:walk']['events'][0]['time_seconds'])

    def test_locomotion_cannot_target_loop_with_next(self):
        self.config['animation']['states']['blendlib_authoring:walk']['next'] = 'blendlib_authoring:idle'
        with self.assertRaises(ValueError): self.compile()

    def test_locomotion_large_finite_threshold_normalization(self):
        condition = self.config['locomotion']['rules'][0]['conditions'][1]
        condition['enter_min'] = 10**200
        result = self.compile()[1]
        serialized = json.dumps(result)
        self.assertIn('1e+200', serialized)
        self.assertNotIn(str(10**200), serialized)

    def test_locomotion_input_contract(self):
        rules = self.config['locomotion']['rules']
        rules[0]['conditions'].append({'input': 'speed', 'equals': True})
        with self.assertRaises(ValueError): self.compile()
        rules.clear()
        for i in range(4):
            rules.append({'animation':'blendlib_authoring:walk', 'conditions':[
                {'input':f'input{j}', 'equals':True} for j in range(i*8,(i+1)*8)]})
        self.compile()
        rules.append({'animation':'blendlib_authoring:walk', 'conditions':[{'input':'extra','equals':True}]})
        with self.assertRaises(ValueError): self.compile()

    def test_total_event_limit(self):
        del self.config['locomotion']
        states = self.config['animation']['states']
        states.clear()
        for i in range(4):
            states[f'x:s{i}'] = {'clip': 'Walk', 'loop': True, 'speed': 1,
                'events': [{'marker': 'Footstep', 'event': 'x:e'}]*4096}
        self.config['animation']['initial_state'] = 'x:s0'
        self.assertEqual(16384, sum(len(s['events']) for s in self.compile()[0]['animation']['states'].values()))
        states['x:extra'] = {'clip':'Walk','loop':True,'speed':1,'events':[{'marker':'Footstep','event':'x:e'}]}
        with self.assertRaises(ValueError): self.compile()

    def test_bounds(self):
        original = copy.deepcopy(self.config)
        for mutate in [lambda c: c['animation']['states'].update({f'x:s{i}': {'clip':'Idle','loop':True,'speed':1} for i in range(256)}),
                       lambda c: c.update(sockets={f'x:s{i}': {'node':'Root/Hand'} for i in range(513)}),
                       lambda c: c['animation']['states']['blendlib_authoring:walk'].update(events=[{'marker':'Footstep','event':'x:e'}]*4097),
                       lambda c: c['locomotion'].update(rules=c['locomotion']['rules']*33),
                       lambda c: c['locomotion']['rules'][0].update(conditions=[{'input':'grounded','equals':True}]*9)]:
            self.config = copy.deepcopy(original); mutate(self.config)
            with self.assertRaises(ValueError): self.compile()

if __name__ == '__main__': unittest.main()
