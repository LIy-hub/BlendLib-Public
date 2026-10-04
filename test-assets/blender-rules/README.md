# Genuine Blender rule-editor fixture

Open `source.blend` in Blender 5.1+ with the add-on installed. Its canonical Text
and `runtime-authoring.json` contain Idle/Walk/Run looping states, a non-loop
Attack→Idle state, marker events, a Root/Hand socket and structured locomotion
rules created through the real state/event/rule operators.

The exact rule order is Run, then Walk; the default is Idle. All use `grounded`
boolean true. Run requires speed >=2 to enter, >=1.5 to stay, and slope <=0.5 to
enter, <=0.75 to stay. Walk enters at speed >=0.1 and stays at >=0.05. Accepted
state changes have a 4-tick minimum interval. These typed inputs must be provided
by an existing runtime locomotion input callback; the fixture does not infer them.
All four Actions are distinct 1-second exported clips from frames 10..34 at 24 FPS.

Load / Add Locomotion Rules opens a transient draft. Rule arrows change explicit
priority; Apply validates then changes the Text. Export ignores unapplied drafts.
The PNG path and export root are `.blend`-relative for portability.

Reproduce from the repository root:

    blender --background --python-exit-code 1 --python blender-addon/scripts/verify_rules_editor.py -- --project-root .

`exported/` is the actual deterministic strict exporter output, loaded by
`BlenderRuleAuthoringLocomotionAcceptanceTest`. `verification.json` records Blender
build identity and output hashes. Java tests cover priority, inclusive min/max
thresholds, hysteresis, typed-input rejection, minimum interval, sampled poses and
independent asset/rule reload. This is genuine headless Blender operator and Java
runtime evidence, not an interactive sidebar or Minecraft graphics claim.

Fixture assets are first-party Apache-2.0 under the root license. The Blender
add-on and verification scripts retain their separate GPL-3.0-or-later scope.
