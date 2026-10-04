# Blender locomotion rule editor plan

Stack on socket editor tree `87ebd65ca2560ce3fe76feefbab0c1ff2ec50c8a`
(local `864e556c322a8d5303e03f3bcd05b3b655ccf23e`, remote
`70e0ae40e5e88b35fb704a680b63f8b4dcc91b98`). Source inspection confirms
locomotion is currently manual JSON; the sidebar says rules stay in Text.

- Add one explicit locomotion Load/Apply/Discard draft sharing the existing
  transient draft lifecycle and canonical Text identity/content conflict guard.
  Preserve states, events, sockets, advanced fields and absent optional values.
  Do not create a second parser, schema, exporter or runtime path.
- Choose a continuous-loop default and ordered rule targets from valid authored
  states. Add/remove/reorder rules and conditions only through deliberate controls.
  Conditions are explicitly Boolean, Number >= or Number <=; numeric enter/exit
  thresholds remain JSON-number text to preserve precision. Validate mixed input
  types, bounds and hysteresis through the existing strict compiler before writing.
- New drafts require an explicit default selection; do not select a fallback from
  an invalid state. Minimum interval is integer 0..200; preserve its absence when
  unchanged. Export reads only applied Text and never deletes optional sidecars.
- Build a usable idle/walk/run fixture through genuine Blender 5.1.2 operators,
  save/reload and deterministic strict export. Prove Java priority, inclusive
  thresholds, enter/exit hysteresis, interval and reload with those actual bytes.
  Cover repeated/interrupted drafts, Text changes, wrong types and invalid loops.
- Run Python, real Blender and Java/build gates, one independent critical review,
  publish only this stacked branch and await all exact-head CI terminals before
  guarded replacement of the same cumulative Library bundle. Keep disabled legacy
  auto-export, X5 and GPL boundaries intact. No main merge, release/tag, CurseForge,
  EULA/security changes or new Minecraft graphics run.
