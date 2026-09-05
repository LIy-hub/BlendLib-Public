# X8 ecosystem, examples, and developer experience

**Status: the predecessor candidate's independent static-review FAIL is preserved; the same
independent reviewer issued a PASS (0C/0H/0M/0L) for reviewed source HEAD
`148a2c935a7cc7398107107c0317728f03a2b82f`.** This later documentation bookkeeping is not part
of that reviewed source identity. Build, conversion, reload, client, server, visual, backend, and platform evidence are
**WAITING**. X8 does not override the expansion
dependency order recorded in docs/expansion-progress.md. In particular, its still-LOCKED/PENDING
Gate is not unlocked by this candidate, and X2, X3, X4, X6, or X7 production integration is not
accepted merely because source, candidate packaging, examples, or documentation exist.

X8 turns the frozen strict-v1 boundary into material a downstream developer can inspect and adopt:

| Surface | Delivered location | Runtime claim |
| --- | --- | --- |
| Offline Blockbench/GeckoLib converter | tools/model-converter | Offline authoring only; no GeckoLib runtime dependency |
| Fabric 26.2 adapter | platforms/fabric-26.2 | Separate candidate JAR with Loom-nested API/core closure plus public entity/block-entity/item dispatch, per-host frozen rigid animation pose source, and retryable lifecycle source; compile/package/start/reload/visual evidence WAITING |
| NeoForge 26.2 bridge | platforms/neoforge-26.2 | Separate pure bridge and non-loadable metadata template; official binding WAITING |
| Pure-Java datagen | blendlib-datagen | Root-included candidate module with frozen strict-v1/output limits; no runtime platform dependency |
| Independent Fabric example mod | examples/blendlib-ecosystem-example | Source and metadata implemented; dynamic use WAITING |
| Minimal local-Maven consumer | examples/independent-consumer | Source and metadata implemented; static compile/run WAITING |
| Provider and adapter examples | examples/third-party-providers | Experimental SPI source implemented; host acceptance WAITING |
| Copyable resource-pack model template | templates/model-pack | Strict asset layout present; target pack compatibility WAITING |
| Tutorials and migration material | this directory | Documentation implementation |

## Non-negotiable boundary

BlendLib runtime input remains only a strict versioned descriptor plus strict GLB/Profile and
external PNG resources. It does not read .blend, FBX, OBJ, Blockbench project JSON, GeckoLib
geometry JSON, GeckoLib animation JSON, or an authoring application at runtime.

The converter calls these formats offline input dialects only. It imports no GeckoLib code or JAR,
creates no network connection, and is never wired into Gradle, a mod entrypoint, resource reload,
submit, animation advance, socket query, or provider discovery. Conversion omissions either reject
the run or appear in the structured report under explicit lossy mode; they are never silent.

Public/common code remains server-safe. Client submission consumes immutable prepared snapshots:
no GLB/JSON parsing, provider discovery, resource lookup, network I/O, raw GL, reflection, or
socket lookup occurs in a submit/advance/socket hot path. Visual event examples are
presentation-only; gameplay remains authoritative outside the visual system.

## Reading order

1. [Developer tutorial](developer-tutorial.md) for the supported consumer workflow.
2. [Converter guide](converter-guide.md) before importing Blockbench or GeckoLib projects.
3. [Migration guide](migration-guide.md) for construct-by-construct mapping and loss boundaries.
4. [Third-party provider guide](third-party-provider-guide.md) before implementing Experimental SPI.
5. [Compatibility matrix](compatibility-matrix.md) for exact status, version, and evidence labels.
6. [Licenses and sources](licenses-and-sources.md) before redistributing source, examples, or assets.
7. [Platform implementation](platform-implementation.md) for target-specific lifecycle and bridge facts.
8. [Integration handoff](integration-handoff.md) for local packaging, inventory, review, and Gate facts.
9. [Independent static-review closure](static-review-closure.md) for the immutable reviewed source
   identity, historical FAIL chronology, and finding closure map.

## Outcome labels

X8 uses the following vocabulary consistently:

| Label | Meaning |
| --- | --- |
| IMPLEMENTED | A source, asset, or document is in this branch. It is not dynamic proof. |
| STATIC-REVIEWED | An independent static reviewer has accepted the stated source scope. X8 source HEAD `148a2c9` has this evidence; its predecessor FAIL remains historical, and no dynamic or Gate claim follows. |
| WAITING | Evidence needs a build, runtime, platform owner, visual/GPU exercise, or independent review not performed here. |
| OUT OF SCOPE | The current X8 owner cannot change that platform or shared integration surface. |

This integration deliberately performed no tests, Gradle builds, compilation, converter execution,
resource reload, game launch, benchmark, or network exercise. The static-only checks and future
local candidate entrypoint are recorded in [integration handoff](integration-handoff.md); do not
reinterpret either as evidence for a build or any dynamic gate.
