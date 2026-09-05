# Third-party provider and host-adapter guide

All types in com.liy.blendlib.spi.experimental are explicitly Experimental. A downstream provider
must opt in intentionally, constrain itself to the current protocol range [1.1.0, 1.2.0), and
expect source/binary/lifecycle changes to be versioned independently from the stable API.

The examples live below examples/third-party-providers. Each is a separate pure-Java package with
a direct published blendlib-api Maven dependency; none imports impl/internal code, Minecraft,
Fabric, raw GL, reflection, or GeckoLib.

## Choose the narrowest SPI

| Need | SPI | Example package | What it may expose |
| --- | --- | --- | --- |
| Strict profile metadata | AssetProfileProvider | asset-profile-provider | Canonical profile IDs only |
| Standard material capability metadata | MaterialProvider | material-provider | Canonical semantic material IDs only |
| Render route metadata | RenderBackendProvider | render-backend-provider | Canonical backend IDs only |
| Host-kind compatibility metadata | HostRendererProvider | host-renderer-provider | HostKind values only |
| Version-specific stable registration bridge | PlatformAdapter | host-adapter | Immutable HostRegistrationSpec in, RegistrationReceipt out |

Do not use a provider to ship a raw renderer, GL object, model parser, resource manager, controller,
network client, world reference, or internal BlendLib handle. The stable API remains pure and
platform-neutral.

## Offers, versions, and priorities

A provider returns a canonical immutable providerId and a snapshot-safe collection of
CapabilityOffer objects. Each offer consists of:

    new CapabilityOffer(providerId, capabilityId, CapabilityVersion.CURRENT_PROTOCOL, priority)

Use CapabilityVersion.CURRENT_PROTOCOL_RANGE when constructing a request or documenting accepted
input. Do not use an unbounded range that silently selects historical 1.0 behavior. Priority is
bounded by CapabilityOffer.MAX_ABSOLUTE_PRIORITY; a deterministic host resolves offers according to
the experimental capability contract and fails closed on an invalid/tied required decision rather
than relying on insertion order.

Offer metadata is registration/discovery control-plane work. It cannot run in submit, animation
advance, socket query, or an ordinary game tick hot path.

## Required and optional capability requests

A required request has no fallback:

    CapabilityRequest.required(profileId, CapabilityVersion.CURRENT_PROTOCOL_RANGE)

An optional request must state a semantic-equivalent fallback:

    CapabilityRequest.optional(
            backendId,
            CapabilityVersion.CURRENT_PROTOCOL_RANGE,
            new CapabilityFallback(cpuFallbackId, "standard CPU route preserves the same semantics"))

A fallback declaration is data, not an implementation object and not an automatic selection. The
platform owner validates equivalence before publishing a generation. It must not use fallback to
introduce a custom shader, raw GL route, PBR implementation, or a nearby-but-different visual
effect. Unknown required capabilities fail closed.

The material and backend examples use optional standard-route fallbacks. The profile and host
adapter examples use required requests because a missing profile/adapter cannot safely be guessed.

## Lifecycle and leases

A controlled host, not a provider, owns the generation flow:

    metadata registration and discovery
        -> frozen CapabilityPlan
        -> prepare on its designated preparation owner
        -> apply on its designated apply owner
        -> publish immutable generation
        -> issue ProviderLease to snapshot consumers
        -> wait for every lease to drain
        -> retire generation
        -> close provider-global resources after final owner release

Providers may implement prepare, apply, retire, and close. Those callbacks are outside the
hot paths and receive only ProviderLifecycleContext for the exact generation. A provider can be
shared by overlapping generations, so retire must be generation-specific and must not tear down
provider-global state. close is global and should be idempotent from the provider's perspective.

A ProviderLease is owned by the session/snapshot consumer that received it. Close only that exact
lease after its snapshot use ends. Provider examples expose a small releaseSnapshotLease helper to
make the ownership visible; they do not manufacture a lease, close another provider's lease, or
call close early from metadata/lifecycle code.

Do not create an executor, hidden thread, unbounded queue, or reload loop in a provider. The
platform lifecycle owner is responsible for scheduling and for aggregating callback failure
according to the current experimental protocol.

## AssetProfileProvider

ExampleAssetProfileProvider offers blendlib:rigid_v1 at priority 40. It demonstrates:

- immutable provider/profile IDs;
- current protocol offer and exact request range;
- a required request with no unsafe fallback;
- lifecycle observations that contain only generation/stage data;
- idempotent close and exact-lease release helper.

It does not parse GLB, inspect descriptors, construct an asset profile implementation, or load an
asset during a callback.

## MaterialProvider

ExampleMaterialProvider offers a standard opaque material at priority 30. Its optional request
declares an opaque CPU fallback. The provider does not return a RenderType, shader, texture handle,
or material implementation. A host maps only accepted material semantics to approved standard
routes during preparation.

## RenderBackendProvider

ExampleRenderBackendProvider offers a public-standard backend at priority 20 and declares a CPU
standard fallback. It contains no graphics API import. The backend choice must be frozen before
submit; a submit path cannot rediscover providers, instantiate a backend, read a resource, or
switch to raw GL.

Hardware behavior, GPU/driver fallback, performance, and Iris/Sodium-style compatibility are
**WAITING** for X8.

## HostRendererProvider

ExampleHostRendererProvider offers host compatibility at priority 10 for ENTITY, BLOCK_ENTITY, and
ITEM. HostKind is a semantic enum, not a platform renderer type. A platform integration translates
host objects only in its controlled adapter/extraction work, never through a public provider
metadata call.

## PlatformAdapter / host adapter

ExamplePlatformAdapter demonstrates the only bridge from a complete stable HostRegistrationSpec to
a version-specific platform registration. It returns a stable RegistrationReceipt and retains
diagnostic strings only; it does not keep a host object, model bytes, renderer, or private
platform handle.

A real platform bootstrap must do all of the following outside normal consumer code:

1. verify it is the one global adapter owner;
2. construct an explicitly Experimental adapter;
3. install it at the correct version-specific lifecycle phase;
4. retain the exact ownership/install receipt or owner state;
5. accept stable registrations only after installation;
6. retire and uninstall through the same owner during reload/shutdown;
7. avoid installing from common/server entrypoints or static initialization;
8. preserve a known-good generation when a replacement is rejected.

A consumer must not call PlatformAdapterControl global install/uninstall directly. The current X4
production wiring and platform lifecycle proof are **WAITING**; the example is educational code,
not authorization to wire it into a running mod.

## Consumer isolation rules

- Depend only on published public artifacts; never use a root project dependency in an external
  consumer or import implementation/internal packages.
- Put client renderer use in a client source set. Keep common/server free of client types.
- Build immutable stable specifications with BlendLib builders before any controlled adapter work.
- Keep resources, mutable instances, and immutable snapshots separate.
- Keep provider discovery/lifecycle outside submit, advance, and socket query.
- Treat visual events as presentation only and gameplay as server-authoritative.
- Publish one platform/version artifact per target; do not claim 26.2 or NeoForge compatibility
  from the 26.1.2 examples.
- Record independently reviewed static, build, runtime, visual, and compatibility evidence. X8 has
  not produced dynamic evidence, so those outcomes are **WAITING**.
