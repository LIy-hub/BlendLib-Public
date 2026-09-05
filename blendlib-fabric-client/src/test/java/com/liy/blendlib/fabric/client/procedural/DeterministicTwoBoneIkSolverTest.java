package com.liy.blendlib.fabric.client.procedural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.procedural.ProceduralRigPlan;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.procedural.ProceduralFrameInput;
import com.liy.blendlib.core.procedural.ProceduralOperation;
import org.junit.jupiter.api.Test;

class DeterministicTwoBoneIkSolverTest {
    private static final DeterministicTwoBoneIkSolver SOLVER = new DeterministicTwoBoneIkSolver(
            BlendResourceId.of("x3client", "two-bone"));

    @Test
    void reachableTargetProducesExactlyOneDeterministicAnalyticalPassAndControlledDirectives() {
        ProceduralClientTestFixtures.Harness harness = ProceduralClientTestFixtures.chain(1.0F, 1.0F);
        ClientIkRequest request = ProceduralClientTestFixtures.request(
                harness, new Vec3(1.0F, 0.0F, 1.5F), new Vec3(0.0F, 1.0F, 0.0F));

        ClientIkResult first = SOLVER.solve(request);
        ClientIkResult second = SOLVER.solve(request);

        assertEquals(ClientIkStatus.REACHED, first.status());
        assertEquals(1, first.iterations());
        assertEquals(first, second);
        assertEquals(2, first.directives().size());
        assertTrue(first.directives().stream()
                .allMatch(value -> value.operation() instanceof ProceduralOperation.RotationOffset));
        var applied = harness.runtime().evaluate(new ProceduralFrameInput(
                harness.instance(), nextRevision(harness.evaluation()), first.directives(), java.util.List.of()));
        assertTrue(applied.published());
        Vec3 reachedEnd = applied.snapshot().modelTransforms().get(2).translation();
        assertEquals(first.effectiveTargetModelSpace().x(), reachedEnd.x(), 0.0001F);
        assertEquals(first.effectiveTargetModelSpace().y(), reachedEnd.y(), 0.0001F);
        assertEquals(first.effectiveTargetModelSpace().z(), reachedEnd.z(), 0.0001F);
    }

    @Test
    void farNearAndPoleSingularityTakeFiniteExplicitFallbackPaths() {
        ProceduralClientTestFixtures.Harness equalChain = ProceduralClientTestFixtures.chain(1.0F, 1.0F);
        ClientIkResult far = SOLVER.solve(ProceduralClientTestFixtures.request(
                equalChain, new Vec3(0.0F, 0.0F, 10.0F), new Vec3(0.0F, 1.0F, 0.0F)));
        assertEquals(ClientIkStatus.CLAMPED_FAR, far.status());
        assertTrue(far.effectiveTargetModelSpace().length() < 2.0F);

        ProceduralClientTestFixtures.Harness unequalChain = ProceduralClientTestFixtures.chain(2.0F, 1.0F);
        ClientIkResult near = SOLVER.solve(ProceduralClientTestFixtures.request(
                unequalChain, new Vec3(0.0F, 0.0F, 0.2F), new Vec3(0.0F, 1.0F, 0.0F)));
        assertEquals(ClientIkStatus.CLAMPED_NEAR, near.status());
        assertTrue(near.effectiveTargetModelSpace().length() > 0.9F);

        ClientIkRequest singular = ProceduralClientTestFixtures.request(
                equalChain, new Vec3(0.0F, 0.0F, 1.5F), new Vec3(0.0F, 0.0F, 4.0F));
        ClientIkResult fallback = SOLVER.solve(singular);
        assertEquals(ClientIkStatus.DEGENERATE, fallback.status());
        assertEquals(fallback, SOLVER.solve(singular));
        assertFalse(fallback.directives().isEmpty());
    }

