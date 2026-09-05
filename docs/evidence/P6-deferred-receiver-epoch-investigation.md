# P6 deferred receiver level-epoch investigation

Status: `PROPOSED / NOT A BEHAVIORAL REPRODUCTION`  
Date: 2026-07-30

## Finding

The current clientbound entity and block-entity handlers defer onto the client
executor and resolve `context.client().level` inside the deferred lambda. If a
payload received for level A remains queued until the client has switched to
level B, `ClientAnimationSyncRuntime` receives B and may create B-scoped
unknown-target state for the old payload. This violates the accepted v1 rule
that cross-dimension/session state cannot be reused.

This is a deterministic source-path finding, not a claim that Fabric has
scheduled such a callback in a retained real client session.

## One bounded test attempt

The coordinator ran exactly one Java-25/no-daemon/max-worker-1 targeted test
with a 1 GiB Gradle JVM cap, targeting
`ClientAnimationDeferredReceiverEpochTest`. The command's Gradle option was
placed before the task selector, the test compiled, and JUnit started.

It failed before either payload callback because direct use of
`Level.OVERWORLD` triggered Minecraft registry initialisation without the game
bootstrap:

```text
java.lang.IllegalArgumentException: Not bootstrapped (called from registry minecraft:game_event)
```

The retained XML is
`blendlib-fabric-client/build/test-results/test/TEST-com.liy.blendlib.fabric.client.animation.sync.ClientAnimationDeferredReceiverEpochTest.xml`.
It proves neither packet misattribution nor a product defect in a live client.

## Test-harness disposition

The uncommitted harness used Fabric implementation registry access, global
receiver registration/removal, reflection of a private static flag, and
`Unsafe`. It was removed after the attempt because it makes normal test runs
environment-dependent and cannot be a stable regression. No production, API,
wire, or packet change was made.

ADR-021 proposes the smallest client-private receive-time level guard and a
new deterministic no-global-registry regression. P6 production work at this
seam is paused pending the local owner decision.
