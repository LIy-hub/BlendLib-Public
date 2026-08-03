package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, reusable sampler prepared from immutable node data once per asset generation.
 *
 * <p>Sampling only consumes compiled key frames and immutable model data. It does
 * not discover assets, parse data, or access platform state.</p>
 */
public final class PoseSampler {
    private final List<ModelNode> nodes;
    private final Map<Integer, Transform> baseTransforms;

    public PoseSampler(List<ModelNode> nodes) {
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        LinkedHashMap<Integer, Transform> bases = new LinkedHashMap<>();
        for (ModelNode node : this.nodes) {
            if (bases.putIfAbsent(node.index(), node.localTransform()) != null) {
                throw new IllegalArgumentException("Model nodes must have unique indices: " + node.index());
            }
        }
        this.baseTransforms = Collections.unmodifiableMap(bases);
    }

    /** Creates a sampler from the frozen node data in one loaded model asset. */
    public static PoseSampler fromModelAsset(ModelAsset asset) {
        return new PoseSampler(Objects.requireNonNull(asset, "asset").nodes());
    }

    public List<ModelNode> nodes() {
        return nodes;
    }

    /** Samples one state at its already-normalized local clip time. */
    public LocalPose sample(AnimationState state, double timeSeconds) {
        Objects.requireNonNull(state, "state");
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Sample time must be finite and non-negative");
        }
        LinkedHashMap<Integer, MutableTransform> mutable = new LinkedHashMap<>();
        for (Map.Entry<Integer, Transform> entry : baseTransforms.entrySet()) {
            mutable.put(entry.getKey(), new MutableTransform(entry.getValue()));
        }
        for (CompiledAnimationChannel channel : state.compiledClip().channels()) {
            MutableTransform target = mutable.get(channel.targetNode());
            if (target == null) {
                throw new IllegalArgumentException("Animation channel targets an absent model node: " + channel.targetNode());
            }
            channel.apply(timeSeconds, target);
        }
        LinkedHashMap<Integer, Transform> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, MutableTransform> entry : mutable.entrySet()) {
            result.put(entry.getKey(), entry.getValue().freeze());
        }
        return new LocalPose(result);
    }

    /** Blends two complete local poses with v1 linear-vector and normalized-slerp semantics. */
    public LocalPose blend(LocalPose previous, LocalPose current, double amount) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (!Double.isFinite(amount) || amount < 0.0 || amount > 1.0) {
            throw new IllegalArgumentException("Blend amount must be finite and in [0, 1]");
        }
        if (!previous.transforms().keySet().equals(current.transforms().keySet())) {
            throw new IllegalArgumentException("Only poses with identical node sets can be blended");
        }
        float factor = (float) amount;
        LinkedHashMap<Integer, Transform> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Transform> entry : previous.transforms().entrySet()) {
            Transform left = entry.getValue();
            Transform right = current.transform(entry.getKey());
            result.put(entry.getKey(), new Transform(
                    lerp(left.translation(), right.translation(), factor),
                    Quaternion.slerp(left.rotation(), right.rotation(), factor),
                    lerp(left.scale(), right.scale(), factor)));
        }
        return new LocalPose(result);
    }

    private static Vec3 lerp(Vec3 left, Vec3 right, float amount) {
        return new Vec3(
                finite((double) left.x() + amount * (right.x() - left.x())),
                finite((double) left.y() + amount * (right.y() - left.y())),
                finite((double) left.z() + amount * (right.z() - left.z())));
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException("Pose interpolation produced a non-finite component");
        }
        return result;
    }
}
