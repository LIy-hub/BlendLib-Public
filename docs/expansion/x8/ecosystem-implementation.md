# X8 ecosystem implementation handoff

This document records the original source handoff and its subsequent scoped shared integration.
The source commits intentionally did not modify root settings/build files, platform trees, or
central ledgers; the dedicated integration candidate now does so only for X8 local assembly,
metadata, navigation, and truthful PENDING/WAITING recording. It does not modify tests or claim
production behavior.

## Delivered and isolated surfaces

| Delivered surface | X8-owned location | What it does now |
| --- | --- | --- |
| Offline converter | tools/model-converter | Parses supported offline JSON, emits strict rigid-v1 GLB/descriptor/external PNG manifest |
| Fabric 26.2 adapter | platforms/fabric-26.2 | Separate lifecycle-owned candidate artifact with real public entity/block-entity/item dispatcher source, typed registration-source animation extraction, frozen pose submit, and Loom-nested API/core closure; no build/package/start/reload/visual claim |
| NeoForge 26.2 bridge | platforms/neoforge-26.2 | Pure bridge plus non-loadable metadata template; official binding WAITING |
| Pure-Java datagen | blendlib-datagen | Root-included API-dependent descriptor/sidecar generator candidate with frozen strict-v1/output limits |
| Full consumer example | examples/blendlib-ecosystem-example | Independent Maven consumer with common/client source, strict assets, and an owner-only public X4 GUI-host factory |
| Minimal consumer | examples/independent-consumer | Independent Maven-only stable API/facade consumer |
| SPI packages | examples/third-party-providers | Isolated API-only Experimental provider/adapter examples |
| Pack template | templates/model-pack | Copyable strict resource layout and provenance record |
| Documentation | docs/expansion/x8 | Tutorial, migration, compatibility, license, and boundary guidance |
| Boundary ADRs | docs/expansion/adr/ADR-X8004 and ADR-X8005 | Offline and third-party extension decisions |

None of these additions changes the current strict runtime loader or asserts a platform integration
result.

## Shared integration now completed by the designated owner

| Shared surface | Candidate wiring now present | Remaining owner decision/evidence |
| --- | --- | --- |
| Root settings.gradle.kts | `blendlib-datagen` is included as a pure-Java root subproject; examples remain detached | Build/Javadoc evidence and any publication decision remain pending |
| Root local aggregate/inventory | `x8AssembleLocalCandidate` declares separate artifact/package, inventory, and SHA tasks | It is unrun, local-only, license-pending, and has no remote publication task |
| Local Maven workflow | Detached builds have an explicit same-checkout `build/local-maven` prerequisite and task dependency | Coordinate resolution/compilation is WAITING; aggregate ZIP is not a standalone Maven closure |
| Fabric client entrypoint | 26.2 candidate retains one exact lifecycle receipt, registers public client stopping, and enters retryable closing until retire/drain/exact-uninstall succeeds; failed snapshot release remains in its exact dispatcher owner inventory | Public entity/block-entity/item dispatcher source evaluates the original typed registration token only, advances per-live-host controllers during extraction, and submits frozen palettes; API compilation, loader/reload and visual behavior remain WAITING; normal consumers still cannot own global control |
| X3 event integration | Connect presentation event source/dispatch/listener only after lifecycle/reload ownership is accepted | Visual events must remain presentation-only |
| X6 variant/material integration | Validate sidecar schema/input, freeze plan and standard routes before submit | Existing X6 production integration is not an X8-owned surface |
| X7 backend integration | Select/version/lease providers off hot paths and retain CPU standard fallback | GPU/backend owner and hardware evidence are outside X8 |
| Datagen integration | Root-included pure Java module emits strict assets/sidecars without runtime authoring readers | Filesystem/build integration evidence remains WAITING; no Fabric datagen hook is claimed |
| Fabric 26.2 / NeoForge | Separate source sets, JAR identity, metadata, local aggregate locations, and root API/core composite consumption are declared; Fabric also declares its Loom nested API/core JAR closure | No cross-loader/version claim; Fabric package evidence and NeoForge official binding remain WAITING |
| Documentation indexes/progress | X8 navigation/ADR/progress candidate record is now linked truthfully | Static closure PASS at reviewed source `148a2c9`; all dynamic evidence remains WAITING |
| License/publish gate | Select non-addon license, source availability, notices, and publication policy | LICENSE-PENDING is authoritative |

