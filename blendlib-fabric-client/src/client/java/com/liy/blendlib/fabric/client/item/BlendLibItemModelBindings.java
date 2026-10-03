package com.liy.blendlib.fabric.client.item;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.resources.Identifier;

/**
 * Public 26.1.2 adapter registration for ordinary vanilla marker items.
 *
 * <p>This intentionally does <em>not</em> register a {@code blendlib:model} JSON type: the fixed
 * Minecraft/Fabric baseline exposes no public registry for such a special-renderer codec. Instead,
 * the installed public {@link ModelLoadingPlugin} replaces only item IDs registered here with a
 * programmatic {@link SpecialModelWrapper.Unbaked} at the public before-bake extension point.</p>
 */
public final class BlendLibItemModelBindings {
    private static final ConcurrentMap<Identifier, Registration> BINDINGS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PLUGIN_REGISTERED = new AtomicBoolean();

    private BlendLibItemModelBindings() {
    }

    /**
     * Registers an explicit marker-item binding before the client item-model bake cycle.
     *
     * <p>Repeating the exact same binding is harmless; a conflicting binding is rejected rather
     * than making model ownership depend on entrypoint ordering.</p>
     */
    public static void register(BlendLibItemBinding binding) {
        registerInternal(binding, null, null);
    }

    /**
     * Registers an extraction-only selector atomically with the marker binding, before bake.
     * A plain re-registration preserves the configured selector. A selector may be added once
     * before bake; a different non-null selector or binding is rejected. There is no replacement
     * or unregistration API. Existing baked renderers keep their captured configuration until
     * the next bake. Do not capture stack objects in a registration-lifetime callback.
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
     * Already baked renderers retain their captured configuration until the next bake.
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

    private record Registration(BlendLibItemBinding binding, BlendLibItemMaterialAppearance appearance,
            BlendLibItemSkinSelector skin) { }

    /** Read-only binding lookup, primarily useful for diagnostics and deterministic adapter tests. */
    public static Optional<BlendLibItemBinding> find(Identifier itemId) {
        return Optional.ofNullable(BINDINGS.get(Objects.requireNonNull(itemId, "itemId"))).map(Registration::binding);
    }

    /** Immutable diagnostic snapshot of registered marker bindings. */
    public static Map<Identifier, BlendLibItemBinding> bindings() {
        var result = new java.util.HashMap<Identifier, BlendLibItemBinding>();
        BINDINGS.forEach((id, registration) -> result.put(id, registration.binding()));
        return Map.copyOf(result);
    }

    /** Installs the public Fabric before-bake hook once from the BlendLib client entrypoint. */
    public static void installModelLoadingPlugin() {
        if (PLUGIN_REGISTERED.compareAndSet(false, true)) {
            ModelLoadingPlugin.register(context -> context.modifyItemModelBeforeBake().register(
                    BlendLibItemModelBindings::replaceRegisteredMarker));
        }
    }

    static ItemModel.Unbaked replaceRegisteredMarker(
            ItemModel.Unbaked incoming, ModelModifier.BeforeBakeItem.Context context) {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(context, "context");
        Registration registration = BINDINGS.get(context.itemId());
        if (registration == null) {
            return incoming;
        }
        var binding = registration.binding();
        return new SpecialModelWrapper.Unbaked(
                binding.baseModelId(), Optional.empty(), new BlendLibItemSpecialRenderer.Unbaked(binding, registration.appearance(), registration.skin()));
    }
}
