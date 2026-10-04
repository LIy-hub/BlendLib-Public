# Genuine Blender transition-authoring fixture

Open `source.blend` in Blender 5.1+ with the add-on installed. The canonical
`TransitionEditor.runtime.json` Text and `runtime-authoring.json` were authored
entirely through real state/event/transition/socket/rule operators, without preset
JSON. The source/export/texture paths are `.blend`-relative and portable.

Idle, Walk and Run are continuous loops without Next, suitable for the included
ordered Run/Walk rules. Idle has Blend In 0.4, Walk explicit 0, and Run an absent
blend field. Attack uses speed 2, Next Idle and Blend In 0.2. All four distinct
Actions export frames 10..34 at 24 FPS as one-second clips. Thus Attack ends after
0.5 real seconds, and its automatic return blends over the entered Idle's 0.4s.
Footstep/Impact Action markers, visual-event keys and a Root/Hand socket are also
authored through the editor.

Additional states demonstrate the existing contract: Hold (non-loop, no next),
Self (non-loop, next itself), Cycle A/B (positive-duration cycle), Loop Next
(loop retains next but does not follow it), and Precise (exact blend double).
They are deliberately not locomotion targets. A speed-only runtime input callback
must supply the rules' `speed`; no gameplay inputs are inferred by the add-on.

Reproduce:

    blender --background --python-exit-code 1 --python blender-addon/scripts/verify_transition_editor.py -- --project-root .

`verification.json` records genuine Blender 5.1.2 identity, guards and deterministic
output hashes. Java `BlenderTransitionAuthoringLocomotionAcceptanceTest` loads those
exact committed GLB/descriptor/rule bytes and checks automatic next timing, entered
state blend duration, zero/half/full cross-fade poses, speed, hold/loop semantics,
self/cycles, precise numbers and a fresh controller built from a reloaded asset. No replacement
clips or transition JSON are injected by the test.

This is headless operator and Java runtime verification, not an interactive panel
or rendered Minecraft acceptance claim. Assets use the root Apache-2.0 license;
Blender implementation and verifier scripts use GPL-3.0-or-later.
