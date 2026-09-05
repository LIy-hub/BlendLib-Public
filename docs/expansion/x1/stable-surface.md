# X1 Stable Public Facade Surface

Status: the R11 controlled Experimental protocol update awaits a fresh r11 independent review. This document does not change any existing P0--P8 Gate or declare a runtime adapter available; no real platform adapter is supplied and the SPI remains Experimental. Earlier r10 review evidence is historical and is not a review verdict for this R11 candidate.

## Ordinary-consumer path

The stable pure-Java entry point is deliberately small:

```java
BlendLib.entity(myHost)
        .model(MyModels.CLOCKWORK)
        .animation(host -> AnimationRequest.loop(MyAnimations.IDLE))
        .register();
```

The equivalent `BlendLib.blockEntity(host)` and `BlendLib.item(host)` builders have the same typed flow. Builders produce `HostRegistrationSpec<H>`, an immutable semantic specification containing exactly host kind, typed host token, `BlendModelKey`, and typed `AnimationRequestSource`. They do not expose model assets, descriptor data, accessors, poses, bone matrices, platform identifiers, renderer state, payloads, reflection, or mutable registry objects.

`register()` is intentionally fail-closed:

| Condition | Stable result |
|---|---|
| model not assigned | `BlendRegistrationException` / `BLENDLIB-X1-REG-002` |
| animation source not assigned | `BlendRegistrationException` / `BLENDLIB-X1-REG-003` |
| no controlled platform adapter installed | `BlendRegistrationException` / `BLENDLIB-X1-REG-001` |
| same host-kind and equal host token registered twice in one adapter scope | explicit `BLENDLIB-X1-REG-004` rejection |
| adapter failure or inconsistent acknowledgement | explicit `BLENDLIB-X1-REG-005` or `-006` rejection |
| item animation is `ONCE` or `HOLD` without per-ItemStack identity | explicit `BLENDLIB-X1-REG-007` rejection at `build()` and every later evaluation, including the public `animationSource()` accessor |

The facade uses explicit duplicate rejection rather than silently treating registration order as a winner. A platform adapter can never cause a second registration call to replace an earlier stable target.

## Immutable semantic values

| Type | Meaning | Intentionally absent |
|---|---|---|
| `ModelInstance` | typed `BlendInstanceKey`, canonical `BlendModelKey`, non-negative resource generation | resource bytes, controller state, pose, renderer handle |
| `AnimationRequest` | canonical `BlendAnimationKey`, playback mode, bounded `1/64..64` speed, bounded `0..60s` transition | playhead, sampled pose, matrix, payload |
| `SocketQuery` | `ModelInstance` plus canonical socket `BlendResourceId` | transform, matrix, world or renderer object |

`PlaybackMode` is `LOOP`, `ONCE`, or `HOLD` for entity and block-entity registrations. The X1 item surface is deliberately stateless and accepts `LOOP` only; one-shot/hold playback awaits a separately designed persistent per-ItemStack identity. An item specification stores a validated wrapper rather than its caller's raw dynamic source, so both `animationFor()` and `animationSource().requestFor()` enforce `LOOP` on every evaluation. Entity and block-entity accessors retain their original source behavior and may return `ONCE` or `HOLD`. Construction rejects non-finite, zero, negative, over-limit, or too-long semantic values. All X1 key usage reuses existing `BlendResourceId`, `BlendModelKey`, `BlendAnimationKey`, and `BlendInstanceKey`; no duplicate model-key alias exists.

## Stable surface inventory

| Type | Why it is public and stable |
|---|---|
| `BlendLib` | the only ordinary-consumer entry point for entity/block-entity/item builders |
| `EntityRegistrationBuilder<H>`, `BlockEntityRegistrationBuilder<H>`, `ItemRegistrationBuilder<H>` | source-safe fluent entry points for the three required host categories |
| `HostKind`, `HostRegistrationSpec<H>`, `AnimationRequestSource<H>`, `RegistrationReceipt` | typed immutable registration boundary and explicit adapter acknowledgement |
| `BlendApiDiagnosticCode`, `BlendApiDiagnostic`, `BlendRegistrationException`, `BlendDiagnosticSeverity` | consumers can handle fail-closed registration behavior without parsing text |
| `ModelInstance`, `AnimationRequest`, `PlaybackMode`, `SocketQuery` | required immutable semantic data without collapsing resource/instance/snapshot state |
| existing `BlendResourceId`, `BlendModelKey`, `BlendAnimationKey`, `BlendInstanceKey` | retained canonical key identities; X1 makes no behavior/signature change to them |

