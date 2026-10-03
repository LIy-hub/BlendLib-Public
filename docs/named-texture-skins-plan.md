# Named texture skins: bounded implementation plan

Stacked on the item visual events branch. Add ordinary entity and marker-item named skins together without descriptor, schema, wire, dependency, shader, or texture-manager ownership changes.

1. Freeze model-scoped startup definitions (skin resource ID to exact authored slot to texture resource ID). Identical registration is idempotent; conflicts fail. Snapshot registration once at reload prepare and validate resources and slots there. No unregister in this slice.
2. Carry immutable valid definitions and precise diagnostics into generation preparation. Compile primitive-ordered material catalogs into existing concrete handles. Reuse X6 texture substitution through a shared package-private helper, without providers, leases, handle wrappers or synthetic part ceilings.
3. Capture an optional named skin once during extraction with exact-handle fencing. Preserve skin and appearance through every snapshot copy. The normal backend applies selected materials followed by existing RGB/visibility appearance.
4. Add entity builder selection and distinct item registerWithSkin methods, preserving previous registrations and ABI. Provide opt-in two-skin entity and wand consumers with authored tiny texture assets.
5. Cover static, rigid, skinned, item, appearance, reload ordering, immutable capture, fallback, selector counts and ABI. Run independent critical review, official 26.3 builds/JAR/examples, root checks, exact-head remote CI, and package a cumulative deliverable.

## Bounds and failure isolation

Only the opt-in registry is bounded: 128 models, 32 named skins per model, 64 slot replacements per named skin. Each model's prepared catalog is limited to 65,536 primitive-material entries, bounding reference storage to approximately 512 KiB before material objects. Exceeding this catalog budget disables its named skins diagnostically while preserving the base model. No new primitive ceiling applies to ordinary geometry. Unknown selections and invalid definitions fall back atomically to authored materials, and invalid individual definitions do not disable valid siblings. Missing-model diagnostics bypass selectors.

Resource validation checks existence, not successful texture decoding. Existing Minecraft texture ownership is unchanged, and old snapshots do not promise historical texture-byte isolation. Native graphics acceptance remains deferred by the user. No merge, tag, release, CurseForge upload, EULA acceptance or security-setting changes.
