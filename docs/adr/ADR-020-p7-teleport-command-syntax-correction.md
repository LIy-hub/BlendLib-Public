# ADR-020: P7 Capture Teleport Command Syntax Correction

Status: Accepted
Proposed: 2026-07-30
Accepted: 2026-07-30 by the local project owner
Decision authority: local project owner

## Context

ADR-017 is accepted and fixes the P7 camera position and orientation at player
centre `(0.5, 67.0, 24.5, yaw=180, pitch=0)`. Before this decision, its table
used the now-rejected historical spelling `/tp 0 67 24 180 0`.

During the first real isolated-client attempt after ADR-017 source
implementation, the coordinator created a new disposable `BlendLib P7 RC
20260730` world only under
`D:\BlendLib\blendlib-showcase\run\p7-benchmark`, selected the generated P7
pack, set the fixed 1920x1080/FOV-90/FOV-effect-0 client conditions, created
the exact 100 rigid plus 25 skinned hosts, then entered the literal ADR-017
command.  Minecraft 26.1.2 rejected it with `Incorrect argument for command`
at the token following `0 67 24`; it did not move the player or start a
capture.

This is an empirical contradiction of ADR-017's exact command spelling, not a
reason to change its camera coordinates, orientation, target work, or capture
conditions. At that point, P7 capture remained paused pending this decision.

## Evidence

- The in-game command parser rejected `/tp 0 67 24 180 0` after the three
  position coordinates in the isolated P7 world.  This was directly retained
  in the coordinator's real-client screen observation.  Minecraft did not
  emit that client-side parser UI error to `latest.log`; the same isolated log
  does retain the pack load, world identity, exact 100/25 host creation, and
  normal shutdown at
  `D:\BlendLib\blendlib-showcase\run\p7-benchmark\logs\latest.log`.
- The local fixed 26.1.2 deobfuscated `TeleportCommand` bytecode shows that the
  location-only branch accepts a `location` and executes immediately; its
  `rotation` branch is nested only below the explicit `targets` plus `location`
  grammar.  It therefore requires an explicit target for position plus yaw and
  pitch.
- The same bytecode accepts a single entity target followed by location and
  rotation.  In the isolated single-player operator context, `@s` selects the
  local player without changing the intended player-centre coordinate contract.
- `P7ReferenceScenario.isAtCaptureCamera` already validates player centre
  `(0.5, 67.0, 24.5)` and `(180, 0)` rather than trusting command text.

## Decision — accepted 2026-07-30

The local project owner accepts the following syntax correction for the fixed
Minecraft 26.1.2 P7 capture protocol only. Replace the historical operator
command spelling with:

```text
/tp @s 0 67 24 180 0
```

The coordinate, player-centre, yaw, pitch, 1920x1080, FOV, dynamic-FOV,
render-distance, host-count, triangle, joint, warm-up, sample, and FPS
contracts remain exactly those accepted by ADR-017. This decision changes
only the syntactically valid way to address the same local player when a
rotation is supplied.

The ADR-017 table/text, `P7ReferenceScenario` constant/canonical manifests,
source-boundary/golden tests, P7 manual instructions, and P7 evidence
narrative must use this accepted spelling. The root coordinator separately
integrates the decision into the progress ledger. Then run one fresh isolated
capture from the current disposable P7 world or another new isolated world; do
not reuse the rejected historical command as evidence of a capture.

## Consequences and Gate Handling

This acceptance authorizes only the exact command-text alignment and a fresh
isolated capture using the accepted spelling. It does not make the rejected
attempt a capture, visual proof, JFR result, FPS result, allocation result, or
Gate result. P7 stays `WAITING` until that fresh capture supplies all required
evidence; all earlier 100/25, diagnostic, safety, and performance guardrails
remain unchanged.
