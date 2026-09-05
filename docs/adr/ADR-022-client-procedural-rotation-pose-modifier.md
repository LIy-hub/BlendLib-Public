# ADR-022: Client Procedural Rotation Pose Modifier

Status: Accepted
Proposed: 2026-08-01
Accepted: 2026-08-01
Decision authority: local project owner

## Context

The Ancient Dragon consumer needs velocity- and turn-driven secondary motion for its body,
neck, tail, primary and secondary wings, and suspended legs. Its authored GLB clips remain the
primary animation, while the secondary response must be evaluated per visible entity instance.

The current strict animated path samples and caches one immutable `LocalPose`, then immediately
constructs the canonical node/skin palettes and CPU-skinned render snapshot. A consumer can choose
semantic animation state or replace the entire snapshot factory, but it has no narrow supported
seam for a procedural pose adjustment. Replacing the snapshot factory would duplicate generation,
lifecycle, synchronization, cache, palette, socket, and skinning responsibilities.

The historical v1 baseline deferred cloth/rigid-body physics, multi-controller animation, bone
masks, and additive layers. The owner explicitly approved the narrower full-body inertia direction
after its client-presentation, attack-mask, reset, and server-authority boundaries were explained.
This ADR records that new authority without reinterpreting the preserved v1 baseline.

## Decision

The 26.1.2 Fabric client entity adapter may expose one optional entity-aware procedural pose
modifier for its existing strict animated path.

The modifier runs in this exact order:

```text
controller advance -> immutable base-pose sample/cache -> procedural rotation modifier
-> canonical node/skin palette -> sockets and CPU skinning -> immutable render snapshot
```

The contract is:

- The cached base pose remains immutable and is never replaced by, or aliased to, a modified pose.
- An unconfigured modifier is an identity operation and preserves existing source behavior.
- The modifier receives the current instance/model/generation, actual controller state and local
  time, client extraction clock, and a read-only unique node-name/index/parent lookup.
- The entity adapter may additionally provide the current entity and immutable extraction request
  to its consumer callback. No entity or world reference crosses into render submit.
- The public entity callback receives an immutable, outer-adapter-owned rotation-pose facade, not
  the nested core `LocalPose`. It can read sampled node rotations and return the supplied facade or
  one derived with sparse finite normalized rotation overrides.
- The facade cannot add or remove nodes and exposes no translation or scale. The entity adapter
  applies its sparse overrides to a new core pose internally, preserving the cached pose's exact
  node set, translation, and scale automatically.
- A null result, a facade captured for another invocation, or an invalid rotation is rejected
  before palette construction. The runtime does not silently mutate, fill, or cache malformed
  consumer output. The lower-level runtime hook may retain core types as an internal boundary.
- Both strict animated profiles currently accepted by the retained `skinnedAnimation` entrypoint
  (`rigid_v1` and `skinned_v1`) keep their existing extraction behavior.
- The modifier performs no resource I/O, descriptor/GLB parsing, networking, or render submission.

Rotation-only output is intentional. The load-time conservative animated envelope is invariant to
additional finite rotations under strict v1 positive uniform scale, while arbitrary procedural
translation or scale could escape the prepared culling bound. Supporting either later requires a
separate bounds contract and ADR.

## Authority and gameplay boundary

This hook is client presentation only. It does not change the descriptor schema, GLB profile,
payload/wire format, common or server code, collision, hit detection, damage, targeting, attack
origins, drops, or persistence. A consumer must mask or bound visual motion where a gameplay proxy
must stay visually close to a server-authoritative result. Visual bones and sockets produced by the
modified pose remain presentation data and cannot become gameplay authority.

This decision authorizes the reusable hook and the Ancient Dragon inertia consumer. It does not
authorize a general cloth/rigid-body engine, root-motion authority, arbitrary additive animation
stack, publication, push, release, or a P3-P8 Gate promotion.

## Verification requirements

- Prove modifier execution occurs after base-pose caching and before rigid/skinned palette capture.
- Prove modifying one extraction cannot alter the cached base pose or another instance.
- Reject null, missing/extra-node, translation-changing, and scale-changing results.
- Preserve the unconfigured rigid and skinned paths.
- Exercise the public builder ordering and configuration constraints.
- Compile a separate local-Maven consumer's `.poseModifier(...)` callback from the outer coordinate
  without any project, core, common, or client-module compile dependency.
- Prove a socket transform is captured from the modified canonical palette, not the cached base
  palette.
- Independently review the implementation before it is used by the Ancient Dragon consumer.
