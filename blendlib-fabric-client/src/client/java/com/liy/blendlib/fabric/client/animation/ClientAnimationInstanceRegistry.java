package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Client extraction-side owner of instance controllers and their sampled-pose cache.
 *
 * <p>This type is deliberately single-owner: access it only from the extraction/update
 * flow that owns controller mutation. A changed model binding or generation replaces the prior
 * controller and drops all poses for that instance.</p>
 */
public final class ClientAnimationInstanceRegistry {
    private final Map<BlendInstanceKey, ClientAnimationInstance> instances = new HashMap<>();
    private final BoundedPoseCache poseCache;

    public ClientAnimationInstanceRegistry(int poseCacheCapacity) {
        poseCache = new BoundedPoseCache(poseCacheCapacity);
    }

    /**
     * Finds or creates state for a concrete instance model binding and generation.
     *
     * <p>Only an identical instance key, model key, and generation preserves the existing
     * controller. A changed model binding or generation retires the prior controller and all
     * cached poses for the instance key.</p>
     */
    public ClientAnimationInstance bind(
            BlendInstanceKey key,
            BlendModelKey modelKey,
            long generation,
            AnimationControllerDefinition definition
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(definition, "definition");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }

        ClientAnimationInstance current = instances.get(key);
        if (current != null
                && current.generation() == generation
                && current.modelKey().equals(modelKey)) {
            return current;
        }
        if (current != null) {
            poseCache.removeInstance(key);
        }

