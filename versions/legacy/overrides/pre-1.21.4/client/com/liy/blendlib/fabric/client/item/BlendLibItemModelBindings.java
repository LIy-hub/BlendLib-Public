package com.liy.blendlib.fabric.client.item;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.resources.ResourceLocation;

/** Marker registration for the older baked-model API, preserving explicit base-model transforms. */
public final class BlendLibItemModelBindings {
    private static final ConcurrentMap<ResourceLocation, BlendLibItemBinding> BINDINGS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PLUGIN_REGISTERED = new AtomicBoolean();
    private BlendLibItemModelBindings() { }
    public static void register(BlendLibItemBinding binding) {
        BlendLibItemBinding checked = Objects.requireNonNull(binding, "binding");
        BlendLibItemBinding previous = BINDINGS.putIfAbsent(checked.itemId(), checked);
        if (previous != null && !previous.equals(checked)) {
            throw new IllegalStateException("Marker item already has a different BlendLib binding: " + checked.itemId());
        }
    }
    public static Optional<BlendLibItemBinding> find(ResourceLocation itemId) {
        return Optional.ofNullable(BINDINGS.get(Objects.requireNonNull(itemId, "itemId")));
    }
    public static Map<ResourceLocation, BlendLibItemBinding> bindings() { return Map.copyOf(BINDINGS); }
    public static void installModelLoadingPlugin() {
        if (PLUGIN_REGISTERED.compareAndSet(false, true)) {
            ModelLoadingPlugin.register(context -> context.addModels(
                    BINDINGS.values().stream().map(BlendLibItemBinding::baseModelId).distinct().toList()));
        }
    }
}
