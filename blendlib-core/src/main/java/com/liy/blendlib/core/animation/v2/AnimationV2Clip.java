package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable complete-pose clip used only by the Experimental/internal v2 runtime. */
public final class AnimationV2Clip {
    private final List<AnimationV2Keyframe> keyframes;
    private final int boneCount;
    private final double durationSeconds;
    private final com.liy.blendlib.core.animation.runtime.PoseSampler nativeSampler;
    private final com.liy.blendlib.core.animation.runtime.AnimationState nativeState;
    private final List<Integer> nativeNodes;

    /** Adapts a loaded strict-v1 clip without baking/resampling its original interpolation. */
    public static AnimationV2Clip fromState(
            com.liy.blendlib.core.animation.runtime.PoseSampler sampler,
            com.liy.blendlib.core.animation.runtime.AnimationState state,
            List<Integer> nodeIndices) {
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(state, "state");
        List<Integer> nodes = List.copyOf(nodeIndices);
        List<Transform> zero = nodes.stream().map(i -> sampler.sampleNode(state, 0.0D, i)).toList();
        List<AnimationV2Keyframe> keys = new ArrayList<>();
        keys.add(new AnimationV2Keyframe(0.0D, new AnimationV2Pose(zero)));
        double duration = state.clip().durationSeconds();
        if (duration > 0.0D) keys.add(new AnimationV2Keyframe(duration,
                new AnimationV2Pose(nodes.stream().map(i -> sampler.sampleNode(state, duration, i)).toList())));
        return new AnimationV2Clip(keys, sampler, state, nodes);
    }

    public AnimationV2Clip(List<AnimationV2Keyframe> keyframes) {
        this(keyframes, null, null, List.of());
    }

    private AnimationV2Clip(List<AnimationV2Keyframe> keyframes,
            com.liy.blendlib.core.animation.runtime.PoseSampler sampler,
            com.liy.blendlib.core.animation.runtime.AnimationState state, List<Integer> nodes) {
        this.nativeSampler = sampler;
        this.nativeState = state;
        this.nativeNodes = nodes;
        Objects.requireNonNull(keyframes, "keyframes");
        if (keyframes.isEmpty() || keyframes.size() > AnimationV2Limits.MAX_KEYFRAMES_PER_CLIP) {
            throw new IllegalArgumentException("clip keyframe count is outside supported bounds");
        }
        List<AnimationV2Keyframe> copied = new ArrayList<>(keyframes.size());
        for (AnimationV2Keyframe keyframe : keyframes) {
            copied.add(Objects.requireNonNull(keyframe, "keyframe"));
        }
        copied.sort(Comparator.comparingDouble(AnimationV2Keyframe::timeSeconds));
        if (copied.getFirst().timeSeconds() != 0.0D) {
            throw new IllegalArgumentException("the first v2 clip keyframe must be at zero seconds");
        }
        this.boneCount = copied.getFirst().pose().boneCount();
        double previous = -1.0D;
        for (AnimationV2Keyframe keyframe : copied) {
            if (keyframe.pose().boneCount() != boneCount) {
                throw new IllegalArgumentException("all clip keyframes must have identical bone counts");
            }
            if (keyframe.timeSeconds() <= previous) {
                throw new IllegalArgumentException("clip keyframe times must be strictly increasing");
            }
            previous = keyframe.timeSeconds();
        }
        if (previous > AnimationV2Limits.MAX_CLIP_DURATION_SECONDS) {
            throw new IllegalArgumentException("clip duration exceeds " + AnimationV2Limits.MAX_CLIP_DURATION_SECONDS + " seconds");
        }
        this.keyframes = List.copyOf(copied);
        this.durationSeconds = previous;
    }

    public int boneCount() {
        return boneCount;
    }

    public double durationSeconds() {
        return durationSeconds;
    }

    public List<AnimationV2Keyframe> keyframes() {
        return keyframes;
    }

    /** Samples a finite, already-normalized state time without asset lookup or parsing. */
    public AnimationV2Pose sample(double timeSeconds) {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0D) {
            throw new IllegalArgumentException("sample time must be finite and non-negative");
        }
        Transform[] sampled = new Transform[boneCount];
        AnimationV2TransformScratch scratch = new AnimationV2TransformScratch();
        for (int bone = 0; bone < boneCount; bone++) {
            sampleIntoUnchecked(timeSeconds, bone, scratch);
            sampled[bone] = scratch.toTransform();
        }
        return AnimationV2Pose.takeOwnership(sampled);
    }

    /** Samples one bone into caller-owned scratch without allocating a full temporary pose. */
    void sampleInto(double timeSeconds, int boneIndex, AnimationV2TransformScratch target) {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0D) {
            throw new IllegalArgumentException("sample time must be finite and non-negative");
        }
        if (boneIndex < 0 || boneIndex >= boneCount) {
            throw new IllegalArgumentException("bone index is outside clip bounds: " + boneIndex);
        }
        sampleIntoUnchecked(timeSeconds, boneIndex, Objects.requireNonNull(target, "target"));
    }

    void sampleIntoUnchecked(double timeSeconds, int boneIndex, AnimationV2TransformScratch target) {
        if (nativeSampler != null) {
            target.set(nativeSampler.sampleNode(nativeState, Math.min(timeSeconds, durationSeconds), nativeNodes.get(boneIndex)));
            return;
        }
        if (timeSeconds <= 0.0D || keyframes.size() == 1) {
            target.set(keyframes.getFirst().pose().transform(boneIndex));
            return;
        }
        if (timeSeconds >= durationSeconds) {
            target.set(keyframes.getLast().pose().transform(boneIndex));
            return;
        }
        int low = 0;
        int high = keyframes.size() - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (keyframes.get(middle).timeSeconds() <= timeSeconds) {
                low = middle;
            } else {
                high = middle;
            }
        }
        AnimationV2Keyframe left = keyframes.get(low);
        AnimationV2Keyframe right = keyframes.get(high);
        double amount = (timeSeconds - left.timeSeconds()) / (right.timeSeconds() - left.timeSeconds());
        target.setInterpolated(left.pose().transform(boneIndex), right.pose().transform(boneIndex), amount);
    }
}
