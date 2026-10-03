# Named texture skins

Named skins are an opt-in client feature for ordinary entities and marker items. Definitions belong to a model, while each extracted instance selects only a registered skin name. No descriptor/schema or networking change is required.

## Register before the first reload

```java
BlendLibModelSkins.register(modelKey, Map.of(
    BlendResourceId.parse("demo:winter"), Map.of(
        "Body", BlendResourceId.parse("demo:textures/entity/winter.png")),
    BlendResourceId.parse("demo:summer"), Map.of(
        "Body", BlendResourceId.parse("demo:textures/entity/summer.png"))));
```

Call this during client initialization, before the first model reload begins. Registration is deeply copied, immutable and model-scoped. Repeating the entire identical definition set is idempotent, even after startup. Conflicting definitions are rejected. The first reload snapshot freezes registration: late additions fail explicitly instead of silently waiting for a later reload. This first version has no replacement or unregister API.

Names are resource IDs. Slot keys are exact case-sensitive authored descriptor material names, including valid slots that currently have no rendered primitive. Each primitive using a replaced slot receives the texture; all unspecified slots retain authored textures.

## Select during extraction

```java
builder.skin((entity, request) -> Optional.of(BlendResourceId.parse("demo:winter")));
BlendLibItemModelBindings.registerWithSkin(binding,
    stack -> Optional.of(BlendResourceId.parse("demo:summer")));
// When combining with per-slot RGB/visibility:
BlendLibItemModelBindings.registerWithSkin(binding, appearance, skinSelector);
```

Return `Optional.empty()` for authored textures. Selectors run once during extraction; they do not run during submit, lighting updates, baking or culling. Missing-model handles bypass selectors and observers so diagnostic geometry retains its appearance. The optional item `captured` callback receives the final skin + appearance snapshot once.

Plain item registration preserves existing callbacks. Registering an omitted callback later is allowed before bake, but replacing a different callback or binding fails atomically. Already baked renderers retain their captured callback configuration until the next bake. Do not capture stack instances in registration-lifetime callbacks.

Skin selection precedes independent material RGB/visibility appearance. Skin substitution preserves render layer, emissive behavior, double-sided flags and alpha/tint. It does not enable new material routes, custom shaders or alpha overrides. Existing unknown appearance-slot fallback is unchanged.

## Reload and fallback

Reload prepare captures startup definitions once, checks exact slots and final selected texture-resource existence, then carries immutable validation results into apply. Apply builds a bounded primitive-ordered catalog inside the existing concrete render handle before generation publication. X6 and ordinary skins share the texture-only material transformation; ordinary models do not use X6 providers, leases or synthetic-part limits.

An invalid named definition is disabled atomically for that generation. Its base model and other valid named skins continue working. Unknown selections also use the full authored material set. `snapshot.selectedSkin()` retains the requested name even when fallback occurs; `snapshot.skinDiagnostic()` explains fallback. Empty selections and missing-model snapshots have neither a selected name nor a skin diagnostic. Reload validation failures also appear as `SKIN_001` warnings in model-generation diagnostics.

Each snapshot captures its selected immutable catalog list with exact handle identity. Reloads rebuild primitive ordinals; older snapshots retain their earlier materials and geometry. All snapshot copies, including item lighting copies, preserve skin and appearance. This is not a promise of historical texture-byte isolation: texture loading and ownership remain Minecraft's existing texture manager. Existence checks do not prove successful PNG decoding.

## Bounds

- 128 registered models
- 32 skin names per model
- 64 slot replacements per skin
- 65,536 prepared primitive-material entries per model catalog

Registration bounds reject invalid configuration. Invalid direct preparation definitions fall back diagnostically. If the valid catalog would exceed its entry budget, its named skins use authored fallback; the model itself remains available. These bounds do not impose a new primitive limit on ordinary models. The reference arrays alone are approximately 512 KiB at the catalog ceiling before material objects.

## Runnable examples

Launch the separate runnable consumer with `-Dblendlib.examples.namedSkins=true`, then run `/function blendlib_runnable_examples:named_skins` as a player. Two actor names and two wand stack names, `Ember` and `Frost`, select opposite combinations of the two authored 8×8 opaque textures. `Ember Orange` and `Frost Blue` additionally exercise RGB/visibility composition. The original unprefixed appearance names keep their old behavior.

`verifyRunnableExamples` checks packaged definitions, slots, assets, materials, reload ordering, fallback and composition headlessly. The public client consumer fixture compiles without X6/provider imports. Native graphics/world acceptance remains deferred and is not claimed.
