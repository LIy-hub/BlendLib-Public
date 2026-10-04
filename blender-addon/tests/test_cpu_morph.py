# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Strict opt-in authoring and dense morph contract regressions."""
import copy
import dataclasses
import json
import math
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import blendlib_exporter as exporter
import blendlib_cpu_morph as morph
import blendlib_runtime_authoring as authoring
import blendlib_authoring_editor as editor

ROOT=Path(__file__).resolve().parents[2]
FIXTURE=ROOT/'test-assets/cpu-morph'


class CpuMorphContractTest(unittest.TestCase):
    def setUp(self):
        self.gltf,self.binary=exporter.read_glb(FIXTURE/'exported/assets/cpu_morph/models3d/face_actor.glb')
        self.config=json.loads((FIXTURE/'runtime-authoring.json').read_text())
        self.controls=self.config['morph_controls']
        self.paths=exporter._exported_node_paths(self.gltf)
        self.node=next(i for i,n in enumerate(self.gltf['nodes']) if n['name']=='FaceBody')
        self.mesh=self.gltf['meshes'][self.gltf['nodes'][self.node]['mesh']]

    def validate(self):
        return morph.validate_gltf(self.gltf,self.binary,self.controls,self.paths,exporter._read_accessor)

    def test_control_names_match_runtime_length_and_control_character_limits(self):
        for field, value in [('alias', 'cpu_morph:' + 'a'*300), ('node', 'a'*1025),
                             ('target', 'a'*129), ('target', '\U0001f642'*65),
                             ('node', 'Root/Bad\x7fNode'), ('target', 'Bad\x85Name')]:
            controls=copy.deepcopy(self.controls)
            name=next(iter(controls))
            if field == 'alias': controls[value]=controls.pop(name)
            else: controls[name][field]=value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                morph.validate_controls(controls)
        for value in ['a'*129, '\U0001f642'*65, 'Bad\x7fName']:
            self.mesh['extras']['targetNames'][0]=value
            with self.assertRaises(ValueError): self.validate()

    def test_palette_transients_and_repeated_channels_are_preflighted_before_decode(self):
        self.gltf['skins'][0]['joints'] *= 256
        self.mesh['primitives'] *= 400
        with patch.object(exporter, '_read_accessor', side_effect=AssertionError('must reject metadata first')):
            with self.assertRaisesRegex(ValueError, 'float budget'):
                morph.validate_gltf(self.gltf,self.binary,self.controls,self.paths,exporter._read_accessor)

    def test_near_affine_inverse_bind_is_rejected_for_morph_only(self):
        import struct
        skin=self.gltf['skins'][0]
        accessor=self.gltf['accessors'][skin['inverseBindMatrices']]
        view=self.gltf['bufferViews'][accessor['bufferView']]
        binary=bytearray(self.binary)
        struct.pack_into('<f',binary,view.get('byteOffset',0)+accessor.get('byteOffset',0)+12,1e-6)
        with self.assertRaisesRegex(ValueError, 'exact affine'):
            morph.validate_gltf(self.gltf,bytes(binary),self.controls,self.paths,exporter._read_accessor)

    def test_fixture_dense_targets_and_weight_only_clips(self):
        result=self.validate();self.assertEqual(result['morph_control_count'],3)
        self.assertEqual(len(self.mesh['primitives']),2)
        self.assertEqual(self.mesh['extras']['targetNames'],['Blink','Smile','Breath'])
        self.assertAlmostEqual(self.mesh['weights'][2],.15)
        for animation in self.gltf['animations']:
            paths={c['target']['path'] for c in animation['channels']}
            self.assertEqual(paths,{'rotation'} if animation['name']=='Nod' else {'weights'})
        self.assertEqual(exporter._validate_animations(self.gltf,binary=self.binary,profile=morph.PROFILE),['Blink','Breath','Nod','Smile'])

    def test_morph_authoring_explicit_opt_in_and_lossless_editor(self):
        raw=json.dumps(self.config)
        with self.assertRaises(ValueError):authoring.parse(raw)
        self.assertEqual(authoring.parse(raw,allow_morph_controls=True),self.config)
        facts={name:(10,46,[('Apex',22)]) for name in ('Nod','Blink','Smile','Breath')}
        edited=editor.apply(raw,mode='EDIT',key='cpu_morph:blink',clip='Blink',loop=True,speed='1',events=[],make_initial=False,actions=facts,fps=24,allow_morph_controls=True)
        self.assertEqual(json.loads(edited),self.config)
        with self.assertRaises(ValueError):editor.load(raw,facts,24)

    def test_manual_only_morph_text_needs_no_dummy_state(self):
        config=copy.deepcopy(self.config);del config['animation']
        parsed=authoring.parse(json.dumps(config),allow_morph_controls=True)
        output,rules=authoring.compile_authoring(parsed,{}, {},set(self.paths.values()),24)
        self.assertNotIn('animation',output);self.assertIsNone(rules)
        self.assertEqual(output['sockets'],config['sockets'])
        with self.assertRaises(ValueError):authoring.parse(json.dumps(config))

    def test_descriptor_opt_in_keeps_legacy_closed(self):
        descriptor=json.loads((FIXTURE/'exported/assets/cpu_morph/blend_models/face_actor.json').read_text())
        root=FIXTURE/'exported/assets/cpu_morph'
        exporter.validate_descriptor(descriptor,root,('MorphSurface','FaceDetails'))
        for profile in ('blendlib:rigid_v1','blendlib:skinned_v1','blendlib:skinned_cubic_v1'):
            wrong=dict(descriptor,profile=profile,format_version=2 if 'cubic' in profile else 1)
            with self.assertRaises(exporter.ExportError):exporter.validate_descriptor(wrong,root,('MorphSurface','FaceDetails'))
        descriptor['format_version']=True
        with self.assertRaises(exporter.ExportError):exporter.validate_descriptor(descriptor,root,('MorphSurface','FaceDetails'))

    def test_controls_reject_missing_duplicate_unknown_targets(self):
        for change in ('missing','duplicate','unknown','short_path'):
            with self.subTest(change=change):
                self.setUp()
                if change=='missing':self.controls.pop('cpu_morph:blink')
                if change=='duplicate':self.controls['cpu_morph:other']=dict(self.controls['cpu_morph:blink'])
                if change=='unknown':self.controls['cpu_morph:blink']['target']='Missing'
                if change=='short_path':self.controls['cpu_morph:blink']['node']='FaceBody'
                with self.assertRaises(ValueError):self.validate()

    def test_control_ranges_are_finite_signed_bounded_and_include_zero(self):
        for low,high in ((.1,1),(-1,-.1),(-2.1,1),(-1,2.1),(math.nan,1),(-1,math.inf),(False,1)):
            with self.subTest(low=low,high=high):
                c=copy.deepcopy(self.controls);c['cpu_morph:blink'].update(min_weight=low,max_weight=high)
                with self.assertRaises(ValueError):morph.validate_controls(c)

    def test_target_names_order_is_checked_against_source(self):
        self.mesh['extras']['targetNames']=['Smile','Blink','Breath']
        with self.assertRaisesRegex(ValueError,'order'):
            morph.validate_gltf(self.gltf,self.binary,self.controls,self.paths,exporter._read_accessor,{'FaceBody':{'names':['Blink','Smile','Breath']}})

    def test_target_name_shape_and_count(self):
        for names in ([],['Blink','Blink','Breath'],['','Smile','Breath'],['Blink']*9):
            self.mesh['extras']['targetNames']=names
            with self.assertRaises(ValueError):self.validate()

    def test_material_split_target_counts_and_semantics(self):
        self.mesh['primitives'][1]['targets'].pop()
        with self.assertRaisesRegex(ValueError,'material-split'):self.validate()
        self.setUp();self.mesh['primitives'][0]['targets'][0]['TANGENT']=0
        with self.assertRaisesRegex(ValueError,'exactly POSITION and NORMAL'):self.validate()
        self.setUp();del self.mesh['primitives'][1]['targets'][0]['NORMAL']
        with self.assertRaises(ValueError):self.validate()

    def test_target_cardinality_dense_and_float(self):
        for field,value in (('count',1),('sparse',{}),('componentType',5123),('normalized',True)):
            self.setUp();target=self.mesh['primitives'][0]['targets'][0]['POSITION'];self.gltf['accessors'][target][field]=value
            with self.assertRaises(ValueError):self.validate()

    def test_defaults_node_override_mesh_and_zero_fallback(self):
        self.gltf['nodes'][self.node]['weights']=[0,.5,.2];self.validate()
        self.gltf['nodes'][self.node]['weights']=[0,1.1,.2]
        with self.assertRaises(ValueError):self.validate()
        self.setUp();del self.mesh['weights'];self.validate()
        self.mesh['weights']=[0,0]
        with self.assertRaises(ValueError):self.validate()
        self.mesh['weights']=[0,0,math.nan]
        with self.assertRaises(ValueError):self.validate()

    def test_animated_weight_keys_must_fit_interval(self):
        self.controls['cpu_morph:smile']['max_weight']=.1
        with self.assertRaisesRegex(ValueError,'animated morph weight'):self.validate()

    def test_cubic_weight_channel_is_always_rejected(self):
        animation=next(a for a in self.gltf['animations'] if a['name']=='Smile')
        animation['samplers'][0]['interpolation']='CUBICSPLINE'
        with self.assertRaisesRegex(ValueError,'LINEAR or STEP'):self.validate()
        with self.assertRaises(exporter.ExportError):exporter._validate_animations(self.gltf,binary=self.binary,profile=morph.PROFILE)

    def test_duplicate_weight_channels_rejected(self):
        animation=self.gltf['animations'][0];animation['channels'].append(copy.deepcopy(animation['channels'][0]))
        with self.assertRaisesRegex(ValueError,'unique morph binding'):self.validate()

    def test_budget_preflight_occurs_before_any_decode(self):
        called=[]
        def forbidden(*args):called.append(True);raise AssertionError('decoded before aggregate rejection')
        with patch.object(morph,'MAX_PAIRS',1):
            with self.assertRaisesRegex(ValueError,'budget'):morph.validate_gltf(self.gltf,self.binary,self.controls,self.paths,forbidden)
        self.assertFalse(called)
        with patch.object(morph,'MAX_FLOATS',1):
            with self.assertRaisesRegex(ValueError,'budget'):morph.validate_gltf(self.gltf,self.binary,self.controls,self.paths,forbidden)
        self.assertFalse(called)

    def test_normal_nondegeneracy_proof_checks_full_intervals(self):
        target=self.mesh['primitives'][0]['targets'][0]['NORMAL']
        def read(gltf,binary,index):
            result=exporter._read_accessor(gltf,binary,index)
            if index==target:result['values']=[(3.,3.,3.)]*result['count']
            return result
        with self.assertRaisesRegex(ValueError,'nondegenerate'):morph.validate_gltf(self.gltf,self.binary,self.controls,self.paths,read)

    def test_weight_replacement_keeps_native_trs(self):
        plan={'Smile':[{'node':'FaceBody','path':'weights','times':[0,1.5],'values':[[0,0,.15],[0,.5,.15]],'interpolation':'LINEAR'}]}
        output=morph.append_weight_animations(self.gltf,self.binary,plan)
        self.assertEqual([a['name'] for a in self.gltf['animations']],['Nod','Smile'])
        self.assertEqual(self.gltf['animations'][0]['samplers'][0]['interpolation'],'CUBICSPLINE')
        self.assertEqual(self.gltf['animations'][1]['channels'][0]['target']['path'],'weights')
        self.assertGreater(len(output),len(self.binary))


if __name__=='__main__':unittest.main()
