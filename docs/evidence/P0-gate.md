# P0 Gate Evidence

Gate: PASS

Local phase commit: chore(p0): freeze BlendLib v1 contract

## Scope

P0 created the independent BlendLib repository and unborn branch Liy/blendlib-v1. The phase contains only contract, documentation, schema, test-asset, and evidence files. It contains no Gradle project, source module, runtime artifact, server operation, client operation, remote operation, license choice, or publication.

## Design provenance

The approved source-package copies match the following SHA-256 values:

| Copy | SHA-256 |
|---|---|
| docs/README.md | CCEBB6D47803B2653F9D1FC95D5DC2527F15D14806F23A40616EBED57724E966 |
| docs/architecture.md | 3B0E16BC1A20F8874AF59076F82001F607A26ED9B2A4B5E0FEC03D1BC1416D5F |
| docs/design-v1.md | E30960F97F6073A8C445748B64ECE7CB59A18B680FC6A78230EA036BBC108E81 |
| docs/implementation-plan.md | B09E90D335503F4658F11619142F1AF555F78B1BEDA46D59384A790A6FBE5CDA |

## Contract verification

- Standard JSON parsing passed for schemas/blendlib-model-v1.schema.json and test-assets/descriptor-example-v1.json.
- Draft 2020-12 schema compilation and example validation passed.
- Rigid and skinned profile positive cases validated.
- Unsupported format_version, unknown top-level fields, and unknown profiles were rejected by the schema.
- The schema freezes the permitted top-level descriptor fields, format_version 1, the rigid_v1 and skinned_v1 profiles, and rejection of unknown top-level properties.
- docs/glb-profile-v1.md freezes the strict archive. docs/error-codes-v1.md freezes diagnostics, hard limits, path restrictions, and the initial stable codes.
- ADR-001 through ADR-008 are Accepted. The RC target and unresolved legal/release decisions are explicit in docs/contract-baseline.md.

## Independent review

Reviewer: p0_reviewer

Result: PASS

The reviewer independently verified source-copy hashes, schema behavior, profile and version constraints, ADR completeness, API stability layers, hard limits, URI restrictions, error codes, absence of release/legal overreach, and the P0-only file scope.

## Repository hygiene

- git diff --check passed.
- No trailing whitespace was found in P0 text/JSON files.
- git diff --cached --quiet passed before phase staging.
- .gitattributes freezes repository text checkout to LF so the copied-source SHA-256 values remain deterministic on this Windows checkout.
- No Gradle, Java/Kotlin source, JAR, or P1 implementation files exist.

## Deferred evidence

P0 does not claim build, dedicated-server, client visual, resource reload, performance, or release validation. Those gates remain assigned to later phases.
