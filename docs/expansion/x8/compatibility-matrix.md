# X8 compatibility matrix

This matrix records implementation scope separately from evidence. **IMPLEMENTED** means files were
written in the X8 worktree. It does not mean a compiler, loader, converter, client, server, GPU,
reload, or third-party platform accepted them. The same independent reviewer closed all nine
findings with a static PASS for source HEAD `148a2c9`. No X8 Gate or runtime compatibility claim
follows from that source evidence; historical FAIL records remain preserved in
[static-review-closure.md](static-review-closure.md).

## Evidence vocabulary

| Status | Exact meaning in this matrix |
| --- | --- |
| IMPLEMENTED | Source, documentation, metadata, or resource exists in the X8 owned paths |
| STATIC-REVIEWED | Independent static review recorded for the exact source scope; it is not dynamic or Gate evidence |
| WAITING | Build, runtime, platform, visual, performance, or still-unavailable external evidence was intentionally not performed |
| OUT OF SCOPE | Requires a platform/shared integration owner whose paths X8 cannot change |
| BLOCKED | Existing prerequisite/acceptance state prevents production claim despite available sample code |

## API and format surface

| Surface | Stability | Fabric 26.1.2 | Fabric 26.2 | NeoForge | X8 implementation state | Dynamic/static evidence |
| --- | --- | --- | --- | --- | --- | --- |
| Strict descriptor v1 | Stable | Existing baseline; X8 template/example descriptors IMPLEMENTED | Separate candidate adapter prepares strict descriptor/GLB inputs and public host-dispatch source | Pure bridge prepares strict descriptor/GLB inputs but is non-loadable | IMPLEMENTED candidate sources/assets | Static closure PASS at `148a2c9`; loader and reload WAITING |
| Strict GLB/Profile v1 | Stable | Existing baseline; copied strict GLB/PNG resources IMPLEMENTED | Separate candidate adapter/JAR applies primitive node palette, frozen rigid-animation pose palette, and units conversion on its public collector path; no compatibility claim | Pure bridge only; official binding WAITING | IMPLEMENTED candidate sources/resources | API compilation, static asset validation, and visual result WAITING |
| Public semantic API | Stable | Used by independent consumer and ecosystem example | Candidate adapter consumes independent pure API/core artifacts through composite substitution and Loom nested dependencies, not copied classes | Pure bridge consumes independent pure API/core artifacts through composite substitution, not copied classes | IMPLEMENTED source examples/candidates | Static compile and runtime WAITING |
| Common semantic animation facade | Stable public Fabric facade | Used by example entity/block entity | Version compatibility not claimed | Not applicable without adapter | IMPLEMENTED source example | Server/client sync WAITING |
| Public 26.1.2 client facades | Version-scoped public facade | Used only in client source set | Separate candidate adapter/JAR exists; no 26.1.2 facade reuse claim | Separate bridge/JAR exists but has no loader binding | IMPLEMENTED source example/candidates | Compile/client/visual WAITING |
| Offline converter | Offline tool, not runtime API | Host-independent Python source IMPLEMENTED | Host-independent Python source IMPLEMENTED | Host-independent Python source IMPLEMENTED | IMPLEMENTED | Help/conversion/asset validation WAITING |
| Model-pack template | Strict resource layout | IMPLEMENTED pack/template layout | pack format not claimed | pack format not claimed | IMPLEMENTED | Exact pack-format/reload result WAITING |

## Experimental expansion surface

| Surface | Stability | Fabric 26.1.2 | Fabric 26.2 | NeoForge | X8 implementation state | Evidence/state |
| --- | --- | --- | --- | --- | --- | --- |
| X3 visual presentation events | Public client facade plus unresolved integration path | Example event/socket entry exists | No target integration | No target integration | IMPLEMENTED documentation/sample callback | Production dispatcher/reload wiring WAITING |
| X4 host adapter | Experimental SPI | Pure-Java adapter plus client-only public GUI-host factory exist; global install intentionally absent | Candidate runtime has controlled adapter/receipt plus public entity, block-entity, and item dispatcher source. It retains each typed registration privately, evaluates `animationFor(host())` during extraction, freezes per-host rigid poses, and does not reuse X4 26.1.2 types | Pure semantic host bridge only; official binding WAITING | IMPLEMENTED source examples/candidates | Static closure PASS at `148a2c9`; API compile, lifecycle/reload and visual acceptance WAITING |
| AssetProfileProvider | Experimental SPI 1.1.x | Pure-Java example exists | Protocol/platform compatibility not claimed | Protocol/platform compatibility not claimed | IMPLEMENTED | Registry/session/reload execution WAITING |
| MaterialProvider | Experimental SPI 1.1.x | Standard-route/fallback example exists | Not claimed | Not claimed | IMPLEMENTED | Selection/material behavior WAITING |
| HostRendererProvider | Experimental SPI 1.1.x | HostKind metadata example exists | Not claimed | Not claimed | IMPLEMENTED | Host binding integration WAITING |
| RenderBackendProvider / X7 | Experimental SPI 1.1.x | Public-standard and CPU fallback metadata example exists | Not claimed | Not claimed | IMPLEMENTED | Backend/GPU/hardware/performance WAITING |
| X6 variants/material layers | Experimental candidate | Authoring intent and guidance exist | No target integration | No target integration | IMPLEMENTED docs/template intent only | Existing X6 production integration remains BLOCKED/WAITING |
| Provider lease/lifecycle | Experimental SPI 1.1.x | Examples show ownership and close helpers | Not claimed | Not claimed | IMPLEMENTED source examples | Session/pin/reload execution WAITING |

