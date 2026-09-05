# ADR-X5002: Publish X5 one-click and batch output through staged rollback

## Status

Proposed/Experimental implementation candidate. X5's isolated track was independently reviewed by /root/x5_postcommit_review_r3 (gpt-5.6-sol/max), Verdict: PASS with no findings. This local X1/X5/X9 integration candidate still awaits a fresh gpt-5.6-sol/max integration review; this status does not accept the proposal or change runtime scope.

## Context

An export writes a GLB, descriptor, external PNGs, sidecar, report, and
optionally refresh message. Writing directly can expose a mixed old/new bundle;
batch UI order can also make duplicate-output behavior non-deterministic.

## Decision

Preflight all items, bounded-serialize each canonical sidecar before creating
its export stage, sort batch entries deterministically, reject duplicate
logical/output IDs, export to private staging, and commit a sorted file bundle
through temporary backups. On replacement failure, restore old targets and
remove newly created targets. If rollback itself fails, preserve the private
backup and report only bounded project-relative affected targets so prior bytes
remain recoverable. Keep the operation project-relative and never delete an
unscoped path.

Before creating a private legacy stage, build a conservative canonical report
envelope using the complete diagnostics, exact predicted strict-v1/sidecar
artifact paths, fixed-size hashes, and worst-case bounded count widths. Reject
an envelope above the inclusive 512 KiB limit. Canonicalize the actual report
again under the same limit before it can enter the atomic publication bundle.
Published reports use compact canonical JSON so pretty-print whitespace cannot
push an otherwise valid semantic document over the writer/reader boundary.
Report index and vertex counts use exact built-in non-negative integers within
the signed Java `long` range; boolean and integer-subclass coercion is not
permitted.

Source all preflight/report diagnostics from the exact result-bound snapshot
registry, not from caller-visible result entries or the report builder's
compatibility argument. Validate result identity and snapshot generation before
path checks or stage/export access. This makes caller mutation unable to add a
false ERROR, hide a real ERROR, or inject report rows, while preserving an O(1)
first-error publication gate.

For batch export, finish and retain every sorted item's trusted preflight before
preparing any item. A late invalid item therefore creates no project root,
private stage, runtime artifact, report, refresh message, or exporter call.
An explicit `--report` path is only a second publication location for the exact
canonical `blendlib-x5-asset-report-v1` bytes; it never receives the separate
`blendlib-x5-export-result-v1` command result.

Before any project-root creation, private stage, legacy exporter call, report
write, or refresh write, construct one complete resolved artifact graph. The
graph uses the strict-v1 exporter's single pure naming helper for descriptor,
GLB, and every external-PNG filename; X5 does not reproduce the texture slug
rule. It also includes the authoring sidecar, default report, optional explicit
report, and optional refresh message. Every target is resolved under the
project root, normalizing dot segments, existing symlink/junction components,
and Windows case identity. Equal targets and file-versus-directory ancestors
are rejected, including an existing directory/non-regular target or parent.
The lone allowed duplicate is one item's explicit report at its own default
report identity, because both receive exactly the same canonical report bytes.
Every other collision reports both deterministic owners and artifact kinds.

Batch preflight retains each item's immutable plan, then validates the union of
all plan graphs under `BLENDLIB-X5-BATCH-002` before the first stage exists.
This catches collisions such as `a/b` and `a_b` external PNG slugs even though
their logical IDs and GLB paths differ. The plan is exact-identity registered
with the trusted snapshot; a copied, forged, or Python-mutated plan/options/
claims object fails closed rather than changing the approved publication set.