        ClientAnimationInstance replacement = new ClientAnimationInstance(key, modelKey, generation, definition);
        instances.put(key, replacement);
        return replacement;
    }

    public Optional<ClientAnimationInstance> find(BlendInstanceKey key) {
        return Optional.ofNullable(instances.get(Objects.requireNonNull(key, "key")));
    }

    public int size() {
        return instances.size();
    }

    /**
     * Explicitly removes one instance and every cached pose associated with it.
     */
    public boolean remove(BlendInstanceKey key) {
        Objects.requireNonNull(key, "key");
        ClientAnimationInstance removed = instances.remove(key);
        poseCache.removeInstance(key);
        return removed != null;
    }

    /**
     * Removes one fully typed entity instance when it leaves the current client level.
     *
     * @return number of removed typed entity instances
     */
    public int removeUnloadedEntity(BlendInstanceKey.Entity key) {
        Objects.requireNonNull(key, "key");
        return removeMatching(key::equals);
    }

    /**
     * Removes one typed block-entity instance when its client chunk unloads.
     *
     * @return number of removed typed block-entity instances
     */
    public int removeUnloadedBlockEntity(BlendInstanceKey.BlockEntity key) {
        Objects.requireNonNull(key, "key");
        return removeMatching(key::equals);
    }

    /**
     * Retires every instance and cached pose outside the supplied active generation.
     *
     * @return number of retired instances
     */
    public int retireOtherGenerations(long activeGeneration) {
        if (activeGeneration < 0) {
            throw new IllegalArgumentException("activeGeneration must be non-negative");
        }

        int retired = 0;
        Iterator<Map.Entry<BlendInstanceKey, ClientAnimationInstance>> iterator = instances.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().generation() != activeGeneration) {
                iterator.remove();
                retired++;
            }
        }
        poseCache.retireOtherGenerations(activeGeneration);
        return retired;
    }

    /**
     * Samples an already-bound controller through a caller-prepared sampler and caches the result.
     *
     * <p>This extraction-side operation neither advances the controller nor selects timing, cadence,
     * visibility, or resource state. The supplied key must identify the current binding and current
     * animation state; a cache hit still refreshes the instance's latest immutable pose.</p>
     */
    public LocalPose sampleAndCache(PoseCacheKey key, PoseSampler sampler) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sampler, "sampler");
        ClientAnimationInstance current = requireCurrentBinding(key);
        if (!current.controller().currentState().equals(key.animationKey())) {
            throw new IllegalArgumentException("pose key animation state does not match the current controller state");
        }
        LocalPose pose = poseCache.find(key).orElseGet(() -> {
            LocalPose sampled = current.controller().sample(sampler);
            poseCache.put(key, sampled);
            return sampled;
        });
        current.rememberPose(pose);
        return pose;
    }

    public void cachePose(PoseCacheKey key, LocalPose pose) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(pose, "pose");
        requireCurrentBinding(key);
        poseCache.put(key, pose);
    }

    public Optional<LocalPose> cachedPose(PoseCacheKey key) {
        Objects.requireNonNull(key, "key");
        if (!isCurrentBinding(key)) {
            return Optional.empty();
        }
        return poseCache.find(key);
    }

    /**
     * Prepares one immutable, identity-bound pose handoff from the current controller state.
     *
     * <p>The cache and controller checks remain centralized in {@link #sampleAndCache(PoseCacheKey,
     * PoseSampler)}. This method adds no timing, resource, hierarchy, or backend work.</p>
     */
    public ClientAnimationPoseSnapshot preparePoseSnapshot(PoseCacheKey key, PoseSampler sampler) {
        return ClientAnimationPoseSnapshot.from(key, sampleAndCache(key, sampler));
    }

    /**
     * Applies one procedural rotation modifier to an already cached immutable base-pose snapshot.
     *
     * <p>The returned snapshot retains the exact binding key but is never inserted into the pose
     * cache and never replaces the instance's latest sampled base pose. Invalid callback output is
     * rejected before a caller can construct a node or skin palette.</p>
     */
    public ClientAnimationPoseSnapshot applyPoseModifier(
            ClientAnimationPoseSnapshot baseSnapshot,
            ClientAnimationPoseContext context,
            ClientAnimationPoseModifier modifier) {
        ClientAnimationPoseSnapshot checkedBase = Objects.requireNonNull(baseSnapshot, "baseSnapshot");
        ClientAnimationPoseContext checkedContext = Objects.requireNonNull(context, "context");
        ClientAnimationPoseModifier checkedModifier = Objects.requireNonNull(modifier, "modifier");
        requireCurrentPoseSnapshot(checkedBase);
        checkedBase.requireCompatible(
                checkedContext.instanceKey(),
                checkedContext.modelKey(),
                checkedContext.generation(),
                checkedContext.animationKey());

        LocalPose basePose = checkedBase.localPose();
        if (!checkedContext.rig().nodeIndices().equals(basePose.transforms().keySet())) {
            throw new IllegalArgumentException("Animation rig node set does not match the cached base pose");
        }
        LocalPose modifiedPose = checkedModifier.modify(checkedContext, basePose);
        if (modifiedPose == null) {
            throw new IllegalArgumentException("Pose modifier must return a non-null LocalPose");
        }
        validateRotationOnlyPose(basePose, modifiedPose);
        return ClientAnimationPoseSnapshot.from(checkedBase.poseCacheKey(), modifiedPose);
    }

    /**
     * Fails when a previously prepared pose no longer belongs to the current model, generation, or state.
     *
     * <p>A retained snapshot remains immutable after LRU eviction, but it cannot be consumed after a
     * model rebind, generation replacement, or controller state transition.</p>
     */
    public void requireCurrentPoseSnapshot(ClientAnimationPoseSnapshot snapshot) {
        ClientAnimationPoseSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        PoseCacheKey key = checkedSnapshot.poseCacheKey();
        ClientAnimationInstance current = requireCurrentBinding(key);
        checkedSnapshot.requireCompatible(
                current.key(), current.modelKey(), current.generation(), current.controller().currentState());
    }

    public PoseCacheMetrics poseCacheMetrics() {
        return poseCache.metrics();
    }

    /**
     * Clears state and cache observations when the active world is left.
     */
    public void onWorldDisconnect() {
        instances.clear();
        poseCache.clearAndResetMetrics();
    }

    private int removeMatching(Predicate<BlendInstanceKey> predicate) {
        int removed = 0;
        Iterator<Map.Entry<BlendInstanceKey, ClientAnimationInstance>> iterator = instances.entrySet().iterator();
        while (iterator.hasNext()) {
            BlendInstanceKey key = iterator.next().getKey();
            if (predicate.test(key)) {
                iterator.remove();
                poseCache.removeInstance(key);
                removed++;
            }
        }
        return removed;
    }

    private ClientAnimationInstance requireCurrentBinding(PoseCacheKey key) {
        ClientAnimationInstance current = instances.get(key.instanceKey());
        if (current == null
                || current.generation() != key.generation()
                || !current.modelKey().equals(key.modelKey())) {
            throw new IllegalArgumentException("pose key does not belong to a current instance model binding");
        }
        return current;
    }

    private boolean isCurrentBinding(PoseCacheKey key) {
        ClientAnimationInstance current = instances.get(key.instanceKey());
        return current != null
                && current.generation() == key.generation()
                && current.modelKey().equals(key.modelKey());
    }

    private static void validateRotationOnlyPose(LocalPose basePose, LocalPose modifiedPose) {
        if (!basePose.transforms().keySet().equals(modifiedPose.transforms().keySet())) {
            throw new IllegalArgumentException("Pose modifier must preserve the exact base-pose node set");
        }
        for (Map.Entry<Integer, Transform> entry : basePose.transforms().entrySet()) {
            int nodeIndex = entry.getKey();
            Transform baseTransform = entry.getValue();
            Transform modifiedTransform = modifiedPose.transform(nodeIndex);
            if (!baseTransform.translation().equals(modifiedTransform.translation())) {
                throw new IllegalArgumentException(
                        "Pose modifier must preserve translation for node index: " + nodeIndex);
            }
            if (!baseTransform.scale().equals(modifiedTransform.scale())) {
                throw new IllegalArgumentException(
                        "Pose modifier must preserve scale for node index: " + nodeIndex);
            }
            requireFiniteNormalizedRotation(modifiedTransform.rotation(), nodeIndex);
        }
    }

    private static void requireFiniteNormalizedRotation(Quaternion rotation, int nodeIndex) {
        Quaternion checkedRotation = Objects.requireNonNull(rotation, "rotation");
        double lengthSquared = (double) checkedRotation.x() * checkedRotation.x()
                + (double) checkedRotation.y() * checkedRotation.y()
                + (double) checkedRotation.z() * checkedRotation.z()
                + (double) checkedRotation.w() * checkedRotation.w();
        if (!Double.isFinite(lengthSquared) || Math.abs(lengthSquared - 1.0d) > 1.0e-4d) {
            throw new IllegalArgumentException(
                    "Pose modifier rotation must be finite and normalized for node index: " + nodeIndex);
        }
    }
}
