# ADR-023: Complete Entity Root Rotation

Status: Accepted
Proposed: 2026-08-02
Accepted: 2026-08-02
Decision authority: local project owner

## Context

The Ancient Dragon consumer now requires server-authored yaw, pitch, roll, inversion, and
continuous aerial maneuvers. Applying that orientation to an animated skeleton node would mix
world presentation with authored local animation and would not describe the renderer's actual
entity root.

The ordinary entity adapter currently derives a glTF Y rotation from interpolated Minecraft yaw.
The strict animated asset envelope is finite, but a consumer-selected arbitrary root also needs an
explicit rotation-invariant frustum contract.

## Decision

The 26.1.2 animated entity adapter exposes one optional
`BlendEntityRootRotationSelector<E>`. The selector runs during extraction and returns a normalized
outer-adapter `BlendEntityRotation` mapping canonical model axes into world axes. When absent, the
existing interpolated-yaw behavior is unchanged.

Configuring a complete root rotation automatically changes that renderer's culling envelope to an
origin-centered sphere enclosing every corner of the prepared finite model bounds. The sphere is
represented as a finite world AABB for vanilla frustum culling; culling remains enabled.

The selector is not available to custom snapshot factories or static-rest convenience paths in
this first scoped addition. No entity, selector, or world reference reaches render submit. The
addition changes no descriptor, GLB profile, wire format, common/server dependency boundary, or
gameplay authority.

## Verification requirements

- Preserve byte-for-byte-equivalent default interpolated-yaw selection behavior.
- Prove a public normalized quaternion can pitch canonical model forward to world up.
- Reject missing animated configuration, duplicate selectors, null selectors, and null results.
- Prove the invariant culling AABB encloses asymmetric prepared bounds under arbitrary rotation.
- Compile the selector from the separate outer-coordinate Local Maven consumer without a core or
  project dependency.
- Run the complete client test suite, `clean check`, `buildRelease`, and an independent review.
