# Synchronized one-dimensional blendspace

## Scope and invariants

Add an opt-in standard entity renderer capability that maps one captured finite parameter to
adjacent weighted samples. Reuse the existing same-priority OVERRIDE compositor and immutable
frame weight multipliers. Do not change existing constructors, record components, resource
schemas, network protocols, or the discrete locomotion rule path.

A bounded immutable definition names existing layer IDs in strictly increasing finite parameter
order and defines a positive fixed common cycle. Every inactive member gets an explicit zero
multiplier. Binding validates one member controller per sample, equal priority and masks,
configured weight one, and positive-duration looping initial states. Differently sized clips
receive descriptor-speed-compensated rates: duration / (cycle * descriptor speed).

Validate configuration, all member ownership conflicts, commands, and frame weights before
mutating live clocks. The group exclusively owns its member commands and multipliers; upper-body
and other nonmembers retain their existing independent behavior. Initialize once per binding,
never re-seek or increment sequence because the parameter changes. Zero-weight samples still
advance. Define activation, reload, rollback, duplicate-frame, and large-gap event semantics,
retaining bounded no-backfill behavior and current-cycle phase recovery.

## Implementation

1. Core immutable definition, overflow-safe adjacent solver, validated generation binding
2. Standard renderer parameter callback and extraction/runtime integration
3. Separate actual actor opt-in mode using measured horizontal speed, with unequal clip lengths
   and non-unit descriptor rates; preserve discrete rules, attacks, procedural pose and attachments
4. Tests for finite/overflow/zero weights, real loaded GLB poses and CPU vertices, phase continuity,
   lifecycle/reload, conflicts/atomicity, and event bounds; exact public API baseline updates
5. Documentation and honest native visual acceptance limits

## Verification and delivery

Run focused suites followed by root checks and official Minecraft 26.3 checks/build/JAR/example
verification. Obtain an independent critical review, resolve findings, then publish a new stacked
branch and follow every required CI job to terminal state. Replace the cumulative Library archive
only after verifying its contents and identity. Do not merge, release, tag, accept an EULA, change
security settings, or claim native visual acceptance without a real session.

## Non-goals

2D spaces, variable cadence, root motion, foot locking, gait inference, automatic footstep
suppression, a new authoring schema, and synchronization protocol changes. Input clips must be
authored with matching gait phase.
