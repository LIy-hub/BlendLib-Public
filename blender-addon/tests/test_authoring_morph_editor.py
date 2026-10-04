# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Pure, lossless morph-control draft contracts; Blender owns discovery/fences."""
import copy
import json
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_authoring_editor as editor


class MorphAuthoringEditorTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((Path(__file__).resolve().parents[2] /
            'test-assets/blender-authoring/runtime-authoring.json').read_text())
        self.config['morph_controls'] = {
            'face:smile': {'node': 'Root/Face', 'target': 'Smile', 'min_weight': -1, 'max_weight': 1},
            'face:breath': {'node': 'Root/Face', 'target': 'Breath', 'min_weight': -.5, 'max_weight': 1},
        }
        self.targets = [
            {'node': 'Root/Face', 'target': 'Smile', 'default': 0},
            {'node': 'Root/Face', 'target': 'Breath', 'default': .15},
            {'node': 'Root/Face', 'target': 'Squeeze', 'default': 0},
            {'node': 'Root/Other', 'target': 'OtherSmile', 'default': 0},
        ]
        self.actions = {name: (10., 34., [('Footstep', 22), ('Impact', 16)])
                        for name in ('Idle', 'Walk', 'Attack')}

    def apply(self, text=None, **changes):
        args = dict(mode='EDIT', key='face:smile', node='Root/Face', target='Smile',
                    min_weight='-1', max_weight='1', targets=self.targets,
                    actions=self.actions, fps=24)
        args.update(changes)
        return editor.apply_morph(json.dumps(self.config) if text is None else text, **args)

    def test_noop_preserves_states_events_sockets_rules_and_all_controls(self):
        original = copy.deepcopy(self.config)
        targets = copy.deepcopy(self.targets)
        self.assertEqual(original, json.loads(self.apply()))
        self.assertEqual(original, self.config)
        self.assertEqual(targets, self.targets)

    def test_create_manual_only_text_does_not_parse_old_text_or_make_animation(self):
        result = json.loads(self.apply('{broken old Text', mode='CREATE', actions={}))
        self.assertEqual({'schema_version': 1,
                         'morph_controls': {'face:smile': self.config['morph_controls']['face:smile']}}, result)
        self.assertNotIn('animation', result)

    def test_create_and_add_need_no_actions(self):
        first = self.apply('', mode='CREATE', actions={})
        second = self.apply(first, mode='ADD', key='face:squeeze', target='Squeeze', actions={})
        result = json.loads(second)
        self.assertEqual(set(result), {'schema_version', 'morph_controls'})
        self.assertEqual(set(result['morph_controls']), {'face:smile', 'face:squeeze'})

    def test_add_patches_only_new_control(self):
        output = json.loads(self.apply(mode='ADD', key='face:squeeze', target='Squeeze'))
        wanted = copy.deepcopy(self.config)
        wanted['morph_controls']['face:squeeze'] = {
            'node': 'Root/Face', 'target': 'Squeeze', 'min_weight': -1, 'max_weight': 1}
        self.assertEqual(wanted, output)

    def test_add_first_control_to_existing_animation_text(self):
        del self.config['morph_controls']
        output = json.loads(self.apply(mode='ADD'))
        self.assertEqual(self.config, {k: v for k, v in output.items() if k != 'morph_controls'})
        self.assertEqual(['face:smile'], list(output['morph_controls']))

    def test_edit_changes_only_selected_binding_and_interval(self):
        wanted = copy.deepcopy(self.config)
        wanted['morph_controls']['face:smile'].update(target='Squeeze', min_weight=-2, max_weight=.75)
        self.assertEqual(wanted, json.loads(self.apply(target='Squeeze', min_weight='-2', max_weight='0.75')))

    def test_numeric_precision_and_optional_absence_survive(self):
        self.config['morph_controls']['face:smile']['min_weight'] = -.12345678912345678
        self.config['animation']['states']['blendlib_authoring:attack']['blend_seconds'] = .12345678912345678
        self.config['locomotion']['rules'][0]['conditions'][1]['enter_min'] = 10**180
        self.assertEqual(self.config, json.loads(self.apply(min_weight='-0.12345678912345678')))
        del self.config['sockets']
        del self.config['locomotion']
        self.assertEqual(self.config, json.loads(self.apply(min_weight='-0.12345678912345678')))

    def test_duplicate_alias_missing_edit_and_unknown_mode_rejected(self):
        for changes in ({'mode': 'ADD'}, {'key': 'face:missing'}, {'mode': 'DELETE'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.apply(**changes)

    def test_alias_must_be_valid_namespaced_resource_id(self):
        for key in ('', 'smile', 'Face:smile', 'face:../smile', 'face:/smile', 'face:smile//x', 'face:' + 'x'*256):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.apply(mode='ADD', key=key, target='Squeeze')

    def test_duplicate_node_target_under_another_alias_rejected(self):
        with self.assertRaises(ValueError):
            self.apply(mode='ADD', key='face:duplicate')
        with self.assertRaises(ValueError):
            self.apply(target='Breath')

    def test_exact_discovered_pair_required_case_and_hierarchy_matters(self):
        for changes in ({'node': 'Face'}, {'node': 'Root/Other'}, {'node': 'Root/face'},
                        {'target': 'smile'}, {'target': 'Basis'}, {'target': 'Absent'},
                        {'target': 'OtherSmile'}, {'targets': []}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.apply(**changes)

    def test_intervals_are_finite_numeric_include_zero_and_bounded(self):
        for low, high in (('NaN', '1'), ('-1', 'Infinity'), ('-1', '1e500'),
                          ('false', '1'), ('-1', 'true'), ('"-1"', '1'),
                          ('null', '1'), ('[]', '1'), ('-1,0', '1'),
                          ('0.1', '1'), ('-1', '-0.1'), ('-2.01', '1'), ('-1', '2.01')):
            with self.subTest(low=low, high=high), self.assertRaises(ValueError):
                self.apply(min_weight=low, max_weight=high)

    def test_zero_interval_and_closed_limits_are_valid(self):
        for low, high in (('0', '0'), ('-2', '2'), ('0', '2'), ('-2', '0')):
            result = json.loads(self.apply(min_weight=low, max_weight=high))
            self.assertEqual(json.loads(low), result['morph_controls']['face:smile']['min_weight'])
            self.assertEqual(json.loads(high), result['morph_controls']['face:smile']['max_weight'])

    def test_nonzero_default_must_fit_selected_interval_and_is_never_rewritten(self):
        before = copy.deepcopy(self.targets)
        with self.assertRaises(ValueError):
            self.apply(key='face:breath', target='Breath', min_weight='0', max_weight='0.1')
        output = json.loads(self.apply(key='face:breath', target='Breath', min_weight='0', max_weight='0.15'))
        self.assertEqual(.15, self.targets[1]['default'])
        self.assertEqual(before, self.targets)
        self.assertEqual({'node', 'target', 'min_weight', 'max_weight'},
                         set(output['morph_controls']['face:breath']))

    def test_existing_unrelated_config_is_still_validated(self):
        self.config['animation']['states']['blendlib_authoring:idle']['clip'] = 'Missing'
        with self.assertRaises(ValueError):
            self.apply()

    def test_unknown_fields_and_duplicate_json_keys_remain_closed(self):
        self.config['morph_controls']['face:smile']['default'] = 0
        with self.assertRaises(ValueError):
            self.apply()
        with self.assertRaises(ValueError):
            self.apply('{"schema_version":1,"schema_version":1,"morph_controls":{}}', mode='ADD')

    def test_legacy_parser_still_rejects_morph_controls(self):
        output = self.apply('', mode='CREATE', actions={})
        with self.assertRaises(ValueError):
            editor.authoring.parse(output)
        self.assertEqual(json.loads(output), editor.authoring.parse(output, allow_morph_controls=True))


if __name__ == '__main__':
    unittest.main()
