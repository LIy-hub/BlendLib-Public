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
    private static final ConcurrentMap<Identifier, BlendLibItemBinding> BINDINGS = new ConcurrentHashMap<>();
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
        BlendLibItemBinding checked = Objects.requireNonNull(binding, "binding");
        BlendLibItemBinding previous = BINDINGS.putIfAbsent(checked.itemId(), checked);
        if (previous != null && !previous.equals(checked)) {
            throw new IllegalStateException("Marker item already has a different BlendLib binding: " + checked.itemId());
        }
    }

    /** Read-only binding lookup, primarily useful for diagnostics and deterministic adapter tests. */
    public static Optional<BlendLibItemBinding> find(Identifier itemId) {
        return Optional.ofNullable(BINDINGS.get(Objects.requireNonNull(itemId, "itemId")));
    }

    /** Immutable diagnostic snapshot of registered marker bindings. */
    public static Map<Identifier, BlendLibItemBinding> bindings() {
        return Map.copyOf(BINDINGS);
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
        BlendLibItemBinding binding = BINDINGS.get(context.itemId());
        if (binding == null) {
            return incoming;
        }
        return new SpecialModelWrapper.Unbaked(
                binding.baseModelId(), Optional.empty(), new BlendLibItemSpecialRenderer.Unbaked(binding));
    }
}
