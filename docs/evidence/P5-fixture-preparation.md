# P5 Fixture Preparation Evidence

## Scope

This is provenance and fixture-preparation evidence only. It does not mark
P4 or P5 as passed, does not replace the pending audit, and does not provide
client visual evidence.

The package at `test-assets/p5/` introduces a manifest, deterministic
scenario descriptions, and a standard-library verifier. It references the
existing strict BlendLib P2 canonical fixtures instead of copying any GLB,
glTF, Blender, PNG, Khronos, or LiyMod asset.

## Frozen source bindings

| Fixture | Runtime descriptor SHA-256 | Runtime GLB SHA-256 | P2 normalized structure SHA-256 |
|---|---|---|---|
| `rigid-two-node-palette` | `d5ef584999ed54ce17b798e85d0e31b62b85db89bc4ee5f820c46806a5a8852d` | `a2b7f063c8806f3e3eceec2533ed8fe84c91f967ac71c25ca59faf4349a21764` | `adcac8f7c0ffed179708d68344ac094959c022be66ebffa93f64531fd7367dc8` |
| `skinned-single-joint-source` | `1a2b5c3b38218df1d5f95e1d65efe90ec89edccfe399fd6ce5ac2415fc42d012` | `fe780ca51db01961bf492279ccd46cebc55688cc1ad64e67ce98a87596948db9` | `837f3fc4088e4666df00dfae4478c823923d45bc17944fab3071eb02cc310869` |

The verifier compares these values to the existing P2 `golden/sha256.json`
records, hashes the referenced source/runtime bytes, canonicalizes the
normalized structure with sorted compact JSON, and verifies the runtime GLB
node, joint, animation-channel, interpolation, and first/last sample-time
observations.

## Known source facts and deliberate gaps

- Rigid has `RigidArm` and `RigidBase` palette targets and only
  `rigid_pulse`; its source samples span
  `0.0416666679084301` through `0.8333333134651184` seconds.
- Skinned has exactly one joint, `SkinnedBone`, and only `skinned_wave`.
- No frozen source provides a non-loop attack clip, a two-joint skin, a
  descriptor socket table, visual event declarations, sampled pose matrices,
  or post-skin vertex vectors.

The scenario golden records each gap explicitly. It does not invent a clip,
socket transform, joint, matrix, vertex value, or P5 runtime result.

## Verification

```powershell
python -B test-assets/p5/verify_p5_fixtures.py --project-root D:\BlendLib
git diff --check
```

The fixture verifier is intentionally independent of P5 runtime APIs so
future pure-core implementation can consume the same provenance package.

Current preparation verification produced:

```text
BLENDLIB_P5_FIXTURE_VALIDATION_OK rigid-two-node-palette skinned-single-joint-source (not a phase gate)
```