Windows publication deliberately remains on ordinary legacy Win32 path
spellings; X5 neither accepts `\\?\`/`\\.\` project roots nor claims opt-in
long-path support. After the complete single-item graph (or complete batch
union graph) is valid, but before root-route capture, root creation, private
stage allocation, or legacy export, X5 budgets every potential mutation path
in UTF-16 code units. A file leaf is limited to 259 visible units (`MAX_PATH`
including its terminating NUL); every `CreateDirectoryW` path is limited to
247 visible units. The directory inventory includes every project-root missing
suffix, resolved physical public parent, private legacy/stage/backup root,
nested artifact/texture parent, and the strict-v1 private `.raw.glb` scratch
leaf/parent. A `BLENDLIB-X5-PATH-005` rejection names only an approved
project-relative artifact or logical scope and occurs before any mutation. A
batch graph collision still reports `BLENDLIB-X5-BATCH-002` before this capacity
gate; a later direct prepare and the generic atomic writer repeat the check as
a TOCTOU defense.

The plan also captures a physical publication route: an existing project root
is bound by device/inode, while a legal missing root is represented as that
same approval-time ancestor plus its exact missing suffix. Preparation reopens
the approved anchor and may create only that suffix through the retained lease.
It separately binds every existing output-parent directory in the artifact
graph. The writer reopens the prepared root binding before it stages bytes and
rejects a same-spelling root or output-parent replacement rather than treating
it as a new baseline. For a batch whose root was initially absent, the first
prepared item creates it once; later items must reopen that exact physical
root binding.

The current exact-source publication backend is Windows-only. Windows derives
every transaction directory and leaf identity from a no-follow Win32 handle's
`FileIdInfo` (64-bit volume serial plus 128-bit file ID), never from a Python
`st_dev` encoding. It keeps each generic bundle stage leaf, authoritative old
public leaf/backup, and installed public leaf on one transaction-lifetime
content handle that denies external WRITE and DELETE sharing. A stage is
written, flushed, and hashed through that handle; old public bytes are hashed
through their handle before backup; the same handle performs every move,
delete, and restore and is re-hashed after each transaction boundary. Before
the first public move, X5 locks every already-existing public target, so an
incompatible writer fails closed before a partial public replacement begins.
Leaf handles are released only after commit cleanup or after rollback has
either restored the authoritative old bytes or deliberately retained recovery
backup. Root and relevant parent directory leases remain live throughout. On
POSIX, X5 rejects before project-root creation, private staging, or legacy
export because this implementation has no equivalent exact source-handle move
primitive. It deliberately does not claim that a pathname `rename`, including
`renameat2`, supplies that missing capability.

The cleanup guard begins before private stage/backup creation and target
resolution. Pre-transaction failure cleans both new directories; recovery
backup retention begins only when rollback cannot restore or remove an affected
target.

Resolve and freeze the original blend-directory/project-root external-PNG
allowlist before substituting the private stage as `project_root`; the staged
legacy exporter must enforce that frozen allowlist rather than silently
narrowing it to the stage.

The legacy exporter is a trusted in-process compatibility seam, not a public
publication authority or a sandbox. It receives only the private stage root;
report, refresh, and manifest paths are disabled. X5 accepts bytes only from
the exact approved staged leaves, and success/error cleanup uses the original
lease graph rather than closing it and rediscovering a pathname. Unknown or
changed private content is retained with a bounded recovery diagnostic.

Hash authoring `.blend` sources under a separate 1 GiB source cap with the
same fixed 8 KiB requests and file-change checks. Do not reuse the 64 MiB GLB
cap for source compatibility; runtime artifact caps are unchanged.

## Consequences

- Replacement failure, successful rollback, and rollback failure are distinct
  fault-injection cases; the last preserves its recovery backup.
- This is staged replacement with best-effort rollback, not a cross-file or
  cross-filesystem transaction. OS durability remains an environmental
  limitation.
- Batch failures block publication of every staged item.
- Full-graph conflicts preserve old published bytes because they occur before
  project-root/stage creation and before any legacy export invocation.
- On the current POSIX backend, X5 export is unavailable by design rather than
  falling back to a weaker pathname move.

## Alternatives rejected

- Direct overwrite: can leave a descriptor and GLB from different runs.
- Per-item batch commits: violates all-or-nothing batch expectation.
- New shared build transaction service: exceeds the X5 authoring-only scope.
