# Synchronized recovery and runnable 26.3 examples

This batch is stacked on `a17c4198ddb1b7091abd97ef1121c262e67b5f38` and remains
on `feature/mc26.3-sync-recovery-showcase`; it is not a release or a main merge.

## Recovery contract

An accepted synchronized animation can temporarily disappear from an extraction
input. The runtime may use its fallback during that interval. When the exact
previously accepted command returns, it now immediately resumes that command's
absolute timeline, even at the same sample tick or outside a low-frequency
update bucket's cadence. The complete command (sequence, animation key, start tick, speed, seed and
persistence flag) must match. Older sequences and conflicting same-sequence commands remain rejected.

Recovery is a silent baseline, not catch-up playback: markers skipped during
fallback are not replayed. The previous event high-water mark survives clock
rewinds. Normal future markers remain exactly-once observations. Model,
generation, entity/block unload, retirement and play-session boundaries still
reset the owned controller/clock history.

A regression compiled against the original production sources failed with
`expected WALK, actual IDLE`. After the fix, the focused runtime, event cursor,
controller and source-boundary suite passed 51 tests. The focused check compiled
real changed production classes with the prior verified 26.3 runtime artifact
as dependencies; it did not substitute Minecraft types.

## Runnable consumer

See [build, launch, install and in-world instructions](../versions/modern/showcase/README.md).
The example is enabled explicitly with `-Prunnable_examples=true` for 26.3 and
packaged in its own JAR. Standard BlendLib registration and packaging are unchanged.

The consumer demonstrates a walking base layer, a periodically triggered masked
upper layer, procedural tip motion, a separately captured model attached to the
final socket, and animation controls for the actual held item stack. Commands
with the same semantic sequence retain an immutable captured playhead; retracking
and resource changes receive a fresh capture.

The initial opt-in build compiled the actual library and both example source
sets against official Minecraft 26.3/Fabric dependencies and passed JAR/resource
isolation checks. `verifyRunnableExampleAssets` additionally exercises the
packaged descriptors/GLBs through the real strict loader and the shared scene
configuration, without launching Minecraft.

## Final local verification

The combined official 26.3 build passed `test verifyRuntimeJar verifyRunnableExamples`.
The modern suite passed 84 tests with no failures, errors or skips. The headless
packaged-fixture check passed strict loading, masked composition, procedural final
sockets, repeated/retriggered commands and identity/lifecycle cue cache cases.
A bounded critical review identified two example cue issues (changing a command
under one sequence and equality-keyed entity caching); both were fixed and are
covered by the executable fixture checks.

## Verification limits

Compilation, headless animation checks and CI are not visual acceptance. The opt-in client was actually launched on the cloud Xfce desktop (`DISPLAY=:0`).
Fabric loaded the library and example mod, but Minecraft stopped before opening
a world: OpenGL could not find a matching GLX visual, and Vulkan lacked
`VK_KHR_surface`. The error dialog was dismissed and the client closed. Gradle
returned exit zero despite that graphics failure, so launch-task success must not
be treated as visual acceptance. No in-world native/visual pass is claimed, and
no EULA or new security setting was accepted. Use the manual acceptance checklist in the example
README for native rendering, reload and multiplayer tracking checks.
