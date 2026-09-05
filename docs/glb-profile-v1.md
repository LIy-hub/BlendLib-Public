# GLB Profile v1

Status: frozen for P0. BlendLib v1 accepts a strict, explicitly declared GLB 2.0 archive rather than general glTF compatibility.

## Common archive matrix

| Capability | V1 behavior |
|---|---|
| GLB container version 2 | Supported |
| External .gltf plus .bin | Rejected |
| Primitive mode TRIANGLES | Supported |
| POSITION | Required |
| NORMAL | Required |
| TEXCOORD_0 | Required |
| Indices U16 or U32 | Supported |
| Node TRS | Supported |
| Node matrix | Supported after load-time decomposition and normalization |
| LINEAR interpolation | Supported |
| STEP interpolation | Supported |
| CUBICSPLINE | Rejected |
| Morph targets | Rejected |
| Sparse accessors | Rejected |
| Draco or Meshopt | Rejected |
| Embedded images | Not a v1 material source |
| Vertex colors | Rejected |
| Multiple UV sets | Rejected |
| Cameras and lights | Ignored with a warning |

## Rigid profile: blendlib:rigid_v1

- Supports static models and node-based rigid animation.
- Each mesh primitive is bound to one node.
- JOINTS_0 and WEIGHTS_0 are not read.
- Node translation, rotation, and scale can animate.

## Skinned profile: blendlib:skinned_v1

- Supports one or more skins.
- Reads JOINTS_0, WEIGHTS_0, and inverse bind matrices.
- Each vertex has at most four effective, normalized weights.
- Supports translation, rotation, and scale animation for bones.
- Does not support dual-quaternion skinning.

The strict v1 skin rules are narrower than general glTF where stated. Every
skin must declare a non-empty, unique joint list whose nodes have one closest
common ancestor (a joint counts as its own ancestor). A declared `skeleton`
must be that closest common ancestor or one of its direct/indirect ancestors.
The strict profile requires `inverseBindMatrices` even though general glTF
allows it to be omitted; the accessor must be unnormalized FLOAT MAT4 with
exactly one finite affine matrix per joint, in joint-slot order. Each inverse
bind matrix uses the affine fourth row `[0, 0, 0, 1]` under the existing strict
matrix tolerance. `JOINTS_0` palette indices must be valid even in zero-weight
padding slots. Repeated palette indices are allowed only when at most one of
the repeated slots has positive weight; two positive-weight references to the
same joint on one vertex are invalid.

## Required validation

The loader validates GLB magic, version, declared length, chunk bounds, bufferView and accessor bounds, stride, component type, index range, NaN and Infinity, node cycles, hierarchy depth, and monotonic animation time. Descriptor profile and actual GLB content must agree.

The canonical asset coordinate space is right-handed: +Y up, +Z forward, +X right, and one asset unit equals one Minecraft block. Blender conversion occurs once at the export/import boundary:

    Minecraft X = Blender X
    Minecraft Y = Blender Z
    Minecraft Z = -Blender Y

The Fabric renderer never applies hidden per-model axis compensation.
