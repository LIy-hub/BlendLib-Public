package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.util.Map;
import java.util.Objects;

/** Constrains total shortest-arc angular distance from an explicit local reference rotation. */
public final class RotationLimitPoseComponent implements ProceduralPoseComponent {
    private final String nodeName;
    private final Quaternion reference;
    private final float maximumRadians;

    public RotationLimitPoseComponent(String nodeName, Quaternion reference, float maximumRadians) {
        this.nodeName = PoseRotationMath.nodeName(nodeName);
        this.reference = Objects.requireNonNull(reference, "reference").normalized();
        if (!Float.isFinite(maximumRadians) || maximumRadians < 0 || maximumRadians > (float) Math.PI) {
            throw new IllegalArgumentException("maximumRadians must be finite and in [0, pi]");
        }
        this.maximumRadians = maximumRadians;
    }

    @Override
    public LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose) {
        int node = context.rig().requireNodeIndex(nodeName);
        Vec3 delta = PoseRotationMath.log(PoseRotationMath.inverse(reference).multiply(basePose.transform(node).rotation()));
        float angle = delta.length();
        if (angle <= maximumRadians) return basePose;
        Quaternion limited = reference.multiply(PoseRotationMath.exp(delta.multiply(maximumRadians / angle)));
        return PoseRotationMath.replace(basePose, Map.of(node, limited));
    }
}