    @Test
    void invalidChainAndUncheckedResultShapesAreRejectedBeforeTheyCanInfluenceAFrame() {
        ProceduralClientTestFixtures.Harness harness = ProceduralClientTestFixtures.chain(1.0F, 1.0F);
        ClientIkRequest valid = ProceduralClientTestFixtures.request(
                harness, new Vec3(0.0F, 0.0F, 1.5F), new Vec3(0.0F, 1.0F, 0.0F));
        ClientIkRequest invalidEnd = ClientIkRequest.forRig(valid.rig(), 0, 1, 8,
                valid.targetModelSpace(), valid.poleModelSpace(), valid.priority());
        assertEquals(ClientIkStatus.REJECTED, SOLVER.solve(invalidEnd).status());
        assertThrows(IllegalArgumentException.class, () -> ClientIkRequest.forRig(valid.rig(), 0, 0, 2,
                valid.targetModelSpace(), valid.poleModelSpace(), valid.priority()));
        assertThrows(IllegalArgumentException.class, () -> ClientIkResult.solved(ClientIkStatus.REACHED,
                Vec3.ZERO, Vec3.ZERO, 0, 1, java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> ClientIkResult.solved(ClientIkStatus.REACHED,
                Vec3.ZERO, Vec3.ZERO, 0, 1, java.util.List.of(rotationDirective(0))));
        assertThrows(IllegalArgumentException.class, () -> ClientIkResult.solved(ClientIkStatus.REACHED,
                Vec3.ZERO, Vec3.ZERO, 0, 1, java.util.List.of(rotationDirective(0), rotationDirective(1), rotationDirective(1))));
        assertThrows(IllegalArgumentException.class, () -> ClientIkResult.solved(ClientIkStatus.REACHED,
                Vec3.ZERO, Vec3.ZERO, 0, 1, java.util.List.of(
                        new com.liy.blendlib.core.procedural.ProceduralDirective(
                                BlendResourceId.of("x3client", "bad"), 0,
                                new ProceduralOperation.BoneVisibility(0, false)),
                        new com.liy.blendlib.core.procedural.ProceduralDirective(
                                BlendResourceId.of("x3client", "bad"), 0,
                                new ProceduralOperation.RotationOffset(1,
                                        com.liy.blendlib.core.model.Quaternion.IDENTITY)))));
    }

    @Test
    void ikRejectsUnrelatedBonesAndHidesResultConstruction() {
        ProceduralClientTestFixtures.Harness unrelated = ProceduralClientTestFixtures.unrelatedBones();
        ClientIkRequest forged = ClientIkRequest.forRig(
                ClientIkRigSnapshot.capture(unrelated.plan(), unrelated.snapshot()), 0, 1, 2,
                new Vec3(1.0F, 0.0F, 1.5F), new Vec3(0.0F, 1.0F, 0.0F), 5);

        ClientIkResult result = SOLVER.solve(forged);

        assertEquals(ClientIkStatus.REJECTED, result.status(),
                "unrelated roots must never be accepted as a fabricated two-bone chain");
        assertEquals(0, result.directives().size());
        assertEquals(0, ClientIkResult.class.getConstructors().length,
                "callers must not construct a result carrying arbitrary non-rotation directives");
    }

    @Test
    void ikCaptureRejectsASnapshotFromADifferentSameModelGenerationAndBoneCountPlan() {
        ProceduralClientTestFixtures.Harness forked = ProceduralClientTestFixtures.forkedHierarchy();
        ProceduralRigPlan unrelatedDirectPlan = new ProceduralRigPlan(
                ProceduralClientTestFixtures.MODEL,
                7L,
                forked.plan().schema(),
                new int[] {-1, 0, 1},
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());

        assertThrows(IllegalArgumentException.class,
                () -> ClientIkRigSnapshot.capture(unrelatedDirectPlan, forked.snapshot()),
                "same model/generation/cardinality does not prove an X3 snapshot came from this hierarchy plan");
    }

    private static AnimationV2EvaluationSnapshot nextRevision(AnimationV2EvaluationSnapshot prior) {
        return new AnimationV2EvaluationSnapshot(prior.revision() + 1L, prior.pose(), prior.playheads(), prior.diagnostics());
    }

    private static com.liy.blendlib.core.procedural.ProceduralDirective rotationDirective(int bone) {
        return new com.liy.blendlib.core.procedural.ProceduralDirective(BlendResourceId.of("x3client", "test-rotation"), 0,
                new ProceduralOperation.RotationOffset(bone, com.liy.blendlib.core.model.Quaternion.IDENTITY));
    }
}