No stable type exposes or requires the Experimental SPI. The stable facade's internal dispatch is not a public parameter or return type.

## Stable compatibility statement

`com.liy.blendlib.api` is the stable, pure semantic API layer. Additive changes within a BlendLib major version should preserve its source and binary contract. It carries no Minecraft/Fabric import, core/model loader type, asset parser, raw graphics handle, or platform-private renderer object. Existing v1 descriptor/profile and network payload contracts remain unchanged.

The only adapter installation path is deliberately outside ordinary use in `com.liy.blendlib.spi.experimental`. An ordinary mod consumes the stable facade; a platform/provider author explicitly opts into the separately versioned experimental protocol.

Although the facade internally delegates completed specifications to an experimental adapter control, no public `BlendLib` method accepts or returns an Experimental SPI type. The self-contained `StableFacadeOnlySample`, reflection contract test, and an independent Java 25 compiler gate lock that boundary. The compiler gate passes that one source file only the built `blendlib-api` runtime JAR, disables implicit compilation, and supplies an empty source path; it therefore cannot hide a dependency on another consumer-fixture class. A future SPI protocol change must not change stable facade signatures or the documented stable registration failure codes.

The current Experimental host contract is separately versioned as `1.1.0`; `1.0.0` remains historical capability metadata only. This changes neither a stable facade signature nor a stable error code. It formalizes the controlled rule that every callback `Error` escapes by the exact original object after terminal cleanup, while ordinary non-`Error` failures remain diagnostic-contained. Current X6 is additionally constrained to the compatible `[1.1.0, 1.2.0)` protocol line even when an adapter caller supplies a broader request range.

## Compatibility and non-goals

- No v1 descriptor `extensions` field is retained, decoded, or reused by this facade.
- No Profile/schema, wire payload, resource lookup, registry discovery, parsing, I/O, or backend switch occurs in `register`, animation source execution, or socket query semantics.
- No model asset, render snapshot, Minecraft identifier, render pipeline, render type, raw graphics state, private API, or reflection type is public API data.
- `ModelInstance` does not merge resource, per-instance, and snapshot state; downstream extraction/runtime layers retain those jobs.
- This branch provides no Fabric bootstrap or renderer mapping. A missing adapter remains a correct diagnostic rather than a silent registration.
- Adapter-thrown registration failures are normalized to `REG-005`; null or inconsistent receipts are `REG-006`. Normalization retains only a safely obtained, bounded exception class name. It never calls an untrusted exception's `getMessage()` or `toString()`, never trusts adapter-supplied stable codes, severities, or text, and never exposes the external throwable through the public exception cause chain. The public cause-taking constructor remains available for source/binary compatibility with caller-managed local exceptions.
- A failed adapter install rolls back its acquired ownership while the install operation still blocks reentrant/concurrent mutation, then ends the operation before propagating failure. If both the ordinary primary and containable rollback close fail, the final `REG-005` diagnostic retains both safely obtained bounded types without exposing either throwable as its cause. A fatal rollback close outranks an ordinary primary and suppresses that primary; an original fatal primary retains identity and suppresses any containable or fatal rollback close failure.
- Stable diagnostic construction replaces control characters and unpaired surrogates before a message can be formatted or returned, so public `message()`, exception formatting, and cause access remain safe for hostile boundary failures.
- Adapter/provider/capability identities crossing the X1 control plane are canonical ASCII `BlendResourceId` values of at most 256 UTF-16 code units. An overlong or malformed adapter identity is rejected before installation, and an invalid receipt identity is rejected before registration publication.

## Agent Innovation

None. The bounded request values, explicit receipts, and duplicate rejection implement the GOAL/X0 requirements directly rather than expanding the public API beyond them.
