# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
import copy
import json
from pathlib import Path
import sys
import unittest
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_authoring_editor as editor
import blendlib_exporter as exporter


class SocketEditorTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((Path(__file__).resolve().parents[2]/'test-assets/blender-authoring/runtime-authoring.json').read_text())
        self.actions = {name: (10., 34., [('Footstep', 22), ('Impact', 16)]) for name in ('Idle', 'Walk', 'Attack')}
        self.key = next(iter(self.config['sockets']))

    def apply(self, **overrides):
        args = dict(mode='EDIT', key=self.key, node='Root/Hand', node_paths={'Root', 'Root/Hand', 'Root/Helper'}, actions=self.actions, fps=24.)
        args.update(overrides)
        return editor.apply_socket(json.dumps(self.config), **args)

    def test_noop_preserves_all_source_fields_and_precision(self):
        self.config['animation']['states']['blendlib_authoring:attack']['speed'] = 1.23456789123456
        self.config['locomotion']['rules'][0]['conditions'][1]['enter_min'] = 10**180
        self.assertEqual(self.config, json.loads(self.apply()))

    def test_add_and_edit_only_named_socket(self):
        output = json.loads(self.apply(mode='ADD', key='demo:helper', node='Root/Helper'))
        expected = copy.deepcopy(self.config)
        expected['sockets']['demo:helper'] = {'node': 'Root/Helper'}
        self.assertEqual(expected, output)
        expected = copy.deepcopy(self.config)
        expected['sockets'][self.key] = {'node': 'Root/Helper'}
        self.assertEqual(expected, json.loads(self.apply(node='Root/Helper')))

    def test_duplicate_missing_and_invalid_keys_rejected(self):
        for patch in [dict(mode='CREATE'), dict(mode='ADD'), dict(key='missing:key'), dict(key='bad'),
                      dict(node='Hand'), dict(node='__NONE__'), dict(node_paths={'Root/Helper'}), dict(node='Root//Hand')]:
            with self.subTest(patch=patch), self.assertRaises(ValueError): self.apply(**patch)

    def test_all_preserved_sockets_and_states_share_strict_validation(self):
        for raw in [{'node': 'Absent'}, {'node': 'Root/Hand', 'offset': [0, 0, 0]}]:
            self.config['sockets']['demo:other'] = raw
            with self.assertRaises(ValueError): self.apply()
        del self.config['sockets']['demo:other']
        self.config['animation']['states']['blendlib_authoring:idle']['future'] = True
        with self.assertRaises(ValueError): self.apply()

    def test_selected_unknown_fields_are_not_silently_removed(self):
        self.config['sockets'][self.key]['offset'] = [0, 0, 0]
        with self.assertRaises(ValueError): self.apply()

    def test_add_without_sockets_and_entry_limit(self):
        del self.config['sockets']
        self.assertEqual({'demo:helper': {'node': 'Root/Helper'}}, json.loads(self.apply(mode='ADD', key='demo:helper', node='Root/Helper'))['sockets'])
        self.config['sockets'] = {f'demo:s{i}': {'node': 'Root/Hand'} for i in range(512)}
        with self.assertRaises(ValueError): self.apply(mode='ADD', key='demo:extra')
        self.assertEqual(512, len(json.loads(self.apply(key='demo:s0'))['sockets']))

    def test_shared_export_path_contract(self):
        graph = {'nodes': [{'name': 'Armature', 'children': [1]}, {'name': 'Bone', 'children': [2]}, {'name': 'Helper'}], 'scenes': [{'nodes': [0]}], 'scene': 0}
        self.assertEqual({0: 'Armature', 1: 'Armature/Bone', 2: 'Armature/Bone/Helper'}, exporter._exported_node_paths(graph))
        for patch in [dict(nodes=[{'name': 'Root', 'children': [0]}]),
                      dict(nodes=[{'name': 'Root', 'children': [3]}]),
                      dict(nodes=[{'name': 'Root', 'children': [1]}, {'name': 'Root'}]),
                      dict(nodes=[{'name': 'Root', 'children': [1, 1]}, {'name': 'Child'}])]:
            with self.subTest(patch=patch), self.assertRaises(exporter.ExportError): exporter._exported_node_paths(graph | patch)


if __name__ == '__main__': unittest.main()
