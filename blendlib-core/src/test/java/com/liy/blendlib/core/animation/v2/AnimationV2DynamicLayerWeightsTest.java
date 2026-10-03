package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnimationV2DynamicLayerWeightsTest {
    private static final BoneSchema SCHEMA = new BoneSchema(List.of("root"), List.of(Transform.IDENTITY));
    private static final BlendAnimationKey IDLE = BlendAnimationKey.of("weights", "idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.of("weights", "walk");

    @Test
    void inputIsBoundedImmutableAndRejectsInvalidValues() {
        var key = target("main", "base");
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1F, 1.1F}) {
            assertThrows(IllegalArgumentException.class, () -> new AnimationV2LayerWeights(Map.of(key, invalid)));
        }
        assertThrows(NullPointerException.class, () -> new AnimationV2LayerWeights(null));
        Map<AnimationV2LayerWeights.Key, Float> source = new LinkedHashMap<>();
        source.put(key, 0.25F);
        var weights = new AnimationV2LayerWeights(source);
        source.put(key, 0.75F);
        assertEquals(0.25F, weights.multiplier(key));
        assertEquals(1.0F, weights.multiplier(target("other", "base")));
        assertThrows(UnsupportedOperationException.class, () -> weights.multipliers().clear());
        source.put(key, null);
        assertThrows(NullPointerException.class, () -> new AnimationV2LayerWeights(source));
        source.clear();
        source.put(null, 1.0F);
        assertThrows(NullPointerException.class, () -> new AnimationV2LayerWeights(source));
        source.clear();
        for (int index = 0; index <= AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE
                * AnimationV2Limits.MAX_LAYERS_PER_CONTROLLER; index++) {
            source.put(target("main", "layer" + index), 1.0F);
        }
        assertThrows(IllegalArgumentException.class, () -> new AnimationV2LayerWeights(source));
    }

    @Test
    void weightsApplyImmediatelyResetNextFrameAndRemainInstanceLocal() {
        var base = layer("base", 0.5F, AnimationV2LayerMode.OVERRIDE, 1.0F);
        var plan = plan(controller("main", 0, List.of(base), Map.of(IDLE, state(IDLE, 0, Map.of(base.id(), clip(8))))));
        var first = new AnimationV2InstanceRuntime(plan);
        var second = new AnimationV2InstanceRuntime(plan);
        var weighted = first.advanceWeightedAtFrame(0, List.of(), weights("main", "base", 0.5F));
        assertX(2, weighted);
        assertEquals(Map.of(target("main", "base"), 0.25F), weighted.effectiveLayerWeights());
        assertThrows(UnsupportedOperationException.class, () -> weighted.effectiveLayerWeights().clear());
        assertX(4, second.advance(0));
        var normal = first.advance(0);
        assertX(4, normal);
        assertEquals(0.5F, normal.effectiveLayerWeights().get(target("main", "base")));
        assertX(2, weighted);
        assertEquals(0.25F, weighted.effectiveLayerWeights().get(target("main", "base")));
    }

    @Test
    void unknownControllerOrLayerLeavesSnapshotTimeAndIngressUntouched() {
        var runtime = movingRuntime();
        var before = runtime.advance(0.1);
        runtime.enqueue(new AnimationV2Command(id("main"), WALK, 7, 0.2, 1));
        for (var invalid : List.of(weights("missing", "base", 0), weights("main", "missing", 0))) {
            assertThrows(IllegalArgumentException.class, () -> runtime.advanceWeightedAtFrame(0.3, List.of(), invalid));
            assertSame(before, runtime.latestSnapshot());
        }
        var after = runtime.advance(0);
        assertEquals(before.revision() + 1, after.revision());
        assertEquals(WALK, after.playheads().get(id("main")).state());
        assertEquals(0.2, after.playheads().get(id("main")).timeSeconds());
        assertEquals(7, after.playheads().get(id("main")).acceptedSequence());
    }

    @Test
    void zeroWeightPreservesCommandsTransitionsAndTime() {
        var runtime = movingRuntime();
        var muted = weights("main", "base", 0);
        var start = runtime.advanceWeightedAtFrame(0.1,
                List.of(new AnimationV2Command(id("main"), WALK, 4, 0.2, 1)), muted);
        assertX(0, start);
        assertEquals(0.2, start.playheads().get(id("main")).timeSeconds());
        var progressing = runtime.advanceWeightedAtFrame(0.25, List.of(), muted);
        assertX(0, progressing);
        assertEquals(0.45, progressing.playheads().get(id("main")).timeSeconds(), 1e-10);
        assertEquals(0.5, progressing.playheads().get(id("main")).transitionProgress(), 1e-10);
        assertFalse(progressing.observerTraversal().controller(id("main")).segments().isEmpty());
        var restored = runtime.advance(0.25);
        assertX(10, restored);
        assertEquals(0.7, restored.playheads().get(id("main")).timeSeconds(), 1e-10);
        assertEquals(4, restored.playheads().get(id("main")).acceptedSequence());
        assertEquals(1, restored.playheads().get(id("main")).transitionProgress());
    }

    @Test
    void zeroHighPriorityRevealsLowerPriorityAndSameLayerIdsAreIndependent() {
        var shared = layer("base", 1, AnimationV2LayerMode.OVERRIDE, 1);
        var low = controller("low", 0, List.of(shared), Map.of(IDLE, state(IDLE, 0, Map.of(shared.id(), clip(2)))));
        var high = controller("high", 1, List.of(shared), Map.of(IDLE, state(IDLE, 0, Map.of(shared.id(), clip(8)))));
        var runtime = new AnimationV2InstanceRuntime(plan(high, low));
        assertX(2, runtime.advanceWeightedAtFrame(0, List.of(), weights("high", "base", 0)));
        assertX(4, runtime.advanceWeightedAtFrame(0, List.of(), weights("high", "base", 0.5F)));
        assertX(8, runtime.advance(0));
    }

    @Test
    void overrideNormalizationAndAdditiveMasksUseEffectiveWeights() {
        var a = layer("a", 1, AnimationV2LayerMode.OVERRIDE, 1);
        var b = layer("b", 1, AnimationV2LayerMode.OVERRIDE, 1);
        var add = layer("add", 0.5F, AnimationV2LayerMode.ADDITIVE, 0.5F);
        var runtime = new AnimationV2InstanceRuntime(plan(controller("main", 0, List.of(a, b, add),
                Map.of(IDLE, state(IDLE, 0, Map.of(a.id(), clip(2), b.id(), clip(8), add.id(), clip(4)))))));
        var weights = new AnimationV2LayerWeights(Map.of(target("main", "b"), 0.5F, target("main", "add"), 0.5F));
        assertX(4.5F, runtime.advanceWeightedAtFrame(0, List.of(), weights));
        var masked = layer("masked", 0.5F, AnimationV2LayerMode.OVERRIDE, 0.5F);
        var maskedRuntime = new AnimationV2InstanceRuntime(plan(controller("main", 0, List.of(masked),
                Map.of(IDLE, state(IDLE, 0, Map.of(masked.id(), clip(8)))))));
        assertX(1, maskedRuntime.advanceWeightedAtFrame(0, List.of(), weights("main", "masked", 0.5F)));
    }

    @Test
    void zeroWeightStillFollowsAutomaticNextAndConfiguredZeroCannotBeRaised() {
        var base = layer("base", 1, AnimationV2LayerMode.OVERRIDE, 1);
        var once = new AnimationV2ControllerState(IDLE, AnimationV2PlaybackMode.ONCE, 1, 0, WALK,
                Map.of(base.id(), clip(2)));
        var hold = new AnimationV2ControllerState(WALK, AnimationV2PlaybackMode.HOLD, 1, 0, null,
                Map.of(base.id(), clip(10)));
        var runtime = new AnimationV2InstanceRuntime(plan(controller("main", 0, List.of(base), Map.of(IDLE, once, WALK, hold))));
        var muted = runtime.advanceWeightedAtFrame(1.25, List.of(), weights("main", "base", 0));
        assertX(0, muted);
        assertEquals(WALK, muted.playheads().get(id("main")).state());
        assertEquals(0.25, muted.playheads().get(id("main")).timeSeconds());
        assertX(10, runtime.advance(0));
        var disabled = layer("disabled", 0, AnimationV2LayerMode.OVERRIDE, 1);
        var disabledRuntime = new AnimationV2InstanceRuntime(plan(controller("main", 0, List.of(disabled),
                Map.of(IDLE, state(IDLE, 0, Map.of(disabled.id(), clip(10)))))));
        assertX(0, disabledRuntime.advanceWeightedAtFrame(0, List.of(), weights("main", "disabled", 1)));
    }

    @Test
    void existingEntryPointsAndSnapshotConstructorRetainTheirExactSignatures() throws Exception {
        assertEquals(AnimationV2EvaluationSnapshot.class,
                AnimationV2InstanceRuntime.class.getMethod("advance", double.class).getReturnType());
        assertEquals(AnimationV2EvaluationSnapshot.class,
                AnimationV2InstanceRuntime.class.getMethod("advanceAtFrame", double.class, List.class).getReturnType());
        assertEquals(AnimationV2EvaluationSnapshot.class,
                AnimationV2InstanceRuntime.class.getMethod("advanceAtFrame", double.class, List.class, List.class).getReturnType());
        assertNotNull(AnimationV2EvaluationSnapshot.class.getConstructor(long.class, AnimationV2Pose.class, Map.class, List.class));
        assertEquals(AnimationV2EvaluationSnapshot.class, AnimationV2InstanceRuntime.class.getMethod(
                "advanceWeightedAtFrame", double.class, List.class, AnimationV2LayerWeights.class).getReturnType());
        assertEquals(AnimationV2EvaluationSnapshot.class, AnimationV2InstanceRuntime.class.getMethod(
                "advanceWeightedAtFrame", double.class, List.class, List.class, AnimationV2LayerWeights.class).getReturnType());
        assertEquals(void.class, AnimationV2InstanceRuntime.class.getMethod(
                "validateLayerWeights", AnimationV2LayerWeights.class).getReturnType());
        assertEquals(Map.class, AnimationV2EvaluationSnapshot.class.getMethod("effectiveLayerWeights").getReturnType());
        assertNotNull(AnimationV2LayerWeights.class.getConstructor(Map.class));
        assertNotNull(AnimationV2LayerWeights.Key.class.getConstructor(BlendResourceId.class, BlendResourceId.class));
        var snapshot = new AnimationV2EvaluationSnapshot(0, SCHEMA.restPose(), Map.of(), List.of());
        assertTrue(snapshot.effectiveLayerWeights().isEmpty());
        // The distinct weighted name preserves source compatibility for the old null-rejections call.
        assertThrows(NullPointerException.class, () -> movingRuntime().advanceAtFrame(0, List.of(), null));
    }

    private static AnimationV2InstanceRuntime movingRuntime() {
        var base = layer("base", 1, AnimationV2LayerMode.OVERRIDE, 1);
        return new AnimationV2InstanceRuntime(plan(controller("main", 0, List.of(base), Map.of(
                IDLE, state(IDLE, 0, Map.of(base.id(), clip(2))),
                WALK, state(WALK, 0.5, Map.of(base.id(), clip(10)))))));
    }

    private static AnimationV2InstancePlan plan(AnimationV2ControllerDefinition... controllers) {
        return new AnimationV2InstancePlan(SCHEMA, List.of(controllers));
    }

    private static AnimationV2ControllerDefinition controller(String name, int priority,
            List<AnimationV2LayerDefinition> layers, Map<BlendAnimationKey, AnimationV2ControllerState> states) {
        return new AnimationV2ControllerDefinition(id(name), priority, layers, IDLE, states);
    }

    private static AnimationV2ControllerState state(BlendAnimationKey key, double transition,
            Map<BlendResourceId, AnimationV2Clip> clips) {
        return new AnimationV2ControllerState(key, AnimationV2PlaybackMode.LOOP, 1, transition, null, clips);
    }

    private static AnimationV2LayerDefinition layer(String name, float weight, AnimationV2LayerMode mode, float mask) {
        return new AnimationV2LayerDefinition(id(name), 0, mode, weight,
                BoneMask.named(SCHEMA, List.of(new BoneMask.NamedWeight("root", mask))), false);
    }

    private static AnimationV2Clip clip(float x) {
        var pose = new AnimationV2Pose(List.of(new Transform(new Vec3(x, 0, 0), Quaternion.IDENTITY, Vec3.ONE)));
        return new AnimationV2Clip(List.of(new AnimationV2Keyframe(0, pose), new AnimationV2Keyframe(1, pose)));
    }

    private static BlendResourceId id(String path) { return BlendResourceId.of("weights", path); }
    private static AnimationV2LayerWeights.Key target(String controller, String layer) {
        return new AnimationV2LayerWeights.Key(id(controller), id(layer));
    }
    private static AnimationV2LayerWeights weights(String controller, String layer, float weight) {
        return new AnimationV2LayerWeights(Map.of(target(controller, layer), weight));
    }
    private static void assertX(float expected, AnimationV2EvaluationSnapshot snapshot) {
        assertEquals(expected, snapshot.pose().transform(0).translation().x(), 0.00001F);
    }
}
