# BlendLib Canonical Authoring Fixtures

`static`, `rigid`, and `skinned` each contain a committed Blender source,
external source PNG, expected export contract, deterministic export report, and
golden normalized GLB/descriptor structure plus SHA-256 hashes.

The runtime never reads these `.blend` files. They are authoring fixtures
only. The runtime-facing generated copies live in
`blendlib-showcase/src/main/resources/assets/blendlib_showcase/`.

Every fixture descriptor's `materials.*.base_color` is a complete Minecraft
resource path ending in `.png`; it resolves directly to the copied external
file below `assets/<namespace>/textures/**`. Its `textures/` path uses only
non-empty, non-dot segments, so `.`/`..`, duplicate slashes, traversal, and
any leading, embedded, or trailing whitespace/control character are not valid
descriptor forms. No runtime adapter is permitted to append a missing extension
or normalize an unsafe path.

Regenerate source fixtures with Blender 5.1.2:

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
& 'D:\Program Files\Blender\blender.exe' --background `
  --python blender-addon\scripts\create_canonical_fixtures.py -- `
  --project-root .
```

Run the parser and two-run determinism verification with:

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
& 'D:\Program Files\Blender\blender.exe' --background `
  --python blender-addon\scripts\verify_p2_fixtures.py -- `
  --project-root .
```

That verification exports each fixture twice and asserts that every
`base_color` both ends in `.png` and exactly names the copied external PNG.
It also rejects a dirty source tree containing `*.pyc` or `*.blend1` before
and after the run.

Validate the frozen Draft 2020-12 descriptor contract, including tracked
negative fixtures for a missing `.png` suffix, `.`/`..` segments, a double
slash, terminal LF/CRLF, leading whitespace, and embedded whitespace/control
characters, with:

```powershell
python -B blender-addon\scripts\verify_p2_descriptor_schema.py `
  --project-root .
```
