package com.liy.blendlib.examples.ecosystem.client;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
import com.liy.blendlib.examples.ecosystem.blockentity.ExampleAnimatedAltarBlockEntity;
import com.liy.blendlib.examples.ecosystem.blockentity.ExampleBlockEntities;
import com.liy.blendlib.examples.ecosystem.entity.ExampleAnimatedEntity;
import com.liy.blendlib.examples.ecosystem.entity.ExampleEntities;
import com.liy.blendlib.examples.ecosystem.item.ExampleItems;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderer;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderers;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.resources.Identifier;

/**
 * Client-only use of the public 26.1.2 BlendLib renderer facades.
 *
 * <p>Renderer extraction is owned by the public facade and submit consumes an immutable snapshot.
 * This class does not issue OpenGL calls, resolve a provider, read a GLB, or inspect a socket
 * during submit.</p>
 */
public final class EcosystemExampleClientEntrypoint implements ClientModInitializer {
    private static final System.Logger LOGGER = System.getLogger("BlendLib Ecosystem Example Client");

    @Override
    public void onInitializeClient() {
        BlendLibItemModelBindings.register(new BlendLibItemBinding(
                ExampleItems.MODEL_MARKER_ID,
                ExampleKeys.ITEM_MODEL,
                Identifier.withDefaultNamespace("item/stick")));
        BlendEntityRenderers.register(
                ExampleEntities.ANIMATED_ACTOR,
                context -> BlendEntityRenderer.<ExampleAnimatedEntity>builder(context, ExampleKeys.ACTOR_MODEL)
                        .skinnedAnimation((entity, request) -> ExampleKeys.IDLE)
                        .skinnedSocketMarker(ExampleKeys.TIP_SOCKET)
                        .onSkinnedVisualEvent((entity, eventKey) -> {
                            if (ExampleKeys.ATTACK_WHOOSH.equals(eventKey)) {
                                LOGGER.log(System.Logger.Level.DEBUG,
                                        "Received presentation-only event {0} for entity {1}",
                                        eventKey,
                                        entity.getId());
                            }
                        })
                        .shadowRadius(0.45F)
                        .build());
        BlendBlockEntityRenderers.register(
                ExampleBlockEntities.ANIMATED_ALTAR,
                context -> BlendBlockEntityRenderer.<ExampleAnimatedAltarBlockEntity>builder(context, ExampleKeys.ACTOR_MODEL)
                        .syncedSkinnedAnimation(ExampleKeys.IDLE)
                        .build());
    }
}
