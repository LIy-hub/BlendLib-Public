# Item animation reload safety plan

Base: feature/mc26.3-item-observation (remote 2e8058f; identical local tree).
Branch: fix/mc26.3-item-animation-reload.

1. Make duration discovery empty for an undeclared state, without changing strict controller lookup or hiding other errors.
2. Preserve the requested state and all playback controls. Unavailable extraction does not clamp, wrap, stop, or advance the control clock; elapsed playing time is included on the next control/sample operation, as before. Recovery normalizes against the restored duration with the requested mode.
3. Keep the existing renderer fallback: rigid/static handles use their default static pose; skinned or missing handles use the missing-model placeholder. Never reuse a prior-generation pose or silently select a different animation.
4. Add immutable last-extraction status separately from the existing observation record, preserving its constructor and binary ABI. Report requested animation, generation, explicit unavailable reason, fallback, and whether that attempt belongs to the current generation. Historical sample remains historical. Neither observation promises submit success.
5. Exercise ordinary item extraction across actual published generations, missing/undeclared animation, recovery, loop and paused controls; pin additive ABI and consumer formatting. Run focused tests plus builds/JAR/example checks, one review, publish only this branch, verify exact-commit CI, and package evidence.

No merge, tag, release, CurseForge, or repeated unsupported graphics run.