## Proposed integration sequence

1. Assign one platform/lifecycle owner for the target Fabric version and confirm no competing global
   PlatformAdapterControl owner exists.
2. Independently inspect X8 examples and third-party provider code for public-API-only imports,
   no raw GL/reflection, no GeckoLib runtime dependency, and no client class on common/server.
3. Decide how a platform bootstrap will construct, install, retain, retire, and uninstall one
   Experimental adapter. Do not install from a normal consumer or server/common initializer.
4. Define resource/reload ownership for strict descriptors, GLB, and PNG. Keep resource,
   mutable instance, and immutable snapshot ownership separate.
5. If accepting X3/X6/X7, specify the exact preparation owner, frozen input schema, fallback
   admission rule, lease lifetime, close/retire ordering, and standard CPU route. Keep discovery,
   parsing, and provider callbacks out of submit/advance/socket.
6. Choose whether the independent examples receive a supported local-Maven packaging workflow.
   Preserve their detached project dependency boundary.
7. Run a separately authorized static review and then version-specific builds/runtimes. Record
   outcomes in the owner-controlled evidence/progress surfaces, distinguishing static, build,
   server, client, reload, visual, hardware, and performance evidence.
8. Complete licensing and third-party notice decisions before copying any X8 material into a
   distributable artifact.

## Required runtime invariants

Any owner that adopts X8 material must preserve these invariants:

- runtime reads only strict versioned GLB/Profile and descriptor/PNG data;
- no .blend, FBX, OBJ, Blockbench, or GeckoLib input enters the runtime path;
- no GeckoLib code/JAR becomes a runtime dependency;
- stable API/core remain pure Java and do not expose platform/private renderer types;
- common/server do not load client code;
- resource input, mutable instance state, and immutable snapshot state stay distinct;
- submit, animation advance, and socket query perform no I/O, JSON/GLB parsing, provider discovery,
  backend construction, or socket lookup;
- network carries semantic state only; visual events do not create gameplay authority;
- required unknown data fails closed;
- no raw GL or private reflection is introduced;
- standard CPU/public routes remain available for supported material/backend behavior;
- every platform/version has a separate JAR and evidence set.

## X8 status at handoff

X8's predecessor candidate independent-review **FAIL** remains historical. Its implementation and
shared local-candidate final-findings repair wiring are present; the same independent reviewer
issued static **PASS (0C/0H/0M/0L)** for source HEAD `148a2c9`. The later documentation record is
not part of that reviewed source identity; see [static-review-closure.md](static-review-closure.md).
No test, build,
compile, converter invocation, resource reload, client/server launch, benchmark, visual exercise,
provider lifecycle execution, or platform compatibility run occurred under this task. All such
evidence is **WAITING**.

## Agent Innovation

X8 records two narrow authoring/developer-experience innovations, neither of which changes the
stable descriptor/profile/API contract or introduces runtime wiring:

| Innovation | Motivation | Rejected alternative | Guardrail |
| --- | --- | --- | --- |
| Standard-library offline JSON-to-strict-GLB converter | Give Blockbench/GeckoLib users a local path into strict v1 without a runtime bridge | Runtime parser, GeckoLib dependency, network service, or automatic reload conversion | Offline-only CLI, safe relative paths, bounded inputs, structured loss/error report, atomic file publication |
| Detached consumer/provider/template ecosystem set | Make public/Experimental boundaries legible without coupling release or platform code | Import impl/internal code, use root project dependencies, or let ordinary consumers seize global adapter ownership | Maven-coordinate-only examples, pure-Java SPI packages, explicit lifecycle owner handoff, all dynamic evidence WAITING |

These additions need independent review and explicit platform/release approval before they can alter
any project-level status.
