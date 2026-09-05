# X6 Isolated Variant Manifest v1

**Status:** Experimental prepare-time input only. It is not a v1 descriptor extension, not a
`ModelProfile`, and not a runtime wire format.

`X6VariantManifestParser.parseForPrepare(byte[])` accepts one bounded strict UTF-8 byte source
before an X6 snapshot exists. The 64 KiB limit is checked **before** decoding; malformed or
unmappable UTF-8 and a UTF-8 BOM are rejected. Limits are 64 KiB and 1024 lines. Blank lines and `#` comments are allowed; every
non-comment line must have no surrounding whitespace. Unknown fields, duplicate
`format_version`, duplicate defaults, malformed IDs, invalid enum names, unbounded predicate
values, or malformed separators fail with `BLENDLIB-X6-MANIFEST-001`.

```text
format_version=1
variant=<variant-id>|<SKIN|MATERIAL|MESH|EQUIPMENT|DAMAGE_STAGE|PART_VISIBILITY>|<part-id>
default=<selector-id>|<variant-id>
rule=<rule-id>|<selector-id>|<priority>|<variant-id>|<condition-id>=<value>[;<condition-id>=<value>...]
```

Use `-` in the final rule field for an unconditional rule. Rule predicates are an AND-set; their
values must match `[a-z0-9._/-]+` and are bounded to 64 characters. A rule may contain at most
eight predicates. Rule priority is an integer; the engine sorts it descending and treats a highest
priority tie as a hard conflict.

The checked positive fixture is
`blendlib-fabric-client/src/test/resources/x6/variant-manifest-v1.txt`; an identical human-facing
copy lives at `test-assets/x6/variant-manifest-v1.txt`. It carries all six variant kinds and a
single `x6_test:state=active` condition.

`parseForPrepare(String)` exists only for code-owned convenience text. A Java `String` has already
lost its source encoding, so that overload can measure its canonical UTF-8 re-encoding but cannot
prove the original source bytes were strict UTF-8. Resource and reload callers must use the byte
overload. The unit suite covers exact 65,536-byte acceptance, a 65,537-byte rejection, multibyte
comments, malformed sequences, and BOM rejection.

## Migration boundary

There is no migration from v1 `extensions`, `extensions_required`, or `rigid_v1` payloads into
this text. Existing v1 validation runs unchanged and continues to reject non-empty required
extensions. A future accepted profile/version proposal would need its own schema, codec,
compatibility decision, fixture corpus, migration decision, and independent review; it cannot be
smuggled through X6 manifest parsing.

The intended caller resolves bytes and chooses an X6 manifest only in a future client reload
prepare seam. It must parse once, build a frozen `X6VariantSelectionPlan`, and discard the source
text. `X6PlanSubmitter` has no manifest parser or resource access.
