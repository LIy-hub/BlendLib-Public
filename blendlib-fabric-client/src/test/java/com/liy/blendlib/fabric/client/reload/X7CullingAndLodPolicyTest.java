package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class X7CullingAndLodPolicyTest {
    @Test
    void cullingKeepsInstancePrimitiveAndBoneEvidenceInNonInterchangeableScopes() {
        Object handle = new Object();
        Object primitive = new Object();
        Object bone = new Object();
        X7CullingPolicy.InstanceTarget instanceTarget = new X7CullingPolicy.InstanceTarget(5L, handle);
        X7CullingPolicy.PrimitiveTarget primitiveTarget = new X7CullingPolicy.PrimitiveTarget(instanceTarget, primitive);
        X7CullingPolicy.BoneTarget boneTarget = new X7CullingPolicy.BoneTarget(primitiveTarget, bone);

        X7CullingPolicy.InstanceDecision visibleInstance = X7CullingPolicy.decideInstance(
                instanceTarget, evidence(X7CullingPolicy.Visibility.VISIBLE, true));
        X7CullingPolicy.PrimitiveDecision visiblePrimitive = X7CullingPolicy.decidePrimitive(
                primitiveTarget, evidence(X7CullingPolicy.Visibility.VISIBLE, true));
        X7CullingPolicy.BoneDecision hiddenBone = X7CullingPolicy.decideBone(
                boneTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, true));

        assertSame(instanceTarget, visibleInstance.target());
        assertSame(primitiveTarget, visiblePrimitive.target());
        assertSame(boneTarget, hiddenBone.target());
        X7CullingPolicy.CurrentInstanceDecision currentVisibleInstance = visibleInstance.readCurrent(5L, handle);
        assertDecision(currentVisibleInstance.decision(), currentVisibleInstance.reason(),
                X7CullingPolicy.Decision.DRAW, X7CullingPolicy.Reason.VISIBLE_OR_UNCULLED);
        assertDecision(visiblePrimitive.decision(), visiblePrimitive.reason(),
                X7CullingPolicy.Decision.DRAW, X7CullingPolicy.Reason.VISIBLE_OR_UNCULLED);
        assertDecision(hiddenBone.decision(), hiddenBone.reason(),
                X7CullingPolicy.Decision.CULL, X7CullingPolicy.Reason.KNOWN_HIDDEN);

        X7CullingPolicy.PrimitiveDecision hiddenPrimitive = X7CullingPolicy.decidePrimitive(
                primitiveTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, true));
        X7CullingPolicy.BoneDecision visibleBone = X7CullingPolicy.decideBone(
                boneTarget, evidence(X7CullingPolicy.Visibility.VISIBLE, true));
        assertDecision(hiddenPrimitive.decision(), hiddenPrimitive.reason(),
                X7CullingPolicy.Decision.CULL, X7CullingPolicy.Reason.KNOWN_HIDDEN);
        assertDecision(currentVisibleInstance.decision(), currentVisibleInstance.reason(),
                X7CullingPolicy.Decision.DRAW, X7CullingPolicy.Reason.VISIBLE_OR_UNCULLED);
        assertDecision(visibleBone.decision(), visibleBone.reason(),
                X7CullingPolicy.Decision.DRAW, X7CullingPolicy.Reason.VISIBLE_OR_UNCULLED);
    }

    @Test
    void unknownOrIncompleteEvidenceIsConservativelyVisibleAtEveryScope() {
        Object handle = new Object();
        X7CullingPolicy.InstanceTarget instanceTarget = new X7CullingPolicy.InstanceTarget(6L, handle);
        X7CullingPolicy.PrimitiveTarget primitiveTarget = new X7CullingPolicy.PrimitiveTarget(instanceTarget, new Object());
        X7CullingPolicy.BoneTarget boneTarget = new X7CullingPolicy.BoneTarget(primitiveTarget, new Object());

        assertDecision(
                X7CullingPolicy.decideInstance(instanceTarget, evidence(X7CullingPolicy.Visibility.UNKNOWN, true)),
                X7CullingPolicy.Decision.DRAW,
                X7CullingPolicy.Reason.UNKNOWN_INPUT);
        assertDecision(
                X7CullingPolicy.decidePrimitive(primitiveTarget, evidence(X7CullingPolicy.Visibility.UNKNOWN, true)),
                X7CullingPolicy.Decision.DRAW,
                X7CullingPolicy.Reason.UNKNOWN_INPUT);
        assertDecision(
                X7CullingPolicy.decideBone(boneTarget, evidence(X7CullingPolicy.Visibility.UNKNOWN, true)),
                X7CullingPolicy.Decision.DRAW,
                X7CullingPolicy.Reason.UNKNOWN_INPUT);
        assertDecision(
                X7CullingPolicy.decideInstance(instanceTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, false)),
                X7CullingPolicy.Decision.DRAW,
                X7CullingPolicy.Reason.DYNAMIC_BOUNDS_INSUFFICIENT);
        assertDecision(
                X7CullingPolicy.decidePrimitive(primitiveTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, false)),
                X7CullingPolicy.Decision.DRAW,
                X7CullingPolicy.Reason.DYNAMIC_BOUNDS_INSUFFICIENT);
        assertDecision(
                X7CullingPolicy.decideBone(boneTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, false)),
                X7CullingPolicy.Decision.DRAW,
                X7CullingPolicy.Reason.DYNAMIC_BOUNDS_INSUFFICIENT);
    }

    @Test
    void cullingDecisionsRejectStaleAndCrossScopeIdentityReuse() {
        Object handle = new Object();
        Object primitive = new Object();
        Object bone = new Object();
        X7CullingPolicy.InstanceTarget instanceTarget = new X7CullingPolicy.InstanceTarget(7L, handle);
        X7CullingPolicy.PrimitiveTarget primitiveTarget = new X7CullingPolicy.PrimitiveTarget(instanceTarget, primitive);
        X7CullingPolicy.BoneTarget boneTarget = new X7CullingPolicy.BoneTarget(primitiveTarget, bone);

        X7CullingPolicy.InstanceDecision instanceDecision = X7CullingPolicy.decideInstance(
                instanceTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, true));
        X7CullingPolicy.PrimitiveDecision primitiveDecision = X7CullingPolicy.decidePrimitive(
                primitiveTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, true));
        X7CullingPolicy.BoneDecision boneDecision = X7CullingPolicy.decideBone(
                boneTarget, evidence(X7CullingPolicy.Visibility.HIDDEN, true));

        instanceDecision.requireCurrent(7L, handle);
        primitiveDecision.requireCurrent(7L, handle, primitive);
        boneDecision.requireCurrent(7L, handle, primitive, bone);
        assertThrows(IllegalStateException.class, () -> instanceDecision.requireCurrent(8L, handle));
        assertThrows(IllegalStateException.class, () -> instanceDecision.requireCurrent(7L, new Object()));
        assertThrows(IllegalStateException.class, () -> primitiveDecision.requireCurrent(7L, handle, new Object()));
        assertThrows(IllegalStateException.class, () -> boneDecision.requireCurrent(7L, handle, primitive, new Object()));
        assertThrows(IllegalStateException.class, () -> boneDecision.requireCurrent(8L, handle, primitive, bone));
    }

    @Test
    void lodUsesOrderedThresholdsHysteresisAndExactGenerationMaterialIdentity() {
        X7LodPolicy policy = policy();
        Object model = new Object();
        Object firstMaterial = new Object();
        X7LodPolicy.Binding firstBinding = new X7LodPolicy.Binding(3L, model, firstMaterial);

        X7LodPolicy.Selection atFirstThreshold = policy.select(firstBinding, 10.0D, null);
        assertEquals(X7LodPolicy.Disposition.SELECTED, atFirstThreshold.disposition());
        assertEquals(1, atFirstThreshold.level());

        X7LodPolicy.Selection retainedAtExitBoundary = policy.select(firstBinding, 8.0D, atFirstThreshold.history());
        assertEquals(1, retainedAtExitBoundary.level());
        assertEquals(X7LodPolicy.Reason.HYSTERESIS_HELD, retainedAtExitBoundary.reason());

        X7LodPolicy.Selection loweredBelowExit = policy.select(firstBinding, 7.99D, retainedAtExitBoundary.history());
        assertEquals(0, loweredBelowExit.level());

        X7LodPolicy.Selection far = policy.select(firstBinding, 20.0D, loweredBelowExit.history());
        assertEquals(2, far.level());

        X7LodPolicy.Selection differentMaterial = policy.select(
                new X7LodPolicy.Binding(3L, model, new Object()), 1.0D, far.history());
        X7LodPolicy.Selection differentGeneration = policy.select(
                new X7LodPolicy.Binding(4L, model, firstMaterial), 1.0D, far.history());
        assertEquals(0, differentMaterial.level());
        assertEquals(X7LodPolicy.Reason.FRESH_DISTANCE, differentMaterial.reason());
        assertEquals(0, differentGeneration.level());
        assertEquals(X7LodPolicy.Reason.FRESH_DISTANCE, differentGeneration.reason());
    }

    @Test
    void lodFailsClosedForInvalidDistanceHistoryAndBandDefinitions() {
        X7LodPolicy policy = policy();
        X7LodPolicy.Binding binding = new X7LodPolicy.Binding(1L, new Object(), new Object());

        assertRejected(policy.select(binding, Double.NaN, null), X7LodPolicy.Reason.INVALID_DISTANCE);
        assertRejected(policy.select(binding, Double.POSITIVE_INFINITY, null), X7LodPolicy.Reason.INVALID_DISTANCE);
        assertRejected(policy.select(binding, -0.1D, null), X7LodPolicy.Reason.INVALID_DISTANCE);
        assertRejected(policy.select(binding, 1.0D, new X7LodPolicy.History(binding, 99)),
                X7LodPolicy.Reason.INVALID_HISTORY);

        assertThrows(IllegalArgumentException.class, () -> new X7LodPolicy(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new X7LodPolicy(List.of(
                new X7LodPolicy.Band(0, 0.0D, 0.0D),
                new X7LodPolicy.Band(1, 10.0D, 11.0D))));
        assertThrows(IllegalArgumentException.class, () -> new X7LodPolicy(List.of(
                new X7LodPolicy.Band(0, 0.0D, 0.0D),
                new X7LodPolicy.Band(2, 10.0D, 8.0D))));
    }

    private static X7CullingPolicy.Evidence evidence(X7CullingPolicy.Visibility visibility, boolean complete) {
        return new X7CullingPolicy.Evidence(visibility, complete);
    }

    private static void assertDecision(
            X7CullingPolicy.Decision actualDecision,
            X7CullingPolicy.Reason actualReason,
            X7CullingPolicy.Decision expectedDecision,
            X7CullingPolicy.Reason expectedReason) {
        assertEquals(expectedDecision, actualDecision);
        assertEquals(expectedReason, actualReason);
    }

    private static void assertDecision(
            X7CullingPolicy.InstanceDecision decision,
            X7CullingPolicy.Decision expectedDecision,
            X7CullingPolicy.Reason expectedReason) {
        X7CullingPolicy.InstanceTarget target = decision.target();
        X7CullingPolicy.CurrentInstanceDecision current = decision.readCurrent(
                target.generation(), target.handleIdentity());
        assertDecision(current.decision(), current.reason(), expectedDecision, expectedReason);
    }

    private static void assertDecision(
            X7CullingPolicy.PrimitiveDecision decision,
            X7CullingPolicy.Decision expectedDecision,
            X7CullingPolicy.Reason expectedReason) {
        assertDecision(decision.decision(), decision.reason(), expectedDecision, expectedReason);
    }

    private static void assertDecision(
            X7CullingPolicy.BoneDecision decision,
            X7CullingPolicy.Decision expectedDecision,
            X7CullingPolicy.Reason expectedReason) {
        assertDecision(decision.decision(), decision.reason(), expectedDecision, expectedReason);
    }

    private static X7LodPolicy policy() {
        return new X7LodPolicy(List.of(
                new X7LodPolicy.Band(0, 0.0D, 0.0D),
                new X7LodPolicy.Band(1, 10.0D, 8.0D),
                new X7LodPolicy.Band(2, 20.0D, 18.0D)));
    }

    private static void assertRejected(X7LodPolicy.Selection selection, X7LodPolicy.Reason reason) {
        assertEquals(X7LodPolicy.Disposition.REJECTED, selection.disposition());
        assertEquals(-1, selection.level());
        assertEquals(null, selection.history());
        assertEquals(reason, selection.reason());
    }
}
