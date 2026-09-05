# ADR-X8002: NeoForge 26.2 WAITING boundary

Status: **Proposed — explicit external-binding WAITING state**

## Context

An independent NeoForge 26.2 adapter requires authoritative build coordinates, mappings, metadata
range, loader constructor/event lifecycle, client resource reload, and client rendering API facts.
At X8 implementation time, official getting-started material was documented for 26.1; the official
26.2 migration primer provides vanilla migration context but not sufficient verified loader binding
data. Guessing would create an artifact that appears production-ready without a truthful basis.

## Decision

Create `platforms/neoforge-26.2` as a standalone pure-Java bridge/spike:

- `NeoForge262PlatformBridge` provides strict descriptor/GLB preparation, monotonic immutable
  generation publication that rejects stale generations, ready-or-no-render fallback selection,
  diagnostics, and semantic host translation. Its prepared-generation constructor and issuer token
  are private: only the owning bridge can issue/apply a plan, every asset is checked against the
  enclosing generation, an apply is single-use, and foreign/fabricated or `Long.MAX_VALUE`
  generations are rejected before publication.
- Native resource access and host identity are injected through pure interfaces. Bridge code imports
  no Fabric, NeoForge, Minecraft, raw OpenGL, reflection, or GeckoLib type.
- The entrypoint is non-annotated. The project includes `neoforge.mods.toml.template`, not loadable
  `neoforge.mods.toml`, and its manifest labels it as a waiting bridge. The local candidate
  inventory also rejects a packaged loadable `META-INF/neoforge.mods.toml`.
- A future owner replaces the boundary only after official 26.2 dependency, metadata, event,
  mapping, resource, and render details are confirmed and independently validated.

## Consequences

- The bridge remains a source-only candidate with standalone build configuration, without claiming
  a NeoForge runtime or an executed build.
- Required unknown capabilities remain fail-closed; no synthetic platform fallback is offered.
- A release inventory must list the artifact as WAITING, never a NeoForge runtime release.
- Gate and visual status stay unchanged.

## Official sources

Accessed 2026-09-05:

- <https://docs.neoforged.net/docs/gettingstarted/>
- <https://github.com/neoforged/.github/blob/main/primers/26.2/index.md>
