# X1–X9 Alpha merge integration record

Status: **the initial merge candidate failed independent final static review;
its directed F1–F6 recovery later passed a separate, limited independent static
closure.**

This record describes the 2026-09-05 true merge of the existing Alpha line and the complete
X1–X9 extension line. It records sources and conflict policy, not a release, test result, Gate
promotion, legal opinion, or user-visual acceptance.

## Source identity

| Role | Reference | Commit |
|---|---|---|
| Existing Alpha parent | `Liy/blendlib-v1-alpha` | `2a0dc2456ee9b640d3e7ad329fcc1dae6d4add8b` |
| Complete X1–X9 source parent | `agentloop/x8-platform-ecosystem` | `432942b22daf3589242e7471d857bf1aa83e0849` |
| Common base | shared ancestry | `7d88c85` |
| Initial true merge candidate | two-parent merge | `204a0075da878d23b817b9f2481f865b5a9e5498` / tree `95036a3f35e779ec42b75ecba2b7879ceba94d24` |
| Merge worktree | `D:\BlendLib-worktrees\x8-alpha-publish` | `agentloop/x8-alpha-publish` |

The initial candidate above is a real two-parent merge. Its FAIL history is
preserved and is not amended or rewritten by directed recovery. This document
does not pre-claim a remote push receipt, retained source branch, or remote
Alpha head.

## What the Alpha parent retains

- The current identity remains `1.0.0-alpha.1+26.1.2`; historical RC strings remain only inside
  evidence that was produced under those earlier candidates.
- The folded-B logo, pixel wordmark, source/verification references, Alpha release materials, root
  `LICENSE` (Apache License 2.0), and `NOTICE` remain current.
- The Alpha parent contains the existing audit-repair baseline, procedural
  timeline comments, negative synthetic entity-ID handling, ADR-022 pose
  modifier, and ADR-023 full root rotation. The initial conflict resolution
  intended to preserve their X3/X7 interactions, but the final review found
  selected Alpha repairs and production links had been lost in the initial
  composite; directed recovery is required before any current preservation
  claim can be made.
- `LICENSE-PENDING` and old local LicenseRef descriptions are not restored as
  current repository licensing. The initial candidate nevertheless retained
  inconsistent X8 artifact/documentation descriptions, recorded as F6 below.

## Initial final review failure and directed recovery

For the initial true merge `204a0075da878d23b817b9f2481f865b5a9e5498` / tree
`95036a3f35e779ec42b75ecba2b7879ceba94d24`, independent reviewer
`/root/x1_x9_alpha_final_static_review` returned **FAIL
(0C/3H/3M/0L)**. The review is immutable history, not a replacement for the
earlier Alpha audit PASS or any individual X-track Gate.

| Finding | Initial-composite defect recorded by the reviewer | Directed recovery state |
|---|---|---|
| F1 (H) | Strict accessor validation and production symbols expected by existing tests were absent. | CLOSED by the directed static closure below |
| F2 (H) | The receive-time level guard had no production caller. | CLOSED by the directed static closure below |
| F3 (H) | Detached consumers still resolved the historical RC identity rather than Alpha. | CLOSED by the directed static closure below |
| F4 (M) | Strict skin-validation rules had been rolled back. | CLOSED by the directed static closure below |
| F5 (M) | Conservative animated-bounds production invocation was absent. | CLOSED by the directed static closure below |
| F6 (M) | X8 artifact/license descriptions conflicted with the existing Apache-2.0/`NOTICE` decision. | CLOSED by the directed static closure below |

The initial merge selected conflicting blobs that regressed parts of the
earlier Alpha repair line; it did not retain every early Alpha repair as a
current fact. The directed source/core/metadata/documentation repair chain is
`f498c6c12b7656e43ac5da7d2b01f96710e1ead4` ->
`6c34e67331dc0955dadd6e1bf70f843e7c488bb0` ->
`311e97db322878514a1677f1822c7e031b4750cb`.

## Directed F1–F6 static closure

- Reviewed recovery candidate: `311e97db322878514a1677f1822c7e031b4750cb` /
  tree `d50c17ff9e15f20833ee472e83c4b4956103ab9e`.
- Independent reviewer: `/root/x1_x9_alpha_static_finding_closure`.
- Verdict: **PASS (0C/0H/0M/0L)**; F1 through F6 are **CLOSED** for this
  directed static recovery scope.
- The reviewer performed no test, Gradle, compilation, build, client, server,
  Blender, benchmark, converter, or other dynamic execution. The closure does
  not rewrite or supersede the immutable `204a007` FAIL.

This limited source/metadata closure is not a new all-track dynamic,
runtime, visual, reload, hardware, performance, NeoForge-binding, package,
release, or user-acceptance PASS. Existing `WAITING`/`PENDING` Gates retain
their original scope. The user separately authorized an ordinary non-force
Git push after this closure; this record does not claim that the push has
already succeeded.

## X1–X9 source coverage

