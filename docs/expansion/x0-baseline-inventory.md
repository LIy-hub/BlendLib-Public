# X0 Baseline Inventory

This inventory is the audit record for the inherited source snapshot used by X0. It is deliberately a checkpoint record, not a release manifest, a stable-v1 statement, or a P0--P8 Gate result.

## Authority and capture boundary

- GOAL SHA-256: `233AC7AFDC85A21CBA67330E51007BAA55D74DEA2DF4ED833C848695E8113E06` (28,124 bytes).
- Source at authoritative capture: `D:\BlendLib`, `Liy/blendlib-v1`, `7d88c85d77a25667ad45bd5a792f4918429e979e`.
- Target started clean at the same commit: `D:\BlendLib-agentloop`, `agentloop/blendlib-expansion`.
- Authoritative PRE/POST capture time: `2026-07-31T19:13:45.8567039Z`; both sides report 490 porcelain entries: 19 tracked modifications, 471 non-ignored untracked paths, and zero staged paths. The candidate file set is 108 tracked files plus those 471 untracked paths.

The source remained a live, dirty worktree while X0 ran. Earlier observations are timeline evidence only: the task-reference file set was 577 files / 2,867,707 bytes (reference canonical text SHA-256 `9D6EB82462496B2A39096F01398C19D5001F21BEEB3F233577E5326A94AEF2C8`); a later capture was 2,889,658 bytes; the authoritative near-copy PRE/POST capture is 577 files / 2,890,219 bytes. Its canonical records are the only source of the checkpoint contents. The source's current status display was not used to rewrite the checkpoint after that capture.

A later, post-checkpoint probe at `2026-07-31T19:37:05.9498300Z` used `git status --porcelain=v1 --untracked-files=all` and observed 492 source entries (21 modified, 471 untracked, zero staged). It found seven changed candidate paths, no missing candidate paths, and no mismatch in the target's 577 captured paths. The exact time, command, counts and conclusion are in [final-equivalence-probe.json](manifest/final-equivalence-probe.json); the seven paths' captured/live size and SHA-256 records are in [post-checkpoint-source-drift.json](manifest/post-checkpoint-source-drift.json). This is recorded only as external concurrent activity of unknown authorship. It does not alter the authoritative near-copy PRE/POST evidence or rewrite the checkpoint.

## Included and excluded material

The candidate set contained 579 paths. It included 577 paths / 2,890,219 bytes and excluded two crash logs / 3,094,838 bytes. The full, line-addressable records are intentionally kept outside this Markdown file:

- [included files with source/category/size/SHA-256](manifest/eligible-files.tsv)
- [included TSV validation: Import-Csv plus independent tab split](manifest/eligible-files-validation.json)
- [excluded files with reason/category/size/SHA-256](manifest/excluded-files.tsv)
- [headerless canonical path/size/SHA-256 manifest](manifest/canonical-path-size-sha256.tsv)
- [machine-readable capture summary](manifest/capture-summary.json)
- [source status PRE](manifest/source-status-pre.txt) and [POST](manifest/source-status-post.txt)

The included category totals are 17 build files (180,609 bytes), 45 docs (270,731), 37 evidence (259,380), 236 production (800,429), 115 resources (617,948), 1 schema (4,919), 109 tests (618,647), and 17 tools (137,556). The excluded paths are `hs_err_pid25596.log` (113,629 bytes) and `replay_pid25596.log` (2,981,209 bytes), both classified as crash logs.

Independent reviewer r1 found one Medium blocker in the original rendering of `eligible-files.tsv`: its quote handling was not conventional TSV. This follow-up regenerates the same 577 ordered values as unquoted five-field TSV and commits a dual-parser validation record. The remediation has not been self-reviewed; it awaits a brand-new reviewer.

Canonical bytes were hashed with sorted, UTF-8-no-BOM records. The tab `path<TAB>size<TAB>lowercase-SHA-256` manifest is `777d4616772a8f4327b8dc4146e2e55dc0ae27e73e33853283940d60d27efd09`; the NUL `path<NUL>size<NUL>uppercase-SHA-256<LF>` manifest is `fa08eeacc39d8518d1ac60a688a7aed66c9043691df344209da6fef2181e9ef1`; the NUL-delimited path list is `ab071cf53b6d5a843b4d4048b318c41dd9f18297f0c1b42c57c03bb58eddd585`.

## Preservation proof and inherited whitespace

The target overlay was made only from the explicit 577-path list. Source PRE and POST canonical manifests were identical. Before the checkpoint commit, every included target path was compared with the source by SHA-256: 0 source paths missing and 0 target/source byte mismatches. `docs/implementation-progress.md` was separately equal at SHA-256 `E8DE72E2C83D71B90BA28FE965818135E6FB76BDA97405C9D4CA2116D7471D2C`.

`git -C D:\BlendLib-agentloop diff --cached --check` returned exit code 2. Its transcript has 66 output lines containing 16 trailing-whitespace diagnostics in 7 files; a reviewer can reproduce the diagnostic count by matching `^.+:[0-9]+: trailing whitespace\.$`. The exact file, line and diagnostic output is preserved in [checkpoint-inherited-diff-check.txt](manifest/checkpoint-inherited-diff-check.txt); only trailing spaces in the evidence display are rendered as visible `<SP>` tokens so that this X0 documentation commit itself remains whitespace-clean. Each diagnostic has a location line and its displayed offending-content line, so transcript lines are not the diagnostic count. These are inherited source bytes, not introduced by X0 copying: the full 577-file SHA comparison above is the proof, including every file named by that check. They must not be repaired inside this checkpoint because doing so would break the required byte identity.

