# ADR-X8005: Keep third-party ecosystem examples on public stable or explicit Experimental SPI

## Status

Proposed X8 implementation candidate. Example packages and guidance are present, while independent
static review, compilation, provider lifecycle execution, adapter installation, reload, and
platform/visual evidence are **WAITING**. This ADR does not accept any X4/X6/X7 production wiring.

## Context

Third-party consumers need understandable examples for asset profiles, materials, backends, host
renderers, and host adapters. The repository has a stable pure semantic facade and a separately
versioned Experimental capability protocol. Internal implementation classes, platform-private
renderer handles, raw graphics APIs, and reflective access are not supported extension points.

Without an explicit boundary, an example can accidentally teach consumers to depend on impl/internal
code, install a global adapter from ordinary mod code, discover providers in a render hot path, or
treat presentation events as gameplay. Such examples would make future platform versions and
provider ABI evolution unsafe.

## Decision

X8 provides detached independent consumer and provider projects. Ordinary examples use only public
stable API/facade types. Provider and adapter examples use only types explicitly under
com.liy.blendlib.spi.experimental and declare current protocol 1.1.0 plus the bounded current
range [1.1.0, 1.2.0).

Each provider example:

- returns canonical provider/capability IDs and bounded priority offers;
- exposes snapshot-safe metadata rather than platform renderer or resource handles;
- demonstrates required fail-closed or optional explicit semantic-equivalent fallback requests;
- records non-hot prepare/apply/retire/close observations;
- demonstrates that a session/snapshot owner, not the provider, releases an exact ProviderLease;
- contains no Minecraft/Fabric, raw GL, reflection, GeckoLib, or implementation/internal import.

The PlatformAdapter example returns a stable RegistrationReceipt from a validated
HostRegistrationSpec but never calls global installation control. A version-specific platform
bootstrap alone owns construction, install, registration timing, reload, retirement, and uninstall.
For the Fabric 26.2 candidate, that bootstrap releases only its exact adapter through
`PlatformAdapterControl.uninstallIfSame`; this protects a newer same-ID replacement and does not
change ordinary `uninstall()` semantics.

Examples state that X3 visual events are presentation-only, X6 selection/material data is
preparation-time, and X7 backend choice retains a standard CPU fallback. No provider callback,
resource parse, JSON/GLB read, provider discovery, backend creation, or socket lookup may occur in
submit, animation advance, or socket query.

## Consequences

- Stable consumers remain isolated from provider ABI and platform/private renderer state.
- Experimental consumers can see version/fallback/lifecycle/lease boundaries without copying
  internal classes.
- A platform owner has a concrete handoff shape without X8 seizing global lifecycle control.
- X4/X6/X7 production acceptance remains separate from example implementation.
- Each platform/version still needs its own artifact and evidence.
- Detached examples resolve only the same-checkout local Maven prerequisite in the declared local
  candidate chain; that chain is not a standalone dependency closure or publication route.

## Alternatives rejected

- Import implementation/internal classes in third-party examples: breaks compatibility and
  platform isolation.
- Let a normal consumer install PlatformAdapterControl: creates competing global ownership and
  mixes common/client lifecycle.
- Expose raw renderer/GL objects through a provider: prevents safe fallback and adapter evolution.
- Discover/call providers per frame: violates frozen generation and hot-path constraints.
- Treat a fallback ID as automatic behavior: permits semantic drift and hides host responsibility.
