# Resource-pack locomotion rules plan

## Contract before implementation

Stack on the standard two-bone IK source tree `046d0e88a04968bfbc65c3fae3f2da8a80b55e73`.
Keep all existing APIs, strict-v1 descriptors and experimental X6 rules unchanged.

- Optional resource: `assets/<namespace>/blend_animation_rules/<model-path>.json` for the
  corresponding model key. Reload prepare reads and validates it; apply publishes immutable
  rules with the same model generation. No extraction or submit resource I/O.
- Version 1 root: `schema_version`, `default`, `minimum_interval_ticks`, `rules`. Default
  interval is zero. Each ordered rule contains `animation` and `conditions`. Boolean
  conditions have `input` and `equals`; numeric conditions have `input`, `enter_min`,
  `exit_min` or `input`, `enter_max`, `exit_max`. All conditions must match. Boundaries
  are inclusive. Min exit <= enter; max enter <= exit. Current rule uses exit thresholds,
  earlier rules may preempt using enter thresholds. Otherwise the default wins.
- Default and every target must be a declared continuous loop with no `next`; attacks and
  other one-shots remain existing sequenced cues. No expression language or gameplay authority.
- Strict unknown/duplicate keys and invalid types rejected. Bounds: 64 KiB sidecar, 32 rules,
  8 conditions per rule, 32 immutable named boolean/double inputs, name length <=64 ASCII
  identifier characters, minimum interval integer 0..200 ticks. Reject non-finite numbers.
  Missing/wrong-type/non-finite inputs preserve current playback with a bounded diagnostic. Malformed optional resources produce one bounded
  warning per model/reload and disable only rules, preserving ordinary model/layer behavior.
- Add `animationLocomotionRules(controllerId, inputs)` after standard entity layers/cues.
  Require a declared target controller. Rules own its sequence domain; competing explicit
  commands to that controller fail before rule selection/controller mutation. Other controllers keep their cues.
  This drives the actual layered evaluator, not only its legacy selector.
- Typed immutable input snapshot captures booleans and finite doubles once per extraction.
  Runtime state is scoped to exact owner and source identities, connection key, model and
  generation. A selected animation change emits a new increasing command at playhead zero;
  unchanged animation replays the same immutable command/sequence and does not restart. Ordered-rule identity can change without restarting
  the same target. Minimum interval is a selected-state hold, not candidate debounce.
  Clock rollback cannot move its watermark backwards or defeat the hold.
- Existing unload, disconnect, play init, generation and explicit retirement clear rule state.
  Shared source/entity isolation and recursive evaluation are covered. No new wire protocol.

## Verification and delivery

Use packaged idle/walk/run descriptor states and a sidecar with speed/grounded inputs in an
opt-in runnable 26.3 consumer. Headless verification must load the packaged resource and prove
that final layered poses change, stable inputs keep playback advancing, and upper-layer cues
remain independent. Cover parser bounds, invalid targets/default, reload disable/recovery,
threshold jitter, order/interval, immutable input, shared instance isolation and lifecycle.
Pin additive API; retain old ABI. Run root applicable suites and full official 26.3 build,
runtime JAR and runnable example checks; retain and report inherited Linux symlink baseline.
One focused independent critical review; publish only this new branch, verify exact-commit
CI terminal outcomes, and update the cumulative Library bundle with code-bound evidence.
No graphics rerun, desktop setup, main merge, release/tag/CurseForge, EULA or security change.

Next dependency handoff: Blender authoring improvements should build on this sidecar contract
only after runtime validation and actual layered consumption are established.
