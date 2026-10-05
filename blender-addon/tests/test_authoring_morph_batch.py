# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Atomic, deterministic missing-target batch authoring contracts."""
import copy
import json
import sys
import unittest
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_authoring_editor as editor
import test_authoring_morph_editor as single


class MorphBatchTest(unittest.TestCase):
    def setUp(self):
        fixture = single.MorphAuthoringEditorTest()
        fixture.setUp()
        self.config, self.targets, self.actions = fixture.config, fixture.targets, fixture.actions

    def plan(self, **kwargs):
        args = dict(mode='ADD', namespace='face', targets=self.targets, actions=self.actions, fps=24)
        args.update(kwargs)
        return editor.missing_morph_controls(json.dumps(self.config), **args)

    def apply(self, rows=None, **kwargs):
        rows = self.plan() if rows is None else copy.deepcopy(rows)
        for row in rows:
            for key in ('min_weight', 'max_weight'):
                if type(row[key]) in (int, float):
                    row[key] = json.dumps(row[key])
        args = dict(mode='ADD', controls=rows, targets=self.targets, actions=self.actions, fps=24)
        args.update(kwargs)
        return json.loads(editor.apply_morph_batch(json.dumps(self.config), **args))

    def test_missing_only_preserves_complete_config_and_inputs(self):
        original, targets = copy.deepcopy(self.config), copy.deepcopy(self.targets)
        rows = self.plan()
        self.assertEqual({'Squeeze', 'OtherSmile'}, {r['target'] for r in rows})
        result = self.apply(rows)
        added = {r['key'] for r in rows}
        result['morph_controls'] = {k: v for k, v in result['morph_controls'].items() if k not in added}
        self.assertEqual(original, result)
        self.assertEqual(original, self.config)
        self.assertEqual(targets, self.targets)

    def test_aliases_are_order_independent_unicode_safe_and_mesh_specific(self):
        self.targets += [{'node': 'Root/Other', 'target': t, 'default': 0} for t in
                         ('Squeeze', '你好', 'A B', 'A?B', 'X'*128, '../', 'é')]
        rows = self.plan()
        self.assertEqual(rows, self.plan(targets=list(reversed(self.targets))))
        aliases = [r['key'] for r in rows]
        self.assertEqual(len(aliases), len(set(aliases)))
        self.assertTrue(all(len(a) <= 256 for a in aliases))
        self.apply(rows)

    def test_existing_alias_collision_gets_stable_suffix(self):
        alias = self.plan()[0]['key']
        self.config['morph_controls'][alias] = self.config['morph_controls'].pop('face:smile')
        rows = self.plan()
        self.assertEqual(alias+'_2', rows[0]['key'])
        self.apply(rows)

    def test_complete_is_noop_and_apply_empty_rejected(self):
        self.config = self.apply()
        self.assertEqual([], self.plan())
        with self.assertRaises(ValueError):
            self.apply([])

    def test_create_ignores_invalid_old_text_and_needs_no_actions(self):
        rows = editor.missing_morph_controls('{invalid', mode='CREATE', namespace='face',
                                            targets=self.targets, actions={}, fps=24)
        output = self.apply(rows, mode='CREATE', actions={})
        self.assertEqual({'schema_version', 'morph_controls'}, set(output))
        self.assertEqual(len(self.targets), len(output['morph_controls']))

    def test_ranges_expand_only_for_defaults_and_include_zero(self):
        for default in (-2, -1.25, 0, .15, 1.25, 2):
            self.targets[2]['default'] = default
            row = next(r for r in self.plan() if r['target'] == 'Squeeze')
            self.assertEqual(min(-1, default), row['min_weight'])
            self.assertEqual(max(1, default), row['max_weight'])
            self.apply()

    def test_invalid_default_or_discovery_is_not_silently_repaired(self):
        for default in (-2.01, 2.01, float('nan'), float('inf'), True, '0'):
            self.targets[2]['default'] = default
            with self.subTest(default=default), self.assertRaises(ValueError):
                self.plan()
        self.targets[2]['default'] = 0
        self.targets.append(self.targets[2].copy())
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            self.plan()

    def test_existing_missing_target_or_default_conflict_is_actionable(self):
        self.targets[0]['default'] = 1.5
        with self.assertRaisesRegex(ValueError, 'face:smile.*default'):
            self.plan()
        self.targets.pop(0)
        with self.assertRaisesRegex(ValueError, 'face:smile.*exported'):
            self.plan()

    def test_bad_namespaces_rejected(self):
        for namespace in ('', 'Face', 'face:bad', '../face', 'a'*129):
            with self.subTest(namespace=namespace), self.assertRaises(ValueError):
                self.plan(namespace=namespace)

    def test_batch_rows_must_cover_exact_missing_set(self):
        rows = self.plan()
        for bad in (rows[:-1], rows+rows[:1], rows+[{'key':'face:x', 'node':'Root/Face',
                    'target':'Smile', 'min_weight':-1, 'max_weight':1}]):
            with self.assertRaises(ValueError):
                self.apply(bad)
        rows[0]['target'] = 'Invented'
        with self.assertRaises(ValueError):
            self.apply(rows)

    def test_editable_aliases_and_ranges_validate_before_atomic_apply(self):
        rows = self.plan()
        for field, value in (('key', 'face:smile'), ('key', 'BadAlias'), ('min_weight', 'false'),
                             ('min_weight', '-2.01'), ('max_weight', 'NaN')):
            bad = copy.deepcopy(rows)
            bad[1][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.apply(bad)
        rows[1]['key'] = rows[0]['key']
        with self.assertRaises(ValueError):
            self.apply(rows)
        rows = self.plan()
        rows[0]['key'], rows[0]['min_weight'] = 'custom:chosen', '-0.12345678912345678'
        self.assertEqual(-.12345678912345678, self.apply(rows)['morph_controls']['custom:chosen']['min_weight'])

    def test_apply_catches_existing_conflicts_and_changed_defaults(self):
        rows = self.plan()
        self.targets[2]['default'] = 1.5
        with self.assertRaisesRegex(ValueError, 'default'):
            self.apply(rows)
        self.targets[0]['default'] = 1.5
        with self.assertRaisesRegex(ValueError, 'Existing control'):
            self.apply(rows)

    def test_limit_is_not_partially_applied(self):
        targets = [{'node': 'Root/Mesh'+str(i), 'target':'Target', 'default':0} for i in range(1025)]
        with self.assertRaisesRegex(ValueError, '1024'):
            self.plan(mode='CREATE', targets=targets)


if __name__ == '__main__':
    unittest.main()
