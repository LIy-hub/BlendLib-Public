package com.liy.blendlib.showcase.client;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderer;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderers;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import com.liy.blendlib.showcase.BlendLibShowcaseEntrypoint;
import com.liy.blendlib.showcase.blockentity.ShowcaseAnimatedAltarBlockEntity;
import com.liy.blendlib.showcase.blockentity.ShowcaseBlockEntities;
import com.liy.blendlib.showcase.blockentity.ShowcaseBlockEntityAnimations;
import com.liy.blendlib.showcase.client.blockentity.ShowcaseAnimatedAltarClientBinding;
import com.liy.blendlib.showcase.entity.ShowcaseAnimatedActorEntity;
import com.liy.blendlib.showcase.entity.ShowcaseEntities;
import com.liy.blendlib.showcase.entity.ShowcaseP5FallbackActorEntity;
import com.liy.blendlib.showcase.entity.ShowcaseRigidEntity;
import com.liy.blendlib.showcase.entity.P7BenchmarkRigidEntity;
import com.liy.blendlib.showcase.entity.P7BenchmarkSkinnedEntity;
import com.liy.blendlib.showcase.item.ShowcaseItems;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;

/** Client-only registration of the Showcase static-rigid and P5 animated-actor consumers. */
public final class BlendLibShowcaseClientEntrypoint implements ClientModInitializer {
    private static final BlendResourceId ATTACK_WHOOSH =
            BlendResourceId.parse("blendlib_showcase:attack_whoosh");
    private static final BlendResourceId TIP_SOCKET_KEY =
            BlendResourceId.parse("blendlib_showcase:tip");
    private static final BlendModelKey RIGID_PULSE_MODEL =
            BlendModelKey.parse("blendlib_showcase:fixtures/rigid_model");
    private static final BlendAnimationKey RIGID_PULSE =
            BlendAnimationKey.parse("blendlib_showcase:rigid_pulse");

    @Override
    public void onInitializeClient() {
        P7BenchmarkCaptureController p7BenchmarkCapture = P7BenchmarkCaptureController.fromSystemProperties();
        ShowcaseSkinnedAnimationBinding.validateCanonicalContract();
        ShowcaseAnimatedAltarClientBinding.validateCanonicalContract();
        BlendLibItemModelBindings.register(new BlendLibItemBinding(
                ShowcaseItems.STATIC_RIGID_ITEM_ID,
                BlendLibShowcaseEntrypoint.STATIC_RIGID_MODEL,
                Identifier.withDefaultNamespace("item/stick")));
        BlendEntityRenderers.register(
                ShowcaseEntities.STATIC_RIGID,
                context -> BlendEntityRenderer.<ShowcaseRigidEntity>builder(
                                context, BlendLibShowcaseEntrypoint.STATIC_RIGID_MODEL)
                        .staticRestPose()
                        .shadowRadius(0.4F)
                        .build());
        BlendEntityRenderers.register(
                ShowcaseEntities.RIGID_PULSE,
                context -> BlendEntityRenderer.<ShowcaseRigidEntity>builder(context, RIGID_PULSE_MODEL)
                        .skinnedAnimation((entity, request) -> RIGID_PULSE)
                        .shadowRadius(0.4F)
                        .build());
        BlendEntityRenderers.register(
                ShowcaseEntities.ANIMATED_ACTOR,
                context -> BlendEntityRenderer.<ShowcaseAnimatedActorEntity>builder(
                                context, ShowcaseSkinnedAnimationBinding.MODEL_KEY)
                        .synchronizedSkinnedAnimation(
                                (entity, request) -> ShowcaseAnimatedActorStateSchedule.stateAt(request.ageInTicks()))
                        .skinnedSocketMarker(TIP_SOCKET_KEY)
                        .onSkinnedVisualEvent((entity, eventKey) -> {
                            if (!RenderSystem.isOnRenderThread()
                                    || entity.isRemoved()
                                    || entity.isInvisible()
                                    || !ATTACK_WHOOSH.equals(eventKey)) {
                                return;
                            }
                            entity.level().addParticle(
                                    ParticleTypes.SWEEP_ATTACK,
                                    entity.getX(),
                                    entity.getY() + 0.9D,
                                    entity.getZ(),
                                    0.0D,
                                    0.0D,
                                    0.0D);
                        })
                        .shadowRadius(0.45F)
                        .build());
        BlendEntityRenderers.register(
                ShowcaseEntities.P5_FALLBACK_ACTOR,
                context -> BlendEntityRenderer.<ShowcaseP5FallbackActorEntity>builder(
                                context, ShowcaseSkinnedAnimationBinding.MODEL_KEY)
                        .skinnedAnimation((entity, request) -> ShowcaseAnimatedActorStateSchedule.stateAt(request.ageInTicks()))
                        .shadowRadius(0.45F)
                        .build());
        BlendEntityRenderers.register(
                ShowcaseEntities.P7_BENCHMARK_RIGID,
                context -> BlendEntityRenderer.<P7BenchmarkRigidEntity>builder(
                                context, P7BenchmarkClientBinding.RIGID_MODEL)
                        .staticRestPose()
                        .shadowRadius(0.0F)
                        .build());
        BlendEntityRenderers.register(
                ShowcaseEntities.P7_BENCHMARK_SKINNED,
                context -> BlendEntityRenderer.<P7BenchmarkSkinnedEntity>builder(
                                context, P7BenchmarkClientBinding.SKINNED_MODEL)
                        .skinnedAnimation((entity, request) -> P7BenchmarkClientBinding.SKINNED_LOOP)
                        .shadowRadius(0.0F)
                        .build());
        BlendBlockEntityRenderers.register(
                ShowcaseBlockEntities.ANIMATED_ALTAR,
                context -> BlendBlockEntityRenderer.<ShowcaseAnimatedAltarBlockEntity>builder(
                                context, ShowcaseAnimatedAltarClientBinding.MODEL_KEY)
                        .syncedSkinnedAnimation(ShowcaseBlockEntityAnimations.IDLE_LOOP)
                        .build());
        ClientTickEvents.END_CLIENT_TICK.register(client -> p7BenchmarkCapture.onClientTick(client));
        LevelRenderEvents.END_MAIN.register(context -> p7BenchmarkCapture.onEndMainFrame());
    }
}
