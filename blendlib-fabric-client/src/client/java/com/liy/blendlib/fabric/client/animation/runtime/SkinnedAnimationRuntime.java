package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.ClientAnimationRuntimeMetrics;
import com.liy.blendlib.core.animation.runtime.AnimationAdvance;
import com.liy.blendlib.core.animation.runtime.AnimationCorrection;
import com.liy.blendlib.core.animation.runtime.AnimationCorrectionResult;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.ClientAnimationInstance;
import com.liy.blendlib.fabric.client.animation.ClientAnimationInstanceRegistry;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.ClientAnimationPoseSnapshot;
import com.liy.blendlib.fabric.client.animation.PoseCacheKey;
import com.liy.blendlib.fabric.client.animation.PoseCacheMetrics;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionBridge;
import com.liy.blendlib.fabric.client.animation.extract.ClientSkinnedExtractionFrame;
import com.liy.blendlib.fabric.client.perf.ClientRenderMeasurementCollector;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-owner extraction adapter for P5 animated instance state.
 *
 * <p>It joins an already-published immutable model generation to the lifecycle-owned instance
 * registry. It accepts the two strict v1 animated profiles ({@code rigid_v1} and
 * {@code skinned_v1}) through that one registry, so one typed instance cannot acquire a second
 * controller merely because its bound model profile changes. The adapter owns only per-instance
 * clocks and generation-scoped controller/sampler preparations; it has no platform,
 * resource-reading, parser, or rendering invocation role.</p>
 */
public final class SkinnedAnimationRuntime {
    private static final double TICKS_PER_SECOND = 20.0d;
    private static final double SYNCHRONIZED_SNAP_THRESHOLD_SECONDS = 1.0d / TICKS_PER_SECOND;
    private static final long NO_OBSERVED_GENERATION = -1L;

    private final ClientModelRegistry modelRegistry;
    private final ClientAnimationLifecycleBridge lifecycle;
    private final Map<ModelGenerationKey, PreparedAnimationAsset> preparedAssets = new HashMap<>();
    private final Map<BlendInstanceKey, InstanceClock> clocks = new HashMap<>();

    private long observedGeneration = NO_OBSERVED_GENERATION;

    /**
     * Creates a single-extraction-owner runtime over the active model registry and client lifecycle.
     */
    public SkinnedAnimationRuntime(ClientModelRegistry modelRegistry, ClientAnimationLifecycleBridge lifecycle) {
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Starts a new client play epoch and clears all clocks and prepared generation bindings. */
    public void onPlayInit() {
        lifecycle.onPlayInit();
        clearRuntimeState();
    }

    /** Retires all instance state that belongs to the disconnected client play epoch. */
    public void onWorldDisconnect() {
        lifecycle.onWorldDisconnect();
        clearRuntimeState();
    }

    /**
     * Retires one active-session entity clock together with the lifecycle-owned controller state.
     *
     * @return count returned by the lifecycle registry removal
     */
    public int onEntityUnload(int entityId) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        int removed = lifecycle.onEntityUnload(entityId);
        Iterator<BlendInstanceKey> keys = clocks.keySet().iterator();
        while (keys.hasNext()) {
            BlendInstanceKey key = keys.next();
            if (key instanceof BlendInstanceKey.Entity entity && entity.entityId() == entityId) {
                if (lifecycle.registry().remove(key)) {
                    removed++;
                }
                keys.remove();
            }
        }
        return removed;
    }

