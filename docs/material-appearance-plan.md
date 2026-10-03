# Ordinary entity material appearance

## Bounded goal
Add an extraction-only entity selector for exact authored material-slot names, RGB tint multipliers and visibility. Unspecified slots stay unchanged. Keep all retained public JVM descriptors and default behavior. Existing X6 variants remain separate.

## Layered delivery
1. Preserve reload-time slot identity as immutable handle metadata without duplicating geometry. Add immutable RGB/visibility values and an exact-handle-bound primitive capture. Unknown slots fail explicitly during extraction; missing-model diagnostic handles ignore selections.
2. Add an optional builder selector around the completed snapshot factory, covering static, animated rigid, and CPU-skinned snapshots. Resolve names during extraction only. Snapshot copies preserve appearance, palettes, lighting, sockets and attachments.
3. Consume captured indexed appearance in rigid and skinned backend submission. Multiply RGB with existing whole-model/authored tint, retain alpha and conservative bounds, and omit hidden primitives.
4. Add real-submission tests, defensive-copy/instance/reload/unknown-name tests, retained ABI plus additive pins, and a runnable independently colored actor/accessory example.
5. Run official 26.3 build/JAR/example checks and root checks, obtain one critical review, repair material failures, push only the isolated feature branch and observe every exact-head CI result. Package cumulative source/JAR/docs/evidence.

## Explicit exclusions
No textures, opacity, emissive or shader changes; no providers, schema, networking, items, nested attachments or X6 rollout. No release, merge or tags. Native graphics verification remains user-deferred.
