# Blender runtime authoring verification

## Scope

Explicit Text authoring compiles to existing strict-v1 descriptor and optional locomotion
resources. No production Java runtime/API/schema changes. Native Minecraft graphics remains
deferred; headless asset/controller/pose/callback evidence is not a rendered world claim.

## Genuine Blender gate

Official Blender **5.1.2**, build `ec6e62d40fa9`, downloaded from the vendor release directory.
Linux archive SHA-256 verified against the vendor checksum:
`aaccb355f50183979b698bcce7467103a76261b5fa59f4972295842662a285fb`.

`verify_runtime_authoring.py` creates real attached Idle/Walk/Attack Actions, Action pose
markers, exact Root/Hand node and a saved versioned Text. It executes the registered sidebar
operator and exports byte-identical GLB/descriptor/rules twice. A fresh CLI process reopens
source.blend and reads the persisted Text. Missing markers and stale rules fail without
replacing previous assets. Sidecar symlink escape leaves an outside sentinel unchanged.
Malformed unselected Text does not alter automatic export. Real fractional `fps_base=1.001`
export checks the corrected clip time domain and event location.

Static, rigid and skinned P2 two-run determinism pass. X5 registration/unregistration,
preview apply/restore, invalid-binding preflight and the actual frozen strict-export staging
options seam pass. Successful X5 atomic publication is
not claimed: this POSIX platform intentionally rejects its existing exact-handle publication
requirement with `BLENDLIB-X5-ATOMIC-001` before staging. No safety fallback was added.

## Runtime and compiler tests

Ten pure Python test methods include adversarial subcases: strict duplicate/unknown JSON,
versions/types, state targets, Action membership, missing/ambiguous/out-of-range markers,
exported clip duration mismatch, exact socket paths, event/state/socket/rule/condition limits,
16,384/16,385 aggregate events, 32/33 uniquely typed inputs, mixed-type conflicts, finite
large-threshold normalization, invalid hysteresis and non-loop locomotion targets.

Six `BlenderAuthoringLocomotionAcceptanceTest` cases consume the actual committed Blender
exports using ModelAssetLoader, real visual-event dispatch and client locomotion runtime.
They check one-second clip timing and transforms, footstep/impact callback crossing,
looping/Attack→Idle/cross-fade, exact socket coordinates and locomotion state/hysteresis/
advancing playhead/layered pose/events. All six pass on official Minecraft 26.3 and root 26.1.2.

Official 26.3 build, runtime-JAR and runnable-consumer verification pass. Root client and core
pass; root aggregate retains the inherited Showcase POSIX symlink-policy test failure.
The local sandbox uses the inherited selector-provider shim and quiet test JVM setup, while
GitHub CI uses normal toolchains. Exact-head workflow/job evidence is included in delivery.

## Independent critical review fixes

- Preserve X5 frozen legacy staging options when the new explicit field is absent
- Added the missing runtime aggregate visual-event cap
- Added shared-input type consistency and 32-input-key limits to locomotion validation
- Reject sidecar symlink destinations before staging, rather than after an outside write
- Bypass legacy auto-key slug collisions for explicitly mapped states
- Correct detected Blender 5.1 fractional-FPS animation time inputs and verify real export
- Normalize finite numeric rule thresholds so oversized integer tokens cannot violate the
  runtime JSON parser's 128-character number-token limit

The sidebar was exercised through real Blender registration and operator execution, not an
interactive visual screenshot review. No merge, release/tag, CurseForge publication, EULA
acceptance or security-setting change is part of this checkpoint.
