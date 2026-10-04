# Positive dynamic blendspace cadence

## Bounded additive contract

Extend the existing shared 1D/2D group with a positive finite cadence multiplier in
`[1/64, 64]`, rejecting tighter per-member command/effective-rate violations. Preserve all
fixed-cadence APIs and constructors with default cadence one. Add an extraction-only entity
callback and direct extraction overloads. Keep the existing clock; no custom time source.

A binding creates an immutable, exact-plan-bound complete member rate update. The existing
core FrameStage advances elapsed time with old committed rates, applies ordinary commands,
then validates and installs all new member rates for future intervals before one publication.
Rate changes do not issue commands, seek, alter sequence/watermark, reset occurrences/event
cursors, or restart transitions. Previous transition source rates remain unchanged.

The activation retains normalized integrated phase, a monotonic time high-water mark, and held
cadence. Piecewise phase integration uses the old cadence and overflow-safe modulo arithmetic;
it does not retain unbounded cycle history or apply the latest rate to all elapsed time.
Duplicate/backward timestamps do not advance and accept the last valid cadence at that boundary.
Reload rebinds changed clip lengths/descriptor speeds at the integrated phase. Long gaps retain
bounded silent recovery without marker backfill. Zero-weight members keep advancing.

Capture parameter and cadence once, validate every derived rate before external weights and cue
transactions, and check lifecycle/ownership/capacity before live changes. Commit clock proposals
only after core publication. Failed captured input or prospective core frames preserve prior state.
Do not expand this guarantee to post-publication pose modifier or event listener failures.

## Consumer and verification

Demonstrate authored validated cadence `[0.5, 2]` proportional to measured actual horizontal speed
in opt-in 1D and 2D consumers with unequal clips and nonunit descriptor speeds. Idle still advances
at positive cadence. Preserve independent upper animation, procedural pose, sockets and attachments.

Test old-rate interval semantics, immutable rates/foreign-plan rejection, all-member atomic failure,
stable sequences/transitions/observer traversal, endpoint/wrap markers, zero-weight consumption,
duplicate/backward time, repeated rates, lifecycle callback fences, reload with changed metadata,
long-gap recovery and nonmember isolation. Verify real GLB samples, final poses and CPU vertices;
retain fixed-cadence suites and additive ABI pins/external consumer compile.

Run focused tests, root aggregate checks, official Minecraft 26.3 build/JAR/runnable example checks,
one focused independent review, and public-branch CI through terminal results. Replace the existing
cumulative Library deliverable, with distinctly identified beta.4 cadence-preview artifacts.

## Explicit exclusions

No zero/pause/reverse, arbitrary clocks, reconstructed culled rate history, multiple groups, root
motion, foot locking, stride warping, phase-marker inference/merging, network/schema/editor changes,
private exporter changes, EULA/security changes, native graphics acceptance, merge, release, tags or
CurseForge publication. Native graphics remains user-deferred.
