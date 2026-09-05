# X9 Experimental Asset Fixtures

Status: Proposed/Experimental validation candidate. X9 single-track review
`/root/x9_independent_review_r6` is PASS; the original X1/X5/X9 integration
r1 is FAIL and its remediation is PENDING a fresh integration review.

This directory contains declarative, repository-owned X9 fixture expectations.
It does not contain runtime `.blend`, FBX, OBJ, Draco, Meshopt, or KTX2 input.
The Java tests construct a compact in-memory GLB so bounds and non-finite input
paths can be exercised without introducing unreviewed third-party assets.

- `descriptor-matrix.json` records the positive, negative, and fallback cases.
- Its `r5_buffer_view_usage_graph` section records the in-memory derived GLB
  checks for actual accessor role binding: target hints, legal omitted targets,
  shared-view conflicts, vertex-only effective stride (including tightly packed
  U8/U16 layouts that require explicit padding), valid interleaving, and the
  vertex/morph accessor-offset rule. Component-size alignment still applies to
  both accessor offsets and their total buffer offsets. The repository-derived
  JUnit fixtures are not an external Khronos Validator result: no external
  validator executable/runnable corpus integration is available in this
  environment.
- `golden/validation-summary.json` defines the expected feature counters for
  the positive morph candidate.
- `disabled-codecs.json` makes the no-enable decision machine-readable.
- `schema-corpus/valid-maximum-capabilities.json` is the shared exact 32-entry
  positive boundary; the adjacent 33-entry negative is rejected by both
  schema and Java. `verify-schema-corpus.py` also regenerates that boundary,
  while Java proves a caller-supplied smaller limit can only tighten it.
- `schema-corpus/valid-skinned-without-multiple-uv.json` and
  `valid-morph-without-multiple-uv.json` prove that the second-UV capability
  is optional in both profiles; Java separately proves its exact bidirectional
  relationship with `TEXCOORD_1`.

The in-memory legal morph GLB carries exact target POSITION bounds, matching
base/target attributes, unit base normals/tangents, and non-unit morph deltas.
Negative rewrites cover zero accessor counts, FLOAT/U32 normalization,
UNSIGNED_INT outside primitive indices, missing base morph attributes,
non-unit base vectors, tangent handedness, and explicitly empty node weights.
They also exercise referenced and unused FLOAT NaN/infinities, raw extrema for
FLOAT and unsigned integer accessors (including normalized, strided, and
offset layouts), U8/U16/U32 primitive-restart maxima and unsigned U32
boundaries, and one/two-set UV limits across multiple primitives.
Additional Java rewrites reject noncanonical `TEXCOORD_` suffixes before they
can be parsed, counted, or used for accessor lookup.
