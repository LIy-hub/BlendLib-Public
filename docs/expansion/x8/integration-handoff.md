# X8 local integration handoff

Status: **the predecessor candidate's independent static-review FAIL is preserved. The same
independent reviewer ultimately closed H1/H2/H3/H4/H5/H6/M1/M2/M3 with PASS (0C/0H/0M/0L) for
reviewed source HEAD `148a2c9`; this later bookkeeping commit is outside that reviewed source
identity.** The expansion dependency Gate remains **LOCKED / PENDING**. This record does not
promote X1--X7, P0--P8, release readiness, a license, or any build/runtime/visual result.

## Provenance and scope

This candidate started from exact base `8df1b90f9ebca03de6a1b78bb0b184e6529aaa5e`. The supplied
single-parent implementation chain was applied in this order without changing its intent:

| Supplied source commit | Integrated commit | Scope |
| --- | --- | --- |
| `56e20a756d9882502fb5e0a3239c3802549b3717` | `3976f8f` | Fabric 26.2 adapter candidate and platform handoff |
| `8942db703b90876ad4d7a3e16d8cf36ace02d3d5` | `759a329` | Datagen and model-pack template |
| `25fc0cd441d9ab44e736268a562bf3e8e1cc2cf4` | `347ec4e` | Detached examples and third-party SPI samples |
| `79c0b607ab2cd1f480d17631be0581cb15c0465f` | `1e90dd3` | Offline Blockbench/GeckoLib converter, NeoForge bridge, and X8 documentation |

Shared root/configuration wiring is `1f77131` (`build(x8): wire local candidate assembly`). The
platform/datagen/NeoForge repair chain is `35d888a` (`fix(x8): close platform static findings`) then
`e906b0f` (`fix(x8): preserve selected scene palette semantics`), covering
X8-H1/X8-H2/X8-H3/X8-M1/X8-M2. The independently authored converter repair source
`c36cd90fff37a2bb4753618744fb1cfea506fdad` was integrated without conflict as `5504048`
(`fix: harden X8 offline converter`) for X8-H4/X8-H5/X8-M3. Final shared-documentation commits
follow that chain. The independent reviewer accepted exact source HEAD `148a2c9`/tree `f08c58d`;
later closure bookkeeping is intentionally not self-referenced as part of that reviewed source.

The same independent reviewer's intermediate final-finding pass recorded H2/H5/M1/M2/M3 as closed,
retained H1/H3/H4, and added H6 (actual standalone Fabric loader closure). This remediation added
`5bef5b0` (`fix(x8): complete Fabric animation and retry lifecycle`) for H1/H3/H6 and integrates
the converter owner's narrow H4 mirror-type repair
`0488adb8bf91ded9ab1a2531df8bfb556ce83901` as `ae90a39`
(`fix: reject invalid Gecko cube mirror values`). The same reviewer then issued PASS
(0C/0H/0M/0L) for source `148a2c9`; see [independent static-review closure](static-review-closure.md).
That source PASS is not dynamic evidence.

- Branch: `agentloop/x8-platform-ecosystem`
- Worktree: `D:\BlendLib-worktrees\x8-platform-ecosystem`
- Implementing agent: `/root/x8_integration_impl`
- Model: GPT-5 implementation agent

## Shared local-only wiring

`settings.gradle.kts` includes the pure-Java `blendlib-datagen` subproject, which depends on
`:blendlib-api` rather than copying API classes into its JAR. The standalone Fabric and NeoForge
settings use explicit composite substitution for the root API/core artifacts rather than copying
those source trees into either platform JAR. Fabric additionally declares Loom `include` for its
exact API/core artifacts so the future remapped mod JAR has a traceable nested runtime closure.
`x8AssembleLocalCandidate` is an explicit, future-use
local aggregate entrypoint. It is not connected to `buildRelease`, remote publishing, a tag, or a
release action.

The declared aggregate chain keeps every artifact identity separate:

| Candidate component | Local artifact/package rule | State |
| --- | --- | --- |
| API/core/datagen | API/core are root pure-Java build prerequisites; datagen has a separate sources/Javadoc package | Candidate; build/Javadoc WAITING |
| Fabric 26.2 | Standalone remapped JAR with sources/Javadoc and Loom-nested API/core artifacts | Candidate; build/package/start/reload/visual WAITING |
| NeoForge 26.2 | Standalone pure bridge JAR with sources/Javadoc and a non-loadable metadata template | Official loader binding WAITING |
| Ecosystem/independent consumers | Detached 26.1.2 example JARs with sources/Javadoc | Local-Maven resolution/compile/run WAITING |
| Five third-party provider samples | Detached pure-Java JARs with sources/Javadoc | Compile/lifecycle evidence WAITING |
| Converter/model template | Offline source/template ZIPs | Conversion/pack evidence WAITING |

