# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Pure contract checks; the fixture generator supplies real-Blender evidence."""
import copy
import dataclasses
import math
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import blendlib_exporter as exporter
import blendlib_native_cubic as native


class NativeCubicContractTest(unittest.TestCase):
    def channel(self,path='translation'):
        width=4 if path=='rotation' else 3
        zero=(0.0,)*width
        value=(0,0,0,1) if path=='rotation' else (1,1,1)
        return {'path':path,'interpolation':'CUBICSPLINE','times':[0,2],
                'values':[zero,value,zero,zero,value,zero]}

    def assert_invalid(self,channel):
        with self.assertRaises(ValueError):native.validate_channel(channel)

    def test_translation_controls_not_just_keys_are_bounded(self):
        channel=self.channel();native.validate_channel(channel)
        channel['values'][2]=(math.inf,0,0);self.assert_invalid(channel)
        channel=self.channel();channel['values'][2]=(native.FLOAT_SAFETY_LIMIT*2,0,0);self.assert_invalid(channel)

    def test_scale_controls_positive_and_exactly_uniform(self):
        channel=self.channel('scale');native.validate_channel(channel)
        channel['values'][2]=(-3,-3,-3);self.assert_invalid(channel)
        channel=self.channel('scale');channel['values'][2]=(0,0,1e-12);self.assert_invalid(channel)
        channel=self.channel('scale');channel['values'][1]=(1e-8,)*3;self.assert_invalid(channel)

    def test_quaternion_keeps_signs_and_rejects_near_zero_hulls(self):
        channel=self.channel('rotation');native.validate_channel(channel)
        channel['values'][4]=(0,0,0,-1);self.assert_invalid(channel)
        channel=self.channel('rotation');channel['values'][2]=(0,0,0,-1.5);self.assert_invalid(channel)
        channel=self.channel('rotation');channel['values'][1]=(0,0,0,2);self.assert_invalid(channel)

    def test_quaternion_uses_one_hemisphere_for_entire_channel(self):
        channel=self.channel('rotation');channel['times']=[0,1,2]
        zero=(0,0,0,0)
        channel['values']=[zero,(0,0,0,1),zero, zero,(.8660254,0,0,.5),zero, zero,(.8660254,0,0,-.5),zero]
        self.assert_invalid(channel)

    def test_connected_location_and_nonconstant_extrapolation_fall_back(self):
        bone=SimpleNamespace(parent=object(),bone=SimpleNamespace(use_connect=True))
        with self.assertRaisesRegex(ValueError,'connected child bone location'):
            native._channel(None,bone,'location',[],0,24)
        curves=[SimpleNamespace(array_index=i,modifiers=[],mute=False,is_valid=True,extrapolation='LINEAR') for i in range(3)]
        with self.assertRaisesRegex(ValueError,'nonconstant FCurve extrapolation'):
            native._channel(None,None,'location',curves,0,24)

    def test_time_cardinality_and_finiteness(self):
        for times in ([0],[0,0],[0,-1],[0,math.nan]):
            channel=self.channel();channel['times']=times;self.assert_invalid(channel)
        channel=self.channel();channel['values'].pop();self.assert_invalid(channel)

    def test_format_branch_cannot_change_legacy_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)/'source.blend';source.write_bytes(b'BLENDER')
            args=['blender','--','--blend',str(source),'--project-root',directory,'--namespace','example','--model-id','model','--profile',native.PROFILE]
            options=exporter.parse_blender_arguments(args)
            self.assertEqual(exporter._build_descriptor(options,[],{})['format_version'],2)
            for profile in ('blendlib:rigid_v1','blendlib:skinned_v1'):
                old=dataclasses.replace(options,profile=profile)
                descriptor=exporter._build_descriptor(old,[],{})
                self.assertEqual(descriptor['format_version'],1)
                exporter.validate_descriptor(descriptor,Path(directory),())
            mismatch=exporter._build_descriptor(options,[],{});mismatch['format_version']=1
            with self.assertRaises(exporter.ExportError):exporter.validate_descriptor(mismatch,Path(directory),())
            mismatch['format_version']=2;mismatch['profile']='blendlib:skinned_v1'
            with self.assertRaises(exporter.ExportError):exporter.validate_descriptor(mismatch,Path(directory),())

    def test_strict_validator_still_rejects_cubic(self):
        gltf={'animations':[{'name':'Test','samplers':[{'interpolation':'CUBICSPLINE'}],'channels':[]}]}
        with self.assertRaises(exporter.ExportError):exporter._validate_animations(gltf)

    def test_native_glb_has_source_key_cardinality_and_no_images(self):
        root=Path(__file__).resolve().parents[2]/'test-assets/native-cubic/exported/assets/native_cubic'
        gltf,binary=exporter.read_glb(root/'models3d/eased_actor.glb')
        self.assertEqual(exporter._validate_animations(gltf,binary=binary,profile=native.PROFILE),['EaseWave'])
        self.assertNotIn('images',gltf)
        for sampler in gltf['animations'][0]['samplers']:
            self.assertEqual(sampler['interpolation'],'CUBICSPLINE')
            self.assertEqual(gltf['accessors'][sampler['input']]['count'],3)
            self.assertEqual(gltf['accessors'][sampler['output']]['count'],9)


if __name__=='__main__':unittest.main()
