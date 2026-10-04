package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.animation.sync.BlendLibClientAnimationSync;
import java.util.Objects;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;

/** Mutable-at-configuration-time builder for the version-scoped P4/P5 entity adapter. */
public final class BlendEntityRendererBuilder<E extends Entity> {
    private final EntityRendererProvider.Context context;
    private final BlendModelKey modelKey;
    private final BlendRenderer renderer;
    private BlendEntitySnapshotFactory<? super E> snapshotFactory;
    private SkinnedAnimationStateSelector<? super E> skinnedAnimationStateSelector;
    private SyncedSkinnedAnimationStateSelector<? super E> syncedSkinnedAnimationStateSelector;
    private SkinnedAnimationVisualEventHandler<? super E> skinnedAnimationVisualEventHandler;
    private BlendEntityPoseModifier<? super E> poseModifier;
    private BlendEntityRootRotationSelector<? super E> rootRotationSelector;
    private BlendResourceId skinnedSocketMarkerKey;
    private java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> animationLayers;
    private BlendEntityLayerCommands<? super E> layerCommands;
    private BlendEntityLayerWeights<? super E> layerWeights;
    private BlendEntityMorphControls<? super E> morphControls;
    private com.liy.blendlib.core.animation.v2.AnimationBlendSpaceSyncGroup blendSpace;
    private java.util.function.BiFunction<E, BlendEntitySnapshotRequest, com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights> blendSpaceWeights;
    private BlendEntityBlendSpaceCadence<? super E> blendSpaceCadence;
    private BlendResourceId locomotionController;
    private BlendEntityLocomotionInputs<? super E> locomotionInputs;
    private BlendEntityLayerVisualEventHandler<? super E> layerVisualEventHandler;
    private com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier poseComponents;
    private BlendEntitySocketHandler<? super E> socketHandler;
    private BlendEntityAttachmentProvider<? super E> attachmentProvider;
    private BlendEntityMaterialAppearance<? super E> materialAppearance;
    private BlendEntitySkinSelector<? super E> skinSelector;
    private BlendEntityCullingEnvelope cullingEnvelope;
    private float shadowRadius = 0.5F;
    private float shadowStrength = 1.0F;

