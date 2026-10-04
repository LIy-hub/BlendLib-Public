# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Transition controls patch only their selected strict state, without graph invention."""
import copy
import json
from pathlib import Path
import sys
import unittest
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_authoring_editor as editor


class TransitionEditorTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((Path(__file__).resolve().parents[2]/'test-assets/blender-authoring/runtime-authoring.json').read_text())
        self.actions = {name: (10., 34., [('Footstep', 22), ('Impact', 16)]) for name in ('Idle', 'Walk', 'Attack')}

    def apply(self, **changes):
        state = self.config['animation']['states']['blendlib_authoring:attack']
        args = dict(mode='EDIT', key='blendlib_authoring:attack', clip='Attack', loop=False, speed='1',
                    events=state['events'], make_initial=False, actions=self.actions, fps=24.,
                    next_state=state.get('next'), blend_seconds=json.dumps(state['blend_seconds']) if 'blend_seconds' in state else None)
        args.update(changes)
        return json.loads(editor.apply(json.dumps(self.config), **args))

    def test_precise_noop_preserves_every_section(self):
        self.config['animation']['states']['blendlib_authoring:attack']['blend_seconds'] = .12345678912345678
        self.config['locomotion']['rules'][0]['conditions'][1]['enter_min'] = 10**180
        original = copy.deepcopy(self.config)
        self.assertEqual(original, self.apply())
        self.assertEqual(original, self.config)

    def test_absent_explicit_zero_and_large_finite_blend_preserved(self):
        state = self.config['animation']['states']['blendlib_authoring:attack']
        for value in (0, -0.0, 1e-100, 10**180):
            state['blend_seconds'] = value
            self.assertEqual(self.config, self.apply())
        del state['blend_seconds']
        del state['next']
        self.assertEqual(self.config, self.apply())

    def test_none_and_unchecked_remove_only_optional_fields(self):
        expected = copy.deepcopy(self.config)
        expected['animation']['states']['blendlib_authoring:attack'].pop('next')
        expected['animation']['states']['blendlib_authoring:attack'].pop('blend_seconds')
        self.assertEqual(expected, self.apply(next_state=None, blend_seconds=None))

    def test_target_replacement_and_precise_blend_are_explicit(self):
        expected = copy.deepcopy(self.config)
        expected['animation']['states']['blendlib_authoring:attack'].update(next='blendlib_authoring:walk', blend_seconds=.12345678912345678)
        self.assertEqual(expected, self.apply(next_state='blendlib_authoring:walk', blend_seconds='0.12345678912345678'))

    def test_invalid_blend_tokens_and_references_fail_without_mutation(self):
        original = copy.deepcopy(self.config)
        for bad in ('NaN', 'Infinity', 'true', 'null', '"0.2"', '[]', '{}', '-1', '1e500', '', '.2', '9'*5000):
            with self.subTest(bad=bad[:40]), self.assertRaises(ValueError): self.apply(blend_seconds=bad)
        for bad in ('', '/', 'demo:missing', 1, False):
            with self.subTest(next=bad), self.assertRaises(ValueError): self.apply(next_state=bad)
        self.assertEqual(original, self.config)

    def test_existing_contract_allows_self_cycle_and_loop_next(self):
        self.assertEqual('blendlib_authoring:attack', self.apply(next_state='blendlib_authoring:attack')['animation']['states']['blendlib_authoring:attack']['next'])
        self.config.pop('locomotion')
        self.config['animation']['states']['blendlib_authoring:idle'].update(loop=False, next='blendlib_authoring:attack')
        self.assertEqual(self.config, self.apply())
        result = self.apply(loop=True)
        self.assertTrue(result['animation']['states']['blendlib_authoring:attack']['loop'])
        self.assertEqual('blendlib_authoring:idle', result['animation']['states']['blendlib_authoring:attack']['next'])

    def test_locomotion_target_cannot_gain_next_or_lose_loop(self):
        for loop, target in [(True, 'blendlib_authoring:idle'), (True, 'blendlib_authoring:walk'), (False, None)]:
            with self.assertRaisesRegex(ValueError, 'continuous loops'):
                self.apply(key='blendlib_authoring:walk', clip='Walk', loop=loop, next_state=target, events=[], blend_seconds=None)
        self.assertEqual(.25, self.apply(key='blendlib_authoring:idle', clip='Idle', loop=True, next_state=None, events=[], blend_seconds='0.25')['animation']['states']['blendlib_authoring:idle']['blend_seconds'])

    def test_new_state_can_reference_itself_but_not_uncreated_targets(self):
        result = self.apply(mode='ADD', key='demo:self', next_state='demo:self')
        self.assertEqual('demo:self', result['animation']['states']['demo:self']['next'])
        with self.assertRaises(ValueError): self.apply(mode='ADD', key='demo:new', next_state='demo:later')
        result = self.apply(mode='CREATE', key='demo:self', next_state='demo:self')
        self.assertEqual({'demo:self'}, set(result['animation']['states']))

    def test_deleted_target_or_source_state_is_not_silently_repaired(self):
        self.config.pop('locomotion')
        del self.config['animation']['states']['blendlib_authoring:walk']
        with self.assertRaises(ValueError): self.apply(next_state='blendlib_authoring:walk')
        self.config['animation']['states']['demo:renamed'] = self.config['animation']['states'].pop('blendlib_authoring:idle')
        self.config['animation']['initial_state'] = 'demo:renamed'
        with self.assertRaisesRegex(ValueError, 'next must name'): self.apply()


if __name__ == '__main__':
    unittest.main()
