package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.api.BlendModelKey;
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
import net.minecraft.world.item.Item;

/**
 * Public Fabric model-loading dispatcher for exact 26.2 item bindings.
 *
 * <p>The public plugin is installed once during the client runtime start. Individual bindings are
 * owned by one dispatcher instance and removed at exact adapter close; the permanent Fabric model
 * hook then observes no binding and returns the ordinary incoming item model.</p>
 */
final class Fabric262ItemModelBindings {
    private static final ConcurrentMap<Identifier, ItemBinding> BINDINGS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PLUGIN_INSTALLED = new AtomicBoolean();

    private Fabric262ItemModelBindings() {
    }

    static void installPluginOnce() {
        if (PLUGIN_INSTALLED.compareAndSet(false, true)) {
            ModelLoadingPlugin.register(context -> context.modifyItemModelBeforeBake().register(
                    Fabric262ItemModelBindings::replaceRegisteredItem));
        }
    }

    static void register(Item item, Fabric262RegisteredHost registered, Fabric262HostRenderDispatcher owner) {
        Objects.requireNonNull(item, "item");
        Fabric262RegisteredHost checkedRegistered = Objects.requireNonNull(registered, "registered");
        Fabric262HostRenderDispatcher checkedOwner = Objects.requireNonNull(owner, "owner");
        Fabric262HostBinding binding = checkedRegistered.binding();
        Identifier itemId = Identifier.fromNamespaceAndPath(
                binding.target().registryId().namespace(), binding.target().registryId().path());
        ItemBinding candidate = new ItemBinding(
                itemId,
                checkedRegistered,
                Identifier.fromNamespaceAndPath(itemId.getNamespace(), "item/" + itemId.getPath()),
                checkedOwner);
        ItemBinding previous = BINDINGS.putIfAbsent(itemId, candidate);
        if (previous != null) {
            throw new IllegalStateException("A Fabric 26.2 item renderer binding already exists for " + itemId);
        }
    }

    static void unregisterOwner(Fabric262HostRenderDispatcher owner) {
        Fabric262HostRenderDispatcher checkedOwner = Objects.requireNonNull(owner, "owner");
        BINDINGS.entrySet().removeIf(entry -> entry.getValue().owner() == checkedOwner);
    }

    static Optional<BlendModelKey> modelFor(Identifier itemId) {
        ItemBinding binding = BINDINGS.get(Objects.requireNonNull(itemId, "itemId"));
        return binding == null ? Optional.empty() : Optional.of(binding.registered().binding().modelKey());
    }

    private static ItemModel.Unbaked replaceRegisteredItem(
            ItemModel.Unbaked incoming,
            ModelModifier.BeforeBakeItem.Context context) {
        ItemModel.Unbaked checkedIncoming = Objects.requireNonNull(incoming, "incoming");
        ItemBinding binding = BINDINGS.get(Objects.requireNonNull(context, "context").itemId());
        if (binding == null || binding.owner().isClosed()) {
            return checkedIncoming;
        }
        return new SpecialModelWrapper.Unbaked(
                binding.baseModelId(), Optional.empty(), new Fabric262ItemSpecialRenderer.Unbaked(binding));
    }

    static record ItemBinding(
            Identifier itemId,
            Fabric262RegisteredHost registered,
            Identifier baseModelId,
            Fabric262HostRenderDispatcher owner) {
        ItemBinding {
            itemId = Objects.requireNonNull(itemId, "itemId");
            registered = Objects.requireNonNull(registered, "registered");
            baseModelId = Objects.requireNonNull(baseModelId, "baseModelId");
            owner = Objects.requireNonNull(owner, "owner");
        }
    }
}
