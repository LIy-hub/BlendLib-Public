# BlendLib v1 audit-repair final local verification

Status: **six local finding reviews PASS / independent audit rereview PASS / audit-repair PASS**
Date: 2026-08-01
Branch: `Liy/blendlib-v1`
HEAD: `7d88c85d77a25667ad45bd5a792f4918429e979e`

This record covers the repairs made after independent audit thread
`019fb323-b710-7140-8d19-4b42d76bd754` returned `FAIL` against the stable
489-file digest
`982148ae0b75a5fb00830e3c993999712ba3786a41f289af5faa90eca9c19935`.
The pre-repair recovery snapshot is
`D:\BlendLib-recovery\20260731-004030`; its binary working-tree patch SHA-256
is `188a5e2be393a7ddd60381ccd9e3cc5e6e6c6b03429be488e2e29df5a4b3d861`.

## Local review results

1. Finding 1 — `PASS`: unified finite speed/state/event/advance limits and
   closed-form bounded loop/event advancement. Primary files include
   `BlendAssetLimits`, `DescriptorDecoder`, `AnimationController`, their
   definition types, schema, error-code documentation, and focused tests.
2. Finding 2 — `PASS` after one return: correction and continuous time now use
   `real delta * network speed * descriptor state speed`; speed-scaled boundary,
   replay, stale, next-cycle, and payload validation regressions pass.
3. Finding 3 / ADR-021 — `PASS`: both client payload receivers capture the
   receive-time `ClientLevel` and share a package-private object-identity seam;
   null, disconnect, A-to-B, and A-to-null are discarded before runtime/queue.
4. Finding 4 — `PASS` after three returns: all accessors, including unused
   declarations, receive bounded strict `normalized`/`min`/`max`, layout,
   extrema, and usage validation; derived Khronos fixtures and manifests verify.
5. Finding 5 — `PASS`: all skins receive joint common-ancestor, `skeleton`
   ancestor, palette, duplicate-positive-influence, and exact finite affine IBM
   validation; the high-joint performance control retains its threshold.
6. Finding 6 — `PASS` after one evidence-only return: generation-scoped
   conservative animated bounds cover rigid, skinned, all clips, cross-fades,
   IBM transforms, real culling consumers, overflow, and reload replacement.

Each finding was modified by its implementer and reviewed by a different,
read-only reviewer. Every review failure was returned to the original
implementer. No two write stages overlapped.

## Final Java 25 commands and retained logs

All Gradle commands used
`JAVA_HOME=C:\Program Files\Java\latest\jdk-25`, `--no-daemon`, and
`--max-workers=1`. Logs, exit-code sidecars, copied targeted XML, final XML, and
hash manifests are retained at:

`D:\BlendLib-recovery\20260731-004030\final-verification-20260801-060850`

- `01-clean-check.log`: `clean check`, exit `0`, SHA-256
  `bc6443f1ec0bc67e5773894dd8acf9c639f86c638352665f5a1807b8aab4991d`.
- `02-core-test.log`: `:blendlib-core:test`, exit `0`, SHA-256
  `b4100366709724a51fb8e423115621dd3a03b1e0a439111a6aaa6f5d27689126`.
- `03-build-release.log`: `buildRelease`, exit `0`, SHA-256
  `2e60a0673589a4e983a387aa932967a6946cbce1a0bd4a67d7f083230506dcbe`.
  This performed only local RC/Maven and local package verification; it did not
  publish externally.
- `04-targeted-core.log`: Findings 1, 2, 4, 5, and 6 core regressions, exit `0`,
  SHA-256 `e652fe4673dcdff2cc230d7916b3f72993015377c191d6f3f47f9a26964cafe4`.
- `05-targeted-common.log`: Finding 2 payload-codec regressions, exit `0`,
  SHA-256 `69dad0458b44281652347c4d036607aaa9533a0ff2e7d709eed77c75b611bc7b`.
- `06-targeted-client.log`: Findings 2, 3, and 6 client regressions, exit `0`,
  SHA-256 `9684b5702c5df038a07e1339544882200b91cc5c0e1333997234106c72e37286`.
- `07-targeted-showcase.log`: strict P7 reference generator control, exit `0`,
  SHA-256 `3c3cc370f41a9d9bb31e54f34ce6f1ace6128c31a997ae3f3fd73b74a33a4351`.
- `08-final-forced-check.log`: final `check --rerun-tasks`, exit `0`, SHA-256
  `e0962cc8796f80a7d70abd95e20e188720a2e5f80a8b81f4c079a19b01117657`.

Targeted copied XML aggregates and manifest SHA-256 values:

- core: 9 suites / 64 tests / zero failures, errors, or skips;
  `3e67769b4adfe89966889f89e95a92831f219e5df7062fab434658895711f134`.
- common: 1 suite / 3 tests / zero failures, errors, or skips;
  `1cc4c6f24e047e1f2168ece4bb45e3be8ba0e2371fb9e2b51df75a340712aeee`.
- client: 6 suites / 47 tests / zero failures, errors, or skips;
  `19ebebe057b9d7b36e2360128dbe305ce327b2bae86b138dc2a25cbb3fc0d68e`.
- Showcase: 1 suite / 3 tests / zero failures, errors, or skips;
  `2e916e7fdfa93848418848ed84f5b0255acb5d0d1b63284fff13fba1438babca`.

Final forced full-check XML aggregates:

- BlendLib API: 5 suites / 12 tests.
- API consumer fixture: 1 suite / 1 test.
- core: 22 suites / 102 tests.
- Fabric common: 5 suites / 10 tests.
- Fabric client: 33 suites / 140 tests.
- Fabric consumer fixture: 1 suite / 2 tests.
- Showcase: 20 suites / 59 tests.

All final suites contain zero failures, errors, and skips. The SHA-256 of the
complete sorted per-XML hash manifest is
`52b041849601ff888dc1d59d3d88c275bbcbf8e9f263add6d0c6a982f8d3dc3b`.

## Gate and safety state

Existing independent audit thread
`019fb323-b710-7140-8d19-4b42d76bd754` completed its read-only rereview at
2026-08-01 06:54 +08:00 and explicitly returned overall `PASS`. It found no
remaining P0/P1/P2 safety, correctness, or architecture issue and closed all
six original findings. Its final binding checks were:

- branch `Liy/blendlib-v1`, HEAD
  `7d88c85d77a25667ad45bd5a792f4918429e979e`;
- 270022-byte `exact-repair.diff`, SHA-256
  `0e1a3e5a78d902d50383941a7b9ddea452ae75c815040badf2cd8cecdddd258a`;
- 57 repair files: 49 modified + 8 added + 0 deleted;
- zero repository/current/baseline hash mismatches and no audit-time drift;
- 87/87 final XML files, 326 tests, zero failures/errors/skips; and
- all retained exit-code sidecars equal to zero.

The audit-repair Gate is therefore `PASS`, but P3--P8 are not promoted to
PASS. Real-client visual, two-client synchronization, Iris/Sodium
compatibility, 20-reload, and performance acceptance remain `WAITING` for
user-managed observation. This post-review edit changes only the two status
records; it does not change any reviewed production code, tests, resource
formats, public API, payload, wire format, or version.

No real Minecraft client or server was started. No formal server/world was
read, written, started, or stopped. No file was staged or committed, and no
push, public tag, PR, public publication, production deployment, or final
non-Add-on license selection was performed.
