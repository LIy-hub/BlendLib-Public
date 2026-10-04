# Blender state/event authoring editor plan

Stack on strict authoring local commit `03529c833855c1279b9211eeb1d9f77f82b5b05c`
(remote equivalent `e1658801afa9bcffa89f1c6746aeac86d4a941e6`, tree
`51dfdad1b9b353bd4596bae43ac8a2c7d648e9af`).

The existing strict sidebar only has an opt-in toggle and Text picker. Users must
manually type exact attached Action and Action pose-marker names in JSON. Address
that proven burden with a bounded state/event editor, not a second asset engine.

- Keep explicitly selected strict Text JSON canonical. Load a single selected
  state into a draft; expose clip, loop, speed and ordered marker/event rows.
  Discover attached Actions using the exporter's existing discovery and pose
  markers from that exact Action. Support adding a state and choosing it as the
  initial state, without implicit renaming/deletion or reference rewriting.
- Explicitly apply the draft only after common source validation succeeds. Guard
  against Text selection/content changes while the draft is open. Preserve other
  states, optional fields such as next/blend_seconds, sockets and locomotion.
  Unknown fields still fail strict validation rather than being silently lost.
  Cancel/reload discards only the draft; export reads only saved Text content.
- Provide a deliberate new-Text path for a first configuration without overwriting
  an existing Text or implicitly enabling the runtime opt-in. Preserve the legacy
  exporter and X5 behavior. GPL UI/compiler sources stay in blender-addon; no
  runtime API/schema changes.
- Share source validation with the existing compiler, retaining exact exported
  GLB duration and node-path checks at export. Do not claim editor validation is
  proof that a source Action or socket will export successfully.
- Add pure adversarial roundtrip tests and genuine Blender 5.1.2 registration,
  interrupted/repeated/stale-draft, Action/marker discovery, explicit apply and
  output export tests. Feed genuine editor-produced output to existing Java
  acceptance on root and official 26.3. No new Minecraft graphics test.
- Update consumer docs, run focused and build gates, obtain one critical review,
  publish only this stacked branch, verify all exact-head CI terminals and replace
  the same cumulative Library bundle with exact source binding and evidence.
  No merge/tag/release/CurseForge/EULA/security changes.
