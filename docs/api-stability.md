# API Stability Rules

BlendLib separates public semantic contracts from adapter-specific and internal implementation details.

| Layer | Examples | Compatibility commitment |
|---|---|---|
| Pure semantic API | BlendResourceId, model/animation keys, diagnostics | Compatible within a BlendLib major version |
| Asset schema | Descriptor v1 and GLB profiles | Compatible across the 26.1.2 and 26.2 adapters |
| Fabric adapter API | Entity, block-entity, item renderer builders | Compatible only within the same Minecraft target adapter |
| Controlled Experimental SPI | `com.liy.blendlib.spi.experimental` capability protocol | Separately versioned; current host contract is 1.1.0, and no stable consumer ABI promise is made |
| Internal SPI / implementation | Parser, backend handle, pose cache | No consumer compatibility promise |

## Public API rules

- Public API must not expose mutable core arrays, GLB JSON structures, Minecraft internal GL objects, or impl packages.
- Constructing public keys performs no I/O.
- Pure API and core stay free of Minecraft/Fabric types.
- Server animation APIs accept semantic animation keys and synchronization parameters, never model objects or render data.
- Current-version Fabric conveniences may convert to and from Minecraft identifiers only in the adapter layer.

## Change rules

- Additive, compatible pure API changes may occur within the v1 major line when documented and tested.
- Incompatible public API, schema, profile, version, or acceptance changes require an ADR proposal and approved source-of-truth update before implementation.
- Internal implementation may change behind the public contract, subject to every applicable gate.
- A controlled Experimental protocol migration must document its current host version, compatible request range, historical-data handling, and callback/error policy. It must not change stable facade signatures or stable diagnostic codes merely by updating an Experimental annotation.