The following representative integration commits are already contained in the X1–X9 source parent.
Cherry-pick provenance can make an original source SHA absent from direct ancestry; the listed
source parent is the formal inclusion reference.

| Track | Representative commit | Canonical record |
|---|---|---|
| X1 | `b2582bf24e92278c3be15acb5d1442b83356f050` | [X1/X5/X9 r1](x1-x5-x9-r1.md) |
| X2 | `8d35adc` | [X2 r1](x2-r1.md) |
| X3 | `7ccf02d59a6198f409b09f4b552312f5bdda2730` | [X3 r2](x3-r2.md) |
| X4 | `ccad8f94ecbcad7063ab34d351b8d56605f5e108` | [X4 r1](x4-r1.md) |
| X5 | `e4d29833200b8c9aa523658d03da1206985af21d` | [X1/X5/X9 r1](x1-x5-x9-r1.md) |
| X6 | `eb6be3e6e834fef5fc44eb5e8a0e3ee11c225746` | [X6 r1](x6-r1.md) |
| X7 | `7ef959be52ee69971dc70041a430a489e1d0ceac` | [X7 final r1](x7-final-r1.md) |
| X8 | `432942b22daf3589242e7471d857bf1aa83e0849` | [X8 static-review closure](../x8/static-review-closure.md) |
| X9 | `e70f4bfd81d8962333de55491dd681048f88bc8b` | [X1/X5/X9 r1](x1-x5-x9-r1.md) |

X8's separate static source PASS is scoped to `148a2c935a7cc7398107107c0317728f03a2b82f`;
`432942b` adds its later documentation closeout. Neither result is a PASS for this newly merged
tree, a standalone X8 release, or NeoForge/runtime/visual validation.

## Documentation conflict policy

| Topic | Retained Alpha fact | Retained X1–X9 fact |
|---|---|---|
| Identity and release materials | Alpha version, brand, release notes, Apache-2.0/NOTICE | Local artifact, inventory, consumer, and X8 documentation remains available as candidate evidence |
| Architecture decisions | ADR-022/ADR-023 and historic P0–P8 decisions | ADR-X7003 and expansion ADR/record links |
| Progress | Existing audit/P0–P8 history stays unmodified | X7's formal-candidate and X8 LOCKED/PENDING boundaries stay explicit |
| Platform scope | 26.1.2 runtime remains the Alpha adapter | 26.2 is separate; NeoForge binding remains WAITING; X8 aggregate stays local-only |
| License/source records | Existing root Apache-2.0/NOTICE and Add-on GPL scope | X8 provenance, copied-asset table, and third-party review boundaries remain, without stale pending status becoming current |

The document resolver edited only the listed documentation conflict paths. Runtime, Gradle, metadata,
attributes, properties, Git index, staging, commit, and push remain owned by the integration task.

## Acceptance boundary

- The user explicitly prohibited new tests and every test/build/client/server/Blender/benchmark/
  verification-script execution for this merge. No such command was run by the documentation work.
- Existing test, review, smoke, SHA, FAIL, PASS, and WAITING records remain historical evidence with
  their original scope. They are not re-run, erased, or promoted here.
- The initial independent, static, whole-merge review completed with the FAIL
  recorded above. A later independent directed F1–F6 static closure passed as
  recorded above; it does not erase the initial FAIL or promote any broader
  Gate.
- Runtime, dynamic synchronization, visual/manual, reload, Iris/Sodium, hardware, performance,
  NeoForge binding, package/release, and user-acceptance Gates retain their prior
  `WAITING`/`PENDING` boundaries.

## Integration handoff

This repair record does not amend `204a007` or claim a remote push result. The
directed repairs and their independent static closure are recorded above; the
user separately authorized only the ordinary non-force Git push that follows
this record. That authorization does not permit a tag, release, branch
deletion, history rewrite, CI/workflow change, or a Gate promotion.

## Post-merge bounded Showcase visual acceptance — 2026-09-06

The earlier WAITING statements above remain accurate for their historical merge-review boundary.
A later repaired source identity, `2dc81d56d1bb346cdce007e3fb0b8286ca03c4ed`, completed root
`check buildRelease` and a normal Loom Showcase client session. The log records four loaded models,
zero BlendLib diagnostics, multiple registered Showcase host summons, all-dimension saves, and
normal Render-thread shutdown. After operating and viewing the client, the user explicitly returned
`PASS 视觉验证`.

That user statement closes only the ordinary Showcase entity visual check exercised by this later
session. It does not recast the initial composite review FAIL, and it does not close full material,
external packaged-JAR, synchronization, repeated-reload, Iris/Sodium, hardware, P7/X7 performance,
NeoForge/26.2, P3–P8 aggregate, X1–X9 aggregate, or stable-release acceptance. The runtime X7 command
still truthfully returned `WAITING / NO_COMPLETED_X7_METRICS`. See the exact
[session evidence](../../evidence/merged-showcase-user-visual-pass-2026-09-06.md).
