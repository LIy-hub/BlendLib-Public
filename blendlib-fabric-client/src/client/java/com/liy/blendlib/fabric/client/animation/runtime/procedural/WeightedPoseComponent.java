package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Blends a component's local rotations against its incoming pose using an extraction-time weight.
 * Empty node weights select all nodes; otherwise unlisted nodes contribute nothing. Weights multiply.
 * The delegate runs once even at zero weight, so stateful components continue tracking their input.
 * Complete delegate output is validated before masking; lifecycle resets are forwarded.
 */
public final class WeightedPoseComponent implements ProceduralPoseComponent {
    private final ProceduralPoseComponent component;
    private final ToDoubleFunction<ClientAnimationPoseContext> weight;
    private final Map<String, Float> nodeWeights;

    /** Creates an all-node wrapper with a finite dynamic weight in [0,1]. */
    public WeightedPoseComponent(ProceduralPoseComponent component,
            ToDoubleFunction<ClientAnimationPoseContext> weight) {
        this(component, weight, Map.of());
    }

    /** Copies a named local-node mask; each value must be finite and in [0,1]. */
    public WeightedPoseComponent(ProceduralPoseComponent component,
            ToDoubleFunction<ClientAnimationPoseContext> weight, Map<String, Float> nodeWeights) {
        this.component = Objects.requireNonNull(component, "component");
        this.weight = Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(nodeWeights, "nodeWeights").forEach((name, value) -> {
            PoseRotationMath.nodeName(name);
            PoseRotationMath.weight(Objects.requireNonNull(value, "node weight"));
        });
        this.nodeWeights = Map.copyOf(nodeWeights);
    }

    @Override
    public LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(basePose, "basePose");
        if (!context.rig().nodeIndices().equals(basePose.transforms().keySet())) {
            throw new IllegalArgumentException("Weighted component requires the exact rig node set");
        }
        double amount = weight.applyAsDouble(context);
        if (!Double.isFinite(amount) || amount < 0 || amount > 1) {
            throw new IllegalArgumentException("weight must be finite and in [0, 1]");
        }
        Map<Integer, Float> mask = new LinkedHashMap<>();
        nodeWeights.forEach((name, value) -> mask.put(context.rig().requireNodeIndex(name), value));
        LocalPose result = Objects.requireNonNull(component.modify(context, basePose), "component result");
        if (!result.transforms().keySet().equals(basePose.transforms().keySet())) {
            throw new IllegalArgumentException("Weighted component must preserve the exact pose node set");
        }
        // Validate every node, including masked-out nodes and zero-weight invocations.
        basePose.transforms().forEach((node, before) -> {
            var after = result.transform(node);
            if (!before.translation().equals(after.translation()) || !before.scale().equals(after.scale())) {
                throw new IllegalArgumentException("Weighted component must preserve translation and scale: " + node);
            }
        });
        if (amount == 0) return basePose;
        Map<Integer, Quaternion> rotations = new LinkedHashMap<>();
        basePose.transforms().forEach((node, before) -> {
            float contribution = (float) (amount * (nodeWeights.isEmpty() ? 1F : mask.getOrDefault(node, 0F)));
            var target = result.transform(node).rotation();
            if (contribution > 0 && !before.rotation().equals(target)) {
                rotations.put(node, contribution == 1 ? target : Quaternion.slerp(before.rotation(), target, contribution));
            }
        });
        return rotations.isEmpty() ? basePose : PoseRotationMath.replace(basePose, rotations);
    }

    @Override public void reset() { component.reset(); }
    @Override public void reset(BlendInstanceKey instanceKey) { component.reset(instanceKey); }
}
