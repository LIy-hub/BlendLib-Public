# Fixed-cycle directional 2D blendspace

## Contract and scope

Add an immutable center + radial-ring definition for continuous idle/forward/back/strafe
mixing. The center is the origin. Three to fifteen unique ring members are authored as angles
on one finite positive radius; sort angles deterministically and require every sector to be
strictly smaller than 180 degrees. Solve only the containing adjacent sector. Triangle
barycentric weights interpolate center and its two endpoints, clamping the input radially to
the polygon boundary outside the hull. Explicitly zero every inactive member. Reject invalid
finite/bound inputs, duplicate directions/IDs and unknown bindings before live mutation.

Extract the existing 1D fixed-cycle member resolution/ownership into a shared immutable group;
retain every current 1D API and reuse its runtime clock, preflight transaction, generation,
activation, monotonic-time, zero-weight, event and recovery behavior. Add a vector capture and
standard renderer 2D binding. No per-frame seek or sequence change, dynamic cadence, root motion,
foot locking, inferred phase, event deduplication, general triangulation, graph, new schema,
network protocol or editor. Equal OVERRIDE priority/masks and bounded rates remain required.

## Real consumer and verification

Add a separate opt-in actor mode and asset with unequal-duration forward/back/left/right/idle
loops, nonunit descriptor rates, and actual collision-resolved world displacement transformed
to entity-local horizontal coordinates. Preserve independent upper attack, procedural pose,
sockets and attachments. Default, discrete rules, and 1D modes remain unchanged.

Cover exact direction/center, sector interiors, ring seam and quadrant continuity, outside hull,
finite bounds, duplicate policy, explicit zeros, no partial mutation, shared phase and stable
commands across stop/restart, reload, zero weights and retirement. Compare final poses and
CPU-skinned vertices against independent samples of the actual packaged GLB.

Commit this plan before implementation. Run focused tests, root checks and official 26.3
build/JAR/example checks; obtain one critical review and resolve findings. Publish only the
new stacked feature branch, await all CI terminal results, then verify and replace the same
cumulative Library archive. Preserve released beta.4 shader/reflection/cache-guard alignment
and distinctly identify preview JARs. No main merge, releases, tags, CurseForge, EULA/security
changes, private exporter changes, or deferred native graphics acceptance.
