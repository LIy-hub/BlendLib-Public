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

    /** 1.21.4 has native special models but no Fabric item-before-bake event. */
    public static ItemModel registeredMarkerModel(ResourceLocation itemId, ModelManager models) {
        return registeredMarkerModel(itemId, ((FabricBakedModelManager) models)::getModel);
    }

    static ItemModel registeredMarkerModel(ResourceLocation itemId, Function<ResourceLocation, BakedModel> bakedModels) {
        BlendLibItemBinding binding = BINDINGS.get(Objects.requireNonNull(itemId));
        if (binding == null) return null;
        // Resolve the current generation's base each time; never retain a baked model across reload.
        return new SpecialModelWrapper<>(new BlendLibItemSpecialRenderer(binding),
                Objects.requireNonNull(bakedModels.apply(binding.baseModelId()), "baked base model"));
    }
}
