package com.liy.blendlib.fabric.client.animation.runtime.procedural;

import static org.junit.jupiter.api.Assertions.*;

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
import org.junit.jupiter.api.Test;

class ProceduralPoseComponentsTest {
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);
    private static final Quaternion TURN = new Quaternion(0, 0.70710677f, 0, 0.70710677f);
    private static final ClientAnimationRigView RIG = ClientAnimationRigViewTestAccess.fromNodes(List.of(
            new ModelNode(0, "root", Transform.IDENTITY, List.of(1), -1, -1, false),
            new ModelNode(1, "head", Transform.IDENTITY, List.of(2), -1, -1, false),
            new ModelNode(2, "tip", Transform.IDENTITY, List.of(), -1, -1, false)));

    @Test void runsConsumerProbe() { ProceduralPoseConsumerProbe.main(new String[0]); }

    @Test void lookAtAccountsForParentRotationAndPreservesOtherChannels() {
        LocalPose base = pose(TURN, Quaternion.IDENTITY, Quaternion.IDENTITY);
        ClientAnimationPoseModifier component = new LookAtPoseComponent("head", FORWARD, 1,
                ignored -> new Vec3(0, 0, 4));
        LocalPose result = component.modify(context(1, 1, 0), base);
        Vec3 globalForward = TURN.multiply(result.transform(1).rotation()).rotate(FORWARD);
        assertVector(FORWARD, globalForward);
        assertPreserved(base, result);
        assertEquals(Quaternion.IDENTITY, base.transform(1).rotation());
    }

    @Test void lookAtHandlesOppositeAndCoincidentTargets() {
        LocalPose base = pose(Quaternion.IDENTITY, Quaternion.IDENTITY, Quaternion.IDENTITY);
        LocalPose opposite = new LookAtPoseComponent("head", FORWARD, 1,
                ignored -> new Vec3(0, 0, -1)).modify(context(1, 1, 0), base);
        assertVector(new Vec3(0, 0, -1), opposite.transform(1).rotation().rotate(FORWARD));
        assertSame(base, new LookAtPoseComponent("head", FORWARD, 1, ignored -> Vec3.ZERO)
                .modify(context(1, 1, 0), base));
    }

    @Test void pipelineRunsInOrderAndChainUsesIncomingLocalRotations() {
        LocalPose base = pose(TURN, Quaternion.IDENTITY, Quaternion.IDENTITY);
        ProceduralPosePipeline pipeline = ProceduralPosePipeline.of(
                new ChainFollowPoseComponent(List.of("root", "head", "tip"), 1),
                new RotationLimitPoseComponent("head", Quaternion.IDENTITY, (float)Math.PI / 4));
        LocalPose result = pipeline.modify(context(1, 1, 0), base);
        assertEquals(Math.PI / 4, PoseRotationMath.log(result.transform(1).rotation()).length(), 1e-5);
        assertEquals(Quaternion.IDENTITY, result.transform(2).rotation());
        assertPreserved(base, result);
        assertThrows(IllegalArgumentException.class, () -> new ChainFollowPoseComponent(List.of("root", "tip"), 1)
                .modify(context(1, 1, 0), base));
    }

    @Test void limitUsesReferenceAndTreatsQuaternionSignsEqually() {
        LocalPose base = pose(Quaternion.IDENTITY, Quaternion.IDENTITY, Quaternion.IDENTITY);
        var limit = new RotationLimitPoseComponent("head", TURN, 0);
        assertVector(TURN.rotate(FORWARD), limit.modify(context(1, 1, 0), base).transform(1).rotation().rotate(FORWARD));
        Quaternion negativeTurn = new Quaternion(-TURN.x(), -TURN.y(), -TURN.z(), -TURN.w());
        assertVector(TURN.rotate(FORWARD), new RotationLimitPoseComponent("head", negativeTurn, 0)
                .modify(context(1, 1, 0), base).transform(1).rotation().rotate(FORWARD));
    }

    @Test void springIsInstanceSafeIdempotentBoundedAndResettable() {
        var spring = new SpringInertiaPoseComponent("head", 2, 2);
        LocalPose rest = pose(Quaternion.IDENTITY, Quaternion.IDENTITY, Quaternion.IDENTITY);
        LocalPose goal = pose(Quaternion.IDENTITY, TURN, Quaternion.IDENTITY);
        assertSame(rest, spring.modify(context(1, 1, 0), rest));
        LocalPose moving = spring.modify(context(1, 1, 1), goal);
        float angle = PoseRotationMath.log(moving.transform(1).rotation()).length();
        assertTrue(angle > 0 && angle < Math.PI / 2);
        assertEquals(moving.transform(1), spring.modify(context(1, 1, 1), goal).transform(1));
        assertSame(goal, spring.modify(context(2, 1, 1), goal));
        assertPreserved(goal, moving);
        spring.reset(BlendInstanceKey.entity("procedural", 1));
        assertSame(goal, spring.modify(context(1, 1, 2), goal));
        spring.modify(context(3, 1, 2), rest);
        assertEquals(2, spring.retainedInstanceCount());
        ProceduralPosePipeline.of(spring).reset();
        assertEquals(0, spring.retainedInstanceCount());
    }

    @Test void springResetsOnReloadBackwardTimeAndLongGapAndConverges() {
        var spring = new SpringInertiaPoseComponent("head", 2, 4);
        LocalPose rest = pose(Quaternion.IDENTITY, Quaternion.IDENTITY, Quaternion.IDENTITY);
        LocalPose goal = pose(Quaternion.IDENTITY, TURN, Quaternion.IDENTITY);
        spring.modify(context(1, 1, 0), rest);
        LocalPose result = rest;
        for (int i = 1; i <= 100; i++) result = spring.modify(context(1, 1, i), goal);
        assertVector(TURN.rotate(FORWARD), result.transform(1).rotation().rotate(FORWARD));
        assertSame(rest, spring.modify(context(1, 2, 101), rest));
        assertSame(goal, spring.modify(context(1, 2, 99), goal));
        assertSame(rest, spring.modify(context(1, 2, 120), rest));
    }

    @Test void springIsFrameRateIndependentForStationarySingleAxisTarget() {
        LocalPose rest = pose(Quaternion.IDENTITY, Quaternion.IDENTITY, Quaternion.IDENTITY);
        LocalPose goal = pose(Quaternion.IDENTITY, TURN, Quaternion.IDENTITY);
        var a = new SpringInertiaPoseComponent("head", 2, 1);
        var b = new SpringInertiaPoseComponent("head", 2, 1);
        a.modify(context(1, 1, 0), rest);
        b.modify(context(1, 1, 0), rest);
        LocalPose once = a.modify(context(1, 1, 2), goal);
        b.modify(context(1, 1, 1), goal);
        LocalPose twice = b.modify(context(1, 1, 2), goal);
        assertVector(once.transform(1).rotation().rotate(FORWARD), twice.transform(1).rotation().rotate(FORWARD));
    }

    @Test void acceptsFloatPiAsUnrestrictedLimit() {
        LocalPose base = pose(TURN, TURN, TURN);
        assertSame(base, new RotationLimitPoseComponent("head", Quaternion.IDENTITY, (float)Math.PI)
                .modify(context(1, 1, 0), base));
    }

    @Test void rejectsInvalidConfigurationAndUnknownNodes() {
        assertThrows(IllegalArgumentException.class, () -> new SpringInertiaPoseComponent("head", Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new SpringInertiaPoseComponent("head", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RotationLimitPoseComponent("head", TURN, -1));
        assertThrows(IllegalArgumentException.class, () -> new ChainFollowPoseComponent(List.of("head", "head"), 1));
        assertThrows(IllegalArgumentException.class, () -> new LookAtPoseComponent("head", Vec3.ZERO, 1, c -> FORWARD));
        assertThrows(IllegalArgumentException.class, () -> new LookAtPoseComponent("head", FORWARD, Float.NaN, c -> FORWARD));
        assertThrows(IllegalArgumentException.class, () -> new RotationLimitPoseComponent("absent", TURN, 1)
                .modify(context(1, 1, 0), pose(TURN, TURN, TURN)));
    }

    private static LocalPose pose(Quaternion root, Quaternion head, Quaternion tip) {
        return new LocalPose(Map.of(0, new Transform(Vec3.ZERO, root, Vec3.ONE),
                1, new Transform(Vec3.ZERO, head, Vec3.ONE),
                2, new Transform(new Vec3(0, 0, 2), tip, new Vec3(2, 2, 2))));
    }

    private static ClientAnimationPoseContext context(int instance, long generation, double ticks) {
        return new ClientAnimationPoseContext(BlendInstanceKey.entity("procedural", instance),
                BlendModelKey.of("test", "rig"), generation, BlendAnimationKey.of("test", "idle"), 0, ticks, RIG);
    }

    private static void assertPreserved(LocalPose before, LocalPose after) {
        assertEquals(before.transforms().keySet(), after.transforms().keySet());
        before.transforms().forEach((index, transform) -> {
            assertEquals(transform.translation(), after.transform(index).translation());
            assertEquals(transform.scale(), after.transform(index).scale());
        });
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), 1e-5);
        assertEquals(expected.y(), actual.y(), 1e-5);
        assertEquals(expected.z(), actual.z(), 1e-5);
    }
}
