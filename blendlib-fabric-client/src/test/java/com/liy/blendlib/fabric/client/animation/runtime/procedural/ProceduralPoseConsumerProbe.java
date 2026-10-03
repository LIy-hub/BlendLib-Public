package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseModifier;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigView;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigViewTestAccess;
import java.util.List;
import java.util.Map;

/** Executable consumer example requiring no Minecraft runtime or test framework. */
public final class ProceduralPoseConsumerProbe {
    private ProceduralPoseConsumerProbe() { }

    public static void main(String[] args) {
        ClientAnimationRigView rig = ClientAnimationRigViewTestAccess.fromNodes(List.of(
                new ModelNode(0, "head", Transform.IDENTITY, List.of(1), -1, -1, false),
                new ModelNode(1, "tip", Transform.IDENTITY, List.of(), -1, -1, false)));
        LocalPose rest = new LocalPose(Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY));
        var spring = new SpringInertiaPoseComponent("head", 2, 2);
        var pipeline = ProceduralPosePipeline.of(
                new LookAtPoseComponent("head", new Vec3(0, 0, 1), 1, c -> new Vec3(1, 0, 0)),
                spring,
                new ChainFollowPoseComponent(List.of("head", "tip"), 0.5f),
                new RotationLimitPoseComponent("head", Quaternion.IDENTITY, (float)Math.PI / 4));
        // Any component or pipeline is directly usable by the existing poseModifier contract.
        ClientAnimationPoseModifier modifier = pipeline;
        LocalPose result = modifier.modify(context(rig, 1, 1, 0), rest);
        require(Math.abs(angle(result, 0) - Math.PI / 4) < 1e-5, "pipeline limit/order");
        require(Math.abs(angle(result, 1) - Math.PI / 4) < 1e-5, "chain following");
        require(rest.transform(0).equals(Transform.IDENTITY), "base immutability");
        pipeline.reset();
        require(spring.retainedInstanceCount() == 0, "reset propagation");
        spring.modify(context(rig, 1, 1, 0), rest);
        LocalPose goal = new LookAtPoseComponent("head", new Vec3(0, 0, 1), 1, c -> new Vec3(1, 0, 0))
                .modify(context(rig, 1, 1, 1), rest);
        LocalPose moving = spring.modify(context(rig, 1, 1, 1), goal);
        require(angle(moving, 0) > 0 && angle(moving, 0) < Math.PI / 2, "spring inertia");
        require(moving.transform(0).equals(spring.modify(context(rig, 1, 1, 1), goal).transform(0)), "same-time idempotence");
        require(spring.modify(context(rig, 2, 1, 1), goal) == goal, "instance isolation");
        for (int ticks = 2; ticks <= 100; ticks++) moving = spring.modify(context(rig, 1, 1, ticks), goal);
        require(Math.abs(angle(moving, 0) - Math.PI / 2) < 1e-5, "spring convergence");
        require(spring.modify(context(rig, 1, 2, 101), rest) == rest, "generation reset");
        require(spring.modify(context(rig, 1, 2, 99), goal) == goal, "backward-clock reset");
        require(spring.modify(context(rig, 1, 2, 120), rest) == rest, "long-gap reset");
        spring.modify(context(rig, 3, 1, 1), rest);
        require(spring.retainedInstanceCount() == 2, "bounded retention");
        System.out.println("Procedural pose consumer probe passed");
    }

    private static double angle(LocalPose pose, int index) {
        return 2 * Math.acos(Math.min(1, Math.abs(pose.transform(index).rotation().w())));
    }

    private static ClientAnimationPoseContext context(ClientAnimationRigView rig, int instance, long generation, double ticks) {
        return new ClientAnimationPoseContext(BlendInstanceKey.entity("consumer", instance),
                BlendModelKey.of("example", "rig"), generation, BlendAnimationKey.of("example", "idle"), 0, ticks, rig);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