    /**
     * Resolves the current play-epoch key for a high-level entity adapter.
     *
     * <p>Generic callers should instead pass their own full {@link BlendInstanceKey} to
     * {@link SkinnedAnimationRuntimeInput}. This convenience exists only because the vanilla
     * entity adapter owns the active client lifecycle.</p>
     */
    public BlendInstanceKey.Entity entityKey(int entityId) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        return lifecycle.entityKey(entityId);
    }

    /**
     * Resolves an entity key only while a client play epoch remains active.
     *
     * <p>High-level extraction adapters use this teardown-safe form so a late render callback
     * returns a missing snapshot instead of binding state to an entity id after disconnect.</p>
     */
    public Optional<BlendInstanceKey.Entity> activeEntityKey(int entityId) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must be non-negative");
        }
        return lifecycle.activeEntityKey(entityId);
    }

    /**
     * Retires one block-entity lifecycle key and any matching internal timing state.
     *
     * @return count returned by the lifecycle registry removal
     */
    public int onBlockEntityUnload(BlendInstanceKey.BlockEntity key) {
        BlendInstanceKey.BlockEntity checkedKey = Objects.requireNonNull(key, "key");
        int removed = lifecycle.onBlockEntityUnload(checkedKey);
        clocks.remove(checkedKey);
        return removed;
    }

    /**
     * Retains only the supplied active model generation in all controller, pose, and preparation state.
     */
    public void onActiveGeneration(long activeGeneration) {
        if (activeGeneration < 0L) {
            throw new IllegalArgumentException("activeGeneration must be non-negative");
        }
        lifecycle.registry().retireOtherGenerations(activeGeneration);
        preparedAssets.keySet().removeIf(key -> key.generation() != activeGeneration);
        clocks.entrySet().removeIf(entry -> entry.getValue().generation != activeGeneration);
        observedGeneration = activeGeneration;
    }

    /**
     * Advances and samples one entity-bound strict-v1 animated controller, then captures its
     * immutable frame.
     *
     * <p>An absent, missing, non-animated, or mismatched active handle produces no result. A declared
     * animation state that is not present in an otherwise loaded model remains a caller-visible
     * controller error rather than a fallback to a different model profile.</p>
     */
    public Optional<SkinnedAnimationRuntimeResult> extract(SkinnedAnimationRuntimeInput input) {
        return extractInternal(input, null);
    }

    /**
     * Advances and samples one strict animated instance, then applies a validated procedural
     * rotation modifier before either rigid or skinned palette construction.
     */
    public Optional<SkinnedAnimationRuntimeResult> extract(
            SkinnedAnimationRuntimeInput input, ClientAnimationPoseModifier poseModifier) {
        return extractInternal(input, Objects.requireNonNull(poseModifier, "poseModifier"));
    }

    private Optional<SkinnedAnimationRuntimeResult> extractInternal(
            SkinnedAnimationRuntimeInput input, ClientAnimationPoseModifier poseModifier) {
        long preparationStartedNanos = ClientRenderMeasurementCollector.startAnimationPreparation();
        try {
            SkinnedAnimationRuntimeInput checkedInput = Objects.requireNonNull(input, "input");
            ModelRegistryGeneration currentGeneration = modelRegistry.current();
            long generation = currentGeneration.generationId();
            if (generation != observedGeneration) {
                onActiveGeneration(generation);
            }

            Optional<ModelHandle> discovered = currentGeneration.find(checkedInput.modelKey());
            if (discovered.isEmpty() || !(discovered.get() instanceof LoadedModelHandle loaded)) {
                return Optional.empty();
            }
            if (loaded.generationId() != generation
                    || !loaded.key().equals(checkedInput.modelKey())
                    || loaded.asset().animationDefinition() == null
                    || !supportsAnimatedHandle(loaded, checkedInput.modelKey(), generation)) {
                return Optional.empty();
            }

            PreparedAnimationAsset prepared = preparedAsset(loaded);
            BlendInstanceKey instanceKey = checkedInput.instanceKey();
            ClientAnimationInstanceRegistry instances = lifecycle.registry();
            ClientAnimationInstance instance = instances.bind(instanceKey, checkedInput.modelKey(), generation, prepared.definition());
            InstanceClock clock = clockFor(
                    instanceKey, checkedInput.modelKey(), generation, checkedInput.clientGameTimeInTicks());
            AnimationAdvance advance = advance(instance, clock, checkedInput);
            PoseCacheKey poseKey = new PoseCacheKey(
                    instanceKey,
                    checkedInput.modelKey(),
                    generation,
                    instance.controller().currentState(),
                    clock.sampleRevision);
            ClientAnimationPoseSnapshot basePose = instances.preparePoseSnapshot(poseKey, prepared.sampler());
            ClientAnimationPoseSnapshot effectivePose = basePose;
            if (poseModifier != null) {
                ClientAnimationPoseContext poseContext = new ClientAnimationPoseContext(
                        instanceKey,
                        checkedInput.modelKey(),
                        generation,
                        advance.state(),
                        advance.timeSeconds(),
                        checkedInput.clientGameTimeInTicks(),
                        prepared.rig());
                effectivePose = instances.applyPoseModifier(basePose, poseContext, poseModifier);
            }
            ClientSkinnedExtractionFrame frame = ClientSkinnedExtractionBridge.extract(
                    instances, loaded, effectivePose, checkedInput.extractionRequest());
            return Optional.of(new SkinnedAnimationRuntimeResult(instanceKey, frame, advance));
        } finally {
            ClientRenderMeasurementCollector.finishAnimationPreparation(preparationStartedNanos);
        }
    }

    /**
     * Returns immutable counts for an explicit diagnostics or benchmark capture.
     *
     * <p>No controller, pose, model asset, or cache entry is exposed; callers receive only the
     * configured bound and current aggregate observations.</p>
     */
    public ClientAnimationRuntimeMetrics measurementSnapshot() {
        PoseCacheMetrics cache = lifecycle.registry().poseCacheMetrics();
        return new ClientAnimationRuntimeMetrics(
                true,
                cache.size(),
                cache.capacity(),
                cache.hits(),
                cache.misses(),
                cache.evictions(),
                lifecycle.registry().size(),
                preparedAssets.size());
    }

    int preparedAssetCount() {
        return preparedAssets.size();
    }

    int trackedClockCount() {
        return clocks.size();
    }

    private PreparedAnimationAsset preparedAsset(LoadedModelHandle loaded) {
        ModelGenerationKey key = new ModelGenerationKey(loaded.key(), loaded.generationId());
        return preparedAssets.computeIfAbsent(key, ignored -> {
            ModelAsset asset = loaded.asset();
            return new PreparedAnimationAsset(
                    AnimationControllerDefinition.fromModelAsset(asset),
                    PoseSampler.fromModelAsset(asset),
                    ClientAnimationRigView.fromNodes(asset.nodes()));
        });
    }

    /**
     * Confirms that one already-loaded strict-v1 animated asset remains paired with the exact
     * render handle prepared for its model key and generation. The profile decision stays on the
     * extraction side; rendering still receives only a frozen snapshot.
     */
    private static boolean supportsAnimatedHandle(LoadedModelHandle loaded, BlendModelKey modelKey, long generation) {
        return switch (loaded.asset().profile()) {
            case SKINNED_V1 -> loaded.renderHandle() instanceof SkinnedRenderHandle skinnedHandle
                    && skinnedHandle.modelKey().equals(modelKey)
                    && skinnedHandle.generation() == generation;
            case RIGID_V1 -> loaded.renderHandle() instanceof StaticRigidRenderHandle rigidHandle
                    && rigidHandle.modelKey().equals(modelKey)
                    && rigidHandle.generation() == generation;
        };
    }

    private InstanceClock clockFor(
            BlendInstanceKey key, BlendModelKey modelKey, long generation, double sampleTick) {
        InstanceClock current = clocks.get(key);
        if (current != null && current.matches(modelKey, generation)) {
            return current;
        }
        InstanceClock replacement = new InstanceClock(modelKey, generation, sampleTick);
        clocks.put(key, replacement);
        return replacement;
    }

    private static AnimationAdvance advance(
            ClientAnimationInstance instance,
            InstanceClock clock,
            SkinnedAnimationRuntimeInput input) {
        return input.syncedAnimation()
                .map(state -> advanceSynchronized(instance, clock, input, state))
                .orElseGet(() -> advanceFallback(instance, clock, input));
    }

    private static AnimationAdvance advanceSynchronized(
            ClientAnimationInstance instance,
            InstanceClock clock,
            SkinnedAnimationRuntimeInput input,
            SyncedAnimationState state) {
        double sampleTick = input.clientGameTimeInTicks();
        AnimationCorrectionResult correction = instance.controller().applyTimelineCorrection(new AnimationCorrection(
                state.animationKey(),
                synchronizedControllerTimeSeconds(state, sampleTick),
                state.sequence(),
                SYNCHRONIZED_SNAP_THRESHOLD_SECONDS));
        if (correction != AnimationCorrectionResult.STALE_DROPPED) {
            clock.acceptSynchronizedState(state.sequence(), state.speed());
            clock.resetAt(sampleTick, input.updateBucket());
            clock.incrementSampleRevision();
            return instance.advance(0.0d);
        }
        if (!clock.hasActiveSynchronizedState()) {
            return advanceFallback(instance, clock, input);
        }
        return advanceAt(instance, clock, sampleTick, input.updateBucket(), clock.synchronizedSpeed());
    }

    private static AnimationAdvance advanceFallback(
            ClientAnimationInstance instance,
            InstanceClock clock,
            SkinnedAnimationRuntimeInput input) {
        clock.deactivateSynchronizedState();
        double sampleTick = input.clientGameTimeInTicks();
        boolean stateChanged = !instance.controller().currentState().equals(input.fallbackAnimation());
        if (stateChanged) {
            instance.controller().trigger(input.fallbackAnimation());
            clock.resetAt(sampleTick, input.updateBucket());
            clock.incrementSampleRevision();
            return instance.advance(0.0d);
        }
        return advanceAt(instance, clock, sampleTick, input.updateBucket(), 1.0d);
    }

    private static AnimationAdvance advanceAt(
            ClientAnimationInstance instance,
            InstanceClock clock,
            double sampleTick,
            AnimationUpdateBucket bucket,
            double timeScale) {
        if (!clock.initialized) {
            clock.initializeAt(sampleTick, bucket);
            return instance.advance(0.0d);
        }
        if (!clock.dueForAdvance(sampleTick, bucket)) {
            return new AnimationAdvance(
                    instance.controller().currentState(), instance.controller().currentTimeSeconds(), List.of());
        }

        double deltaSeconds = (sampleTick - clock.lastAdvancedGameTick) / TICKS_PER_SECOND * timeScale;
        AnimationAdvance advance = instance.advance(deltaSeconds);
        clock.recordAdvanceAt(sampleTick, bucket);
        clock.incrementSampleRevision();
        return advance;
    }

    /**
     * Converts real elapsed synchronized time to controller time. The controller then applies
     * each current descriptor state's local-clip speed while resolving that timeline.
     */
    private static double synchronizedControllerTimeSeconds(
            SyncedAnimationState state, double clientGameTimeInTicks) {
        double elapsedTicks = Math.max(0.0d, clientGameTimeInTicks - state.startGameTick());
        return elapsedTicks / TICKS_PER_SECOND * state.speed();
    }

    private void clearRuntimeState() {
        preparedAssets.clear();
        clocks.clear();
        observedGeneration = NO_OBSERVED_GENERATION;
    }

    private record ModelGenerationKey(BlendModelKey modelKey, long generation) {
        private ModelGenerationKey {
            modelKey = Objects.requireNonNull(modelKey, "modelKey");
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
        }
    }

    private record PreparedAnimationAsset(
            AnimationControllerDefinition definition,
            PoseSampler sampler,
            ClientAnimationRigView rig) {
        private PreparedAnimationAsset {
            definition = Objects.requireNonNull(definition, "definition");
            sampler = Objects.requireNonNull(sampler, "sampler");
            rig = Objects.requireNonNull(rig, "rig");
        }
    }

    private static final class InstanceClock {
        private final BlendModelKey modelKey;
        private final long generation;
        private double lastAdvancedGameTick;
        private double lastCadenceTick = Double.NaN;
        private long sampleRevision;
        private long synchronizedSequence = -1L;
        private float synchronizedSpeed = 1.0F;
        private boolean synchronizedStateActive;
        private boolean initialized;

        private InstanceClock(BlendModelKey modelKey, long generation, double sampleTick) {
            this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
            this.generation = generation;
            this.lastAdvancedGameTick = sampleTick;
        }

        private boolean matches(BlendModelKey expectedModelKey, long expectedGeneration) {
            return generation == expectedGeneration && modelKey.equals(expectedModelKey);
        }

        private void initializeAt(double sampleTick, AnimationUpdateBucket bucket) {
            initialized = true;
            lastAdvancedGameTick = sampleTick;
            rememberCadenceTick(sampleTick, bucket);
        }

        private void resetAt(double sampleTick, AnimationUpdateBucket bucket) {
            initialized = true;
            lastAdvancedGameTick = sampleTick;
            rememberCadenceTick(sampleTick, bucket);
        }

        private boolean dueForAdvance(double sampleTick, AnimationUpdateBucket bucket) {
            if (sampleTick <= lastAdvancedGameTick) {
                return false;
            }
            if (bucket == AnimationUpdateBucket.VISIBLE_NEAR) {
                return true;
            }
            double cadenceTick = Math.floor(sampleTick);
            if (cadenceTick % bucket.cadenceTicks() != 0.0d) {
                return false;
            }
            return Double.compare(lastCadenceTick, cadenceTick) != 0;
        }

        private void recordAdvanceAt(double sampleTick, AnimationUpdateBucket bucket) {
            lastAdvancedGameTick = sampleTick;
            rememberCadenceTick(sampleTick, bucket);
        }

        private void rememberCadenceTick(double sampleTick, AnimationUpdateBucket bucket) {
            if (bucket != AnimationUpdateBucket.VISIBLE_NEAR) {
                lastCadenceTick = Math.floor(sampleTick);
            }
        }

        private void incrementSampleRevision() {
            sampleRevision = Math.incrementExact(sampleRevision);
        }

        private void acceptSynchronizedState(long sequence, float speed) {
            synchronizedSequence = sequence;
            synchronizedSpeed = speed;
            synchronizedStateActive = true;
        }

        private boolean hasActiveSynchronizedState() {
            return synchronizedStateActive && synchronizedSequence >= 0L;
        }

        private double synchronizedSpeed() {
            return synchronizedSpeed;
        }

        private void deactivateSynchronizedState() {
            synchronizedStateActive = false;
        }
    }
}
