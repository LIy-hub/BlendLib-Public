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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Executable standard-IK consumer example requiring no Minecraft runtime or test framework. */
public final class TwoBoneIkConsumerProbe {
    private TwoBoneIkConsumerProbe() { }

    public static void main(String[] args) {
        Transform segment = new Transform(new Vec3(1, 0, 0), Quaternion.IDENTITY, Vec3.ONE);
        ClientAnimationRigView rig = ClientAnimationRigViewTestAccess.fromNodes(List.of(
                new ModelNode(0, "upper", Transform.IDENTITY, List.of(1), -1, -1, false),
                new ModelNode(1, "lower", segment, List.of(2), -1, -1, false),
                new ModelNode(2, "hand", segment, List.of(), -1, -1, false),
                new ModelNode(3, "unrelated", Transform.IDENTITY, List.of(), -1, -1, false)));
        LocalPose incoming = new LocalPose(Map.of(
                0, Transform.IDENTITY, 1, segment, 2, segment, 3, Transform.IDENTITY));
        ClientAnimationPoseContext context = new ClientAnimationPoseContext(
                BlendInstanceKey.entity("ik-consumer", 1), BlendModelKey.of("example", "rig"), 1,
                BlendAnimationKey.of("example", "idle"), 0, 0, rig);
        TwoBoneIkTarget requested = new TwoBoneIkTarget(new Vec3(1, 1, 0), new Vec3(0, 0, 1));
        var currentTarget = new AtomicReference<>(requested);
        Function<ClientAnimationPoseContext, TwoBoneIkTarget> target = ignored -> currentTarget.get();
        var diagnostics = new ArrayList<TwoBoneIkPoseComponent.Result>();
        BiConsumer<ClientAnimationPoseContext, TwoBoneIkPoseComponent.Result> observer = (observed, result) -> {
            require(observed == context, "observer receives the extraction context");
            diagnostics.add(result);
        };

        // Both constructors work through the existing modifier/component contracts.
        ClientAnimationPoseModifier simple = new TwoBoneIkPoseComponent("upper", "lower", "hand", target);
        ProceduralPoseComponent component = new TwoBoneIkPoseComponent("upper", "lower", "hand", target, observer);
        ClientAnimationPoseModifier modifier = component;
        LocalPose solved = modifier.modify(context, incoming);
        require(solved.transforms().equals(simple.modify(context, incoming).transforms()), "constructor equivalence");
        require(close(endpoint(solved), requested.targetModelSpace()), "full-weight endpoint reaches target");
        require(diagnostics.size() == 1, "one diagnostic for one invocation");
        TwoBoneIkPoseComponent.Result first = diagnostics.getFirst();
        require(first.status() == TwoBoneIkPoseComponent.Status.REACHED, "reachable status");
        require(close(first.effectiveTargetModelSpace(), requested.targetModelSpace()), "effective model-space target");
        require(!first.usedDirectionFallback() && !first.usedPoleFallback(), "ordinary solve needs no fallback");
        require(first.equals(new TwoBoneIkPoseComponent.Result(
                TwoBoneIkPoseComponent.Status.REACHED, first.effectiveTargetModelSpace(), false, false)),
                "public immutable diagnostic value");
        require(requested.equals(new TwoBoneIkTarget(requested.targetModelSpace(), requested.poleModelSpace())),
                "public immutable target value");
        require(incoming.transform(0).equals(Transform.IDENTITY), "incoming pose remains immutable");
        for (int index : incoming.transforms().keySet()) {
            require(solved.transform(index).translation().equals(incoming.transform(index).translation()),
                    "local translations are preserved");
            require(solved.transform(index).scale().equals(incoming.transform(index).scale()), "local scales are preserved");
        }
        require(solved.transform(2).equals(incoming.transform(2)), "end local transform is preserved");
        require(solved.transform(3).equals(incoming.transform(3)), "unrelated node is preserved");

        var weighted = new WeightedPoseComponent(component, ignored -> 0.5, Map.of("upper", 1F, "lower", 1F));
        LocalPose blended = weighted.modify(context, incoming);
        for (int index : List.of(0, 1)) {
            Quaternion expected = Quaternion.slerp(incoming.transform(index).rotation(), solved.transform(index).rotation(), 0.5F);
            require(sameRotation(blended.transform(index).rotation(), expected), "weighted local-rotation composition");
        }
        require(blended.transform(2).equals(incoming.transform(2)), "weighted mask leaves end unchanged");
        require(diagnostics.size() == 2 && first.equals(diagnostics.getLast()), "diagnostics describe full-weight solve");
        var disabled = new WeightedPoseComponent(component, ignored -> 0);
        require(disabled.modify(context, incoming) == incoming, "zero weight preserves the incoming object");
        require(diagnostics.size() == 3, "zero weight still evaluates the component");

        component.reset(context.instanceKey());
        component.reset();
        weighted.reset(context.instanceKey());
        weighted.reset();
        require(modifier.modify(context, incoming).transforms().equals(solved.transforms()), "stateless lifecycle resets");

        currentTarget.set(new TwoBoneIkTarget(new Vec3(5, 0, 0), requested.poleModelSpace()));
        LocalPose clamped = modifier.modify(context, incoming);
        TwoBoneIkPoseComponent.Result latest = diagnostics.getLast();
        require(latest.status() == TwoBoneIkPoseComponent.Status.CLAMPED_FAR, "unreachable target status");
        require(close(endpoint(clamped), latest.effectiveTargetModelSpace()), "clamped endpoint matches diagnostics");
        require(first.status() == TwoBoneIkPoseComponent.Status.REACHED
                && first.effectiveTargetModelSpace().equals(requested.targetModelSpace()),
                "retained diagnostics are immutable snapshots");
        require(TwoBoneIkPoseComponent.Status.valueOf("CLAMPED_NEAR") == TwoBoneIkPoseComponent.Status.CLAMPED_NEAR,
                "near status is public");
        require(List.of(TwoBoneIkPoseComponent.Status.values()).contains(TwoBoneIkPoseComponent.Status.DEGENERATE_CHAIN),
                "degenerate status is public");
        System.out.println("Two-bone IK consumer probe passed");
    }

    private static Vec3 endpoint(LocalPose pose) {
        return pose.transform(0).compose(pose.transform(1)).compose(pose.transform(2)).translation();
    }

    private static boolean close(Vec3 actual, Vec3 expected) {
        return actual.subtract(expected).length() < 1e-4F;
    }

    private static boolean sameRotation(Quaternion actual, Quaternion expected) {
        double dot = actual.x() * expected.x() + actual.y() * expected.y()
                + actual.z() * expected.z() + actual.w() * expected.w();
        return Math.abs(Math.abs(dot) - 1) < 1e-5;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
