# Khronos derived-fixture provenance

This directory is test data only. It records a deterministic strict-GLB v1
derivation for compatibility testing; it does not declare
or change a BlendLib project license.

## Fixed upstream source and license facts

- Upstream repository: `KhronosGroup/glTF-Sample-Assets`
- Fixed revision: `5109ab2a499c5a2c784b86e460fa491d52256e25`
- Raw source base: <https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/Models/>
- Imported raw payloads are exactly the seven entries in
  [`DERIVATION-MANIFEST.json`](DERIVATION-MANIFEST.json), which records every
  exact raw URL, byte length, and SHA-256.

The upstream per-model license records say text, image, and binary model
payloads are CC0-1.0. The upstream `LICENSE.md` and `metadata.json` documents
are themselves CC-BY-4.0; neither document is copied here. Their fixed-source
URLs remain the attribution/license evidence:

- SimpleSkin license: <https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/Models/SimpleSkin/LICENSE.md>
- SimpleSkin metadata: <https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/Models/SimpleSkin/metadata.json>
- AnimatedCube license: <https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/Models/AnimatedCube/LICENSE.md>
- AnimatedCube metadata: <https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/Models/AnimatedCube/metadata.json>

Attribution facts recorded by those fixed upstream metadata files:

| Source model | Payload status | Artist | Owner | Year |
|---|---|---|---|---:|
| SimpleSkin | CC0-1.0 | Marco Hutter (<https://github.com/javagl/>) | Public | 2017 |
| AnimatedCube | CC0-1.0 | Norbert Nopper | UX3D | 2017 |

Only the raw `.gltf` and required `.bin` model payloads are stored in `raw/`.
No upstream PNG, `LICENSE.md`, or `metadata.json` is imported.

## Strict-v1 derivation

[`derive_khronos_v1.py`](derive_khronos_v1.py) uses only Python's standard
library. It verifies all raw SHA-256 values and known source structure before
generating byte-stable outputs in both `derived/` and the core test-resource
mirror. `DERIVATION-MANIFEST.json` records the exact output SHA-256 values.

### SimpleSkin

The derivative retains the source U16 triangle indices, positions, joint
indices, normalized weights, two joints, inverse-bind matrices, and twelve
strictly increasing LINEAR rotation samples. The original has no required v1
`NORMAL` or `TEXCOORD_0`; the script adds self-authored normals `(0, 0, 1)`
for every vertex and a deterministic two-column UV pattern. It embeds all
accepted data in one GLB BIN chunk, assigns a descriptor-managed material slot,
and never treats the original external `.gltf + .bin` as an accepted runtime
input. Its external texture is a self-authored opaque-white 1x1 RGBA PNG, not
an upstream image.

### AnimatedCube

The derivative retains source U16 triangle indices, `POSITION`, `NORMAL`,
`TEXCOORD_0`, and the three LINEAR rotation samples. It intentionally drops
the source `TANGENT`, external image, root texture/sampler data, PBR material
data, and every external-buffer dependency. The result has a descriptor
material slot, the same self-authored opaque-white 1x1 RGBA PNG, and a single
embedded GLB BIN chunk.

## Reproduction and strict-runtime boundary

From the repository root:

```powershell
python test-assets/third_party/khronos/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/derive_khronos_v1.py --verify
```

`--write` regenerates the committed artifacts after a deliberately reviewed
input update. The raw external `.gltf` files are provenance/strict-rejection
inputs only. BlendLib v1 runtime acceptance remains GLB 2.0 only.

## Output hashes

| Artifact | SHA-256 |
|---|---|
| `derived/SimpleSkin/simple-skin-derived.glb` | `051b36110442d893472bbdc23ab59e04904008b1c2ea181d26eaeca2fbcfbced` |
| `derived/SimpleSkin/simple-skin-derived.json` | `f50f28d501cb03e7490356bde249fa5433a0f221bd6580af4b7ee273cde2be13` |
| `derived/AnimatedCube/animated-cube-derived.glb` | `7a15f0f3e90e748bdb38ec4b36bdcf6a741faf4ee90f628ddc1868330778b7d5` |
| `derived/AnimatedCube/animated-cube-derived.json` | `c51b5e84904a9fc92d3587254d2bbd7dbbb12af6735e63842adb446242ae9eba` |
| `derived/textures/khronos/simple-skin-derived.png` | `36c907715b80d7cdad0e196256966a3353b771387aefb1116d8769dacc81b675` |
| `derived/textures/khronos/animated-cube-derived.png` | `36c907715b80d7cdad0e196256966a3353b771387aefb1116d8769dacc81b675` |

The identically named classpath mirrors have the same SHA-256 values; their
individual paths and hashes are also recorded in the manifest.

The 2026-08-01 strict-accessor remediation preserves the same fixed-revision
binary payloads and adds their source-provided exact `POSITION` and animation
input `min`/`max` metadata, as required by glTF 2.0. The changed GLB hashes above
and the deterministic manifest are therefore metadata-only derivation updates.
