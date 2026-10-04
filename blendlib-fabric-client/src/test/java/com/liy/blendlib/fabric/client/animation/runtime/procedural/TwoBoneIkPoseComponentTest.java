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
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigView;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigViewTestAccess;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.TwoBoneIkPoseComponent.Result;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.TwoBoneIkPoseComponent.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TwoBoneIkPoseComponentTest {
    private static final Vec3 POLE = new Vec3(0, 0, 3);
    private static final TwoBoneIkTarget REACHABLE = new TwoBoneIkTarget(new Vec3(1, 1, 0), POLE);

    @Test void reachesModelSpaceTargetAndPreservesEveryOtherChannelExactly() {
        RigPose fixture = chain(1, 1);
        Map<Integer, Transform> original = new LinkedHashMap<>(fixture.pose.transforms());
        List<Result> results = new ArrayList<>();
        LocalPose solved = component(REACHABLE, results).modify(context(fixture, 1, 1, 0), fixture.pose);
        assertVector(REACHABLE.targetModelSpace(), endpoint(fixture, solved), 2e-6);
        assertEquals(Status.REACHED, only(results).status());
        assertEquals(REACHABLE.targetModelSpace(), only(results).effectiveTargetModelSpace());
        assertFalse(only(results).usedDirectionFallback());
        assertFalse(only(results).usedPoleFallback());
        assertPreserved(fixture, solved);
        assertEquals(original, fixture.pose.transforms());
        assertNotEquals(fixture.pose.transform(fixture.root).rotation(), solved.transform(fixture.root).rotation());
        assertNotEquals(fixture.pose.transform(fixture.middle).rotation(), solved.transform(fixture.middle).rotation());
    }

    @Test void solvesIncomingNonAxisPoseThroughSeveralRotatedScaledAncestors() {
        var transforms = new LinkedHashMap<Integer, Transform>();
        transforms.put(42, transform(new Vec3(-4, 2, 1), rotation(1, 2, -1, 0.4), 0.8f));
        transforms.put(17, transform(new Vec3(2, -1, 3), rotation(-1, 1, 2, -0.7), 1.7f));
        transforms.put(4, transform(new Vec3(1, -0.4f, 0.7f), rotation(1, 3, 2, 0.55), 1.2f));
        transforms.put(9, transform(new Vec3(0.8f, 0.6f, 0.2f), rotation(2, -1, 1, -0.65), 0.8f));
        transforms.put(1, transform(new Vec3(0.9f, -0.4f, 0.6f), rotation(-1, -1, 2, 0.8), 1.3f));
        transforms.put(7, transform(new Vec3(4, 2, -3), rotation(0, 1, 0, -1.2), 0.7f));
        // Rest transforms deliberately differ from the incoming animated pose.
        ClientAnimationRigView rig = rig(List.of(node(42, "outer", 17), node(17, "ancestor", 4, 7),
                node(4, "root", 9), node(9, "middle", 1), node(1, "end"), node(7, "unrelated")));
        RigPose fixture = new RigPose(rig, new LocalPose(transforms), 4, 9, 1);
        Vec3 origin = position(fixture, fixture.pose, fixture.root);
        double a = distance(origin, position(fixture, fixture.pose, fixture.middle));
        double b = distance(position(fixture, fixture.pose, fixture.middle), endpoint(fixture, fixture.pose));
        Vec3 target = offset(origin, unit(new Vec3(-2, 3, 1)), (a + b) * 0.7);
        var snapshot = new TwoBoneIkTarget(target, origin.add(new Vec3(2, -1, 4)));
        List<Result> results = new ArrayList<>();
        LocalPose solved = component(snapshot, results).modify(context(fixture, 2, 8, 16), fixture.pose);
        assertEquals(Status.REACHED, only(results).status());
        assertVector(target, endpoint(fixture, solved), 5e-6);
        assertEquals(a, distance(position(fixture, solved, fixture.root), position(fixture, solved, fixture.middle)), 3e-6);
        assertEquals(b, distance(position(fixture, solved, fixture.middle), endpoint(fixture, solved)), 3e-6);
        assertPreserved(fixture, solved);
    }

    @Test void clampsFarAndNearToDocumentedGuardedReachInterval() {
        RigPose fixture = chain(2, 1);
        for (var testCase : List.of(new ClampCase(new Vec3(8, 0, 0), Status.CLAMPED_FAR, 3 - 3e-6),
                new ClampCase(new Vec3(0.25f, 0, 0), Status.CLAMPED_NEAR, 1 + 3e-6))) {
            List<Result> results = new ArrayList<>();
            LocalPose solved = component(new TwoBoneIkTarget(testCase.target, POLE), results)
                    .modify(context(fixture, 1, 1, 0), fixture.pose);
            Result result = only(results);
            assertEquals(testCase.status, result.status());
            assertEquals(testCase.distance, distance(Vec3.ZERO, result.effectiveTargetModelSpace()), 3e-7);
            assertVector(result.effectiveTargetModelSpace(), endpoint(fixture, solved), 2e-6);
            assertPreserved(fixture, solved);
        }
    }

    @Test void exactExtendedAndFoldedTargetsAreGuardedRatherThanSingular() {
        RigPose fixture = chain(2, 1);
        for (var testCase : List.of(new ClampCase(new Vec3(3, 0, 0), Status.CLAMPED_FAR, 3 - 3e-6),
                new ClampCase(new Vec3(1, 0, 0), Status.CLAMPED_NEAR, 1 + 3e-6))) {
            List<Result> results = new ArrayList<>();
            LocalPose solved = component(new TwoBoneIkTarget(testCase.target, POLE), results)
                    .modify(context(fixture, 1, 1, 0), fixture.pose);
            assertEquals(testCase.status, only(results).status());
            assertVector(only(results).effectiveTargetModelSpace(), endpoint(fixture, solved), 2e-6);
            assertFiniteUnitRotations(solved);
        }
    }

    @Test void poleSelectsBendSideAndIsAPositionRelativeToRoot() {
        RigPose fixture = translated(chain(1, 1), new Vec3(5, -2, 3));
        Vec3 target = new Vec3(6, -2, 3);
        for (int side : new int[]{-1, 1}) {
            List<Result> results = new ArrayList<>();
            LocalPose solved = component(new TwoBoneIkTarget(target, new Vec3(5, -2 + 2 * side, 3)), results)
                    .modify(context(fixture, 1, 1, 0), fixture.pose);
            assertVector(target, endpoint(fixture, solved), 2e-6);
            Vec3 elbow = position(fixture, solved, fixture.middle);
            assertTrue((elbow.y() + 2) * side > 0.8, "elbow bends toward model-space pole");
            assertEquals(3, elbow.z(), 2e-6);
            assertFalse(only(results).usedPoleFallback());
        }
    }

    @Test void coincidentAndCollinearPolesUseStablePerpendicular() {
        RigPose fixture = chain(1, 1);
        List<LocalPose> solutions = new ArrayList<>();
        for (Vec3 pole : List.of(Vec3.ZERO, new Vec3(3, 0, 0), new Vec3(-3, 0, 0))) {
            List<Result> results = new ArrayList<>();
            var solver = component(new TwoBoneIkTarget(new Vec3(1, 0, 0), pole), results);
            LocalPose first = solver.modify(context(fixture, 1, 1, 0), fixture.pose);
            LocalPose second = solver.modify(context(fixture, 1, 1, 0), fixture.pose);
            assertEquals(first.transforms(), second.transforms());
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(Result::usedPoleFallback));
            assertTrue(results.stream().noneMatch(Result::usedDirectionFallback));
            assertVector(new Vec3(1, 0, 0), endpoint(fixture, first), 2e-6);
            solutions.add(first);
        }
        assertEquals(solutions.get(0).transforms(), solutions.get(1).transforms());
        assertEquals(solutions.get(0).transforms(), solutions.get(2).transforms());
    }

    @Test void rootTargetUsesCurrentFirstSegmentDirectionAndReportsBothFallbacks() {
        RigPose fixture = chain(2, 1);
        var rotated = new LinkedHashMap<>(fixture.pose.transforms());
        rotated.put(fixture.root, transform(new Vec3(2, -3, 4), rotation(0, 0, 1, Math.PI / 2), 1));
        fixture = new RigPose(fixture.rig, new LocalPose(rotated), fixture.root, fixture.middle, fixture.end);
        Vec3 origin = position(fixture, fixture.pose, fixture.root);
        Vec3 direction = unit(position(fixture, fixture.pose, fixture.middle).subtract(origin));
        List<Result> results = new ArrayList<>();
        LocalPose solved = component(new TwoBoneIkTarget(origin, origin), results)
                .modify(context(fixture, 1, 1, 0), fixture.pose);
        Result result = only(results);
        assertEquals(Status.CLAMPED_NEAR, result.status());
        assertTrue(result.usedDirectionFallback());
        assertTrue(result.usedPoleFallback());
        assertVector(offset(origin, direction, 1 + 3e-6), result.effectiveTargetModelSpace(), 1e-6);
        assertVector(result.effectiveTargetModelSpace(), endpoint(fixture, solved), 2e-6);
    }

    @Test void equalLengthRootTargetFoldsSafelyWithFiniteRotations() {
        RigPose fixture = chain(1, 1);
        List<Result> results = new ArrayList<>();
        LocalPose solved = component(new TwoBoneIkTarget(Vec3.ZERO, Vec3.ZERO), results)
                .modify(context(fixture, 1, 1, 0), fixture.pose);
        assertEquals(Status.CLAMPED_NEAR, only(results).status());
        assertTrue(only(results).usedDirectionFallback());
        assertTrue(only(results).usedPoleFallback());
        assertVector(only(results).effectiveTargetModelSpace(), endpoint(fixture, solved), 3e-7);
        assertFiniteUnitRotations(solved);
    }

    @Test void oppositeDirectionTargetUsesFiniteShortestArcRotations() {
        RigPose fixture = chain(1, 1);
        List<Result> results = new ArrayList<>();
        var snapshot = new TwoBoneIkTarget(new Vec3(-1.5f, 0, 0), new Vec3(0, 1, 0));
        LocalPose solved = component(snapshot, results).modify(context(fixture, 1, 1, 0), fixture.pose);
        assertVector(snapshot.targetModelSpace(), endpoint(fixture, solved), 2e-6);
        assertEquals(Status.REACHED, only(results).status());
        assertFiniteUnitRotations(solved);
    }

    @Test void zeroTinyAndRelativelyTinySegmentsPreserveExactInputAndStillSampleOnce() {
        for (float[] lengths : List.of(new float[]{0, 1}, new float[]{1, 0}, new float[]{0, 0},
                new float[]{1e-8f, 1}, new float[]{1, 1e-8f}, new float[]{1, 1e8f}, new float[]{1e8f, 1})) {
            RigPose fixture = chain(lengths[0], lengths[1]);
            AtomicInteger samples = new AtomicInteger();
            List<Result> results = new ArrayList<>();
            var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> {
                samples.incrementAndGet();
                return REACHABLE;
            }, (c, result) -> results.add(result));
            assertSame(fixture.pose, solver.modify(context(fixture, 1, 1, 0), fixture.pose));
            assertEquals(1, samples.get());
            assertEquals(Status.DEGENERATE_CHAIN, only(results).status());
            assertEquals(endpoint(fixture, fixture.pose), only(results).effectiveTargetModelSpace());
            assertFalse(only(results).usedDirectionFallback());
            assertFalse(only(results).usedPoleFallback());
        }
    }

    @Test void shortButValidSegmentsAndDisproportionateLengthsHaveRepresentableReachIntervals() {
        for (float[] lengths : List.of(new float[]{2e-7f, 2e-7f}, new float[]{1, 4e-7f}, new float[]{4e-7f, 1})) {
            RigPose fixture = chain(lengths[0], lengths[1]);
            double a = distance(position(fixture, fixture.pose, fixture.root), position(fixture, fixture.pose, fixture.middle));
            double b = distance(position(fixture, fixture.pose, fixture.middle), endpoint(fixture, fixture.pose));
            double guard = Math.min(Math.min(a, b) * 0.25, Math.max(1e-7, (a + b) * 1e-6));
            List<Result> results = new ArrayList<>();
            LocalPose solved = component(new TwoBoneIkTarget(new Vec3(10, 0, 0), POLE), results)
                    .modify(context(fixture, 1, 1, 0), fixture.pose);
            Result result = only(results);
            assertEquals(Status.CLAMPED_FAR, result.status());
            assertEquals(a + b - guard, distance(Vec3.ZERO, result.effectiveTargetModelSpace()), Math.max(1e-13, (a + b) * 1e-7));
            assertTrue(distance(Vec3.ZERO, result.effectiveTargetModelSpace()) <= a + b, "guard never inverts reachable interval");
            assertVector(result.effectiveTargetModelSpace(), endpoint(fixture, solved), Math.max(1e-12, (a + b) * 1e-6));
            assertFiniteUnitRotations(solved);
        }
    }

    @Test void finiteHugeTargetAndPoleUseDoubleIntermediatesWithoutOverflow() {
        RigPose fixture = translated(chain(1e30f, 1e30f), new Vec3(-1e30f, 0, 0));
        List<Result> results = new ArrayList<>();
        var snapshot = new TwoBoneIkTarget(new Vec3(Float.MAX_VALUE, Float.MAX_VALUE, 0),
                new Vec3(-Float.MAX_VALUE, 0, Float.MAX_VALUE));
        LocalPose solved = component(snapshot, results).modify(context(fixture, 1, 1, 0), fixture.pose);
        assertEquals(Status.CLAMPED_FAR, only(results).status());
        assertVector(only(results).effectiveTargetModelSpace(), endpoint(fixture, solved), 1e24);
        assertFiniteUnitRotations(solved);
        assertPreserved(fixture, solved);
    }

    @Test void finiteTargetMinusOppositeHugeRootDoesNotOverflowFloatSubtraction() {
        RigPose fixture = translated(chain(1e32f, 1e32f), new Vec3(-3e38f, 0, 0));
        List<Result> results = new ArrayList<>();
        LocalPose solved = component(new TwoBoneIkTarget(new Vec3(3e38f, 0, 0), new Vec3(-3e38f, 1e35f, 0)), results)
                .modify(context(fixture, 1, 1, 0), fixture.pose);
        assertEquals(Status.CLAMPED_FAR, only(results).status());
        assertVector(only(results).effectiveTargetModelSpace(), endpoint(fixture, solved), 5e31);
        assertFiniteUnitRotations(solved);
    }

    @Test void composedScaleOverflowUnderflowAndPositionOverflowFailBeforeObserver() {
        for (var testCase : List.of(new Transform[]{transform(Vec3.ZERO, Quaternion.IDENTITY, Float.MAX_VALUE),
                        transform(new Vec3(1, 0, 0), Quaternion.IDENTITY, 2)},
                new Transform[]{transform(Vec3.ZERO, Quaternion.IDENTITY, Float.MIN_VALUE),
                        transform(new Vec3(1, 0, 0), Quaternion.IDENTITY, 0.5f)},
                new Transform[]{transform(new Vec3(Float.MAX_VALUE, 0, 0), Quaternion.IDENTITY, 1),
                        transform(new Vec3(Float.MAX_VALUE, 0, 0), Quaternion.IDENTITY, 1)})) {
            RigPose fixture = chain(1, 1);
            Map<Integer, Transform> transforms = new LinkedHashMap<>(fixture.pose.transforms());
            transforms.put(fixture.root, testCase[0]);
            transforms.put(fixture.middle, testCase[1]);
            LocalPose input = new LocalPose(transforms);
            AtomicInteger samples = new AtomicInteger();
            AtomicInteger observers = new AtomicInteger();
            var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> {
                samples.incrementAndGet(); return REACHABLE;
            }, (c, result) -> observers.incrementAndGet());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> solver.modify(context(fixture, 1, 1, 0), input));
            assertTrue(failure.getMessage().contains("finite model space"));
            assertEquals(1, samples.get());
            assertEquals(0, observers.get());
            assertEquals(transforms, input.transforms());
        }
    }

    @Test void rejectsSolvedBendOverflowEvenWhenInputAndTargetAreFinite() {
        RigPose fixture = translated(chain(-2e38f, -2e38f), new Vec3(3e38f, 0, 0));
        assertNotNull(endpoint(fixture, fixture.pose));
        AtomicInteger samples = new AtomicInteger();
        var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> {
            samples.incrementAndGet();
            return new TwoBoneIkTarget(new Vec3(3e38f, 1e38f, 0), new Vec3(Float.MAX_VALUE, 0, 0));
        }, (c, result) -> fail("unrepresentable bend must not be observed as success"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> solver.modify(context(fixture, 1, 1, 0), fixture.pose));
        assertTrue(failure.getMessage().contains("finite model space"));
        assertEquals(1, samples.get());
    }

    @Test void rejectsUnsupportedScaleAndNonFiniteTargetValuesAtImmutableValueBoundary() {
        for (Vec3 scale : List.of(new Vec3(-1, -1, -1), Vec3.ZERO, new Vec3(1, 2, 1))) {
            assertThrows(IllegalArgumentException.class, () -> new Transform(Vec3.ZERO, Quaternion.IDENTITY, scale));
        }
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new TwoBoneIkTarget(new Vec3(value, 0, 0), POLE));
            assertThrows(IllegalArgumentException.class, () -> new TwoBoneIkTarget(Vec3.ZERO, new Vec3(0, value, 0)));
        }
        assertThrows(NullPointerException.class, () -> new TwoBoneIkTarget(null, POLE));
        assertThrows(NullPointerException.class, () -> new TwoBoneIkTarget(Vec3.ZERO, null));
        assertThrows(NullPointerException.class, () -> new Result(null, Vec3.ZERO, false, false));
        assertThrows(NullPointerException.class, () -> new Result(Status.REACHED, null, false, false));
    }

    @Test void rejectsBlankNullAndRepeatedNamesAndNullCallbacksAtConstruction() {
        Function<ClientAnimationPoseContext, TwoBoneIkTarget> target = c -> REACHABLE;
        for (String invalid : new String[]{null, "", " \t"}) {
            assertThrows(IllegalArgumentException.class, () -> new TwoBoneIkPoseComponent(invalid, "middle", "end", target));
            assertThrows(IllegalArgumentException.class, () -> new TwoBoneIkPoseComponent("root", invalid, "end", target));
            assertThrows(IllegalArgumentException.class, () -> new TwoBoneIkPoseComponent("root", "middle", invalid, target));
        }
        for (String[] names : List.of(new String[]{"root", "root", "end"}, new String[]{"root", "middle", "root"},
                new String[]{"root", "middle", "middle"})) {
            assertThrows(IllegalArgumentException.class, () -> new TwoBoneIkPoseComponent(names[0], names[1], names[2], target));
        }
        assertThrows(NullPointerException.class, () -> new TwoBoneIkPoseComponent("root", "middle", "end", null));
        assertThrows(NullPointerException.class, () -> new TwoBoneIkPoseComponent("root", "middle", "end", target, null));
    }

    @Test void rejectsMissingAndAmbiguousNamesBeforeSampling() {
        for (String name : List.of("root", "middle", "end")) {
            for (boolean duplicate : new boolean[]{false, true}) {
                var nodes = new ArrayList<>(List.of(node(0, name.equals("root") && !duplicate ? "other" : "root", 1),
                        node(1, name.equals("middle") && !duplicate ? "other" : "middle", 2),
                        node(2, name.equals("end") && !duplicate ? "other" : "end")));
                var poses = new LinkedHashMap<>(chain(1, 1).pose.transforms());
                if (duplicate) { nodes.add(node(8, name)); poses.put(8, Transform.IDENTITY); }
                assertInvalidBeforeTarget(new RigPose(rig(nodes), new LocalPose(poses), 0, 1, 2), "name");
            }
        }
    }

    @Test void rejectsSkippedReversedAndBranchedChainsBeforeSampling() {
        for (List<ModelNode> nodes : List.of(
                List.of(node(0, "root", 3), node(3, "spacer", 1), node(1, "middle", 2), node(2, "end")),
                List.of(node(1, "middle", 0), node(0, "root", 2), node(2, "end")),
                List.of(node(0, "root", 1, 2), node(1, "middle"), node(2, "end")),
                List.of(node(0, "root", 1), node(1, "middle", 3), node(3, "spacer", 2), node(2, "end")))) {
            var poses = new LinkedHashMap<Integer, Transform>();
            for (ModelNode node : nodes) poses.put(node.index(), Transform.IDENTITY);
            assertInvalidBeforeTarget(new RigPose(rig(nodes), new LocalPose(poses), 0, 1, 2), "direct");
        }
    }

    @Test void rejectsMissingExtraAndSameCountWrongPoseDomainsBeforeSampling() {
        RigPose fixture = chain(1, 1);
        for (Map<Integer, Transform> invalid : List.of(Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY),
                Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY, 2, Transform.IDENTITY, 3, Transform.IDENTITY),
                Map.of(0, Transform.IDENTITY, 1, Transform.IDENTITY, 3, Transform.IDENTITY))) {
            assertInvalidBeforeTarget(new RigPose(fixture.rig, new LocalPose(invalid), 0, 1, 2), "exact rig node set");
        }
    }

    @Test void cyclicAncestorsFailDescriptivelyBeforeAnyPoseOrDiagnosticReturns() {
        ClientAnimationRigView cyclic = rig(List.of(node(0, "root", 1), node(1, "middle", 2), node(2, "end", 0)));
        RigPose fixture = new RigPose(cyclic, chain(1, 1).pose, 0, 1, 2);
        AtomicInteger targets = new AtomicInteger();
        var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> {
            targets.incrementAndGet(); return REACHABLE;
        }, (c, result) -> fail("cyclic rig must not produce diagnostics"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> solver.modify(context(fixture, 1, 1, 0), fixture.pose));
        assertTrue(failure.getMessage().contains("Cyclic"));
        assertEquals(1, targets.get());
    }

    @Test void weightsZeroHalfOneBlendTheFullSolveAndReportItsUnweightedOutcome() {
        RigPose fixture = chain(1, 1);
        LocalPose full = new TwoBoneIkPoseComponent("root", "middle", "end", c -> REACHABLE)
                .modify(context(fixture, 1, 1, 0), fixture.pose);
        for (double weight : new double[]{0, 0.5, 1}) {
            List<Result> results = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> {
                calls.incrementAndGet(); return REACHABLE;
            }, (c, result) -> results.add(result));
            LocalPose blended = new WeightedPoseComponent(solver, c -> weight)
                    .modify(context(fixture, 1, 1, 0), fixture.pose);
            assertEquals(1, calls.get());
            assertEquals(Status.REACHED, only(results).status());
            assertEquals(REACHABLE.targetModelSpace(), only(results).effectiveTargetModelSpace());
            if (weight == 0) assertSame(fixture.pose, blended);
            for (int index : new int[]{fixture.root, fixture.middle}) {
                assertRotation(Quaternion.slerp(fixture.pose.transform(index).rotation(), full.transform(index).rotation(), (float) weight),
                        blended.transform(index).rotation());
            }
            if (weight == 1) assertVector(REACHABLE.targetModelSpace(), endpoint(fixture, blended), 2e-6);
            else assertTrue(distance(REACHABLE.targetModelSpace(), endpoint(fixture, blended)) > 0.1);
            assertPreserved(fixture, blended);
        }
    }

    @Test void namedMasksSelectOnlyIntendedJointAndMultiplyItsBlendWeight() {
        RigPose fixture = chain(1, 1);
        var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> REACHABLE);
        LocalPose full = solver.modify(context(fixture, 1, 1, 0), fixture.pose);
        for (String selected : List.of("root", "middle")) {
            int index = selected.equals("root") ? fixture.root : fixture.middle;
            int unselected = selected.equals("root") ? fixture.middle : fixture.root;
            LocalPose masked = new WeightedPoseComponent(solver, c -> 0.5, Map.of(selected, 0.5F))
                    .modify(context(fixture, 1, 1, 0), fixture.pose);
            assertSame(fixture.pose.transform(unselected), masked.transform(unselected));
            assertRotation(Quaternion.slerp(fixture.pose.transform(index).rotation(), full.transform(index).rotation(), 0.25F),
                    masked.transform(index).rotation());
            assertTrue(distance(REACHABLE.targetModelSpace(), endpoint(fixture, masked)) > 0.1);
            assertPreserved(fixture, masked);
        }
        assertSame(fixture.pose, new WeightedPoseComponent(solver, c -> 1, Map.of("end", 1F))
                .modify(context(fixture, 1, 1, 0), fixture.pose));
    }

    @Test void everyInvocationSamplesAndObservesExactlyOnceIncludingSameTimeAndZeroWeight() {
        RigPose fixture = chain(1, 1);
        ClientAnimationPoseContext context = context(fixture, 1, 1, 4);
        AtomicInteger samples = new AtomicInteger();
        List<Result> results = new ArrayList<>();
        var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> {
            assertSame(context, c);
            int invocation = samples.incrementAndGet();
            return new TwoBoneIkTarget(new Vec3(invocation == 2 ? -1 : 1, 1, 0), POLE);
        }, (c, result) -> { assertSame(context, c); results.add(result); });
        LocalPose first = solver.modify(context, fixture.pose);
        LocalPose second = solver.modify(context, fixture.pose);
        assertSame(fixture.pose, new WeightedPoseComponent(solver, c -> 0).modify(context, fixture.pose));
        assertEquals(3, samples.get());
        assertEquals(3, results.size());
        assertVector(new Vec3(1, 1, 0), endpoint(fixture, first), 2e-6);
        assertVector(new Vec3(-1, 1, 0), endpoint(fixture, second), 2e-6);
        assertNotSame(results.get(0), results.get(2));
        assertEquals(results.get(0), results.get(2));
    }

    @Test void sharedComponentResolvesCurrentRigIndicesAcrossEntitiesReloadsAndAllResets() {
        RigPose original = chain(1, 1);
        var remappedPose = new LocalPose(Map.of(8, original.pose.transform(0), 3, original.pose.transform(1),
                12, original.pose.transform(2), 6, transform(new Vec3(5, 0, 0), Quaternion.IDENTITY, 2)));
        RigPose remapped = new RigPose(rig(List.of(node(8, "root", 3), node(3, "middle", 12),
                node(12, "end"), node(6, "unrelated"))), remappedPose, 8, 3, 12);
        List<ClientAnimationPoseContext> seen = new ArrayList<>();
        var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> { seen.add(c); return REACHABLE; });
        LocalPose expected = solver.modify(context(original, 1, 1, 10), original.pose);
        for (var invocation : List.of(context(original, 2, 1, 10), context(original, 1, 1, 1),
                context(original, 1, 1, 10000))) {
            assertEquals(expected.transforms(), solver.modify(invocation, original.pose).transforms());
        }
        for (long generation : new long[]{1, 2, 3}) {
            LocalPose result = solver.modify(context(remapped, 1, generation, 0), remapped.pose);
            assertVector(REACHABLE.targetModelSpace(), endpoint(remapped, result), 2e-6);
            assertRotation(expected.transform(0).rotation(), result.transform(8).rotation());
            assertRotation(expected.transform(1).rotation(), result.transform(3).rotation());
            assertPreserved(remapped, result);
        }
        solver.reset(context(original, 1, 1, 0).instanceKey());
        solver.reset();
        assertEquals(expected.transforms(), solver.modify(context(original, 1, 1, 10), original.pose).transforms());
        assertEquals(8, seen.size());
    }

    @Test void callbackFailuresAndNullSnapshotPropagateWithoutMutatingInput() {
        RigPose fixture = chain(1, 1);
        ClientAnimationPoseContext context = context(fixture, 1, 1, 0);
        Map<Integer, Transform> original = new LinkedHashMap<>(fixture.pose.transforms());
        var targetFailure = new IllegalStateException("target unavailable");
        var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> { throw targetFailure; },
                (c, result) -> fail("target failed"));
        assertSame(targetFailure, assertThrows(IllegalStateException.class, () -> solver.modify(context, fixture.pose)));
        assertThrows(NullPointerException.class, () -> new TwoBoneIkPoseComponent("root", "middle", "end", c -> null,
                (c, result) -> fail("null target")).modify(context, fixture.pose));
        var observerFailure = new IllegalArgumentException("observer unavailable");
        AtomicInteger observed = new AtomicInteger();
        var failingObserver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> REACHABLE,
                (c, result) -> { observed.incrementAndGet(); throw observerFailure; });
        assertSame(observerFailure, assertThrows(IllegalArgumentException.class, () -> failingObserver.modify(context, fixture.pose)));
        assertEquals(1, observed.get());
        assertThrows(NullPointerException.class, () -> solver.modify(null, fixture.pose));
        assertThrows(NullPointerException.class, () -> solver.modify(context, null));
        assertEquals(original, fixture.pose.transforms());
    }

    private static void assertInvalidBeforeTarget(RigPose fixture, String messagePart) {
        var solver = new TwoBoneIkPoseComponent("root", "middle", "end", c -> { fail("invalid configuration sampled target"); return REACHABLE; },
                (c, result) -> fail("invalid configuration produced diagnostic"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> solver.modify(context(fixture, 1, 1, 0), fixture.pose));
        assertTrue(failure.getMessage().contains(messagePart), failure.getMessage());
    }

    private static TwoBoneIkPoseComponent component(TwoBoneIkTarget target, List<Result> results) {
        return new TwoBoneIkPoseComponent("root", "middle", "end", c -> target, (c, result) -> results.add(result));
    }

    private static RigPose chain(float a, float b) {
        LocalPose pose = new LocalPose(Map.of(0, Transform.IDENTITY,
                1, transform(new Vec3(a, 0, 0), Quaternion.IDENTITY, 1),
                2, transform(new Vec3(b, 0, 0), rotation(1, 1, 1, 0.3), 1)));
        return new RigPose(rig(List.of(node(0, "root", 1), node(1, "middle", 2), node(2, "end"))), pose, 0, 1, 2);
    }

    private static RigPose translated(RigPose fixture, Vec3 translation) {
        Map<Integer, Transform> transforms = new LinkedHashMap<>(fixture.pose.transforms());
        Transform before = transforms.get(fixture.root);
        transforms.put(fixture.root, new Transform(translation, before.rotation(), before.scale()));
        return new RigPose(fixture.rig, new LocalPose(transforms), fixture.root, fixture.middle, fixture.end);
    }

    private static ModelNode node(int index, String name, Integer... children) {
        return new ModelNode(index, name, Transform.IDENTITY, List.of(children), -1, -1, false);
    }

    private static ClientAnimationRigView rig(List<ModelNode> nodes) {
        return ClientAnimationRigViewTestAccess.fromNodes(nodes);
    }

    private static Transform transform(Vec3 translation, Quaternion rotation, float scale) {
        return new Transform(translation, rotation, new Vec3(scale, scale, scale));
    }

    private static Quaternion rotation(double x, double y, double z, double angle) {
        double scale = Math.sin(angle / 2) / Math.sqrt(x * x + y * y + z * z);
        return new Quaternion((float) (x * scale), (float) (y * scale), (float) (z * scale), (float) Math.cos(angle / 2));
    }

    private static ClientAnimationPoseContext context(RigPose fixture, int instance, long generation, double ticks) {
        return new ClientAnimationPoseContext(BlendInstanceKey.entity("two-bone-ik", instance),
                BlendModelKey.of("test", "arm"), generation, BlendAnimationKey.of("test", "idle"), 0, ticks, fixture.rig);
    }

    // Forward kinematics independent of the IK helper: compose the actual returned local TRS.
    private static Vec3 position(RigPose fixture, LocalPose pose, int node) {
        List<Integer> ancestry = new ArrayList<>();
        for (int current = node; ;) {
            ancestry.addFirst(current);
            var parent = fixture.rig.parentIndex(current);
            if (parent.isEmpty()) break;
            current = parent.getAsInt();
        }
        Transform composed = Transform.IDENTITY;
        for (int index : ancestry) composed = composed.compose(pose.transform(index));
        return composed.translation();
    }

    private static Vec3 endpoint(RigPose fixture, LocalPose pose) { return position(fixture, pose, fixture.end); }

    private static Vec3 unit(Vec3 value) {
        double inverse = 1 / distance(Vec3.ZERO, value);
        return new Vec3((float) (value.x() * inverse), (float) (value.y() * inverse), (float) (value.z() * inverse));
    }

    private static Vec3 offset(Vec3 origin, Vec3 direction, double distance) {
        return new Vec3((float) (origin.x() + direction.x() * distance),
                (float) (origin.y() + direction.y() * distance), (float) (origin.z() + direction.z() * distance));
    }

    private static double distance(Vec3 a, Vec3 b) {
        double x = (double) a.x() - b.x(), y = (double) a.y() - b.y(), z = (double) a.z() - b.z();
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static Result only(List<Result> results) { assertEquals(1, results.size()); return results.getFirst(); }

    private static void assertPreserved(RigPose fixture, LocalPose after) {
        assertEquals(fixture.pose.transforms().keySet(), after.transforms().keySet());
        fixture.pose.transforms().forEach((index, before) -> {
            assertSame(before.translation(), after.transform(index).translation(), "translation object " + index);
            assertSame(before.scale(), after.transform(index).scale(), "scale object " + index);
            if (index != fixture.root && index != fixture.middle) assertSame(before, after.transform(index), "untouched transform " + index);
        });
        assertEquals(fixture.pose.transform(fixture.end).rotation(), after.transform(fixture.end).rotation());
        assertFiniteUnitRotations(after);
    }

    private static void assertFiniteUnitRotations(LocalPose pose) {
        pose.transforms().values().forEach(transform -> {
            Quaternion q = transform.rotation();
            assertTrue(Float.isFinite(q.x()) && Float.isFinite(q.y()) && Float.isFinite(q.z()) && Float.isFinite(q.w()));
            assertEquals(1, (double) q.x() * q.x() + (double) q.y() * q.y() + (double) q.z() * q.z() + (double) q.w() * q.w(), 3e-7);
        });
    }

    private static void assertRotation(Quaternion expected, Quaternion actual) {
        for (Vec3 axis : List.of(new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1))) {
            assertVector(expected.rotate(axis), actual.rotate(axis), 2e-6);
        }
    }

    private static void assertVector(Vec3 expected, Vec3 actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance, "x");
        assertEquals(expected.y(), actual.y(), tolerance, "y");
        assertEquals(expected.z(), actual.z(), tolerance, "z");
    }

    private record RigPose(ClientAnimationRigView rig, LocalPose pose, int root, int middle, int end) { }
    private record ClampCase(Vec3 target, Status status, double distance) { }
}
