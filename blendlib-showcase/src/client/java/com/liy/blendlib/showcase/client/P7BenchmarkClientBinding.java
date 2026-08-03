package com.liy.blendlib.showcase.client;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.showcase.entity.P7BenchmarkRigidEntity;
import com.liy.blendlib.showcase.entity.P7BenchmarkSkinnedEntity;
import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * Client-only semantic bindings and live scene observation for the opt-in P7 benchmark hosts.
 *
 * <p>The server hosts never carry a model key. This class binds their types to the frozen P7
 * descriptor keys only after the client has loaded the isolated benchmark resource pack.</p>
 */
final class P7BenchmarkClientBinding {
    static final BlendModelKey RIGID_MODEL = BlendModelKey.parse("blendlib_showcase:p7/rigid_10k");
    static final BlendModelKey SKINNED_MODEL = BlendModelKey.parse("blendlib_showcase:p7/skinned_20k_64j");
    static final BlendAnimationKey SKINNED_LOOP = BlendAnimationKey.parse("blendlib_showcase:p7_loop");

    private P7BenchmarkClientBinding() {
    }

    /** Reads only client-level host counts and immutable public model-registry views. */
    static SceneObservation observe(ClientLevel level) {
        if (level == null || !BlendLibClientServices.isInitialized()) {
            return SceneObservation.unavailable();
        }
        int rigid = 0;
        int skinned = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof P7BenchmarkRigidEntity) {
                rigid++;
            } else if (entity instanceof P7BenchmarkSkinnedEntity) {
                skinned++;
            }
        }
        ClientRegistryView registry = BlendLibClientServices.models().snapshot();
        boolean rigidLoaded = registry.models().containsKey(RIGID_MODEL)
                && !registry.models().get(RIGID_MODEL).missing();
        boolean skinnedLoaded = registry.models().containsKey(SKINNED_MODEL)
                && !registry.models().get(SKINNED_MODEL).missing();
        return new SceneObservation(true, rigid, skinned, rigidLoaded, skinnedLoaded, registry.generationId());
    }

    /** Immutable readiness observation; render submission counts are checked separately per frame. */
    record SceneObservation(
            boolean clientLevelAvailable,
            int rigidHostCount,
            int skinnedHostCount,
            boolean rigidModelLoaded,
            boolean skinnedModelLoaded,
            long registryGeneration) {
        SceneObservation {
            if (rigidHostCount < 0 || skinnedHostCount < 0 || registryGeneration < 0L) {
                throw new IllegalArgumentException("P7 scene observation is invalid");
            }
        }

        static SceneObservation unavailable() {
            return new SceneObservation(false, 0, 0, false, false, 0L);
        }

        boolean readyForCapture() {
            return clientLevelAvailable
                    && rigidHostCount == P7ReferenceScenario.RIGID_INSTANCE_COUNT
                    && skinnedHostCount == P7ReferenceScenario.SKINNED_INSTANCE_COUNT
                    && rigidModelLoaded
                    && skinnedModelLoaded;
        }

        String readinessDescription() {
            return "level=" + clientLevelAvailable
                    + ", rigidHosts=" + rigidHostCount
                    + ", skinnedHosts=" + skinnedHostCount
                    + ", rigidModelLoaded=" + rigidModelLoaded
                    + ", skinnedModelLoaded=" + skinnedModelLoaded
                    + ", generation=" + registryGeneration;
        }
    }
}
