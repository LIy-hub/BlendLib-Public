package com.liy.blendlib.fabric.client;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.sync.BlendLibClientAnimationSync;
import com.liy.blendlib.fabric.client.animation.sync.ClientAnimationSyncRuntime;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.command.ClientDiagnosticsCommandRegistrar;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import com.liy.blendlib.fabric.client.network.ClientAnimationPayloadReceivers;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.ClientModelReloadListener;
import com.liy.blendlib.fabric.client.render.Minecraft2612StaticRigidRenderBackend;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

/**
 * Client-only entrypoint. It is compiled from Loom's dedicated client source set.
 */
public final class BlendLibClientEntrypoint implements ClientModInitializer {
    private static final System.Logger LOGGER = System.getLogger("BlendLib");
    private static final Identifier MODEL_RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath("blendlib", "model_registry");
    private static final ClientModelRegistry MODEL_REGISTRY = new ClientModelRegistry();
    private static final ClientAnimationLifecycleBridge ANIMATION_LIFECYCLE = new ClientAnimationLifecycleBridge(256);
    private static final SkinnedAnimationRuntime SKINNED_ANIMATION_RUNTIME =
            new SkinnedAnimationRuntime(MODEL_REGISTRY, ANIMATION_LIFECYCLE);
    private static final ClientAnimationSyncRuntime ANIMATION_SYNC = BlendLibClientAnimationSync.runtime();

    @Override
    public void onInitializeClient() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(MODEL_RELOAD_LISTENER_ID, new ClientModelReloadListener(
                        MODEL_REGISTRY, SKINNED_ANIMATION_RUNTIME::onActiveGeneration));
        ClientAnimationPayloadReceivers.register(ANIMATION_SYNC);
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            SKINNED_ANIMATION_RUNTIME.onPlayInit();
            ANIMATION_SYNC.onPlayInit();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SKINNED_ANIMATION_RUNTIME.onWorldDisconnect();
            ANIMATION_SYNC.onDisconnect();
        });
        ClientTickEvents.END_CLIENT_TICK.register(ANIMATION_SYNC::onClientEndTick);
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            SKINNED_ANIMATION_RUNTIME.onEntityUnload(entity.getId());
            ANIMATION_SYNC.onEntityUnload(
                    BlendResourceId.parse(level.dimension().identifier().toString()), entity.getId());
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            BlendResourceId dimension = BlendResourceId.parse(level.dimension().identifier().toString());
            SKINNED_ANIMATION_RUNTIME.onBlockEntityUnload(new BlendInstanceKey.BlockEntity(
                    dimension, blockEntity.getBlockPos().asLong()));
            ANIMATION_SYNC.onBlockEntityUnload(dimension, blockEntity.getBlockPos());
        });
        BlendLibClientServices.initialize(
                MODEL_REGISTRY, new Minecraft2612StaticRigidRenderBackend(), SKINNED_ANIMATION_RUNTIME);
        BlendLibItemModelBindings.installModelLoadingPlugin();
        ClientDiagnosticsCommandRegistrar.register(BlendLibClientServices.commands());
        LOGGER.log(System.Logger.Level.DEBUG, "Initialized BlendLib client adapter skeleton");
    }
}
