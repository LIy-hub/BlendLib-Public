# Approved Design Package Provenance

The following source documents were copied into this repository during P0 without semantic editing. Their SHA-256 values bind the P0 copy to the approved source package.

| Source path | Repository copy | SHA-256 |
|---|---|---|
| D:\MinecraftFabricServer-26.1.2\docs\blendlib\README.md | docs/README.md | CCEBB6D47803B2653F9D1FC95D5DC2527F15D14806F23A40616EBED57724E966 |
| D:\MinecraftFabricServer-26.1.2\docs\blendlib\architecture.md | docs/architecture.md | 3B0E16BC1A20F8874AF59076F82001F607A26ED9B2A4B5E0FEC03D1BC1416D5F |
| D:\MinecraftFabricServer-26.1.2\docs\blendlib\design-v1.md | docs/design-v1.md | E30960F97F6073A8C445748B64ECE7CB59A18B680FC6A78230EA036BBC108E81 |
| D:\MinecraftFabricServer-26.1.2\docs\blendlib\implementation-plan.md | docs/implementation-plan.md | B09E90D335503F4658F11619142F1AF555F78B1BEDA46D59384A790A6FBE5CDA |

P0 detected no empirical contradiction requiring an architectural change. The approved plan uses 1.0.0 when describing the eventual stable release, while P8 and the current user instruction require the pre-release candidate 1.0.0-rc.1+26.1.2; docs/contract-baseline.md makes that lifecycle distinction explicit without changing the architecture.

The hashes above remain the immutable P0-copy evidence. Later user-authorized
Accepted ADRs are recorded under `docs/adr/` and are the active local override
only for the decisions they name; they are intentionally not backported into
the historical P0 source copies or their recorded hashes.
