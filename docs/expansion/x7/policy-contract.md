# X7 policy foundation contract

## Status and boundary

This is a proposed, adapter-private X7 repair candidate. Its R1 fresh review was **FAIL
(0C/0H/3M)** for budget overflow accounting, forgeable/incomplete backend binding, and unscoped
culling. The targeted repair is implemented and tested, but it remains subject to a new independent
review.

The six policy sources are pure Java and package-private in the Fabric client reload domain. They
have no Minecraft, Fabric, raw GL, resource, discovery, reflection, I/O, or threading dependency.
D2b wires only a source-bound CPU-route primitive into existing X4/X6 preparation; it does not
wire an accelerated backend or create a public policy API. The terms below are policy terms, not a
public API.

## Generation-frozen backend plan

The lifecycle is strictly preparation then publication then guarded consumption:

```text
X6-derived generation + handle + model + geometry + material-route + LOD identities
        + capability/preparation/upload observations
        -> outer owner creates one private-proof prepared decision
        -> publish transfers the identical binding and proof
        -> future submit validates every retained identity
```

`Binding` retains exact object identity for all six values. The future integration owner must source
the material-route and geometry identities from the existing X6 prepared render-plan/material
authority; this X7 policy retains references only and never creates a second mutable owner.

`GPU_CANDIDATE` is valid only when all three observations are true. The first failed observation
selects CPU before publication:

| Observation | Published backend | Fallback reason |
|---|---|---|
| GPU capability unavailable | CPU | CAPABILITY_UNAVAILABLE |
| GPU preparation failed | CPU | PREPARE_FAILED |
| GPU upload failed | CPU | UPLOAD_FAILED |
| All observations succeeded | GPU_CANDIDATE | NONE |

`Prepared`, `Published`, and their `DecisionProof` have private constructors. This proves the
Java language/API boundary: ordinary same-package source has no direct construction entry for a
candidate/published state from a `BackendChoice` value or a value-type constructor. The unnamed-module
arrangement does not treat malicious reflective access as a trust boundary; production X7 source
forbids reflection. Tests therefore assert private modifiers and no public construction API, rather
than claiming that reflection is unreachable. Publication takes no replacement binding or backend
argument. Submit takes identity inputs only and rejects stale generation or non-identical handle,
model, geometry, material-route, or LOD. It has no submit-time backend switch, and dependent budget
evaluation receives `Published`, not `BackendChoice`.

`GPU_CANDIDATE` is a policy label only: it is not evidence of a real GPU resource, upload, backend,
submission, or performance result.

### D2b CPU-subset projection

Generic X4 extraction does not own trustworthy X6 geometry, material-route, or LOD identities.
It must not synthesize them merely to call the full `Binding` API. D2b therefore uses a separate
private-proof CPU-subset binding of only generation, exact render handle, and exact source-view
identity. It publishes only `CPU/CAPABILITY_UNAVAILABLE`; it has no general capability entry and
cannot publish `GPU_CANDIDATE`.

The trusted `ClientGenerationLeaseBinding` retains this immutable route internally and exposes
only a primitive exact-snapshot check. That primitive is not a lease, owner, handle extractor,
policy value, or pass-completion receipt. X4 reads it before provider pinning and stores a private
submission primitive. Managed X6 reads it before its existing bridge/transfer/pin sequence and
stores only the CPU route. No submit path invokes a policy factory, lookup, registry, budget, LOD,
or animation policy.

## Budget contract

`Budget` has hard limits for generation totals (models, primitives, bones, vertices) and per-frame
totals (animated instances, bone animation work, vertex animation work). `Request` describes one
added model cohort. Before any publication-capable disposition, the policy derives:

```text
boneAnimationWorkPerFrame   = bones    * animatedInstancesPerFrame
vertexAnimationWorkPerFrame = vertices * animatedInstancesPerFrame
```

Both products use checked multiplication. The policy then checked-adds every request field and both
derived fields to the immutable `Usage` returned by the previous successful admission. `Usage` has a
private constructor and is returned only by a publishable result; the future real generation/frame
owner carries it. X7 holds no global mutable accumulator and does not compete with X4/X6 ownership.

| Condition, evaluated in order | Disposition | Diagnostic |
|---|---|---|
| Derived bone or vertex work multiply overflows | REJECT | exact multiplication-overflow reason |
| One request field or derived work exceeds a per-model hard limit | REJECT | exact per-model limit reason |
| Existing usage plus request/derived work add overflows | REJECT | exact cumulative-overflow reason |
| Projected generation/frame total exceeds a hard limit | REJECT | exact cumulative-limit reason |
| Within all limits and frozen published backend is CPU | CPU_FALLBACK | CPU_BACKEND |
| Within all limits, GPU candidate, any projected count exceeds half of its limit | DEGRADE | BUDGET_PRESSURE |
| Otherwise | ACCEPT | NONE |

