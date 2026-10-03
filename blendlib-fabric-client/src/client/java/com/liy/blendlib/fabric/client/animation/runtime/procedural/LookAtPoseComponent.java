package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Aims a local forward axis at a model-space target using the shortest rotation (no roll constraint). */
public final class LookAtPoseComponent implements ProceduralPoseComponent {
    private final String nodeName;
    private final Vec3 localForward;
    private final float weight;
    private final Function<ClientAnimationPoseContext, Vec3> target;

    public LookAtPoseComponent(String nodeName, Vec3 localForward, float weight,
            Function<ClientAnimationPoseContext, Vec3> modelSpaceTarget) {
        this.nodeName = PoseRotationMath.nodeName(nodeName);
        this.localForward = Objects.requireNonNull(localForward, "localForward").normalized();
        this.weight = PoseRotationMath.weight(weight);
        this.target = Objects.requireNonNull(modelSpaceTarget, "modelSpaceTarget");
    }

    @Override
    public LocalPose modify(ClientAnimationPoseContext context, LocalPose basePose) {
        int node = context.rig().requireNodeIndex(nodeName);
        Transform global = PoseRotationMath.global(basePose, context.rig(), node);
        Vec3 direction = Objects.requireNonNull(target.apply(context), "target").subtract(global.translation());
        if (weight == 0 || direction.length() < 1e-8f) return basePose;
        Quaternion delta = PoseRotationMath.between(global.rotation().rotate(localForward), direction);
        Quaternion desiredGlobal = delta.multiply(global.rotation());
        var parent = context.rig().parentIndex(node);
        Quaternion parentRotation = parent.isPresent()
                ? PoseRotationMath.global(basePose, context.rig(), parent.getAsInt()).rotation() : Quaternion.IDENTITY;
        Quaternion desiredLocal = PoseRotationMath.inverse(parentRotation).multiply(desiredGlobal);
        return PoseRotationMath.replace(basePose, Map.of(node,
                Quaternion.slerp(basePose.transform(node).rotation(), desiredLocal, weight)));
    }
}
