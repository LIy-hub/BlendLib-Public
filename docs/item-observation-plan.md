# Animated item observation plan

1. Add immutable read-only status for existing retained stack identities, without creating state,
   reading clocks, sampling, purging weak entries, or refreshing access-order LRU.
2. Separate current controls and stored raw playhead from last successful extraction. Retain only
   semantic keys and scalar sample metadata. Mark historical generation samples explicitly stale.
3. Add `/blendlib_example item status` to the opt-in runnable consumer; absent status must explain
   unrendered, copied, released, disconnected and evicted stacks without pretending to know which.
4. Test non-touching lookup, copies and contexts, bounded eviction, release/clear, control changes,
   paused seek, repeated samples, immutable observations and generation mismatch. Pin additive ABI.
5. Run official 26.3 and aggregate checks, focused review, publish a separate stacked branch,
   verify exact-commit CI and deliver artifacts. Graphics remains unverified on this headless host.

Baseline: remote `feature/mc26.3-runtime-inspection` at
`1e7b220e872327d3bf442899760a7a0a8786bc23`; local content-equivalent parent is `92caa8e`.
