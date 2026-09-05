package com.liy.blendlib.core.procedural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.AnimationV2Pose;
import com.liy.blendlib.core.animation.v2.BoneSchema;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Behavioral red-first contract for the X3 core evaluation boundary. */
class ProceduralRigRuntimeTest {
    private static final BlendModelKey MODEL = BlendModelKey.of("x3test", "rig");
    private static final BlendResourceId SOCKET = BlendResourceId.of("x3test", "tip");
    private static final BlendResourceId OFFSET = BlendResourceId.of("x3test", "offset");
    private static final BlendResourceId LOOK = BlendResourceId.of("x3test", "look");

    @Test
    void frozenRigAppliesOffsetThenConstrainedLookAtAndPublishesSocketFromFinalPose() {
        BoneSchema schema = new BoneSchema(
                List.of("root", "head"),
                List.of(Transform.IDENTITY, new Transform(new Vec3(0.0F, 1.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE)));
        ProceduralRigPlan plan = new ProceduralRigPlan(
                MODEL,
                7L,
                schema,
                new int[] {-1, 0},
                List.of(new ProceduralSocketDefinition(SOCKET, 1, Transform.IDENTITY)),
                List.of(),
                List.of(),
                List.of());
        ModelInstance instance = new ModelInstance(BlendInstanceKey.ephemeral("session", "actor"), MODEL, 7L);
        AnimationV2EvaluationSnapshot evaluation = new AnimationV2EvaluationSnapshot(
                11L,
                new AnimationV2Pose(List.of(
                        Transform.IDENTITY,
                        new Transform(new Vec3(0.0F, 1.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE))),
                Map.of(),
                List.of());

        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan, instance);
        ProceduralEvaluationResult result = runtime.evaluate(new ProceduralFrameInput(
                instance,
                evaluation,
                List.of(
                        new ProceduralDirective(LOOK, 10, new ProceduralOperation.LookAt(
                                1, new Vec3(4.0F, 1.0F, 4.0F), (float) (Math.PI / 4.0D), (float) (Math.PI / 4.0D))),
                        new ProceduralDirective(OFFSET, 0, new ProceduralOperation.Offset(
                                1, new Vec3(1.0F, 0.0F, 0.0F), Quaternion.IDENTITY, 1.0F))),
                List.of()));

        assertTrue(result.published());
        ProceduralFrameSnapshot snapshot = result.snapshot();
        assertEquals(1.0F, snapshot.localPose().transform(1).translation().x(), 0.00001F);
        assertEquals(1.0F, snapshot.socketTransform(SOCKET).orElseThrow().transform().translation().y(), 0.00001F);
        Vec3 forward = snapshot.localPose().transform(1).rotation().rotate(new Vec3(0.0F, 0.0F, 1.0F));
        assertTrue(forward.x() > 0.5F, "look-at must turn canonical +Z toward +X");
    }
}
