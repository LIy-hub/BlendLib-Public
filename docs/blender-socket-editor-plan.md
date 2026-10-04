# Blender socket authoring editor plan

Stack on local state/editor commit `34e557409123db1d9aed2f1a572b31625fdf13fa`
(remote equivalent `369e4c69d93ea4e377e2c3a083b0934bf36275dd`, tree
`9c7db1ce7e7b137638f922ac3735c6ea0d239761`).

Source inspection confirms strict-v1 sockets accept only `{ "node": "full/path" }`.
There is no socket offset or orientation field. Preserve that contract: users can
select an existing exported object, armature bone, or Empty helper. For an offset,
create and transform an ordinary Empty in Blender before loading the draft. This
slice does not create helpers, infer bone-parent transforms, or extend runtime data.

- Discover exact nodes by running the same filtered collection and glTF export
  settings into private temporary output. Share the exported path validation with
  the strict compiler. Resolve globally unique source names to real exported
  paths and label object versus bone targets; reject ambiguous names/paths rather
  than guessing a hierarchy. Restore source context after the discovery pass.
- Reuse the existing single-draft lifecycle, explicit Apply/Discard, canonical
  Text identity/content conflict guard, and common strict compiler. A socket
  draft supports adding or editing one named socket, never silently renaming or
  deleting. Preserve all states/events/locomotion and other sockets.
- Guard source collection/object/armature/bone identity and exported hierarchy
  between Load and Apply, including replaced same-name objects and changed paths.
  Export continues to use only canonical Text. Drafts clear on reload/restart.
- Prove pure patch preservation and genuine Blender 5.1.2 operator discovery,
  no-op side effects, stale drafts, duplicate names, object/bone distinction,
  helper transform hierarchy, save/reload and export-to-Java socket coordinates.
  Preserve legacy defaults and GPL/Apache boundaries; no renderer/runtime changes.
- Update the usable fixture and instructions, run focused/aggregate build gates,
  obtain one independent critical review, publish only this stacked branch, wait
  for all exact-head CI terminals and replace the same cumulative Library bundle.
  No merge, release/tag, CurseForge, EULA/security changes, or new Minecraft graphics.
