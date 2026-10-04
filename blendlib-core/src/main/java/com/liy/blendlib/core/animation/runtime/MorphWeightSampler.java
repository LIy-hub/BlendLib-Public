package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.MorphBindingTable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Generation-owned sampler retaining immutable channels without per-state or per-instance array copies. */
public final class MorphWeightSampler {
    private final MorphBindingTable bindings;
    private final Set<AnimationClip> clips;

    private MorphWeightSampler(ModelAsset asset) {
        bindings = asset.morphBindings();
        Set<AnimationClip> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        retained.addAll(asset.clips());
        clips = Collections.unmodifiableSet(retained);
    }
    public static MorphWeightSampler fromModelAsset(ModelAsset asset) { return new MorphWeightSampler(Objects.requireNonNull(asset, "asset")); }
    public MorphBindingTable bindings() { return bindings; }
    public MorphWeights defaults() { return MorphWeights.defaults(bindings); }
    public MorphWeights sample(AnimationState state, double timeSeconds) { return sample(Objects.requireNonNull(state, "state").clip(), timeSeconds); }
    public MorphWeights sample(AnimationClip clip, double timeSeconds) {
        if (!clips.contains(Objects.requireNonNull(clip, "clip"))) throw new IllegalArgumentException("Clip belongs to a different morph generation");
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0) throw new IllegalArgumentException("Morph time must be finite and non-negative");
        float[] sampled = MorphWeights.defaultValues(bindings);
        for (var channel : clip.morphChannels()) {
            var binding = bindings.binding(channel.targetNode());
            if (binding == null || binding.targetCount() != channel.targetCount())
                throw new IllegalArgumentException("Morph channel does not match its generation binding");
            int low = 0, high = channel.keyCount() - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (channel.keyTime(middle) <= timeSeconds) low = middle; else high = middle - 1;
            }
            int left = low;
            int right = Math.min(left + 1, channel.keyCount() - 1);
            double amount = timeSeconds <= channel.keyTime(left) || left == right || channel.interpolation() == Interpolation.STEP
                    ? 0 : (timeSeconds - channel.keyTime(left)) / (channel.keyTime(right) - channel.keyTime(left));
            for (int target = 0; target < binding.targetCount(); target++) {
                sampled[binding.offset() + target] = amount == 0 ? channel.keyValue(left, target)
                        : (float) ((1 - amount) * channel.keyValue(left, target) + amount * channel.keyValue(right, target));
            }
        }
        return MorphWeights.takeOwnership(bindings, sampled);
    }
}
