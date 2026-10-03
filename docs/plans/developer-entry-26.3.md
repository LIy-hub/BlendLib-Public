# 26.3 developer entry consolidation

## Goal

Make the cumulative 26.3 source branch usable from one entry point without adding runtime
features or changing the published Beta.3 release. Keep historical evidence explicitly historical.

## Scope and sequence

1. Check build requirements, example tasks/properties and minimal public API signatures against source
2. Add a current quickstart linking existing capability guides and runnable consumers
3. Update README, documentation index and handbook summaries that otherwise contradict 26.3
4. Check links, compile the quickstart snippets against the verified 26.3 runtime, and confirm
   no runtime/resource/build logic changed
5. Publish a separate stacked documentation branch, observe its automatic CI to completion,
   and package current source/docs with explicitly identified, unchanged verified JARs

## Acceptance and limits

Show exact game/Java/Loader/Fabric API pairing, runtime versus optional example JARs,
first launch/test commands, supported startup flags, additive migration, ownership/lifecycle,
visual event baselines, attachment budgets/culling and named-skin reload/texture limits.
Distinguish ordinary consumers from advanced experimental X6. Do not imply native graphics,
world, performance or multiplayer acceptance from compilation or headless checks. No release,
tag, default-branch change, merge, new feature or publication-site upload is in scope.

## Next decision

Use one real consumer's integration feedback to choose the next bounded improvement. Existing
unverified native graphics/reload/tracking checks remain a separate acceptance task; do not
expand the feature list merely to keep development busy.
