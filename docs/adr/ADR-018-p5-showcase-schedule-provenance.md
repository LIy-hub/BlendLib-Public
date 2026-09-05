# ADR-018: P5 Showcase schedule provenance under P6 synchronization

Status: Accepted
Proposed: 2026-07-30 by the root coordinator
Accepted: 2026-07-30 by the local project owner
Decision authority: local project owner

## Context

The P5 manual acceptance text currently asks a real client to attribute the
standard Showcase actor to a local 132-tick fallback schedule: `idle [0,80)`,
`walk [80,120)`, and `attack [120,132)`. The same normal Showcase actor is
also deliberately wired to the P6 synchronized-animation path:

1. `BlendLibShowcaseClientEntrypoint` registers
   `synchronizedSkinnedAnimation(...)`.
2. The entity render builder obtains the latest P6 client animation state for
   that entity.
3. `SkinnedAnimationEntitySnapshotFactory` passes that state into the
   runtime.
4. `SkinnedAnimationRuntime` uses accepted synchronized state in preference
   to the local fallback schedule.
5. The P6 client store retains the most recently accepted state until entity
   unload or disconnect; the Showcase server emits an attack trigger every 80
   game ticks.

Therefore, a normal local Showcase session can prove that the synchronized
actor renders, but it cannot honestly attribute observed clip timing to the
P5 fallback clock or prove the whole 132-tick fallback cycle. Treating that
session as such would silently change the accepted P5/P6 test boundary.

## Evidence

- `blendlib-showcase/src/client/java/com/liy/blendlib/showcase/client/BlendLibShowcaseClientEntrypoint.java`
- `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/entity/BlendEntityRendererBuilder.java`
- `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/entity/SkinnedAnimationEntitySnapshotFactory.java`
- `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/animation/runtime/SkinnedAnimationRuntime.java`
- `blendlib-showcase/src/main/java/com/liy/blendlib/showcase/entity/ShowcaseAnimatedActorEntity.java`
- `docs/manual-client-acceptance-v1.md`, P5
- `docs/evidence/P5-isolated-real-client-2026-07-30.md`

## Decision required

Choose exactly one acceptance path before the P5 schedule item can advance:

1. **P5-only fixture.** Add a separately named, local-only P5 fixture whose
   binding intentionally has no P6 state source. It must expose the existing
   deterministic fallback schedule and be documented as a fixture, not as a
   replacement for the P6-synchronized actor.
2. **Revised provenance.** Update the approved manual acceptance text so P5
   verifies controller/palette/skinning mechanics without attributing the
   normal actor to the fallback schedule, while server-driven timing and
   cross-client agreement are verified only under P6.

The decision must state the exact fixture/command or the exact changed
acceptance wording, required automated tests, and which phase owns each real
client observation.

## Decision — accepted 2026-07-30

The owner selects path 1, **P5-only fixture**, with this exact contract:

1. Add `ShowcaseEntities.P5_FALLBACK_ACTOR` with identifier
   `blendlib_showcase:p5_fallback_actor`, backed by a dedicated
   `ShowcaseP5FallbackActorEntity`. Its explicit gameplay dimensions are
   `0.60F × 1.80F`, client tracking range `8`, and update interval `3`; no
   visual mesh may determine collision or gameplay behavior.
2. The dedicated entity must not import or invoke `BlendAnimations`,
   `ShowcaseAnimatedActorAttackSchedule`, P6 payload/state classes, synced
   entity data for animation, or client classes. It must not reuse
   `ShowcaseAnimatedActorEntity`, whose server tick deliberately emits the P6
   attack trigger.
3. Register that type in a separate client renderer block whose sole animation
   source is
   `.skinnedAnimation((entity, request) ->
   ShowcaseAnimatedActorStateSchedule.stateAt(request.ageInTicks()))`.
   It must not call `.synchronizedSkinnedAnimation(...)`. Consequently, no P6
   store state is read and the runtime takes the local fallback schedule even
   if an unrelated synchronized state exists elsewhere.
4. The normal `animated_actor` remains exactly on its existing P6 path,
   including its 80-tick server trigger, payload, sequence, tracking, and
   cleanup semantics.
5. P5 manual observation occurs only in the isolated local Showcase run/world
   using `/summon blendlib_showcase:p5_fallback_actor ~ ~-1 ~-4` followed by
   `/time query gametime`. Capture at least one complete 132-tick cycle,
   attributing `idle [0,80)`, `walk [80,120)`, `attack [120,132)`, and the
   return to idle to this fixture only. A second summon at `~2 ~-1 ~-4` after
   at least 40 observable ticks provides a same-frame different-phase check.
6. Required automated coverage adds a fixture contract test for identifier,
   server isolation, client no-sync binding, and schedule boundary/cycle use;
   the existing normal-actor P6 trigger contract remains unchanged.

The fixture implementation and its real-client evidence remain outstanding.
This acceptance does not make P5 or P6 PASS and does not relax the normal
actor's P6 verification requirements.

## Preserved guardrails

- Do not switch the normal Showcase actor away from P6 synchronization.
- Do not alter P6 payload, sequence, tracking, or retention semantics.
- Do not claim that an ordinary local actor video proves the 132-tick fallback
  schedule, fallback-independent clocks, or state durations.
- Do not edit the P5 acceptance table to PASS this item, relax a Gate, or
  change public API, wire format, version baseline, or asset schema.
- Do not declare P5, P6, P7, or P8 PASS.

## Required follow-up

Automated P5 controller/skinning/socket/lifecycle tests, normal real-client
observations of rendered mesh and texture, and evidence that is explicitly
not attributed to the fallback schedule may continue. P6's isolated network
work remains separate. The schedule-provenance acceptance item stays
WAITING/BLOCKED until this ADR is accepted and its selected path is tested.
