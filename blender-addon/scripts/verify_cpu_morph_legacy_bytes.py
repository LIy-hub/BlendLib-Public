# SPDX-FileCopyrightText: 2026 BlendLib local project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Export frozen legacy fixtures for byte comparisons; run from repository root."""
import sys,json,argparse
from pathlib import Path
sys.dont_write_bytecode=True
parser=argparse.ArgumentParser();parser.add_argument('--addon-root',required=True);parser.add_argument('--output',required=True)
a=parser.parse_args(sys.argv[sys.argv.index('--')+1:]);sys.path.insert(0,a.addon_root)
import blendlib_exporter as exporter
root=Path.cwd();results={}
for fixture in ('static','rigid','skinned','native-cubic'):
 if fixture=='native-cubic':namespace,model,profile,text='native_cubic','eased_actor','blendlib:skinned_cubic_v1','BlendLib.runtime.json'
 else:
  expected=json.loads((root/'test-assets'/fixture/'expected.json').read_text());namespace,model,profile,text=expected['namespace'],expected['model_id'],expected['profile'],None
 result=exporter.run_cli(exporter.ExportOptions(root/'test-assets'/fixture/'source.blend',root/a.output/fixture,namespace,model,profile,'BlendLibExport','resources',None,runtime_authoring_text=text))
 results[fixture]={name:result['sha256'][name] for name in ('mesh_glb','descriptor','normalized_structure')}
(root/a.output/'hashes.json').write_text(json.dumps(results,indent=2)+'\n')
print('BLENDLIB_LEGACY_HASHES '+json.dumps(results))
