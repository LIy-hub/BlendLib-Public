# Blender transition authoring plan

Stack on the reviewed rule-editor tree `b1a77d7e525eac4d0d27d9c1dcdc1c66d93a3828`
(local `a78d0795247222fe71e0e85e61c8b83b6978874d`, remote
`13a02a345a519d40772e06256b3881e04128f1cd`). Source inspection confirms the
remaining state-authoring gap: `next` and `blend_seconds` still require Text edits.

- Extend the existing state/event draft, with one next-state picker offering
  explicit None and the loaded configuration's state keys, plus an optional
  Blend In (seconds) exact-number control. Reuse Load/Apply/Discard and the
  Text pointer/content stale guard. Only validated Apply changes canonical Text.
- Preserve absent optional fields, explicit zero, exact numeric values, events,
  other states, sockets and locomotion. No new parser/schema/runtime or exporter
  path. Missing/renamed/deleted target states and invalid locomotion targets fail
  atomically. Existing state keys remain immutable through the UI.
- Match the existing contract rather than inventing graph restrictions: Loop
  repeats and does not follow next; a non-loop state follows next on completion
  or holds its end pose without next. Valid self-targets and positive-duration
  cycles remain valid. Blend belongs to the entered state, not the outgoing one.
  Locomotion target/default states must continue to be loops without next.
- Build a new common-authoring fixture through actual Blender 5.1.2 state/event,
  socket, rule and transition operators, without preset JSON. Verify absent/zero/
  precise values, guards, invalid edits, recovery, deterministic export and
  save/reopen. Consume those exact exported bytes in Java to prove automatic
  next traversal, destination blend timing, no-next hold and allowed graph cases.
- Document a complete idle/walk/run/attack recipe without JSON editing. Run pure
  tests, genuine Blender regressions, applicable Java/build/package gates and one
  independent critical review. Push only the new stacked feature branch, await
  exact-head CI terminal results and update the same cumulative Library bundle
  with optimistic version checking. No main merge, release/tag, CurseForge,
  EULA/security changes or deferred Minecraft graphics rerun.
