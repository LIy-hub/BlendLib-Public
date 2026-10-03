# Minecraft 26.3 animation capabilities

Baseline: `mc/26.3` at `61620626a0c33cd7142bcb7a8e356854290a4eb5`.
Dedicated local branch: `feature/mc26.3-animation-capabilities`.

The implementation plan was prepared and communicated before product edits. Existing strict-v1 assets,
vanilla extraction/submit separation, and client lifecycle remain the foundation.

## Dependency order and completion criteria

1. **Layered animation** — adapt descriptor clips to the existing v2 evaluator without
   resampling interpolation. Expose independent controllers, masks, additive/override
   weights and transitions through the standard entity builder. Complete when final
   renderer palettes reflect layers, procedural edits happen afterward, and instance,
   generation and unload isolation are tested.
2. **Procedural components** — compose look-at, chain follow, spring inertia and rotation
   limits through a reusable post-animation hook. Complete when consumer code compiles,
   deterministic/duplicate-time behavior and per-instance reset are covered.
3. **Sockets and attachments** — capture model/entity/world transforms after the final
   pose; attach prepared child snapshots without render-time lookups. Complete when
   root/unit/rotation order, missing sockets, immutable capture and submit restoration
   are tested. Child visibility bounds remain a documented consumer responsibility.
4. **Synchronized visual events** — observe an accepted absolute timeline with sequence
   and crossing deduplication, bounded catch-up and loop handling. Complete when repeated,
   backward, skipped, new-sequence and budget cases are tested. No server gameplay logic.
5. **Animated items** — connect per-stack playback to the existing special renderer,
   retaining vanilla hand/GUI/dropped transforms. Complete when independent identities,
   copies, playback modes/controls, bounded retirement and captured palettes are tested.

Each layer includes a documented public entry point and executable or compiled consumer
coverage. The final acceptance pass runs the Minecraft 26.3 build, targeted integration
checks and existing regressions where the environment supports them. Native client
visual verification is reported separately; unit tests never substitute for it.

## Non-goals

No private Alpha import, schema-wide rewrite, advanced foot IK, gameplay event authority,
release publishing, remote push, merge, or changes to AncientDragon. Nested attachment
render trees and persistent item identity across serialization are not promised.
