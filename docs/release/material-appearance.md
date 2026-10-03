# Material appearance implementation evidence

Development branch: `feat/mc26.3-material-appearance`, stacked on the layer visual-events implementation.

## Changes

Ordinary entity snapshot factories now optionally capture exact material-slot RGB multipliers and
visibility. Prepared rigid/skinned handles retain immutable slot names in original primitive
order without duplicating vertex data. Submit reads captured indexed appearance only. Unknown
names produce an immutable sorted diagnostic and atomically retain authored appearance, including
resource-pack slot removal; missing-model diagnostic geometry bypasses the selector.

All retained public JVM descriptors are preserved. The retained ABI regression removes only
explicitly pinned additive methods before comparing its original manifest byte-for-byte. New
value/selector descriptors are pinned by focused tests. Old primitive records and constructors
are unchanged.

## Coverage

- Five actual collector/vertex submission cases: static, animated rigid, captured skinned,
  repeated slots, hidden-first alignment, all-hidden, culling, missing-model immunity,
  whole/authored RGB multiplication, alpha, lighting, overlays and instance isolation
- Six capture cases: mutable map isolation, reload reorder/removal, exact handle fencing,
  malformed input validation, rigid/skinned geometry/palette/attachment/socket copy preservation
- Four entity/ABI cases: extraction once, repeated submit without selector access, builder
  capture and new public descriptors
- Executable packaged consumer verification: independent actors, real accessory triangles,
  strict GLB loading, conservative bounds and CPU-skinned snapshots

## Review and limitations

One independent critical source/test review found no material correctness issue. Review covered
selector wiring, snapshot/handle identity and copies, atomic fallback, missing-model bypass,
ordinal alignment, tint/alpha semantics and the real example asset. This was not an independent
build or graphics run.

Final local and exact-head remote test counts, logs, commit/tree mapping and job results are
included in the delivery bundle. The historical root Linux symlink-diagnostic test mismatch is
reported separately from new feature failures. Native graphical acceptance remains explicitly
user-deferred; no native window or visible GPU result is claimed. No merge, release or tag.

## Final local results

- Official 26.3: 153 tests, zero failures/errors/skips; build, runtime JAR verification,
  runnable example JAR and strict asset/selector execution all passed
- Root: 1,461 tests counted, including all 669 client and 321 core tests passing;
  only the unchanged known Linux showcase symlink-diagnostic assertion failed (one skip)
- `git diff --check` passed
