# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Lossless structured locomotion patch contracts, through the common compiler."""
import copy
import json
from pathlib import Path
import sys
import unittest
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_authoring_editor as editor


class RulesEditorTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((Path(__file__).resolve().parents[2]/'test-assets/blender-authoring/runtime-authoring.json').read_text())
        self.actions = {name: (10., 34., [('Footstep', 22), ('Impact', 16)]) for name in ('Idle', 'Walk', 'Attack')}

    def apply(self, **overrides):
        args = dict(default=self.config['locomotion']['default'], interval=self.config['locomotion'].get('minimum_interval_ticks', 0),
                    rules=self.config['locomotion']['rules'], actions=self.actions, fps=24.)
        args.update(overrides)
        return json.loads(editor.apply_locomotion(json.dumps(self.config), **args))

    def test_noop_preserves_every_section_and_optional_absence(self):
        self.assertEqual(self.config, self.apply())
        del self.config['locomotion']['minimum_interval_ticks']
        self.assertEqual(self.config, self.apply())
        self.assertNotIn('minimum_interval_ticks', self.apply()['locomotion'])
        self.assertEqual(4, self.apply(interval=4)['locomotion']['minimum_interval_ticks'])

    def test_preserve_precise_numbers_and_untouched_fields(self):
        self.config['animation']['states']['blendlib_authoring:attack']['speed'] = 1.234567891234567
        row = self.config['locomotion']['rules'][0]['conditions'][1]
        row.update(enter_min=10**180, exit_min=0.01234567891234567)
        expected = copy.deepcopy(self.config)
        self.assertEqual(expected, self.apply())
        self.assertEqual(expected, self.config)
        self.assertEqual(row, editor.condition(kind='MIN', input_name='speed', equals=True,
            enter=json.dumps(row['enter_min']), exit=json.dumps(row['exit_min'])))

    def test_explicit_add_preserves_source_and_requires_valid_default(self):
        content = copy.deepcopy(self.config)
        del content['locomotion']
        output = json.loads(editor.apply_locomotion(json.dumps(content), default='blendlib_authoring:idle', interval=0,
            rules=[], actions=self.actions, fps=24))
        self.assertEqual(content, {k: v for k, v in output.items() if k != 'locomotion'})
        self.assertEqual([], output['locomotion']['rules'])
        for key in ('/', '', 'blendlib_authoring:missing', 'blendlib_authoring:attack'):
            with self.subTest(key=key), self.assertRaises(ValueError): self.apply(default=key)

    def test_order_and_removal_are_exactly_deliberate(self):
        rules = copy.deepcopy(self.config['locomotion']['rules'])
        rules += [{'animation': 'blendlib_authoring:idle', 'conditions': []}]
        self.assertEqual(rules, self.apply(rules=rules)['locomotion']['rules'])
        self.assertEqual(list(reversed(rules)), self.apply(rules=list(reversed(rules)))['locomotion']['rules'])
        self.assertEqual([], self.apply(rules=[])['locomotion']['rules'])

    def test_typed_shapes_reject_wrong_numeric_values_and_ambiguous_bounds(self):
        for token in ('NaN', 'Infinity', 'true', '"1"', 'null', '{}', '[]', '1e500', '1,2', '9'*5000):
            with self.subTest(token=token[:30]), self.assertRaises((ValueError, OverflowError)):
                self.apply(rules=[{'animation': 'blendlib_authoring:walk', 'conditions': [
                    editor.condition(kind='MIN', input_name='speed', equals=False, enter=token, exit='0')]}])
        for row in ({'input': 'speed', 'equals': True, 'enter_min': 1, 'exit_min': 0},
                    {'input': 'speed', 'enter_min': 1, 'exit_min': 0, 'enter_max': 2, 'exit_max': 3},
                    {'input': 'speed', 'enter_min': 0, 'exit_min': 1},
                    {'input': 'speed', 'enter_max': 1, 'exit_max': 0},
                    {'input': 'grounded', 'equals': 1}):
            with self.subTest(row=row), self.assertRaises(ValueError):
                self.apply(rules=[{'animation': 'blendlib_authoring:walk', 'conditions': [row]}])

    def test_valid_boolean_min_max_and_mixed_type_input_rejection(self):
        conditions = [editor.condition(kind='BOOL', input_name='grounded', equals=False, enter='ignored', exit='ignored'),
                      editor.condition(kind='MIN', input_name='speed', equals=False, enter='0.1', exit='0.05'),
                      editor.condition(kind='MAX', input_name='slope', equals=False, enter='0.5', exit='0.75')]
        self.assertEqual(False, conditions[0]['equals'])
        self.apply(rules=[{'animation': 'blendlib_authoring:walk', 'conditions': conditions}])
        conditions.append({'input': 'speed', 'equals': True})
        with self.assertRaises(ValueError): self.apply(rules=[{'animation': 'blendlib_authoring:walk', 'conditions': conditions}])
        with self.assertRaises(ValueError): editor.condition(kind='RANGE', input_name='x', equals=True, enter='0', exit='1')

    def test_limits_interval_and_no_next_targets(self):
        rule = self.config['locomotion']['rules'][0]
        self.apply(rules=[rule]*32)
        with self.assertRaises(ValueError): self.apply(rules=[rule]*33)
        for interval in (True, 0., -1, 201, '0'):
            with self.subTest(interval=interval), self.assertRaises(ValueError): self.apply(interval=interval)
        for interval in (0, 200): self.apply(interval=interval)
        conditions = [{'input': 'grounded', 'equals': True}]*9
        with self.assertRaises(ValueError): self.apply(rules=[{'animation': 'blendlib_authoring:walk', 'conditions': conditions}])
        self.config['animation']['states']['blendlib_authoring:walk']['next'] = 'blendlib_authoring:idle'
        with self.assertRaises(ValueError): self.apply()

    def test_invalid_source_is_never_silently_repaired_or_dropped(self):
        self.config['locomotion']['future'] = 1
        with self.assertRaises(ValueError): self.apply()


if __name__ == '__main__': unittest.main()
