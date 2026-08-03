package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import java.util.Objects;

/**
 * Immutable extraction handoff for one sampled controller pose and its concrete binding identity.
 *
 * <p>{@link LocalPose} already owns an immutable copy of its transforms, so this carrier can safely
 * retain the sampled value without exposing mutable controller state. It deliberately contains no
 * platform, resource, hierarchy-selection, or backend reference; a later asset-bound bridge must
 * validate ADR-015's canonical active hierarchy before deriving a node or skin palette.</p>
 */
public final class ClientAnimationPoseSnapshot {
    private final PoseCacheKey poseCacheKey;
    private final LocalPose localPose;

    private ClientAnimationPoseSnapshot(PoseCacheKey poseCacheKey, LocalPose localPose) {
        this.poseCacheKey = Objects.requireNonNull(poseCacheKey, "poseCacheKey");
        this.localPose = Objects.requireNonNull(localPose, "localPose");
    }

    static ClientAnimationPoseSnapshot from(PoseCacheKey poseCacheKey, LocalPose localPose) {
        return new ClientAnimationPoseSnapshot(poseCacheKey, localPose);
    }

    /** Immutable identity of the sampled instance, model, generation, state, and sample revision. */
    public PoseCacheKey poseCacheKey() {
        return poseCacheKey;
    }

    public BlendInstanceKey instanceKey() {
        return poseCacheKey.instanceKey();
    }

    public BlendModelKey modelKey() {
        return poseCacheKey.modelKey();
    }

    public long generation() {
        return poseCacheKey.generation();
    }

    public BlendAnimationKey animationKey() {
        return poseCacheKey.animationKey();
    }

    public long sampleRevision() {
        return poseCacheKey.sampleRevision();
    }

    /** Returns the immutable local transforms sampled during extraction. */
    public LocalPose localPose() {
        return localPose;
    }

    void requireCompatible(
            BlendInstanceKey instanceKey,
            BlendModelKey modelKey,
            long generation,
            BlendAnimationKey animationKey
    ) {
        if (!poseCacheKey.instanceKey().equals(Objects.requireNonNull(instanceKey, "instanceKey"))
                || !poseCacheKey.modelKey().equals(Objects.requireNonNull(modelKey, "modelKey"))
                || poseCacheKey.generation() != generation
                || !poseCacheKey.animationKey().equals(Objects.requireNonNull(animationKey, "animationKey"))) {
            throw new IllegalArgumentException("pose snapshot does not belong to the current instance controller binding");
        }
    }
}
