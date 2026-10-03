# Dynamic clip-layer weight plan

## Bounded outcome

Add extraction-time animation-v2 clip-layer weights independently of procedural pose weights.
Keep configured layers immutable and use a captured frame-local multiplier per controller/layer.
Do not turn weight updates into clip commands or rebuild plans, restart clocks, or change protocol.

## Contract

- Runtime multipliers are finite in [0,1]. Effective layer weight is configured weight times the
  supplied multiplier, then the existing bone mask. Missing entries mean one; unknown targets fail
  before frame mutation. Each extraction supplies a complete frame-local value; values are not sticky.
- Zero contribution does not pause controller time, transition progression, commands, or observations.
- Existing priority suppression, normalized same-priority overrides, additive ordering and masks remain.
- Entity callbacks read the entity plus extraction request once and return immutable captured values.
  Render snapshots carry the resulting pose, never a live callback or mutable weights.
- Retain every public constructor and record shape. Add overloads/methods only and pin new ABI.
- Observer snapshots distinguish configured weights from actual published effective layer weights.
  Reset, reload and separate instances cannot inherit another extraction's weights.

## Delivery sequence

1. Commit this plan on its own stacked branch.
2. Implement core frame-local evaluation and fabric extraction/entity adapters; exercise a runnable cue example.
3. Test validation, math, clocks, cues, lifecycle and immutable observation. Run official Java 25 / 26.3
   build, consumer/API checks and JAR verification. Record known unrelated Linux symlink assertion honestly.
4. Obtain one critical review, fix findings, push separate branch, and observe exact-commit CI to terminal.
5. Deliver cumulative source/JAR/evidence bundle. No merge, release, tags or graphics rerun.