## Consumer projects

| Project | Dependency boundary | Main source | Client source | Metadata | Status |
| --- | --- | --- | --- | --- | --- |
| examples/blendlib-ecosystem-example | Local Maven coordinate for blendlib-fabric; no project dependency | Entity, block entity, item, semantic animation | Public 26.1.2 renderer/item facades | Fabric metadata and strict resources | IMPLEMENTED; static compile/run/client/visual WAITING |
| examples/independent-consumer | Local Maven coordinate for blendlib-fabric; no project dependency | Immutable stable specification | Minimal semantic client entrypoint | Fabric metadata | IMPLEMENTED; static compile/run WAITING |
| examples/third-party-providers | Local Maven coordinate for blendlib-api; no implementation dependency | Five pure-Java Experimental packages | No platform/client dependency | Per-package build/readme | IMPLEMENTED; static compile/lifecycle acceptance WAITING |
| templates/model-pack | No code dependency | Not applicable | Not applicable | pack.mcmeta, strict descriptor, GLB, PNG, sidecar intent | IMPLEMENTED; target resource-pack compatibility WAITING |

## Version and packaging rules

- Fabric 26.1.2 is the only version named by the independent example Gradle metadata. It is not a
  statement that the example was compiled or launched.
- Fabric 26.2 has a separate candidate module/JAR identity, common/client entrypoints, strict
  resource lifecycle source, and public entity/block-entity/item dispatcher source. It consumes
  separate root API/core artifacts rather than embedding their classes and declares Loom `include`
  dependencies for the future remapped JAR's API/core runtime closure. Its extraction code retains
  typed registration sources, gives actual entity/block hosts isolated controllers, uses transient
  STATELESS item loops, and submits only generation-bound frozen pose data. Its compile, package,
  start, reload, renderer, close, and visual compatibility are all **WAITING**; no
  backward/forward compatibility follows from source presence.
- NeoForge has a separate pure bridge/JAR identity and intentionally non-loadable metadata template.
  Official 26.2 loader binding is **WAITING**; it is not a NeoForge runtime artifact or compatibility
  claim.
- Every platform/version needs a separate JAR and a distinct dependency/loader review. Do not
  package one 26.1.2 adapter as a universal loader artifact.
- `x8AssembleLocalCandidate` is workspace-local: detached examples/providers resolve the existing
  `build/local-maven` prerequisite from the same checkout. Its ZIP is an inventoryable candidate,
  not a standalone Maven repository or redistributable dependency closure.
- The template's pack.mcmeta value is a working template value, not verified against an exact
  26.1.2 distribution. Resource-pack acceptance is **WAITING**.
- The offline converter's output is profile-limited. It does not establish runtime support for
  arbitrary glTF/GLB or an authoring-source format.
- The offline converter classifies source-dialect object fields by exact path: unknown and known
  unrepresentable semantics reject by default, while `--allow-lossy` records an explicit omission.
  It also requires strict positive/uniform float32 rest and animated scale, maps declared source
  units through `--source-units-per-block`, and protects existing/symlink/reparse output targets
  behind `--force` and bounded input budgets. These are source-level safeguards only; converter
  execution and output acceptance remain **WAITING**.
- A required unknown extension/capability/profile must reject. Optional fallback needs explicit,
  validated semantic equivalence; it cannot broaden version support.

## Evidence still required

The independent static code/resource/dependency boundary review is complete for reviewed source
`148a2c9`. Before claiming BUILD PASS or runtime acceptance, an independently owned process must
record at least the remaining relevant evidence:

1. exact standalone Gradle compilation for each independent project;
2. converter help and representative safe/unsafe conversion exercise;
3. strict descriptor/GLB static validation;
4. Fabric 26.1.2 resource reload and server/client lifecycle;
5. entity, block-entity, item, semantic animation, socket, and presentation event exercise;
6. adapter/provider lifecycle, lease, fallback, reload, and close behavior;
7. standard CPU fallback, visual, performance, hardware, and optional compatibility-mod review;
8. Fabric 26.2 build/start/reload/visual evidence and official NeoForge 26.2 binding evidence
   before their rows can leave WAITING.

X8 performed none of these dynamic actions by task constraint.
