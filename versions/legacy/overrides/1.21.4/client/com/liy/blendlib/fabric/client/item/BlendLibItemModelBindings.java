package com.liy.blendlib.fabric.client.item;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.resources.ResourceLocation;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.SpecialModelWrapper;

/** Marker registration for the older baked-model API, preserving explicit base-model transforms. */
public final class BlendLibItemModelBindings {
    private static final ConcurrentMap<ResourceLocation, Registration> BINDINGS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PLUGIN_REGISTERED = new AtomicBoolean();
    private BlendLibItemModelBindings() { }
    public static void register(BlendLibItemBinding binding) {
        registerInternal(binding, null, null);
    }

    /**
     * Registers an extraction-only selector atomically with the marker binding, before bake.
     * A plain re-registration preserves the configured selector. A selector may be added once
     * before bake; a different non-null selector or binding is rejected. There is no replacement
     * or unregistration API. Configure callbacks during client startup before model loading;
     * this legacy hook reads the current registration when it creates an item render capture.
     * Do not capture stack objects in a registration-lifetime callback.
     */
    public static void register(BlendLibItemBinding binding, BlendLibItemMaterialAppearance appearance) {
        registerInternal(binding, Objects.requireNonNull(appearance, "appearance"), null);
    }

    /** Registers a named-skin selector without overloading the appearance callback API. */
    public static void registerWithSkin(BlendLibItemBinding binding, BlendLibItemSkinSelector selector) {
        registerInternal(binding, null, Objects.requireNonNull(selector, "selector"));
    }

    /**
     * Registers skin and appearance atomically. Existing callbacks are preserved by registrations
     * that omit them; adding a callback is allowed, replacing a different callback is rejected.
     * Register during client startup before model loading. Later callback additions are not a
     * supported configuration flow; this legacy hook may observe them without another bake.
     */
    public static void registerWithSkin(BlendLibItemBinding binding,
            BlendLibItemMaterialAppearance appearance, BlendLibItemSkinSelector selector) {
        registerInternal(binding, Objects.requireNonNull(appearance, "appearance"),
                Objects.requireNonNull(selector, "selector"));
    }

    private static void registerInternal(BlendLibItemBinding binding,
            BlendLibItemMaterialAppearance appearance, BlendLibItemSkinSelector skin) {
        BlendLibItemBinding checked = Objects.requireNonNull(binding, "binding");
        BINDINGS.compute(checked.itemId(), (id, previous) -> {
            if (previous != null && (!previous.binding().equals(checked)
                    || (previous.appearance() != null && appearance != null && previous.appearance() != appearance)
                    || (previous.skin() != null && skin != null && previous.skin() != skin))) {
                throw new IllegalStateException("Marker item already has a different BlendLib binding or selector: " + id);
            }
            return new Registration(checked,
                    previous != null && previous.appearance() != null ? previous.appearance() : appearance,
                    previous != null && previous.skin() != null ? previous.skin() : skin);
        });
    }

    record Registration(BlendLibItemBinding binding, BlendLibItemMaterialAppearance appearance,
            BlendLibItemSkinSelector skin) { }

    /** Read-only binding lookup, primarily useful for diagnostics and deterministic adapter tests. */
    public static Optional<BlendLibItemBinding> find(ResourceLocation itemId) {
        return Optional.ofNullable(BINDINGS.get(Objects.requireNonNull(itemId, "itemId"))).map(Registration::binding);
    }

    /** Immutable diagnostic snapshot of registered marker bindings. */
    public static Map<ResourceLocation, BlendLibItemBinding> bindings() {
        var result = new java.util.HashMap<ResourceLocation, BlendLibItemBinding>();
        BINDINGS.forEach((id, registration) -> result.put(id, registration.binding()));
        return Map.copyOf(result);
    }

    static Registration registration(ResourceLocation itemId) {
        return BINDINGS.get(Objects.requireNonNull(itemId, "itemId"));
    }

    public static void installModelLoadingPlugin() {
        if (PLUGIN_REGISTERED.compareAndSet(false, true)) {
            ModelLoadingPlugin.register(context -> context.addModels(
                    BINDINGS.values().stream().map(Registration::binding).map(BlendLibItemBinding::baseModelId).distinct().toList()));
        }
    }

    /** 1.21.4 has native special models but no Fabric item-before-bake event. */
    public static ItemModel registeredMarkerModel(ResourceLocation itemId, ModelManager models) {
        return registeredMarkerModel(itemId, ((FabricBakedModelManager) models)::getModel);
    }

    static ItemModel registeredMarkerModel(ResourceLocation itemId, Function<ResourceLocation, BakedModel> bakedModels) {
        Registration registration = registration(itemId);
        if (registration == null) return null;
        BlendLibItemBinding binding = registration.binding();
        // Resolve the current generation's base each time; never retain a baked model across reload.
        return new SpecialModelWrapper<>(new BlendLibItemSpecialRenderer(binding, registration.appearance(), registration.skin()),
                Objects.requireNonNull(bakedModels.apply(binding.baseModelId()), "baked base model"));
    }
}