Ignored observations were recorded but never copied recursively: the source contained generated build/run/.gradle trees and crash/manual-output surfaces. No credential, PEM, PFX, or key material was found in the included set. Existing machine-path and provenance limitations remain recorded rather than normalized by X0 (notably the Blender path in `gradle.properties`, P2 machine paths, and the P5 `D:/FabricMod26.1` provenance reference).

## r2 checkpoint-byte preservation correction

A raw Git-blob comparison of `8874d0b5b85a8e5e07270d185f3c0f0e6053feac` against the canonical manifest found exactly 22 mismatched paths. They total 115,018 bytes in the original checkpoint versus 119,339 captured bytes, a 4,321-byte EOL-only loss. The full old/new size, SHA-256, and blob-OID table is in [checkpoint-byte-preservation-correction.json](manifest/checkpoint-byte-preservation-correction.json). This corrects the earlier broad statement that the checkpoint itself was byte-identical: that statement remains true for the copied worktree before Git normalization, but not for the raw blobs of that historical commit.

`0102a9fea4e01abda19cb1975a46360a9cdf02ed` appends exact-path `-text -eol` rules to `.gitattributes` and explicitly re-stages only those 22 paths. Its [staged-index proof](manifest/checkpoint-byte-preservation-staged-index.json) records 22/22 captured SHA-256 matches. The companion [diff-check record](manifest/checkpoint-byte-preservation-diff-check.json) records the expected raw `git diff --check` exit 2 / 4,321 CRLF diagnostics, while `--ignore-space-at-eol` reports zero semantic-content lines. The diagnostics are not repaired because they are the captured bytes being preserved.

The corrected 577-path tree is 2,891,800 bytes. It has 576 matches to the historical canonical capture, zero unexpected mismatches, and exactly one intentional policy delta: `.gitattributes` itself, which records the preservation rule. The full corrected raw manifest is [corrected-checkpoint-tree-manifest.tsv](manifest/corrected-checkpoint-tree-manifest.tsv). The four fixture descriptor-to-golden SHA-256 comparisons are 4/4 in the correction record.

The correction was also checked from a new `--no-local --no-hardlinks` checkout with no object alternates. It re-established the 22/22 byte matches, 576 historical matches plus the one `.gitattributes` policy delta, and 4/4 descriptor-golden matches. Java 25.0.2 `clean check` and `buildRelease` both returned exit code 0; the exact commands, log hashes, and clean post-build status are in [fresh-checkout-validation.json](manifest/fresh-checkout-validation.json). These are automated facts, not visual, synchronization, performance, release, or reviewer evidence.

During the same follow-up, a stale local `refs/remotes/origin/HEAD` pointed to a nonexistent ref although no remotes or alternates were configured. It was removed only with `git symbolic-ref -d refs/remotes/origin/HEAD`; the before/after evidence records the original `git fsck --full --no-dangling` exit 2 and the repaired exit 0 in [repository-metadata-repair.json](manifest/repository-metadata-repair.json). No tracked worktree path was changed by that metadata operation.

Independent r3 review otherwise passed the raw tree, correction scope, fresh build, and metadata evidence, but returned FAIL solely because `checkpoint-byte-preservation-correction.json` encoded the zero-value `unexpected_paths` field without an array token. The field is now the strict-JSON value `[]`. `/root/x0_independent_review_r4` (gpt-5.6-sol/max) then returned `Verdict: PASS` with no findings; r1/r2/r3 FAIL history remains preserved. This is an X0 expansion-track result only: it does not make a release, alter inherited P0--P8 states, or replace the six-repair independent audit FAIL.

## Status separation

The historical checkpoint commit is `8874d0b5b85a8e5e07270d185f3c0f0e6053feac` (`chore(x0): checkpoint inherited BlendLib worktree`). Its raw-blob EOL defect is superseded by `0102a9fea4e01abda19cb1975a46360a9cdf02ed` (`fix(x0): preserve captured checkpoint bytes`), which restores the 22 captured blobs and adds the intentional `.gitattributes` policy delta. Neither commit makes the source stable, release-ready, published, tagged, or approved for any existing Gate.

At capture, the inherited ledger states P0/P1/P2 PASS, P3 REVIEW, P4 WAITING, P5 IN_PROGRESS, and P6/P7/P8 WAITING. The independent audit is still **FAIL**, with six open repairs: descriptor speed/event budget limits; sync speed/correction consistency; deferred A-to-B level protection; strict GLB accessor semantics; skin hierarchy/influence/inverse-bind validation; and animated bounds culling. The original audit must return PASS after independent implementation and review; X0 cannot reclassify these as routine risks.

ADR-021 is **Accepted** in the live captured source. Its scope is only the receipt-time exact `ClientLevel` guard and client-private regression seam; it changes no wire/API/version/Gate and leaves P6 WAITING.
