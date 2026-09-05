# ADR-X8003: Pure-Java datagen contract

Status: **Proposed — X8 implementation candidate**

## Context

BlendLib needs repeatable authoring output for strict descriptors, material intent, animation
states, sockets, variants, and capability declarations. Runtime API/core stays pure and v1
descriptors reject unknown fields. Tooling cannot smuggle experimental platform metadata into the
runtime descriptor contract.

## Decision

Create a root-included pure-Java `blendlib-datagen` artifact with an explicit `:blendlib-api`
project dependency and public `BlendLibDatagen.builder()` / `BlendLibDatagenBuilder`. It emits
frozen v1 descriptor JSON under `blend_models`; variants/capabilities are separate deterministic
sidecars. It also emits an explicit pack skeleton with an owner-supplied pack format and description.

Generation sorts canonical keys, writes UTF-8/LF, normalizes all output paths under one supplied
root, and refuses path escape. Each file is staged then moved with `ATOMIC_MOVE`; lack of atomic
publication fails instead of quietly using a non-atomic replacement.

Immutable authoring objects enforce the frozen strict-v1 ceilings before serialization: 256 material
slots, 512 sockets, 256 animation states, 4,096 events per state, and 16,384 events per descriptor.
The generator also caps request/output cardinality and validates every serialized UTF-8 payload plus
its cumulative byte total before the first atomic write. A request beyond those limits fails with a
named datagen limit diagnostic rather than generating data the core/schema would reject.

## Consequences

- The module imports no Minecraft/Fabric/NeoForge type and reads no `.blend`, FBX, or OBJ.
- Its API dependency is not copied into the datagen JAR; future sources/Javadoc packaging remains
  separate from runtime artifacts.
- It is development/build tooling, not a runtime renderer, resource provider, or provider discovery
  source.
- Capability sidecars preserve explicit required/optional authoring intent but do not replace frozen
  runtime negotiation; unknown required capabilities remain fail-closed.
- Output behavior still needs real filesystem/build integration evidence before any Gate claim.
