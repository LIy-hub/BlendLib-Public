package com.liy.blendlib.fabric.client;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.liy.blendlib.fabric.client.render.X7DeferredSubmissionEndpoint;
import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Minecraft/Fabric 26.1.2 synchronous host for the two future X7 pass-owner scopes.
 *
 * <p>Each Fabric callback obtains the current main target through {@link X7Minecraft2612TargetScope}, then invokes
 * its scope synchronously before returning to Fabric. No {@link LevelRenderContext}, target, pose stack, encoder, or
 * pass is retained by this host. The installed T3a scope remains empty: it creates no command encoder, render pass,
 * allocation, upload, or draw.</p>
 */
final class X7Minecraft2612PassOwnerHost {
    enum Phase {
        AFTER_SOLID_FEATURES,
        BEFORE_TRANSLUCENT_TERRAIN
    }

    @FunctionalInterface
    interface SynchronousScope {
        void run(Phase phase, GpuTextureView colorTarget, GpuTextureView depthTarget);

        default void onTargetOutcome(Phase phase, X7Minecraft2612TargetScope.TargetOutcome outcome) {
            // The ordinary T3a empty scope has no deferred request to classify.
        }
    }

    interface EventRegistrar {
        void registerAfterSolidFeatures(LevelRenderEvents.AfterSolidFeatures callback);

        void registerBeforeTranslucentTerrain(LevelRenderEvents.BeforeTranslucentTerrain callback);
    }

    private static final SynchronousScope NO_PREPARED_DRAW = (phase, colorTarget, depthTarget) -> { };

    private final EventRegistrar events;
    private final X7Minecraft2612TargetScope targetScope;
    private final SynchronousScope scope;
    private boolean installed;

    static X7Minecraft2612PassOwnerHost production() {
        X7Minecraft2612StaticPipeline.registerProductionOnce();
        return new X7Minecraft2612PassOwnerHost(
                new EventRegistrar() {
                    @Override
                    public void registerAfterSolidFeatures(LevelRenderEvents.AfterSolidFeatures callback) {
                        LevelRenderEvents.AFTER_SOLID_FEATURES.register(callback);
                    }

                    @Override
                    public void registerBeforeTranslucentTerrain(
                            LevelRenderEvents.BeforeTranslucentTerrain callback) {
                        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(callback);
                    }
                },
                X7Minecraft2612TargetScope.production(),
                NO_PREPARED_DRAW);
    }

    /**
     * Installs the endpoint only when it can prove source order and its exact T3b registration.
     *
     * <p>The legacy T3a static-rigid probe registration above is intentionally not treated as
     * T3b registration. On the current public Fabric surface this selects {@code NO_PREPARED_DRAW}
     * and X6 keeps its reliable CPU route.</p>
     */
    static X7Minecraft2612PassOwnerHost production(X7DeferredSubmissionEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        X7Minecraft2612StaticPipeline.registerProductionOnce();
        SynchronousScope endpointScope = endpoint.mayInstallHostEndpoint()
                ? new SynchronousScope() {
                    @Override
                    public void run(Phase phase, GpuTextureView colorTarget, GpuTextureView depthTarget) {
                        switch (phase) {
                            case AFTER_SOLID_FEATURES -> endpoint.onAfterSolid(colorTarget, depthTarget);
                            case BEFORE_TRANSLUCENT_TERRAIN -> endpoint.onBeforeTranslucent(colorTarget, depthTarget);
                        }
                    }

                    @Override
                    public void onTargetOutcome(Phase phase, X7Minecraft2612TargetScope.TargetOutcome outcome) {
                        if (outcome == X7Minecraft2612TargetScope.TargetOutcome.DISPATCHED) {
                            return;
                        }
                        // Null views are an explicit unavailable-target signal; no target object is retained.
                        switch (phase) {
                            case AFTER_SOLID_FEATURES -> endpoint.onAfterSolid(null, null);
                            case BEFORE_TRANSLUCENT_TERRAIN -> endpoint.onBeforeTranslucent(null, null);
                        }
                    }
                }
                : NO_PREPARED_DRAW;
        return new X7Minecraft2612PassOwnerHost(
                new EventRegistrar() {
                    @Override
                    public void registerAfterSolidFeatures(LevelRenderEvents.AfterSolidFeatures callback) {
                        LevelRenderEvents.AFTER_SOLID_FEATURES.register(callback);
                    }

                    @Override
                    public void registerBeforeTranslucentTerrain(
                            LevelRenderEvents.BeforeTranslucentTerrain callback) {
                        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(callback);
                    }
                },
                X7Minecraft2612TargetScope.production(),
                endpointScope);
    }

    X7Minecraft2612PassOwnerHost(
            EventRegistrar events,
            X7Minecraft2612TargetScope targetScope,
            SynchronousScope scope) {
        this.events = Objects.requireNonNull(events, "events");
        this.targetScope = Objects.requireNonNull(targetScope, "targetScope");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        events.registerAfterSolidFeatures(context -> invoke(Phase.AFTER_SOLID_FEATURES, context));
        events.registerBeforeTranslucentTerrain(context -> invoke(Phase.BEFORE_TRANSLUCENT_TERRAIN, context));
    }

    private void invoke(Phase phase, LevelRenderContext context) {
        Objects.requireNonNull(context, "context");
        X7Minecraft2612TargetScope.TargetOutcome outcome = targetScope.withCurrentMainTarget(phase, scope::run);
        try {
            scope.onTargetOutcome(phase, outcome);
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            // A target-result observer has no permission to make the Fabric callback fail open.
        }
    }
}
