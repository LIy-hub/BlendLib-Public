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
                    skinnedSocketMarkerKey);
        }
        if (snapshotFactory == null) {
            throw new IllegalStateException("A BlendEntityRenderer requires an extraction-only snapshotFactory");
        }
        return new BlendEntityRenderer<>(
                context,
                modelKey,
                renderer,
                snapshotFactory,
                rootRotationSelector != null,
                shadowRadius,
                shadowStrength);
    }

    private static float requireNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }
}
