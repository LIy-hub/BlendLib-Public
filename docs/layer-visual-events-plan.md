# Opt-in layer visual events: bounded implementation plan

Branch: `feat/mc26.3-layer-visual-events`, stacked on dynamic clip-layer weights.

## Scope agreed before implementation

Expose descriptor visual markers to standard entity consumers through an additive callback.
Use the existing immutable v2 observer traversal and generation-scoped descriptor plan; do not
introduce another playback engine, network format, or gameplay event authority.

1. Add an immutable event with controller/layer/state identity, real loop/occurrence provenance,
   descriptor marker and sampled effective weight. Bind its cursor to an exact owner/plan.
2. Keep the cursor in the runtime's instance-local layered clock. Consume every publication,
   even with no listener, before callback dispatch. Preserve old constructors, records and methods.
3. Define silent first observation, seeks, missing revisions, lifecycle resets and truncated traces;
   exact automatic (start,end] crossings; no synthetic time-zero markers; bounded tail catch-up;
   zero-weight consume/suppress; no backfill or replay after callbacks fail.
4. Connect the entity builder and provide actual actor-owned callback measurements in the runnable
   consumer, with bounded storage and read-only inspection.
5. Cover core/runtime/API lifetimes and edge cases, build official 26.3 and both JARs, perform one
   critical review, publish the separate branch, await all exact-head CI and bundle the evidence.

## Explicit exclusions

No merge, release, tag or distribution-platform upload. No native graphics acceptance claim.
The user deferred graphical testing; this work does not retry graphics setup or change that scope.
Legacy visual callbacks remain independently opt-in; callers enabling both can receive both paths.