    BlendEntityRendererBuilder(
            EntityRendererProvider.Context context, BlendModelKey modelKey, BlendRenderer renderer) {
        this.context = Objects.requireNonNull(context, "context");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Supplies extraction-only snapshot construction for this entity type. */
    public BlendEntityRendererBuilder<E> snapshotFactory(BlendEntitySnapshotFactory<? super E> snapshotFactory) {
        if (skinnedAnimationStateSelector != null) {
            throw new IllegalStateException("Choose either skinnedAnimation or a custom snapshotFactory, not both");
        }
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        return this;
    }

    /**
     * Configures the P4 high-level static/rigid rest-pose path.
     *
     * <p>The model key is resolved only while the entity render state is extracted. Submit later
     * receives its already bound immutable snapshot, so a consumer does not need to import core
     * transform, bounds, registry, or snapshot implementation types for the common P4 case.</p>
     */
    public BlendEntityRendererBuilder<E> staticRestPose() {
        if (snapshotFactory != null || skinnedAnimationStateSelector != null) {
            throw new IllegalStateException("Choose either staticRestPose, skinnedAnimation, or a custom snapshotFactory, not both");
        }
        this.snapshotFactory = (entity, request) -> StaticRestPoseEntitySnapshotFactory.create(
                BlendLibClientServices.models(), entity, request);
        return this;
    }

    /**
     * Configures P5 extraction-side controller advancement and captured strict-v1 animated
     * snapshots.
     *
     * <p>The retained method name is source-compatible with the first P5 skinned integration. Its
     * extraction implementation also accepts {@code rigid_v1} assets with declared animation
     * states, while the resulting renderer submit path still consumes only the captured snapshot.</p>
     */
    public BlendEntityRendererBuilder<E> skinnedAnimation(
            SkinnedAnimationStateSelector<? super E> stateSelector) {
        if (snapshotFactory != null || skinnedAnimationStateSelector != null) {
            throw new IllegalStateException("Choose exactly one entity snapshot construction path");
        }
        this.skinnedAnimationStateSelector = Objects.requireNonNull(stateSelector, "stateSelector");
        return this;
    }

    /**
     * Configures a semantic-state selector with a local fallback animation selector.
     *
     * <p>The semantic selector is consulted only while the entity snapshot is extracted. Submit
     * continues to consume only the captured immutable snapshot. Consumers that use this overload
     * supply no internal client-store type; they only return the public common semantic value.</p>
     */
    public BlendEntityRendererBuilder<E> synchronizedSkinnedAnimation(
            SyncedSkinnedAnimationStateSelector<? super E> syncedStateSelector,
            SkinnedAnimationStateSelector<? super E> fallbackStateSelector) {
        if (snapshotFactory != null || skinnedAnimationStateSelector != null) {
            throw new IllegalStateException("Choose exactly one entity snapshot construction path");
        }
        this.syncedSkinnedAnimationStateSelector = Objects.requireNonNull(syncedStateSelector, "syncedStateSelector");
        this.skinnedAnimationStateSelector = Objects.requireNonNull(fallbackStateSelector, "fallbackStateSelector");
        return this;
    }

    /**
     * Configures the standard BlendLib entity semantic lookup with a local fallback selector.
     *
     * <p>This is the ordinary consumer path: it hides the adapter's state store entirely while
     * retaining a deterministic local animation whenever the entity has no accepted semantics.</p>
     */
    public BlendEntityRendererBuilder<E> synchronizedSkinnedAnimation(
            SkinnedAnimationStateSelector<? super E> fallbackStateSelector) {
        return synchronizedSkinnedAnimation(
                (entity, request) -> BlendLibClientAnimationSync.runtime().entityState(entity.getId()),
                fallbackStateSelector);
    }

    /**
     * Adds one entity-aware, client-only procedural rotation layer to the strict animated path.
     *
     * <p>Configure {@link #skinnedAnimation(SkinnedAnimationStateSelector)} or one of the
     * synchronized variants first. The callback runs after BlendLib samples/caches the immutable
     * base pose and before rigid/skinned palette construction. The adapter-owned callback surface
     * exposes only normalized node rotations; node membership, translation, and scale remain
     * fixed by the cached base pose.</p>
     */
    public BlendEntityRendererBuilder<E> poseModifier(BlendEntityPoseModifier<? super E> modifier) {
        if (skinnedAnimationStateSelector == null) {
            throw new IllegalStateException("Configure skinnedAnimation before configuring a pose modifier");
        }
        if (poseModifier != null) {
            throw new IllegalStateException("A strict animated entity renderer can configure only one pose modifier");
        }
        this.poseModifier = Objects.requireNonNull(modifier, "modifier");
        return this;
    }

    /**
     * Replaces the ordinary interpolated-yaw render root with a complete normalized quaternion.
     *
     * <p>The selector runs only during extraction and is available on the strict animated entity
     * path. Configuring it also selects a rotation-invariant culling envelope so arbitrary pitch,
     * roll, inversion, and continuous turns cannot rotate visible geometry outside the frustum
     * box.</p>
     */
    public BlendEntityRendererBuilder<E> rootRotation(
            BlendEntityRootRotationSelector<? super E> selector) {
        if (skinnedAnimationStateSelector == null) {
            throw new IllegalStateException("Configure skinnedAnimation before configuring root rotation");
        }
        if (rootRotationSelector != null) {
            throw new IllegalStateException("A strict animated entity renderer can configure only one root rotation selector");
        }
        this.rootRotationSelector = Objects.requireNonNull(selector, "selector");
        return this;
    }

    /** Registers an optional presentation-only listener for the configured P5 skinned animation. */
    public BlendEntityRendererBuilder<E> onSkinnedVisualEvent(
            SkinnedAnimationVisualEventHandler<? super E> visualEventHandler) {
        if (skinnedAnimationStateSelector == null) {
            throw new IllegalStateException("Configure skinnedAnimation before registering visual animation events");
        }
        this.skinnedAnimationVisualEventHandler = Objects.requireNonNull(visualEventHandler, "visualEventHandler");
        return this;
    }

    /** Registers extraction-only layer markers; configure animationLayers or animationLayerCues first. */
    public BlendEntityRendererBuilder<E> onAnimationLayerVisualEvent(
            BlendEntityLayerVisualEventHandler<? super E> handler) {
        if (animationLayers == null) {
            throw new IllegalStateException("Configure animationLayers before registering layer visual events");
        }
        this.layerVisualEventHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Configures one P5 presentation-only marker for an extraction-captured skinned socket.
     *
     * <p>The marker is client-adapter-only. It neither exposes a world transform nor performs a
     * socket lookup during submit; the configured key selects one transform from the same
     * extraction frame that captured the CPU-skinned snapshot.</p>
     */
    public BlendEntityRendererBuilder<E> skinnedSocketMarker(BlendResourceId socketKey) {
        if (skinnedAnimationStateSelector == null) {
            throw new IllegalStateException("Configure skinnedAnimation before configuring a socket marker");
        }
        if (skinnedSocketMarkerKey != null) {
            throw new IllegalStateException("A skinned entity renderer can configure only one presentation socket marker");
        }
        this.skinnedSocketMarkerKey = Objects.requireNonNull(socketKey, "socketKey");
        return this;
    }

    /** Adds descriptor-backed independent controllers. Commands use increasing per-controller sequences. */
    public BlendEntityRendererBuilder<E> animationLayers(
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> layers,
            BlendEntityLayerCommands<? super E> commands) {
        requireAnimated();
        if (layers.isEmpty()) throw new IllegalArgumentException("At least one layer is required");
        if (locomotionInputs != null || blendSpace != null)
            throw new IllegalStateException("Configure layers before locomotion rules or a blendspace");
        this.animationLayers = java.util.List.copyOf(layers);
        this.layerCommands = Objects.requireNonNull(commands, "commands");
        return this;
    }

    /** Adds tick-based entity cues with runtime-owned capture and automatic lifecycle cleanup. */
    public BlendEntityRendererBuilder<E> animationLayerCues(
            java.util.List<com.liy.blendlib.core.animation.v2.ModelAnimationLayers.Layer> layers,
            BlendEntityLayerCues<? super E> cues) {
        return animationLayers(layers, BlendEntityLayerCommands.fromCues(cues));
    }

    /**
     * Opts one declared layer into its model's optional resource-pack locomotion rules.
     * Configure layers/cues first. With valid rules this controller must receive no explicit
     * commands/cues; other controllers remain independent. Missing/invalid resources keep the
     * original commands unchanged. Inputs are captured only during extraction, once per frame.
     */
    public BlendEntityRendererBuilder<E> animationLocomotionRules(
            BlendResourceId controllerId, BlendEntityLocomotionInputs<? super E> inputs) {
        requireAnimated();
        if (animationLayers == null) throw new IllegalStateException("Configure animation layers or cues first");
        if (locomotionInputs != null) throw new IllegalStateException("Only one locomotion controller may be configured");
        Objects.requireNonNull(controllerId, "controllerId");
        if (animationLayers.stream().noneMatch(layer -> layer.id().equals(controllerId)))
            throw new IllegalArgumentException("Undeclared locomotion controller: " + controllerId);
        if (blendSpace != null && blendSpace.memberLayerIds().contains(controllerId))
            throw new IllegalArgumentException("blendspace owns locomotion controller: " + controllerId);
        this.locomotionController = controllerId;
        this.locomotionInputs = Objects.requireNonNull(inputs, "inputs");
        return this;
    }

    /**
     * Captures entity-aware clip-layer multipliers once per extraction. Configure layers/cues first.
     * Missing entries mean one; zero weight keeps advancing playback. This does not change cue sequences.
     */
    public BlendEntityRendererBuilder<E> animationLayerWeights(BlendEntityLayerWeights<? super E> weights) {
        requireAnimated();
        if (animationLayers == null) throw new IllegalStateException("Configure animation layers or cues first");
        this.layerWeights = Objects.requireNonNull(weights, "weights");
        return this;
    }

    /**
     * Opts existing independent layers into one synchronized, fixed-cycle 1D blendspace.
     * Configure layers/cues first. Member clocks and weights are exclusively owned by the space;
     * unrelated commands/weights remain independent. The finite parameter is captured once per
     * extraction, before commands. Clips must share authored gait phase; zero-weight members run.
     */
    public BlendEntityRendererBuilder<E> animationBlendSpace1D(
            com.liy.blendlib.core.animation.v2.AnimationBlendSpace1D definition,
            BlendEntityBlendSpaceParameter<? super E> parameter) {
        requireAnimated();
        if (animationLayers == null) throw new IllegalStateException("Configure animation layers or cues first");
        if (blendSpace != null) throw new IllegalStateException("Only one blendspace may be configured");
        Objects.requireNonNull(definition, "definition"); Objects.requireNonNull(parameter, "parameter");
        for (var member : definition.memberLayerIds()) {
            if (animationLayers.stream().noneMatch(layer -> layer.id().equals(member)))
                throw new IllegalArgumentException("Undeclared blendspace layer: " + member);
            if (member.equals(locomotionController))
                throw new IllegalArgumentException("blendspace owns locomotion controller: " + member);
        }
        this.blendSpace = definition.syncGroup();
        this.blendSpaceWeights = (entity, request) -> definition.weights(parameter.parameter(entity, request));
        return this;
    }

    /** Opts existing layers into bounded directional center/ring mixing with a fixed shared cycle.
     * Coordinates are consumer-defined; capture and transform local velocity in the callback. */
    public BlendEntityRendererBuilder<E> animationBlendSpace2D(
            com.liy.blendlib.core.animation.v2.AnimationBlendSpace2D definition,
            BlendEntityBlendSpace2DParameter<? super E> parameter) {
        requireAnimated();
        if (animationLayers == null) throw new IllegalStateException("Configure animation layers or cues first");
        if (blendSpace != null) throw new IllegalStateException("Only one blendspace may be configured");
        Objects.requireNonNull(definition, "definition"); Objects.requireNonNull(parameter, "parameter");
        for (var member : definition.memberLayerIds()) {
            if (animationLayers.stream().noneMatch(layer -> layer.id().equals(member)))
                throw new IllegalArgumentException("Undeclared blendspace layer: " + member);
            if (member.equals(locomotionController))
                throw new IllegalArgumentException("blendspace owns locomotion controller: " + member);
        }
        this.blendSpace = definition.syncGroup();
        this.blendSpaceWeights = (entity, request) -> definition.weights(parameter.parameter(entity, request));
        return this;
    }

    /**
     * Changes the shared positive cadence of the configured 1D/2D group. Omission means one.
     * The old cadence advances elapsed time; this capture starts at the current frame boundary.
     * Values must be finite in [1/64, 64] and satisfy every member's derived playback bounds.
     */
    public BlendEntityRendererBuilder<E> animationBlendSpaceCadence(BlendEntityBlendSpaceCadence<? super E> cadence) {
        requireAnimated();
        if (blendSpace == null) throw new IllegalStateException("Configure a blendspace before cadence");
        this.blendSpaceCadence = Objects.requireNonNull(cadence, "cadence");
        return this;
    }

    /** Runs reusable pose components after layered animation, before sockets and palettes. */
    public BlendEntityRendererBuilder<E> poseComponents(
            com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier components) {
        requireAnimated();
        this.poseComponents = Objects.requireNonNull(components, "components");
        return this;
    }

    /** Observes this extraction's complete post-animation socket frame. */
    public BlendEntityRendererBuilder<E> sockets(BlendEntitySocketHandler<? super E> handler) {
        requireAnimated(); this.socketHandler = Objects.requireNonNull(handler, "handler"); return this;
    }

    /** Captures child snapshots at final-pose sockets; submit never calls the provider. */
    public BlendEntityRendererBuilder<E> attachments(BlendEntityAttachmentProvider<? super E> provider) {
        requireAnimated(); this.attachmentProvider = Objects.requireNonNull(provider, "provider"); return this;
    }

    /**
     * Selects per-instance RGB multipliers and visibility during extraction on any snapshot path.
     * Unknown exact names retain authored appearance for that selection; inspect the captured
     * snapshot's unknownMaterialSlots diagnostic. Missing-model diagnostic colors are untouched.
     */
    public BlendEntityRendererBuilder<E> materialAppearance(BlendEntityMaterialAppearance<? super E> selector) {
        this.materialAppearance = Objects.requireNonNull(selector, "selector");
        return this;
    }

    /** Replaces named morph weights for one extracted frame. Omitted names resume clip/default values. */
    public BlendEntityRendererBuilder<E> morphControls(BlendEntityMorphControls<? super E> controls) {
        requireAnimated();
        this.morphControls = Objects.requireNonNull(controls, "controls");
        return this;
    }

    /** Selects a registered named skin once per extraction, before material appearance. */
    public BlendEntityRendererBuilder<E> skin(BlendEntitySkinSelector<? super E> selector) {
        this.skinSelector = Objects.requireNonNull(selector, "selector");
        return this;
    }

    /**
     * Adds a fixed whole-assembly culling envelope on any snapshot path. Captured at build time;
     * no attachment, animation, entity or rotation selector is invoked during culling. The
     * envelope is unioned with vanilla and current-generation root bounds, never substituted.
     * Omit this option to retain existing behavior. See {@link BlendEntityCullingEnvelope} for
     * units, transforms, reload and consumer sizing responsibilities.
     */
    public BlendEntityRendererBuilder<E> cullingEnvelope(BlendEntityCullingEnvelope envelope) {
        this.cullingEnvelope = Objects.requireNonNull(envelope, "envelope");
        return this;
    }

    private void requireAnimated() {
        if (skinnedAnimationStateSelector == null) throw new IllegalStateException("Configure skinnedAnimation first");
    }

    public BlendEntityRendererBuilder<E> shadowRadius(float shadowRadius) {
        this.shadowRadius = requireNonNegativeFinite(shadowRadius, "shadowRadius");
        return this;
    }

    public BlendEntityRendererBuilder<E> shadowStrength(float shadowStrength) {
        this.shadowStrength = requireNonNegativeFinite(shadowStrength, "shadowStrength");
        return this;
    }

    /** Builds the renderer after all extraction data has been specified. */
    public BlendEntityRenderer<E> build() {
        if (snapshotFactory == null && skinnedAnimationStateSelector != null) {
            snapshotFactory = new SkinnedAnimationEntitySnapshotFactory<>(
                    modelKey,
                    skinnedAnimationStateSelector,
                    syncedSkinnedAnimationStateSelector,
                    skinnedAnimationVisualEventHandler,
                    poseModifier,
                    rootRotationSelector,
                    skinnedSocketMarkerKey, animationLayers, captureLocomotionCommands(layerCommands, locomotionController, locomotionInputs), poseComponents, socketHandler, attachmentProvider, layerWeights, layerVisualEventHandler, blendSpace, blendSpaceWeights, blendSpaceCadence, morphControls);
        }
        if (snapshotFactory == null) {
            throw new IllegalStateException("A BlendEntityRenderer requires an extraction-only snapshotFactory");
        }
        BlendEntitySnapshotFactory<E> capturedFactory = captureMaterialAppearance(captureSkin(snapshotFactory, skinSelector), materialAppearance);
        return new BlendEntityRenderer<>(
                context,
                modelKey,
                renderer,
                capturedFactory,
                rootRotationSelector != null,
                cullingEnvelope,
                shadowRadius,
                shadowStrength);
    }

    /** Keeps source identity stable and does not retain the mutable builder. */
    static <E extends Entity> BlendEntityLayerCommands<E> captureLocomotionCommands(
            BlendEntityLayerCommands<? super E> commands, BlendResourceId controller,
            BlendEntityLocomotionInputs<? super E> inputs) {
        if (inputs == null) return commands == null ? null : (entity, request) -> commands.commands(entity, request);
        Object source = new Object();
        return (entity, request) -> {
            var runtime = BlendLibClientServices.skinnedAnimationRuntime();
            var model = BlendLibClientServices.models().resolve(request.modelKey());
            if (model.missing()) return java.util.List.of();
            return runtime.captureEntityLocomotionRules(source, entity, entity.getId(), request.modelKey(),
                    model.generationId(), request.clientGameTick() + (double) request.partialTick(), controller,
                    () -> inputs.capture(entity, request), () -> commands.commands(entity, request));
        };
    }

    /** Captures configuration once; the returned extraction factory retains no mutable builder. */
    static <E extends Entity> BlendEntitySnapshotFactory<E> captureMaterialAppearance(
            BlendEntitySnapshotFactory<? super E> factory,
            BlendEntityMaterialAppearance<? super E> appearance) {
        return (entity, request) -> {
            var snapshot = factory.create(entity, request);
            return snapshot == null || appearance == null || snapshot.handle().missingModel()
                    ? snapshot : snapshot.withMaterialAppearance(appearance.select(entity, request));
        };
    }

    /** Captures the selector without retaining the mutable builder. */
    static <E extends Entity> BlendEntitySnapshotFactory<E> captureSkin(
            BlendEntitySnapshotFactory<? super E> factory, BlendEntitySkinSelector<? super E> selector) {
        return (entity, request) -> {
            var snapshot = factory.create(entity, request);
            return snapshot == null || selector == null || snapshot.handle().missingModel()
                    ? snapshot : snapshot.withSkin(selector.select(entity, request));
        };
    }

    private static float requireNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }
}
