# Architecture Decision Records

All P0 records below are Accepted reproductions of docs/architecture.md section 11.

| ADR | Status | Decision |
|---|---|---|
| ADR-001 | Accepted | Runtime format is GLB 2.0 |
| ADR-002 | Accepted | Only explicit strict profiles are supported |
| ADR-003 | Accepted | Textures are external Minecraft resources |
| ADR-004 | Accepted | Resources, instances, and snapshots are separate layers |
| ADR-005 | Accepted | Rendering is client-only; animation semantics can synchronize from the server |
| ADR-006 | Accepted | No direct raw OpenGL |
| ADR-007 | Accepted | One Fabric runtime artifact per Minecraft version |
| ADR-008 | Accepted | v1 state machines have no expression DSL |
| ADR-009 | Accepted (Blender Add-on scope only) | GPL-3.0-or-later scoped to blender-addon |
| ADR-010 | Accepted | P3 diagnostic code assignments preserve the P0 code meanings |
| ADR-011 | Accepted | P3 Khronos fixture provenance and strict-GLB derivation |
| ADR-012 | Accepted | P3 non-fatal performance warning code |
| ADR-013 | Accepted | P4 public item special-model registration on 26.1.2 |
| ADR-014 | Accepted | P4 material culling and emissive mapping on 26.1.2 |
| ADR-015 | Accepted | P5 active-scene and animation runtime semantics |
| ADR-016 | Accepted | P7 retains the strict RC material/pipeline boundary; extension/additive expansion is deferred |
| ADR-017 | Accepted | P7 true-in-frustum 100/25 reference-scene layout and capture conditions |
| ADR-018 | Accepted | P5-only no-sync fallback-schedule Showcase fixture under P6 synchronization |
| ADR-019 | Accepted | Correct P4 exact-0.10 single-sided cutout classification to the verified public cull path |
| ADR-020 | Accepted | Correct the P7 fixed camera command syntax without changing its coordinate or performance contract |
| ADR-021 | Accepted | Guard P6 deferred client receiver delivery against a replaced level epoch without changing the v1 wire |
| ADR-022 | Accepted | Add an optional client-only post-sample rotation pose modifier without changing cached poses, bounds, or gameplay authority |
| ADR-023 | Accepted | Add an optional complete animated-entity root quaternion with rotation-invariant culling |
| ADR-X7003 | Proposed / Experimental | X7 shared generation-resource owner publishes complete CPU candidates only until a legal render owner exists |

New decisions are not implicit. A change to an accepted format, API, version, or acceptance criterion requires a written ADR proposal and approved source-of-truth update before related implementation proceeds. An Accepted ADR is the active local override for the specific P0 design-copy text it supersedes; the P0 copies remain preserved as provenance snapshots rather than being silently rewritten.