`x8PackageLocalCandidate`, `x8WriteLocalCandidateInventory`,
`x8WriteLocalCandidateSha256`, and `x8VerifyLocalCandidate` declare the future inventory/SHA-256
chain. The aggregate ZIP is workspace-local and depends on the same checkout's
`build/local-maven` for detached consumers/providers. It is neither a standalone Maven closure nor
a redistributable artifact. The pre-Alpha X8 candidate's `LicenseRef-PENDING`
description is historical and is superseded as a current root-license statement:
the existing root `LICENSE` is Apache License 2.0 and its `NOTICE` is
current, while the Blender Add-on remains separately GPL-3.0-or-later in its
limited directory scope. This documentation alignment does not claim that an
unbuilt aggregate's metadata or inventory already conforms; that F6
artifact-alignment work remains pending. The local-only assertion rejects root
publication repositories outside the build directory and the aggregate checks
prevent `.blend`, `.fbx`, or `.obj` from entering packages.

## X8 item matrix

| GOAL item | Candidate implementation location | Current truthful state |
| --- | --- | --- |
| Fabric 26.2 independent adapter/JAR | `platforms/fabric-26.2` | Separate source/JAR declaration plus Loom-nested API/core closure; public entity/block-entity/item dispatcher retains typed source semantics, freezes per-host rigid pose snapshots, and has retryable exact receipt close; all compile/package/loader/visual evidence WAITING |
| NeoForge interface/build bridge/metadata template | `platforms/neoforge-26.2` | Pure bridge/template present with issuer-bound single-use generation provenance; official binding and loadability WAITING |
| Pure-Java datagen | `blendlib-datagen` | Root inclusion and public generator source present; frozen strict-v1/cardinality/output limits are enforced before publication; build/output evidence WAITING |
| Example mod and consumer fixture | `examples/blendlib-ecosystem-example`, `examples/independent-consumer` | Detached source/metadata present; compile/run WAITING |
| Model-pack template/Javadoc | `templates/model-pack`, module Javadoc tasks | Template and future Javadoc package declarations present; generated output WAITING |
| Tutorial and migration guidance | `docs/expansion/x8` | Source guidance present; no execution claim |
| Blockbench/GeckoLib offline converter | `tools/model-converter` | Bounded offline source present; no runtime GeckoLib dependency; execution WAITING |
| AssetProfile/Material/Backend/Host Adapter examples | `examples/third-party-providers` | Public/explicit Experimental source present; lifecycle evidence WAITING |
| Compatibility matrix | `compatibility-matrix.md` | Candidate/WAITING facts recorded |
| License/source inventory | `licenses-and-sources.md` | Inventory present; current root Apache-2.0/`NOTICE` scope is recorded; artifact-metadata alignment and publication evidence remain PENDING |

## Static-review remediation mapping

The following maps historical findings to the independent closure at reviewed source `148a2c9`.
The predecessor FAIL remains historical; all nine findings are closed by the same independent
reviewer, without promoting any source edit to build, loader, runtime, or visual evidence.