Arithmetic overflow always returns `REJECT` with `allowsPublication() == false`; it cannot become
`DEGRADE`, `CPU_FALLBACK`, or an approximate draw. Rejected results expose no replacement usage
token, preventing a caller from silently continuing a corrupt ledger. Exact-boundary products,
zero limits, Long.MAX_VALUE multiplication failures, and Long.MAX_VALUE cumulative failures are
negative/positive test cases.

## Visibility and culling contract

Culling has three non-interchangeable prepared decision types:

| Decision type | Retained identity | May cull only |
|---|---|---|
| Instance decision | generation + handle | that instance |
| Primitive decision | instance identity + primitive | that primitive |
| Bone decision | primitive identity + bone | that bone-influence subset |

Each `requireCurrent` method validates exactly the identity fields retained by its own type. Instance
decision/reason values are exposed only through `readCurrent(activeGeneration, activeHandleIdentity)`,
which performs that validation before returning a checked outcome. A primitive or bone decision cannot
be passed where an instance decision is required; animation work also accepts only an instance
decision.

For each scope independently:

| Scope evidence | Decision | Reason |
|---|---|---|
| Dynamic bounds incomplete | DRAW | DYNAMIC_BOUNDS_INSUFFICIENT |
| Visibility UNKNOWN | DRAW | UNKNOWN_INPUT |
| Complete visibility HIDDEN | CULL | KNOWN_HIDDEN |
| Complete visibility VISIBLE | DRAW | VISIBLE_OR_UNCULLED |

No parent/child inference is performed: a hidden bone cannot cull its primitive or instance, and a
hidden primitive cannot cull its instance. A hidden instance does not authorize a primitive/bone
cull without that scope's own complete hidden evidence. Cross-handle, cross-primitive, cross-bone,
and stale-generation reuse reject. This prepares policy decisions only; it does not prove a backend
performs any culling.

For D2b specifically, `ModelRenderSnapshot.visibility()` is already the completed immutable
instance decision. It is the only per-frame visibility truth. `CullingMetadata.cullable()` is not
a dynamic-bounds-completeness flag, so D2b does not re-evaluate it and cannot turn a `CULLED`
snapshot into DRAW. X4's saved full submission primitive must equal that existing visibility. X6
plans may receive later compatible snapshots, so they retain only the CPU-route primitive and honor
each submitted snapshot's own existing `RenderVisibility`.

## LOD contract

Each LOD band has an integer level, a finite non-negative enter threshold, and a finite
non-negative exit threshold no greater than enter. Bands begin at level 0 at distance 0; subsequent
levels and enter thresholds are strictly ordered. The normal distance selection moves to a band at
its enter threshold (`distance >= enter`).

Previous history can provide hysteresis only when generation, model identity, and material identity
all match by exact identity. When moving nearer, the previously selected band is retained through
its exit threshold and lowers only below it. Different material or generation identity receives a
fresh selection; history cannot cross either boundary. NaN, infinity, negative distance, or invalid
matching history returns a rejected result with no reusable history.

## Animation-work contract

The only selection entry accepts an immutable typed instance decision, client facts, the active
generation, and the exact active handle identity. It first calls the instance decision's checked
`readCurrent` path before reading a decision/reason or returning any recommendation; no unvalidated
selection overload exists.

| Mode | Meaning |
|---|---|
| FULL | Advance normal client animation work. Required for invalid distance, UNKNOWN/incomplete instance evidence, or client state that must advance. |
| REDUCED | Use the existing far/offscreen cadence rather than assert that all work can stop. |
| PAUSED | Permitted only for a conclusively hidden instance when the caller explicitly says pausing is safe and no reusable pose is available. |
| REUSE_POSE | Reuse an available bounded cached pose for a conclusively hidden instance. |

This policy changes no synchronized state and cannot affect server authority. A stale generation or
cross-handle decision throws before it can yield `FULL`, `REDUCED`, `PAUSED`, or `REUSE_POSE`.
Hidden visibility is not by itself a promise that skinning may stop.

## Metrics contract

The mutable collector records backend selection and fallback reason, all budget dispositions, LOD
selection/rejection, scope-typed draw/cull decisions, and all animation-work modes. `snapshot()`
returns an immutable count-only value; `reset()` zeroes every counter. Counters use exact increments,
so overflow fails explicitly rather than silently wrapping. There is no timing benchmark, I/O export,
or stable v1 metrics surface.

## Required future integration invariants

A future owner must preserve all of the following:

- CPU fallback continues through the existing public measurement collector and standard `RenderType`.
- For one frozen snapshot, CPU and `GPU_CANDIDATE` paths preserve material, visibility, and generation semantics.
- Optional-route failure is completely resolved before publication; it cannot alter only part of a submission.
- CPU remains usable on Iris, Sodium, and incompatible hardware.

Those invariants are not yet connected or verified in real hardware. Real GPU/backend work, GPU
resource lifetime, shared mesh/vertex ownership, batching/static instancing, X4/X6/shared-owner and
renderer integration, reload/retirement integration, actual `RenderType` fallback, hardware/Iris/
Sodium compatibility, performance benchmarks, client/server integration, and manual visual
acceptance remain **WAITING**.
