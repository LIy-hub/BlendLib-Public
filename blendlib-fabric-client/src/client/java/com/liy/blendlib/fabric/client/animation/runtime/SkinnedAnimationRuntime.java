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
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.core.animation.runtime.SynchronizedVisualEventCursor;
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
    private final java.util.LinkedHashMap<LayerPlanKey, ModelAnimationLayers> preparedLayerPlans =
            new java.util.LinkedHashMap<>(16, 0.75F, true);

    private final Map<BlendInstanceKey, BlendSpaceClock> blendSpaceClocks = new HashMap<>();
    private final java.util.LinkedHashMap<BlendSpacePlanKey, AnimationBlendSpaceSyncGroup.Binding> preparedBlendSpaces =
            new java.util.LinkedHashMap<>(16, 0.75F, true);
    private AnimationBlendSpaceSyncGroup capturingBlendSpace;

    private EntityLayerCueCache entityLayerCues = new EntityLayerCueCache();
    private EntityLayerCueCache blendSpaceOriginalCues;

    private final EntityLocomotionRuleCache entityLocomotionRules = new EntityLocomotionRuleCache();
    private final java.util.Set<ModelGenerationKey> invalidLocomotionInputs = new java.util.HashSet<>();
    private boolean capturingLocomotion;
    private long locomotionEpoch;

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
        // Negative ids are never bound as instance keys (BlendInstanceKey.Entity requires a
        // non-negative id), so nothing can be retired for them. They arrive only from synthetic
        // or replay entities (e.g. Flashback's Replay Viewer) and must not abort teardown.
        if (entityId < 0) {
            return 0;
        }
        locomotionEpoch++;
        blendSpaceClocks.keySet().removeIf(key -> key instanceof BlendInstanceKey.Entity entity && entity.entityId() == entityId);
        entityLocomotionRules.retireEntity(entityId);
        entityLayerCues.retireEntity(entityId);
        if (blendSpaceOriginalCues != null) blendSpaceOriginalCues.retireEntity(entityId);
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

    /** Internal extraction bridge used by the public tick-based entity cue adapter. */
    public List<AnimationV2Command> captureEntityLayerCues(Object source, Object owner, int entityId,
            BlendModelKey model, long generation, double clientTicks,
            List<com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue> cues) {
        if (capturingBlendSpace != null) {
            for (var cue : List.copyOf(cues)) {
                if (capturingBlendSpace.memberLayerIds().contains(cue.controllerId()))
                    throw new IllegalArgumentException("blendspace owns cue controller: " + cue.controllerId());
            }
        }
        return activeEntityKey(entityId)
                .map(instance -> entityLayerCues.capture(source, owner, instance, model, generation, clientTicks, cues,
                        cue -> cueStateSpeed(model, generation, cue.animationKey())))
                .orElseGet(List::of);
    }

    /**
     * Captures optional generation-published locomotion rules for the standard entity layers.
     * Valid rules exclusively own one controller. Other commands remain independent. Repeated
     * captures return the same immutable command/sequence, so failed extractions can retry without
     * restarting accepted playback. Incomplete inputs retain the last selection with one warning
     * per model/generation. Missing rules retain the complete original command source.
     */
    public List<AnimationV2Command> captureEntityLocomotionRules(Object source, Object owner, int entityId,
            BlendModelKey model, long generation, double clientTicks, com.liy.blendlib.api.BlendResourceId controller,
            java.util.function.Supplier<com.liy.blendlib.core.animation.rules.LocomotionInputs> inputs,
            java.util.function.Supplier<List<AnimationV2Command>> commands) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(model, "model"); Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(inputs, "inputs"); Objects.requireNonNull(commands, "commands");
        if (!Double.isFinite(clientTicks) || generation < 0) throw new IllegalArgumentException("Invalid rule clock/generation");
        if (capturingBlendSpace != null && capturingBlendSpace.memberLayerIds().contains(controller))
            throw new IllegalArgumentException("blendspace owns locomotion controller: " + controller);
        if (capturingLocomotion) throw new IllegalStateException("Recursive locomotion capture is not supported");
        var active = activeEntityKey(entityId);
        var current = modelRegistry.current();
        if (active.isEmpty() || current.isRetired() || current.generationId() != generation) return List.of();
        if (observedGeneration != generation) onActiveGeneration(generation);
        var rules = current.locomotionRules(model);
        boolean replacement = rules.isPresent()
                && ((!entityLocomotionRules.contains(active.get()) && clocks.containsKey(active.get()))
                || entityLocomotionRules.replaced(active.get(), source, owner, model,
                        generation, controller, rules.get()));
        long epoch = locomotionEpoch;
        capturingLocomotion = true;
        try {
            var supplied = List.copyOf(commands.get());
            if (capturingBlendSpace != null) {
                capturingBlendSpace.validateExternalCommands(supplied);
                int completeCount = supplied.size() + (rules.isPresent() ? 1 : 0);
                if (completeCount > AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE - capturingBlendSpace.memberLayerIds().size())
                    throw new IllegalArgumentException("blendspace capture reserves immediate-frame slots for all members");
            }
            if (epoch != locomotionEpoch || modelRegistry.current() != current
                    || !active.equals(activeEntityKey(entityId))) return List.of();
            if (rules.isEmpty()) return supplied;
            for (var command : supplied) {
                if (command.controllerId().equals(controller))
                    throw new IllegalArgumentException("Locomotion rules exclusively own controller " + controller);
            }
            var captured = Objects.requireNonNull(inputs.get(), "captured locomotion inputs");
            if (epoch != locomotionEpoch || modelRegistry.current() != current
                    || !active.equals(activeEntityKey(entityId))) return List.of();
            if (replacement) {
                // A legitimate new owner must not inherit accepted sequences. Stage this until
                // callbacks/conflict validation pass, so a rejected request preserves old playback.
                entityLocomotionRules.retire(active.get());
                entityLayerCues.retireExceptCaptured(active.get(), owner, supplied);
                clocks.remove(active.get());
                lifecycle.registry().remove(active.get());
            }
            if (!rules.get().inputsComplete(captured)
                    && invalidLocomotionInputs.add(new ModelGenerationKey(model, generation))) {
                System.getLogger(SkinnedAnimationRuntime.class.getName()).log(System.Logger.Level.WARNING,
                        "Incomplete or non-finite locomotion inputs for " + model + "; preserving current playback");
            }
            return entityLocomotionRules.capture(active.get(), source, owner, model, generation, clientTicks,
                    controller, rules.get(), captured, supplied);
        } finally {
            capturingLocomotion = false;
        }
    }

    /**
     * Internal entity extraction fence. Capture before invoking frame callbacks and compare
     * again before extraction; a changed value abandons the whole frame, including empty commands.
     * Synchronizes the active resource generation before returning its current lifecycle revision.
     */
    public long captureExtractionLifecycleRevision() {
        long generation = modelRegistry.current().generationId();
        if (generation != observedGeneration) onActiveGeneration(generation);
        return locomotionEpoch;
    }

    int trackedLocomotionCount() { return entityLocomotionRules.size(); }
    int invalidLocomotionInputDiagnosticCount() { return invalidLocomotionInputs.size(); }

    private double cueStateSpeed(BlendModelKey model, long generation, BlendAnimationKey animation) {
        var handle = modelRegistry.current().find(model);
        if (handle.isPresent() && handle.get() instanceof LoadedModelHandle loaded
                && loaded.generationId() == generation && loaded.asset().animationDefinition() != null) {
            var state = loaded.asset().animationDefinition().states().get(animation.resourceId());
            if (state != null) return state.speed();
        }
        // Missing/undeclared states still follow the existing extraction/command rejection path.
        return 1.0;
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
        // Synthetic/replay entities never bound an instance (their ids are negative and rejected
        // by BlendInstanceKey.Entity), so resolve them to an absent key instead of aborting the
        // render callback that queries this per-frame.
        if (entityId < 0) {
            return Optional.empty();
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
        blendSpaceClocks.remove(checkedKey);
        return removed;
    }

    /**
     * Retains only the supplied active model generation in all controller, pose, and preparation state.
     */
    public void onActiveGeneration(long activeGeneration) {
        if (activeGeneration < 0L) {
            throw new IllegalArgumentException("activeGeneration must be non-negative");
        }
        locomotionEpoch++;
        entityLocomotionRules.retainGeneration(activeGeneration);
        invalidLocomotionInputs.removeIf(key -> key.generation() != activeGeneration);
        entityLayerCues.retainGeneration(activeGeneration);
        if (blendSpaceOriginalCues != null) blendSpaceOriginalCues.retainGeneration(activeGeneration);
        lifecycle.registry().retireOtherGenerations(activeGeneration);
        preparedAssets.keySet().removeIf(key -> key.generation() != activeGeneration);
        preparedLayerPlans.keySet().removeIf(key -> key.generation() != activeGeneration);
        preparedBlendSpaces.keySet().removeIf(key -> key.layers().generation() != activeGeneration);
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
        return extractInternal(input, null, null, null, List.of(), AnimationV2LayerWeights.empty(), null);
    }

    /**
     * Advances and samples one strict animated instance, then applies a validated procedural
     * rotation modifier before either rigid or skinned palette construction.
     */
    public Optional<SkinnedAnimationRuntimeResult> extract(
            SkinnedAnimationRuntimeInput input, ClientAnimationPoseModifier poseModifier) {
        return extractInternal(input, Objects.requireNonNull(poseModifier, "poseModifier"), null, null, List.of(), AnimationV2LayerWeights.empty(), null);
    }

    /** Extracts an explicitly controlled clip-local time, bypassing descriptor looping and next-state logic. */
    public Optional<SkinnedAnimationRuntimeResult> extractClipAt(
            SkinnedAnimationRuntimeInput input, double clipSeconds, ClientAnimationPoseModifier modifier) {
        if (!Double.isFinite(clipSeconds) || clipSeconds < 0.0D) {
            throw new IllegalArgumentException("clipSeconds must be finite and non-negative");
        }
        return extractInternal(input, modifier, clipSeconds, null, List.of(), AnimationV2LayerWeights.empty(), null);
    }

    /** Returns raw clip duration without creating an instance; empty for an unavailable model or undeclared state. */
    public java.util.OptionalDouble animationDuration(BlendModelKey key, BlendAnimationKey animation) {
        Objects.requireNonNull(animation, "animation");
        var handle = modelRegistry.current().find(Objects.requireNonNull(key, "key"));
        if (handle.isEmpty() || !(handle.get() instanceof LoadedModelHandle loaded)
                || loaded.asset().animationDefinition() == null) return java.util.OptionalDouble.empty();
        if (!loaded.asset().animationDefinition().states().containsKey(animation.resourceId())) {
            return java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.of(preparedAsset(loaded).definition().state(animation).clip().durationSeconds());
    }

    /**
     * Immutable descriptor markers for explicit clip-local presentation, without advancing an
     * instance. Empty for unavailable models/states. This does not apply descriptor speed,
     * looping or next-state semantics; the explicit playback owner determines crossings.
     */
    public List<com.liy.blendlib.core.animation.runtime.AnimationVisualEvent> animationVisualEvents(
            BlendModelKey key, BlendAnimationKey animation) {
        Objects.requireNonNull(animation, "animation");
        var handle = modelRegistry.current().find(Objects.requireNonNull(key, "key"));
        if (handle.isEmpty() || !(handle.get() instanceof LoadedModelHandle loaded)
                || loaded.asset().animationDefinition() == null
                || !loaded.asset().animationDefinition().states().containsKey(animation.resourceId())) return List.of();
        return preparedAsset(loaded).definition().state(animation).events();
    }

    /** Retires explicit item/ephemeral owners as soon as their bounded registry evicts them. */
    public void retire(BlendInstanceKey key) {
        Objects.requireNonNull(key, "key");
        locomotionEpoch++;
        blendSpaceClocks.remove(key);
        entityLocomotionRules.retire(key);
        entityLayerCues.retire(key);
        if (blendSpaceOriginalCues != null) blendSpaceOriginalCues.retire(key);
        clocks.remove(key);
        lifecycle.registry().remove(key);
    }

    /** Composes descriptor-backed masked controllers before procedural modifiers and palettes. */
    public Optional<SkinnedAnimationRuntimeResult> extractLayered(SkinnedAnimationRuntimeInput input,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            ClientAnimationPoseModifier modifier) {
        return extractLayered(input, layers, commands, AnimationV2LayerWeights.empty(), modifier);
    }

    /**
     * Captures frame-local clip-layer multipliers without rebuilding plans or restarting playback.
     * Missing entries retain configured weight; zero contribution still advances the controller.
     * Values are not retained for the next extraction. Unknown controller/layer pairs are rejected
     * before instance clocks or cue commands are advanced.
     */
    public Optional<SkinnedAnimationRuntimeResult> extractLayered(SkinnedAnimationRuntimeInput input,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            AnimationV2LayerWeights weights, ClientAnimationPoseModifier modifier) {
        return extractInternal(input, modifier, null, List.copyOf(layers), List.copyOf(commands),
                Objects.requireNonNull(weights, "weights"), null);
    }

    /**
     * Extraction-only, presentation-only events from descriptor layers. The callback runs after
     * successful frame extraction. Events are consumed before callbacks; a throwing consumer
     * propagates to its caller and the batch is never replayed. Null disables delivery, not consumption.
     */
    public Optional<SkinnedAnimationRuntimeResult> extractLayered(SkinnedAnimationRuntimeInput input,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            AnimationV2LayerWeights weights, ClientAnimationPoseModifier modifier,
            java.util.function.Consumer<com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent> listener) {
        return extractInternal(input, modifier, null, List.copyOf(layers), List.copyOf(commands),
                Objects.requireNonNull(weights, "weights"), listener);
    }

    /** Validates the exact current-generation binding before entity callbacks can capture cues. */
    public boolean validateBlendSpaceBinding(BlendModelKey model, long generation,
            List<ModelAnimationLayers.Layer> layers, AnimationBlendSpace1D definition) {
        return validateBlendSpaceBinding(model, generation, layers, Objects.requireNonNull(definition, "definition").syncGroup());
    }
    public boolean validateBlendSpaceBinding(BlendModelKey model, long generation,
            List<ModelAnimationLayers.Layer> layers, AnimationBlendSpaceSyncGroup definition) {
        var current = modelRegistry.current();
        if (current.isRetired() || current.generationId() != generation) return false;
        var handle = current.find(Objects.requireNonNull(model, "model"));
        if (handle.isEmpty() || !(handle.get() instanceof LoadedModelHandle loaded)
                || loaded.asset().animationDefinition() == null) return false;
        preparedBlendSpace(loaded, List.copyOf(layers), Objects.requireNonNull(definition, "definition"));
        return true;
    }

    /** Scopes cue/rule preflight so a conflicting member cannot commit its capture cache. */
    public List<AnimationV2Command> captureBlendSpaceCommands(AnimationBlendSpace1D definition,
            java.util.function.Supplier<List<AnimationV2Command>> commands) {
        return captureBlendSpaceCommands(Objects.requireNonNull(definition, "definition").syncGroup(), commands);
    }
    public List<AnimationV2Command> captureBlendSpaceCommands(AnimationBlendSpaceSyncGroup definition,
            java.util.function.Supplier<List<AnimationV2Command>> commands) {
        Objects.requireNonNull(definition, "definition"); Objects.requireNonNull(commands, "commands");
        if (capturingBlendSpace != null) throw new IllegalStateException("Recursive blendspace capture is not supported");
        capturingBlendSpace = definition;
        long epoch = locomotionEpoch;
        var generation = modelRegistry.current();
        blendSpaceOriginalCues = entityLayerCues;
        entityLayerCues = entityLayerCues.copy();
        boolean accepted = false;
        try {
            var captured = List.copyOf(commands.get());
            definition.validateExternalCommands(captured);
            if (captured.size() > AnimationV2Limits.MAX_INGRESS_DRAIN_PER_ADVANCE - definition.memberLayerIds().size())
                throw new IllegalArgumentException("blendspace capture reserves immediate-frame slots for all members");
            accepted = epoch == locomotionEpoch && generation == modelRegistry.current();
            return accepted ? captured : List.of();
        } finally {
            entityLayerCues = accepted ? entityLayerCues.commit() : blendSpaceOriginalCues;
            blendSpaceOriginalCues = null;
            capturingBlendSpace = null;
        }
    }

    /**
     * Fixed common-cycle blendspace over ordinary entity layers. The exact source and owner
     * identities define activation. Generation replacement retains that activation's clock origin;
     * unload, retire, disconnect, another owner/definition, or non-blendspace extraction resets it.
     * Ordinary frames never seek. A gap beyond the core advance bound silently recovers the current
     * cycle with one member discontinuity; unrelated layers keep their existing bounded advance.
     */
    public Optional<SkinnedAnimationRuntimeResult> extractBlendSpace(SkinnedAnimationRuntimeInput input,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            AnimationV2LayerWeights weights, AnimationBlendSpace1D definition, double parameter,
            Object source, Object owner, ClientAnimationPoseModifier modifier,
            java.util.function.Consumer<LayerAnimationVisualEvent> listener) {
        Objects.requireNonNull(definition, "definition");
        return extractBlendSpaceFrame(input, layers, commands, weights, definition.syncGroup(),
                definition.weights(parameter), source, owner, modifier, listener);
    }

    /** Same fixed-cycle scheduling and ownership contract as 1D; vector affects only weights. */
    public Optional<SkinnedAnimationRuntimeResult> extractBlendSpace2D(SkinnedAnimationRuntimeInput input,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            AnimationV2LayerWeights weights, AnimationBlendSpace2D definition, AnimationBlendSpace2D.Input parameter,
            Object source, Object owner, ClientAnimationPoseModifier modifier,
            java.util.function.Consumer<LayerAnimationVisualEvent> listener) {
        Objects.requireNonNull(definition, "definition");
        return extractBlendSpaceFrame(input, layers, commands, weights, definition.syncGroup(),
                definition.weights(parameter), source, owner, modifier, listener);
    }

    /** Capture bridge for a validated solver frame; no competing or missing member weights. */
    public Optional<SkinnedAnimationRuntimeResult> extractBlendSpaceFrame(SkinnedAnimationRuntimeInput input,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            AnimationV2LayerWeights weights, AnimationBlendSpaceSyncGroup definition, AnimationV2LayerWeights memberWeights,
            Object source, Object owner, ClientAnimationPoseModifier modifier,
            java.util.function.Consumer<LayerAnimationVisualEvent> listener) {
        Objects.requireNonNull(definition, "definition");
        definition.validateExternalCommands(commands);
        definition.validateExternalWeights(weights);
        var expectedKeys = new java.util.HashSet<AnimationV2LayerWeights.Key>();
        for (var id : definition.memberLayerIds()) expectedKeys.add(new AnimationV2LayerWeights.Key(id, id));
        if (!Objects.requireNonNull(memberWeights, "memberWeights").multipliers().keySet().equals(expectedKeys))
            throw new IllegalArgumentException("blendspace frame must contain exactly all member weights");
        double memberSum = memberWeights.multipliers().values().stream().mapToDouble(Float::doubleValue).sum();
        if (Math.abs(memberSum - 1) > 1e-6)
            throw new IllegalArgumentException("blendspace member weights must sum to one");
        var request = new BlendSpaceRequest(definition, memberWeights,
                Objects.requireNonNull(source, "source"), Objects.requireNonNull(owner, "owner"));
        return extractInternal(input, modifier, null, List.copyOf(layers), List.copyOf(commands),
                Objects.requireNonNull(weights, "weights"), listener, request);
    }

    private Optional<SkinnedAnimationRuntimeResult> extractInternal(
            SkinnedAnimationRuntimeInput input, ClientAnimationPoseModifier poseModifier, Double clipSeconds,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            AnimationV2LayerWeights weights,
            java.util.function.Consumer<com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent> listener) {
        return extractInternal(input, poseModifier, clipSeconds, layers, commands, weights, listener, null);
    }

    private Optional<SkinnedAnimationRuntimeResult> extractInternal(
            SkinnedAnimationRuntimeInput input, ClientAnimationPoseModifier poseModifier, Double clipSeconds,
            List<ModelAnimationLayers.Layer> layers, List<AnimationV2Command> commands,
            AnimationV2LayerWeights weights,
            java.util.function.Consumer<com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent> listener, BlendSpaceRequest blendSpace) {
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
            // Validate a complete captured frame before binding/advancing live instance state.
            LayeredClock selectedLayered = null;
            BlendSpaceClock selectedBlendSpace = null;
            boolean initializeBlendSpace = false;
            long blendSpaceSequence = -1;
            double blendSpaceTick = checkedInput.clientGameTimeInTicks();
            if (layers != null) {
                InstanceClock prior = clocks.get(instanceKey);
                selectedLayered = prior != null && prior.matches(checkedInput.modelKey(), generation)
                        && prior.layered != null && prior.layered.layers.equals(layers)
                        ? prior.layered
                        : new LayeredClock(preparedLayers(loaded, layers), layers, checkedInput.clientGameTimeInTicks());
                if (blendSpace != null) {
                    var binding = preparedBlendSpace(loaded, layers, blendSpace.definition());
                    blendSpaceTick = Math.max(blendSpaceTick, selectedLayered.lastTick);
                    var previousSpace = blendSpaceClocks.get(instanceKey);
                    selectedBlendSpace = previousSpace != null && previousSpace.matches(checkedInput.modelKey(), blendSpace)
                            ? previousSpace : new BlendSpaceClock(checkedInput.modelKey(), blendSpace, blendSpaceTick);
                    blendSpaceTick = Math.max(blendSpaceTick, selectedBlendSpace.lastTick);
                    initializeBlendSpace = selectedLayered.blendSpace != selectedBlendSpace
                            || (blendSpaceTick - selectedLayered.lastTick) / TICKS_PER_SECOND > AnimationV2Limits.MAX_ADVANCE_SECONDS;
                    var merged = new java.util.LinkedHashMap<>(weights.multipliers());
                    merged.putAll(blendSpace.memberWeights().multipliers());
                    weights = new AnimationV2LayerWeights(merged);
                    if (initializeBlendSpace) {
                        long maximum = -1;
                        for (var member : blendSpace.definition().memberLayerIds())
                            maximum = Math.max(maximum, selectedLayered.commandWatermarks.getOrDefault(member, -1L));
                        blendSpaceSequence = Math.incrementExact(maximum);
                        double elapsed = Math.max(0, blendSpaceTick - selectedBlendSpace.originTick) / TICKS_PER_SECOND;
                        double phase = (elapsed % blendSpace.definition().cycleSeconds()) / blendSpace.definition().cycleSeconds();
                        // Rounding division at the upper endpoint must still satisfy [0, 1).
                        phase = Math.min(phase, Math.nextDown(1.0));
                        var combined = new java.util.ArrayList<>(commands);
                        combined.addAll(binding.commands(phase, blendSpaceSequence));
                        commands = List.copyOf(combined);
                    }
                }
                selectedLayered.runtime.validateLayerWeights(weights);
                if (blendSpace != null) selectedLayered.runtime.validateImmediateFrameCommands(commands);
                if (commands.size() > AnimationV2Limits.MAX_FRAME_COMMANDS_PER_ADVANCE)
                    throw new IllegalArgumentException("blendspace frame command batch exceeds v2 bounds");
            }
            ClientAnimationInstanceRegistry instances = lifecycle.registry();
            ClientAnimationInstance instance = instances.bind(instanceKey, checkedInput.modelKey(), generation, prepared.definition());
            InstanceClock clock = clockFor(
                    instanceKey, checkedInput.modelKey(), generation, checkedInput.clientGameTimeInTicks(), prepared.definition());
            AnimationAdvance advance;
            if (clipSeconds == null) {
                advance = advance(instance, clock, checkedInput);
            } else {
                advance = instance.controller().synchronizeClip(checkedInput.fallbackAnimation(), clipSeconds);
                clock.deactivateSynchronizedState();
                clock.incrementSampleRevision();
            }
            PoseCacheKey poseKey = new PoseCacheKey(
                    instanceKey,
                    checkedInput.modelKey(),
                    generation,
                    instance.controller().currentState(),
                    clock.sampleRevision);
            ClientAnimationPoseSnapshot basePose = instances.preparePoseSnapshot(poseKey, prepared.sampler());
            List<com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent> layerEvents = List.of();
            if (layers != null) {
                LayeredClock layered = selectedLayered;
                double tick = blendSpace == null ? checkedInput.clientGameTimeInTicks() : blendSpaceTick;
                double delta = Math.max(0.0D, tick - layered.lastTick) / TICKS_PER_SECOND;
                var evaluation = layered.runtime.advanceWeightedAtFrame(delta, commands, weights);
                for (var command : commands) {
                    if (layered.model.plan().controllerIds().contains(command.controllerId()))
                        layered.commandWatermarks.merge(command.controllerId(), command.sequence(), Math::max);
                }
                layered.lastTick = Math.max(layered.lastTick, tick);
                clock.layered = layered;
                if (selectedBlendSpace != null) {
                    selectedBlendSpace.lastTick = tick;
                    layered.blendSpace = selectedBlendSpace;
                    blendSpaceClocks.put(instanceKey, selectedBlendSpace);
                } else {
                    layered.blendSpace = null;
                    blendSpaceClocks.remove(instanceKey);
                }
                layerEvents = layered.events.consume(evaluation);
                basePose = instances.captureEvaluatedPose(basePose, layered.model.localPose(evaluation.pose()));
            } else {
                clock.layered = null;
                blendSpaceClocks.remove(instanceKey);
            }
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
            if (listener != null) layerEvents.forEach(listener);
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
            BlendInstanceKey key, BlendModelKey modelKey, long generation, double sampleTick, AnimationControllerDefinition definition) {
        InstanceClock current = clocks.get(key);
        if (current != null && current.matches(modelKey, generation)) {
            return current;
        }
        InstanceClock replacement = new InstanceClock(key, modelKey, generation, sampleTick, definition);
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
        double controllerTimeSeconds = synchronizedControllerTimeSeconds(state, sampleTick);
        AnimationCorrectionResult correction = instance.controller().applyTimelineCorrection(new AnimationCorrection(
                state.animationKey(),
                controllerTimeSeconds,
                state.sequence(),
                SYNCHRONIZED_SNAP_THRESHOLD_SECONDS));
        if (correction != AnimationCorrectionResult.STALE_DROPPED) {
            clock.acceptSynchronizedState(state);
            clock.visualEventCursor.accept(state.sequence(), state.animationKey(),
                    controllerTimeSeconds);
            clock.resetAt(sampleTick, input.updateBucket());
            clock.incrementSampleRevision();
            return instance.advance(0.0d);
        }
        if (!clock.hasActiveSynchronizedState()) {
            // Absence may temporarily select a fallback without revoking the last accepted
            // command. Only that exact immutable command can resume at the same sequence;
            // older or conflicting commands must not reactivate synchronization.
            if (state.equals(clock.synchronizedState)) {
                AnimationAdvance recovered = instance.controller().synchronizeTimeline(
                        state.animationKey(), controllerTimeSeconds);
                clock.visualEventCursor.resume(state.sequence(), controllerTimeSeconds);
                clock.acceptSynchronizedState(state);
                clock.resetAt(sampleTick, input.updateBucket());
                clock.incrementSampleRevision();
                return recovered;
            }
            return advanceFallback(instance, clock, input);
        }
        // A repeated accepted sync state is an absolute client timeline, not an instruction to
        // replay every loop since this instance was last extracted. Reappearing after a long
        // cull interval would otherwise exceed the controller's deliberately bounded loop work.
        if (clock.dueForAdvance(sampleTick, input.updateBucket())) {
            AnimationAdvance synchronizedAdvance = instance.controller().synchronizeTimeline(
                    clock.synchronizedAnimationKey(),
                    synchronizedControllerTimeSeconds(
                            clock.synchronizedStartGameTick(), (float) clock.synchronizedSpeed(), sampleTick));
            clock.recordAdvanceAt(sampleTick, input.updateBucket());
            clock.incrementSampleRevision();
            return new AnimationAdvance(synchronizedAdvance.state(), synchronizedAdvance.timeSeconds(),
                    clock.visualEventCursor.advance(clock.synchronizedState.sequence(),
                            synchronizedControllerTimeSeconds(clock.synchronizedStartGameTick(),
                                    (float) clock.synchronizedSpeed(), sampleTick)));
        }
        return new AnimationAdvance(
                instance.controller().currentState(), instance.controller().currentTimeSeconds(), List.of());
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
        return synchronizedControllerTimeSeconds(state.startGameTick(), state.speed(), clientGameTimeInTicks);
    }

    private static double synchronizedControllerTimeSeconds(
            long startGameTick, float speed, double clientGameTimeInTicks) {
        double elapsedTicks = Math.max(0.0d, clientGameTimeInTicks - startGameTick);
        return elapsedTicks / TICKS_PER_SECOND * speed;
    }

    private void clearRuntimeState() {
        locomotionEpoch++;
        blendSpaceClocks.clear();
        preparedBlendSpaces.clear();
        entityLocomotionRules.clear();
        invalidLocomotionInputs.clear();
        entityLayerCues.clear();
        if (blendSpaceOriginalCues != null) blendSpaceOriginalCues.clear();
        preparedAssets.clear();
        preparedLayerPlans.clear();
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

    /**
     * Most recent immutable layered publication from the current resource generation.
     * Call on the extraction owner thread. This read never advances playback, binds an instance,
     * or performs lifecycle cleanup; a reload before the next extraction returns empty instead
     * of exposing a retired generation. A retained publication may lag while an instance is culled.
     */
    public Optional<AnimationV2EvaluationSnapshot> layeredSnapshot(BlendInstanceKey key) {
        InstanceClock clock = clocks.get(Objects.requireNonNull(key, "key"));
        return clock == null || clock.layered == null || clock.generation != modelRegistry.current().generationId()
                ? Optional.empty()
                : Optional.of(clock.layered.runtime.latestSnapshot());
    }

    private ModelAnimationLayers preparedLayers(LoadedModelHandle loaded, List<ModelAnimationLayers.Layer> layers) {
        LayerPlanKey key = new LayerPlanKey(loaded.key(), loaded.generationId(), layers);
        ModelAnimationLayers prepared = preparedLayerPlans.get(key);
        if (prepared == null) {
            prepared = new ModelAnimationLayers(loaded.asset(), layers);
            preparedLayerPlans.put(key, prepared);
            if (preparedLayerPlans.size() > 64) preparedLayerPlans.remove(preparedLayerPlans.keySet().iterator().next());
        }
        return prepared;
    }

    private AnimationBlendSpaceSyncGroup.Binding preparedBlendSpace(LoadedModelHandle loaded,
            List<ModelAnimationLayers.Layer> layers, AnimationBlendSpaceSyncGroup definition) {
        var key = new BlendSpacePlanKey(new LayerPlanKey(loaded.key(), loaded.generationId(), layers), definition);
        var binding = preparedBlendSpaces.get(key);
        if (binding == null) {
            binding = definition.bind(preparedLayers(loaded, layers).plan());
            preparedBlendSpaces.put(key, binding);
            if (preparedBlendSpaces.size() > 64) preparedBlendSpaces.remove(preparedBlendSpaces.keySet().iterator().next());
        }
        return binding;
    }

    private record BlendSpacePlanKey(LayerPlanKey layers, AnimationBlendSpaceSyncGroup definition) { }
    private record BlendSpaceRequest(AnimationBlendSpaceSyncGroup definition, AnimationV2LayerWeights memberWeights, Object source, Object owner) { }
    private static final class BlendSpaceClock {
        final BlendModelKey model;
        final AnimationBlendSpaceSyncGroup definition;
        final Object source;
        final Object owner;
        final double originTick;
        double lastTick;
        BlendSpaceClock(BlendModelKey model, BlendSpaceRequest request, double tick) {
            this.model = model; definition = request.definition(); source = request.source(); owner = request.owner();
            originTick = tick; lastTick = tick;
        }
        boolean matches(BlendModelKey model, BlendSpaceRequest request) {
            return this.model.equals(model) && definition == request.definition() && source == request.source() && owner == request.owner();
        }
    }

    private record LayerPlanKey(BlendModelKey model, long generation, List<ModelAnimationLayers.Layer> layers) {}

    private static final class LayeredClock {
        private final List<ModelAnimationLayers.Layer> layers;
        private final ModelAnimationLayers model;
        private final AnimationV2InstanceRuntime runtime;
        private final com.liy.blendlib.core.animation.v2.LayerAnimationVisualEventCursor events;
        private double lastTick;
        private BlendSpaceClock blendSpace;
        private final Map<com.liy.blendlib.api.BlendResourceId, Long> commandWatermarks = new HashMap<>();
        private LayeredClock(ModelAnimationLayers model, List<ModelAnimationLayers.Layer> layers, double tick) {
            this.layers = layers;
            this.model = model;
            this.runtime = new AnimationV2InstanceRuntime(model.plan());
            this.events = model.newVisualEventCursor(runtime);
            this.lastTick = tick;
        }
    }

    private static final class InstanceClock {
        private LayeredClock layered;
        private final SynchronizedVisualEventCursor visualEventCursor;
        private final BlendModelKey modelKey;
        private final long generation;
        private double lastAdvancedGameTick;
        private double lastCadenceTick = Double.NaN;
        private long sampleRevision;
        private SyncedAnimationState synchronizedState;
        private boolean synchronizedStateActive;
        private boolean initialized;

        private InstanceClock(BlendInstanceKey key, BlendModelKey modelKey, long generation, double sampleTick,
                AnimationControllerDefinition definition) {
            this.visualEventCursor = new SynchronizedVisualEventCursor(key, definition);
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

        private void acceptSynchronizedState(SyncedAnimationState state) {
            synchronizedState = state;
            synchronizedStateActive = true;
        }

        private boolean hasActiveSynchronizedState() {
            return synchronizedStateActive && synchronizedState != null;
        }

        private double synchronizedSpeed() {
            return synchronizedState.speed();
        }

        private BlendAnimationKey synchronizedAnimationKey() {
            return synchronizedState.animationKey();
        }

        private long synchronizedStartGameTick() {
            return synchronizedState.startGameTick();
        }

        private void deactivateSynchronizedState() {
            synchronizedStateActive = false;
            visualEventCursor.deactivate();
        }
    }
}
