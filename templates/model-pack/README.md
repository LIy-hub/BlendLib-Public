# BlendLib strict model-pack template

Copy this directory into the root of a resource pack, then replace the namespace
"blendlib_template" consistently in every path and resource identifier. The shipped
"starter_rigid" asset is a complete strict-v1 rigid asset: its descriptor points to the
adjacent GLB 2.0 and external PNG, and it contains no .blend, FBX, OBJ, embedded image, or
runtime converter dependency.

The four sidecar directories are deliberately separate from the strict descriptor:

- variants/ records an X6 authoring selection intent.
- materials/ records the material intent that must still map to a standard path.
- sockets/ records named attachment locations for the host integration layer.
- events/ records presentation event names; they never create gameplay authority.

They are authoring/reference data, not descriptor extensions and not currently loaded by the
strict v1 runtime. A future X6/X3/X4 integration owner must explicitly validate, prepare, and
wire them; see docs/expansion/x8/ecosystem-implementation.md. Do not place required unknown
data in extensions_required, and do not make gameplay, collision, networking, or socket
resolution depend on visual data.

## Safe replacement procedure

1. Choose a lower-case namespace and a lower-case model path.
2. Export or convert only to strict GLB 2.0 with POSITION, NORMAL, TEXCOORD_0, triangle
   indices, and node TRS. The offline converter is at
   tools/model-converter/blendlib_model_converter.py.
3. Replace the descriptor's mesh and materials resource identifiers together with the
   GLB and external PNG paths.
4. Keep format_version 1, an empty extensions_required array, and an explicit
   blendlib:rigid_v1 or valid skinned profile.
5. Record original asset authors, license, source URL or local provenance, modification date,
   and redistribution terms in COPYRIGHT-AND-SOURCES.md.
6. Validate the result with the repository's approved static/runtime process when it is
   available. This X8 contribution records no dynamic validation result.

The pack.mcmeta format value is deliberately scoped to the repository's 26.1.2 target and is
marked **WAITING** in the X8 compatibility matrix until a platform integration owner verifies it
against the exact Minecraft distribution.