| Finding | Candidate implementation evidence | Still WAITING |
| --- | --- | --- |
| X8-H1 | `Fabric262RegisteredHost` privately retains every original typed `HostRegistrationSpec` and evaluates it only as `animationFor(host())`; entity/block extraction creates bounded weak-reference actual-host controllers, item extraction uses a transient `Item.STATELESS` LOOP controller, and `Fabric262PoseSnapshot` freezes the generation-bound palette consumed by submit. Unknown clips/source failures record a bounded named diagnostic and use strict rest pose. **CLOSED by the same independent reviewer.** | Frozen-coordinate compilation, actual loader registration, renderer/animation exercise, reload, and visual result. |
| X8-H2 | `Fabric262PreparedModelHandle` retains each primitive node index and a generation-bound palette built from the selected default-scene roots; each primitive receives its world transform and `unitsPerBlock` conversion. **Closed by the same independent reviewer.** | API compilation plus actual transform/render correctness. |
| X8-H3 | Render/dispatch snapshots mark closed only after exact generation release; dispatcher close retains failed raw leases and completed phase markers, so a later exact runtime close drains only remaining work before item-binding/animation/binding cleanup. **CLOSED by the same independent reviewer.** | Client-stop exercise, exceptions/retry behavior, reload, and live cleanup. |
| X8-H4 | The offline converter classifies every supported Blockbench/GeckoLib object field by exact path; named known-unrepresentable and unknown fields fail closed unless explicit `--allow-lossy` records the omission. The final narrow repair also rejects non-JSON-boolean Gecko cube `mirror` values with `BLX8-INPUT-BOOLEAN`, without lossy coercion. **CLOSED by the same independent reviewer.** | Converter invocation and report/output inspection. |
| X8-H5 | The converter validates rest and animation scale after float32 conversion as positive and uniform, and records source units to descriptor `units_per_block` mapping through `--source-units-per-block`. **Closed by the same independent reviewer.** | Converter invocation and strict output validation. |
| X8-H6 | `platforms/fabric-26.2/build.gradle.kts` keeps independent composite coordinates but declares Loom `include` for the exact API/core artifacts, giving the future remapped Fabric JAR a traceable nested loader closure without source copying or a 26.1.2 runtime. **CLOSED by the same independent reviewer.** | Dependency resolution, remapped JAR inspection, loader startup, and visual result. |
| X8-M1 | `DatagenLimits` and the immutable specs reject frozen cardinality/total/output-size excess; `DatagenGenerator` validates the complete output set before its first publication write. **Closed by the same independent reviewer.** | Datagen compilation and generated-output exercise. |
| X8-M2 | `NeoForge262PreparedGeneration` has private issuer provenance and bridge-only creation; bridge application verifies owner, bounded generation, every asset generation, and single use. **Closed by the same independent reviewer.** | Official NeoForge binding, compilation, loader, and lifecycle evidence. |
| X8-M3 | The converter requires `--force` for an existing regular target, caps external input counts/cumulative bytes, rejects reparse/symlink/directory targets, and rechecks before atomic publication. **Closed by the same independent reviewer.** | Converter invocation, filesystem-race exercise, and report inspection. |

## Static-only implementation record

This integration used static source/path/diff inspection only, including `git status`, lineage/tree
inspection, `git diff --check`, `git show --check`, explicit changed-path inventory, and `rg`
boundary scans. The repair commits `35d888a`, `e906b0f`, `5504048`, `5bef5b0`, and `ae90a39`
received whitespace/static diff checks only. Public Fabric source signatures were read as static
reference for entity, block-entity, item-model, and client-stop registration shapes; no dependency
was upgraded and no candidate was compiled. The scans check that newly changed X8 paths do not add test paths, that
datagen and provider examples do not import Minecraft/Fabric/NeoForge/GeckoLib or
implementation/internal packages, and that X8 source contains no raw OpenGL/private-reflection
pattern.

No tests, Gradle/gradlew build/check/compile, Python converter/help/self-test, client, server,
reload, benchmark, network operation, or dynamic verification ran in this task. Static inspection
is not a build, runtime, visual, or performance result. The independent reviewer subsequently
issued a static **PASS (0C/0H/0M/0L)** for reviewed source HEAD `148a2c9`; the predecessor FAIL
remains historical. This documentation bookkeeping commit is not a newly reviewed code version.

## Remaining WAITING and risk

- The same independent reviewer closed H1/H2/H3/H4/H5/H6/M1/M2/M3 at reviewed source
  `148a2c9`; historical FAIL records are retained in
  `D:\BlendLib-AgentLoop-Task\artifacts\x8-final-static-review.md` and summarized in
  [static-review-closure.md](static-review-closure.md).
- All compilation/build/package/SHA-generation execution, loader binding, resource reload,
  client/server, visual, compatibility, hardware, and performance evidence is WAITING.
- NeoForge remains a deliberately non-loadable bridge until an owner verifies official 26.2
  dependency, metadata, lifecycle, and renderer facts.
- The X4/X6/X7 production acceptance paths are not claimed by the candidate.
- The existing root Apache-2.0/`NOTICE` decision and the separate Add-on GPL
  scope are not pending. Exact third-party dependency/asset obligations,
  required notices, redistribution/release approval, actual artifact-metadata
  alignment, and remote publication authorization remain PENDING; no SHA value
  exists until the declared local chain is separately authorized to run.

## Agent innovation

The integration keeps the candidate reviewable without creating a universal runtime: separate
artifact identities, a local-only inventory/SHA chain, exact-instance adapter release protection,
and bounded offline conversion remain isolated from the strict runtime boundary.
