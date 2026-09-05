package com.liy.blendlib.core.procedural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.api.SocketQuery;
import com.liy.blendlib.core.animation.v2.AnimationV2Clip;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerState;
import com.liy.blendlib.core.animation.v2.AnimationV2DiagnosticCode;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.AnimationV2InstancePlan;
import com.liy.blendlib.core.animation.v2.AnimationV2InstanceRuntime;
import com.liy.blendlib.core.animation.v2.AnimationV2Keyframe;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.AnimationV2Limits;
import com.liy.blendlib.core.animation.v2.AnimationV2ObserverTraversal;
import com.liy.blendlib.core.animation.v2.AnimationV2PlaybackMode;
import com.liy.blendlib.core.animation.v2.AnimationV2Pose;
import com.liy.blendlib.core.animation.v2.BoneMask;
import com.liy.blendlib.core.animation.v2.BoneSchema;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProceduralRigContractsTest {
    private static final BlendModelKey MODEL = BlendModelKey.of("x3test", "rig");
    private static final BlendModelKey CHILD_MODEL = BlendModelKey.of("x3test", "child");
    private static final BlendResourceId SOCKET = id("tip");
    private static final BlendResourceId MESH = id("body");
    private static final BlendResourceId PRIMITIVE = id("body/triangle");
    private static final BlendResourceId EVENT_CONTROLLER = id("event-controller");
    private static final BlendResourceId EVENT_LAYER = id("event-layer");
    private static final BlendAnimationKey EVENT_TIMELINE = BlendAnimationKey.of("x3test", "event-timeline");

    @Test
    void configurationRejectsTopologyCardinalityDuplicatesCyclesAndUnsafeBounds() {
        BoneSchema schema = schema();
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigPlan(
                MODEL, 1L, schema, new int[] {1, 0}, List.of(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigPlan(
                MODEL, 1L, schema, new int[] {-1}, List.of(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigPlan(
                MODEL, 1L, schema, new int[] {-1, 0},
                List.of(new ProceduralSocketDefinition(SOCKET, 1, Transform.IDENTITY),
                        new ProceduralSocketDefinition(SOCKET, 1, Transform.IDENTITY)),
                List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProceduralOperation.Offset(
                0, Vec3.ZERO, Quaternion.IDENTITY, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ProceduralOperation.LookAt(
                0, new Vec3(1.0F, 0.0F, 0.0F), (float) Math.PI + 0.01F, 0.0F));
        assertThrows(IllegalArgumentException.class, () -> new Vec3(Float.NaN, 0.0F, 0.0F));
    }

    @Test
    void scopeCardinalityAndFailureDoNotReplacePreviousPublishedSnapshot() {
        ModelInstance active = instance(3L, "actor");
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan(List.of()), active);
        ProceduralEvaluationResult first = runtime.evaluate(frame(active, pose(5L), List.of(), List.of()));
        assertTrue(first.published());
        ProceduralFrameSnapshot published = first.snapshot();

        ProceduralEvaluationResult stale = runtime.evaluate(frame(instance(4L, "actor"), pose(6L), List.of(), List.of()));
        assertFalse(stale.published());
        assertSame(published, stale.snapshot());
        assertDiagnostic(stale, ProceduralDiagnosticCode.STALE_SCOPE);

        AnimationV2EvaluationSnapshot invalidCardinality = new AnimationV2EvaluationSnapshot(
                7L, new AnimationV2Pose(List.of(Transform.IDENTITY)), Map.of(), List.of());
        ProceduralEvaluationResult cardinality = runtime.evaluate(frame(active, invalidCardinality, List.of(), List.of()));
        assertFalse(cardinality.published());
        assertSame(published, cardinality.snapshot());
        assertDiagnostic(cardinality, ProceduralDiagnosticCode.CARDINALITY_MISMATCH);
    }

    @Test
    void runtimeBindsExactScopeAndNeverRollsBackRevisionOrLatest() {
        ModelInstance firstInstance = instance(3L, "first");
        ModelInstance secondInstance = instance(3L, "second");
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan(List.of()), firstInstance);

        ProceduralEvaluationResult first = runtime.evaluate(frame(firstInstance, pose(10L), List.of(), List.of()));
        ProceduralEvaluationResult crossInstance = runtime.evaluate(frame(secondInstance, pose(11L), List.of(), List.of()));
        ProceduralEvaluationResult staleRevision = runtime.evaluate(frame(firstInstance, pose(1L), List.of(), List.of()));
        ProceduralEvaluationResult equalRevision = runtime.evaluate(frame(firstInstance, pose(10L), List.of(), List.of()));

        assertTrue(first.published());
        assertFalse(crossInstance.published(), "a same-model/generation sibling instance must be rejected");
        assertFalse(staleRevision.published(), "a lower revision must not republish or roll latest back");
        assertFalse(equalRevision.published(), "an equal revision must not publish twice");
        assertSame(first.snapshot(), crossInstance.snapshot());
        assertSame(first.snapshot(), staleRevision.snapshot());
        assertDiagnostic(crossInstance, ProceduralDiagnosticCode.STALE_SCOPE);
        assertDiagnostic(staleRevision, ProceduralDiagnosticCode.STALE_REVISION);
        assertDiagnostic(equalRevision, ProceduralDiagnosticCode.STALE_REVISION);
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigRuntime(plan(List.of()), instance(4L, "reload")));
        ModelInstance reloadInstance = instance(4L, "reload");
        ProceduralEvaluationResult reloaded = new ProceduralRigRuntime(plan(4L, List.of()), reloadInstance)
                .evaluate(frame(reloadInstance, pose(1L), List.of(), List.of()));
        assertTrue(reloaded.published(), "a generation replacement must have an independent runtime and revision fence");
        assertTrue(reloaded.snapshot() != first.snapshot(), "a reload must not expose a prior scope snapshot");
    }

    @Test
    void rotationOrderingDoesNotDependOnCallerOrderOrQuaternionSign() {
        Quaternion xNinety = new Quaternion((float) Math.sin(Math.PI / 4.0D), 0.0F, 0.0F,
                (float) Math.cos(Math.PI / 4.0D));
        Quaternion yNinety = new Quaternion(0.0F, (float) Math.sin(Math.PI / 4.0D), 0.0F,
                (float) Math.cos(Math.PI / 4.0D));
        List<ProceduralDirective> xy = List.of(
                new ProceduralDirective(id("same-source"), 4,
                        new ProceduralOperation.Offset(1, Vec3.ZERO, xNinety, 1.0F)),
                new ProceduralDirective(id("same-source"), 4,
                        new ProceduralOperation.Offset(1, Vec3.ZERO, yNinety, 1.0F)));
        List<ProceduralDirective> yx = List.of(xy.get(1), xy.get(0));

        ModelInstance firstInstance = instance(3L, "rotation-a");
        ModelInstance secondInstance = instance(3L, "rotation-b");
        ProceduralEvaluationResult first = new ProceduralRigRuntime(plan(List.of()), firstInstance)
                .evaluate(frame(firstInstance, pose(1L), xy, List.of()));
        ProceduralEvaluationResult second = new ProceduralRigRuntime(plan(List.of()), secondInstance)
                .evaluate(frame(secondInstance, pose(1L), yx, List.of()));

        assertTrue(first.published());
        assertTrue(second.published());
        assertEquals(first.snapshot().localPose().transform(1).rotation(),
                second.snapshot().localPose().transform(1).rotation(),
                "same operations in reverse caller order must canonicalize identically");
        assertEquals(new ProceduralOperation.RotationOffset(1, xNinety),
                new ProceduralOperation.RotationOffset(1,
                        new Quaternion(-xNinety.x(), -xNinety.y(), -xNinety.z(), -xNinety.w())),
                "q and -q must share a canonical rotation operation identity");
        assertEquals(Quaternion.IDENTITY, new ProceduralOperation.RotationOffset(1,
                new Quaternion(-0.0F, -0.0F, -0.0F, -1.0F)).rotationOffset(),
                "canonicalization must erase negative zero and quaternion sign ambiguity");
    }

    @Test
    void childModelTopologyUsesActualDescriptorsRatherThanCallerLineageClaims() {
        ProceduralAttachmentDescriptor childB = attachment("a-to-b", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(CHILD_MODEL));
        ProceduralAttachmentDescriptor childA = attachment("b-to-a", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(MODEL));

        assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.compile(
                3L, Map.of(MODEL, List.of(childB), CHILD_MODEL, List.of(childA))));
        ProceduralAttachmentGraph graph = ProceduralAttachmentGraph.compile(
                3L, Map.of(MODEL, List.of(childB), CHILD_MODEL, List.of()));
        assertEquals(List.of(CHILD_MODEL), graph.childrenOf(MODEL));
        LinkedHashMap<BlendModelKey, List<ProceduralAttachmentDescriptor>> eightEdges = new LinkedHashMap<>();
        List<BlendModelKey> models = new ArrayList<>();
        for (int index = 0; index <= ProceduralLimits.MAX_ATTACHMENT_DEPTH; index++) {
            models.add(BlendModelKey.of("x3test", "topology-" + index));
        }
        for (int index = 0; index < ProceduralLimits.MAX_ATTACHMENT_DEPTH; index++) {
            eightEdges.put(models.get(index), List.of(attachment("edge-" + index,
                    new ProceduralAttachmentAnchor.Bone(0), new ProceduralAttachmentPayload.ChildModel(models.get(index + 1)))));
        }
        eightEdges.put(models.getLast(), List.of());
        assertEquals(ProceduralLimits.MAX_ATTACHMENT_DEPTH, ProceduralAttachmentGraph.compile(3L, eightEdges).maximumDepth());
        BlendModelKey ninth = BlendModelKey.of("x3test", "topology-9");
        eightEdges.put(models.getLast(), List.of(attachment("edge-8", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(ninth))));
        eightEdges.put(ninth, List.of());
        assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.compile(3L, eightEdges));
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigPlan(
                MODEL, 3L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(), List.of(childB)));
        assertThrows(IllegalArgumentException.class, () -> new ProceduralFrameInput(
                instance(3L, "dynamic-child"), pose(1L), List.of(), List.of(childB), List.of()));
    }

    @Test
    void visualEventCallerProvenanceCannotForgeAControllerlessEmptyTimelineCrossing() {
        assertThrows(IllegalArgumentException.class, () -> new ProceduralVisualEventProvenance(
                id("does-not-exist-controller"), id("does-not-exist"), 73L, 99, 0L, 1L, 900.0D, 900.0D),
                "empty or reversed caller boundaries are not accepted as event provenance");
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigRuntime(
                eventPlan(List.of(new ProceduralVisualEventMarker(id("unknown-controller"), EVENT_TIMELINE, 0, 0.5D,
                        id("unknown-controller-event"), 0, new ProceduralVisualEventPayload.TrailStart(id("trail"))))),
                instance(3L, "unknown-controller"), eventSource(schema())),
                "marker catalogs must name a controller from the exact bound X2 runtime plan");
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigRuntime(
                eventPlan(List.of(new ProceduralVisualEventMarker(EVENT_CONTROLLER,
                        BlendAnimationKey.of("x3test", "unknown-timeline"), 0, 0.5D,
                        id("unknown-timeline-event"), 0, new ProceduralVisualEventPayload.TrailStart(id("trail"))))),
                instance(3L, "unknown-timeline"), eventSource(schema())),
                "marker catalogs must name a state/timeline from the exact bound X2 controller plan");

        EventHarness harness = eventHarness("forged-event", List.of(marker(
                "legal-trail", 0, 0, new ProceduralVisualEventPayload.TrailStart(id("trail")))));
        assertTrue(harness.runtime().evaluate(frame(harness.instance(), harness.source().advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot crossing = harness.source().advance(0.75D);
        ProceduralVisualEvent forged = new ProceduralVisualEvent(id("forged-event"), 0,
                new ProceduralVisualEventProvenance(id("does-not-exist-controller"), id("does-not-exist"),
                        73L, 99, 0L, crossing.revision(), 900.0D, 901.0D),
                new ProceduralVisualEventPayload.TrailStart(id("trail")));

        ProceduralEvaluationResult result = harness.runtime().evaluate(new ProceduralFrameInput(
                harness.instance(), crossing, List.of(), List.of(forged)));

        assertFalse(result.published(), "a caller must not publish an event from an empty forged X2 playhead map");
        assertDiagnostic(result, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE);
        AnimationV2EvaluationSnapshot sameValueButForeignSnapshot = new AnimationV2EvaluationSnapshot(
                crossing.revision(), crossing.pose(), crossing.playheads(), crossing.diagnostics());
        ProceduralEvaluationResult foreignSnapshot = harness.runtime().evaluate(new ProceduralFrameInput(
                harness.instance(), sameValueButForeignSnapshot, List.of(), List.of()));
        assertFalse(foreignSnapshot.published(), "a copied snapshot cannot impersonate the bound X2 runtime publication");
        assertDiagnostic(foreignSnapshot, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE);

        ProceduralEvaluationResult trusted = harness.runtime().evaluate(new ProceduralFrameInput(
                harness.instance(), crossing, List.of(), List.of()));
        assertTrue(trusted.published(), "a failed forged frame must not consume the real marker crossing");
        assertEquals(1, trusted.snapshot().visualEvents().events().size());
    }

    @Test
    void attachmentGraphRejectsMissingOwnersAndTwentyThousandEdgeInputWithControlledFailures() {
        ProceduralAttachmentDescriptor child = attachment("missing-owner", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(CHILD_MODEL));
        assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.compile(3L, Map.of(MODEL, List.of(child))),
                "a reload graph cannot infer an unregistered child owner from a partial caller map");
    }

    @Test
    void attachmentGraphRejectsTwentyThousandEdgeInputWithControlledFailureRatherThanStackOverflow() {
        LinkedHashMap<BlendModelKey, List<ProceduralAttachmentDescriptor>> deep = new LinkedHashMap<>();
        List<BlendModelKey> models = new ArrayList<>();
        for (int index = 0; index <= 20_000; index++) {
            models.add(BlendModelKey.of("x3test", "adversarial-depth-" + index));
        }
        for (int index = 0; index < 20_000; index++) {
            deep.put(models.get(index), List.of(attachment("adversarial-edge-" + index,
                    new ProceduralAttachmentAnchor.Bone(0),
                    new ProceduralAttachmentPayload.ChildModel(models.get(index + 1)))));
        }
        deep.put(models.getLast(), List.of());
        assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.compile(3L, deep),
                "a hostile deep graph must fail through the bounded configuration path rather than recurse the VM stack");
    }

    @Test
    void attachmentGraphPublicationRejectsSplitSameGenerationCyclesForeignGraphsAndGenerationMixes() {
        ProceduralAttachmentDescriptor aToB = attachment("a-to-b", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(CHILD_MODEL));
        ProceduralAttachmentDescriptor bToA = attachment("b-to-a", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(MODEL));
        ProceduralAttachmentGraph aToBGraph = ProceduralAttachmentGraph.compile(3L,
                Map.of(MODEL, List.of(aToB), CHILD_MODEL, List.of()));
        ProceduralAttachmentGraph bToAGraph = ProceduralAttachmentGraph.compile(3L,
                Map.of(CHILD_MODEL, List.of(bToA), MODEL, List.of()));
        ProceduralRigPlan planA = new ProceduralRigPlan(
                MODEL, 3L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(),
                List.of(aToB), aToBGraph);
        ProceduralRigPlan planB = new ProceduralRigPlan(
                CHILD_MODEL, 3L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(),
                List.of(bToA), bToAGraph);

        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigRuntime(planA, instance(3L, "split-graph")),
                "a graph with child edges cannot be evaluated before all owners publish the same complete reload graph");
        assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.publish(List.of(planA, planB)),
                "same-generation A->B and B->A plans compiled from separate graph identities cannot be stitched into one reload");
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigPlan(
                MODEL, 3L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(),
                List.of(aToB), bToAGraph),
                "an owner plan cannot attach its descriptors to a foreign graph identity that has different direct edges");
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigPlan(
                MODEL, 4L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(),
                List.of(aToB), aToBGraph),
                "a reload generation cannot reuse an earlier complete graph snapshot");

        ProceduralRigPlan completeChildPlan = new ProceduralRigPlan(
                CHILD_MODEL, 3L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(),
                List.of(), aToBGraph);
        try {
            ProceduralAttachmentGraph.publish(List.of(planA, completeChildPlan));
            assertTrue(new ProceduralRigRuntime(planA, instance(3L, "complete-graph"))
                    .evaluate(frame(instance(3L, "complete-graph"), pose(1L), List.of(), List.of())).published());
        } finally {
            aToBGraph.close();
            bToAGraph.close();
        }
    }

    @Test
    void customClientEventFreezesFieldOrderAndRejectsOverlongUtf16TextRatherThanTruncating() {
        LinkedHashMap<String, String> reverse = new LinkedHashMap<>();
        reverse.put("zeta", "last");
        reverse.put("alpha", "first");
        ProceduralVisualEventPayload.CustomClientEvent payload = new ProceduralVisualEventPayload.CustomClientEvent(
                id("custom"), reverse);

        assertIterableEquals(List.of("alpha", "zeta"), payload.fields().keySet(),
                "semantic custom payload maps must have a canonical frozen key order");
        assertThrows(IllegalArgumentException.class, () -> new ProceduralVisualEventPayload.CustomClientEvent(id("custom"),
                Map.of("field", "x".repeat(ProceduralLimits.MAX_CUSTOM_EVENT_FIELD_UTF16_CODE_UNITS + 1))),
                "65 UTF-16 code units must fail closed rather than silently truncate to 64");
        String exactlySixtyFourCodeUnits = "x".repeat(62) + "\uD83D\uDE00";
        assertEquals(ProceduralLimits.MAX_CUSTOM_EVENT_FIELD_UTF16_CODE_UNITS, exactlySixtyFourCodeUnits.length());
        assertEquals(exactlySixtyFourCodeUnits, new ProceduralVisualEventPayload.CustomClientEvent(
                id("custom"), Map.of("field", exactlySixtyFourCodeUnits)).fields().get("field"));
        String sixtyFiveCodeUnitsAcrossSurrogateBoundary = "x".repeat(63) + "\uD83D\uDE00";
        assertEquals(ProceduralLimits.MAX_CUSTOM_EVENT_FIELD_UTF16_CODE_UNITS + 1,
                sixtyFiveCodeUnitsAcrossSurrogateBoundary.length());
        assertThrows(IllegalArgumentException.class, () -> new ProceduralVisualEventPayload.CustomClientEvent(
                id("custom"), Map.of("field", sixtyFiveCodeUnitsAcrossSurrogateBoundary)));
    }

    @Test
    void visualEventsUsePersistentReplayFenceAndNoPublicUncheckedBatchConstructor() {
        EventHarness harness = eventHarness("events", List.of(marker(
                "replay", 1, 1, new ProceduralVisualEventPayload.TrailStart(id("trail")))));
        assertTrue(harness.runtime().evaluate(frame(harness.instance(), harness.source().advance(0.0D), List.of(), List.of())).published());
        ProceduralEvaluationResult first = harness.runtime().evaluate(frame(
                harness.instance(), harness.source().advance(0.75D), List.of(), List.of()));
        ProceduralEvaluationResult repeat = harness.runtime().evaluate(frame(
                harness.instance(), harness.source().advance(0.01D), List.of(), List.of()));

        assertTrue(first.published());
        assertEquals(1, first.snapshot().visualEvents().events().size());
        assertTrue(repeat.published());
        assertEquals(0, repeat.snapshot().visualEvents().events().size(),
                "a crossing is observed once and cannot replay on a later forward frame");
        EventHarness freshRuntime = eventHarness("events-rescope", List.of(marker(
                "replay", 1, 1, new ProceduralVisualEventPayload.TrailStart(id("trail")))));
        assertTrue(freshRuntime.runtime().evaluate(frame(
                freshRuntime.instance(), freshRuntime.source().advance(0.0D), List.of(), List.of())).published());
        ProceduralEvaluationResult rescopeFirst = freshRuntime.runtime().evaluate(frame(
                freshRuntime.instance(), freshRuntime.source().advance(0.75D), List.of(), List.of()));
        assertTrue(rescopeFirst.published());
        assertEquals(1, rescopeFirst.snapshot().visualEvents().events().size(),
                "a new exact runtime scope receives an independent replay fence and observation state");
        assertEquals(0, ProceduralVisualEventBatch.class.getConstructors().length,
                "external callers must not construct an unchecked event batch");
    }

    @Test
    void exactX2TraversalPublishesLoopWrapAndAutomaticNextEntryMarkersWithoutCallerProvenance() {
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r5-loop");
        AnimationV2InstanceRuntime loopSource = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness loopHarness = eventHarness("r5-loop", List.of(
                marker(loop, "loop-crossing", 0, 0.1D, 0, new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker(loop, "tail-crossing", 1, 0.9D, 0, new ProceduralVisualEventPayload.TrailStop(id("trail")))), loopSource);
        assertTrue(loopHarness.runtime().evaluate(frame(
                loopHarness.instance(), loopSource.advance(0.0D), List.of(), List.of())).published());
        ProceduralEvaluationResult beforeWrap = loopHarness.runtime().evaluate(frame(
                loopHarness.instance(), loopSource.advance(0.75D), List.of(), List.of()));
        ProceduralEvaluationResult wrapped = loopHarness.runtime().evaluate(frame(
                loopHarness.instance(), loopSource.advance(0.50D), List.of(), List.of()));

        assertTrue(beforeWrap.published());
        assertEquals(1, beforeWrap.snapshot().visualEvents().events().size());
        assertTrue(wrapped.published(), "a real X2 LOOP wrap must not reject the complete X3 frame");
        assertEquals(2, wrapped.snapshot().visualEvents().events().size(),
                "one wrap must retain both the tail crossing and the marker crossed after the new loop entry");
        ProceduralVisualEventProvenance before = beforeWrap.snapshot().visualEvents().events().getFirst().provenance();
        ProceduralVisualEventProvenance tail = wrapped.snapshot().visualEvents().events().stream()
                .filter(event -> event.id().equals(id("tail-crossing"))).findFirst().orElseThrow().provenance();
        ProceduralVisualEventProvenance after = wrapped.snapshot().visualEvents().events().stream()
                .filter(event -> event.id().equals(id("loop-crossing"))).findFirst().orElseThrow().provenance();
        assertEquals(before.loopEpoch(), tail.loopEpoch(), "the tail belongs to the pre-wrap loop occurrence");
        assertEquals(before.occurrence(), tail.occurrence(), "the tail must retain the pre-wrap occurrence identity");
        assertTrue(after.loopEpoch() > before.loopEpoch(), "loop epochs must distinguish real repeated loop crossings");
        assertTrue(after.occurrence() > before.occurrence(), "occurrences must increase deterministically across a wrap");

        BlendAnimationKey intro = BlendAnimationKey.of("x3test", "r5-intro");
        BlendAnimationKey active = BlendAnimationKey.of("x3test", "r5-active");
        AnimationV2InstanceRuntime nextSource = eventSource(schema(), intro, Map.of(
                intro, eventState(intro, AnimationV2PlaybackMode.ONCE, 0.01D, active, schema()),
                active, eventState(active, AnimationV2PlaybackMode.HOLD, 1.0D, null, schema())));
        EventHarness nextHarness = eventHarness("r5-next", List.of(marker(
                active, "automatic-entry", 0, 0.005D, 0,
                new ProceduralVisualEventPayload.TrailStart(id("trail")))), nextSource);
        assertTrue(nextHarness.runtime().evaluate(frame(
                nextHarness.instance(), nextSource.advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot transitioned = nextSource.advance(0.02D);
        assertEquals(active, transitioned.playheads().get(EVENT_CONTROLLER).state());
        ProceduralEvaluationResult automaticNext = nextHarness.runtime().evaluate(frame(
                nextHarness.instance(), transitioned, List.of(), List.of()));

        assertTrue(automaticNext.published(), "automatic next transition must remain a complete X3 frame");
        assertEquals(1, automaticNext.snapshot().visualEvents().events().size(),
                "leftover advance time in the entered state must cross its marker exactly once");
        assertEquals(active.resourceId(), automaticNext.snapshot().visualEvents().events().getFirst().provenance().timelineId());
    }

    @Test
    void visualEventMarkerBoundaryUsesTheExactOpenClosedOwnerSegmentAndDefersLaterFloats() {
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r6-exact-boundary");
        AnimationV2InstanceRuntime source = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        double segmentEnd = 0.25D;
        EventHarness harness = eventHarness("r6-exact-boundary", List.of(
                marker(loop, "r6-next-down", 0, Math.nextDown(segmentEnd), 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker(loop, "r6-exact-end", 1, segmentEnd, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker(loop, "r6-next-up", 2, Math.nextUp(segmentEnd), 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker(loop, "r6-epsilon-past", 3, 0.2500000005D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail")))), source);

        assertThrows(IllegalArgumentException.class, () -> marker(loop, "r6-forbidden-start", 4, 0.0D, 0,
                new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                "the catalog itself keeps a marker out of the timeline's open left endpoint");

        assertTrue(harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.0D), List.of(), List.of())).published());
        ProceduralEvaluationResult exactEnd = harness.runtime().evaluate(frame(
                harness.instance(), source.advance(segmentEnd), List.of(), List.of()));

        assertTrue(exactEnd.published());
        assertEquals(Set.of(id("r6-next-down"), id("r6-exact-end")), exactEnd.snapshot().visualEvents().events().stream()
                .map(ResolvedProceduralVisualEvent::id).collect(java.util.stream.Collectors.toSet()),
                "only markers in the exact owner-produced (start, end] segment may publish");
        assertEquals(2, exactEnd.snapshot().visualEvents().events().size());

        ProceduralEvaluationResult laterTraversal = harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.01D), List.of(), List.of()));
        assertTrue(laterTraversal.published());
        assertEquals(Set.of(id("r6-next-up"), id("r6-epsilon-past")), laterTraversal.snapshot().visualEvents().events().stream()
                .map(ResolvedProceduralVisualEvent::id).collect(java.util.stream.Collectors.toSet()),
                "a float not yet crossed must wait for a later real traversal and must not enter the replay fence early");
    }

    @Test
    void visualEventMarkerDurationValidationDoesNotUseAnEpsilonAllowance() {
        BlendAnimationKey shortLoop = BlendAnimationKey.of("x3test", "r6-duration-boundary");
        AnimationV2InstanceRuntime source = eventSource(schema(), shortLoop, Map.of(
                shortLoop, eventState(shortLoop, AnimationV2PlaybackMode.LOOP, 0.25D, null, schema())));

        EventHarness atDuration = eventHarness("r6-duration-at-bound", List.of(
                marker(shortLoop, "r6-at-duration", 0, 0.25D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail")))), source);
        assertNotNull(atDuration.runtime(), "the exact frozen duration is a legal closed right endpoint");
        assertThrows(IllegalArgumentException.class, () -> eventHarness("r6-duration-boundary", List.of(
                marker(shortLoop, "r6-outside-duration", 0, 0.2500000005D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail")))), source),
                "a marker genuinely past the frozen duration must never be accepted by epsilon");
    }

    @Test
    void clipDurationBoundariesDoNotPreconsumeX3EventsOrAutomaticNextResidualProvenance() {
        double duration = 0.25D;
        double nextDown = Math.nextDown(duration);
        double exactResidual = duration - nextDown;
        double nextUp = Math.nextUp(duration);
        double postDurationResidual = nextUp - duration;

        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r7-duration-loop");
        AnimationV2InstanceRuntime loopSource = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, duration, null, schema())));
        EventHarness loopHarness = eventHarness("r7-duration-loop", List.of(marker(
                loop, "r7-loop-duration", 0, duration, 0,
                new ProceduralVisualEventPayload.TrailStart(id("trail")))), loopSource);
        assertTrue(loopHarness.runtime().evaluate(frame(
                loopHarness.instance(), loopSource.advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot loopBelow = loopSource.advance(nextDown);
        ProceduralEvaluationResult loopBeforeEnd = loopHarness.runtime().evaluate(frame(
                loopHarness.instance(), loopBelow, List.of(), List.of()));
        assertTrue(loopBeforeEnd.published());
        assertExactDouble(nextDown, loopBelow.playheads().get(EVENT_CONTROLLER).timeSeconds());
        AnimationV2ObserverTraversal.ControllerTraversal loopBelowTraversal = loopBelow.observerTraversal()
                .controller(EVENT_CONTROLLER);
        assertNotNull(loopBelowTraversal);
        assertEquals(1, loopBelowTraversal.segments().size());
        assertExactDouble(nextDown, loopBelowTraversal.segments().getFirst().endInclusiveSeconds());
        assertEquals(0L, loopBelowTraversal.continuity().terminalAnchor().loopEpoch());
        assertEquals(0L, loopBelowTraversal.continuity().terminalAnchor().occurrence());
        assertTrue(loopBeforeEnd.snapshot().visualEvents().events().isEmpty(),
                "nextDown(duration) must not publish or consume the exact-duration X3 marker");

        AnimationV2EvaluationSnapshot loopAtEnd = loopSource.advance(exactResidual);
        ProceduralEvaluationResult loopExactEnd = loopHarness.runtime().evaluate(frame(
                loopHarness.instance(), loopAtEnd, List.of(), List.of()));
        assertTrue(loopExactEnd.published());
        assertEquals(1, loopExactEnd.snapshot().visualEvents().events().size(),
                "the true remaining duration-nextDown must cross the marker exactly once");
        ResolvedProceduralVisualEvent loopEvent = loopExactEnd.snapshot().visualEvents().events().getFirst();
        assertEquals(id("r7-loop-duration"), loopEvent.id());
        assertEquals(loop.resourceId(), loopEvent.provenance().timelineId());
        assertEquals(0L, loopEvent.provenance().loopEpoch());
        assertEquals(0L, loopEvent.provenance().occurrence());
        assertEquals(loopAtEnd.revision(), loopEvent.provenance().sourceRevision());
        assertExactDouble(nextDown, loopEvent.provenance().frameStartInclusive());
        assertExactDouble(Math.nextUp(duration), loopEvent.provenance().frameEndExclusive());
        assertExactDouble(0.0D, loopAtEnd.playheads().get(EVENT_CONTROLLER).timeSeconds());
        assertEquals(1L, loopAtEnd.observerTraversal().controller(EVENT_CONTROLLER).continuity()
                .terminalAnchor().loopEpoch());
        assertEquals(1L, loopAtEnd.observerTraversal().controller(EVENT_CONTROLLER).continuity()
                .terminalAnchor().occurrence());
        ProceduralEvaluationResult loopReplay = loopHarness.runtime().evaluate(frame(
                loopHarness.instance(), loopSource.advance(0.0D), List.of(), List.of()));
        assertTrue(loopReplay.published());
        assertTrue(loopReplay.snapshot().visualEvents().events().isEmpty(),
                "a later no-movement publication must not replay the already committed duration marker");

        for (AnimationV2PlaybackMode mode : List.of(AnimationV2PlaybackMode.ONCE, AnimationV2PlaybackMode.HOLD)) {
            BlendAnimationKey terminal = BlendAnimationKey.of("x3test", "r7-duration-" + mode.name().toLowerCase());
            AnimationV2InstanceRuntime terminalSource = eventSource(schema(), terminal, Map.of(
                    terminal, eventState(terminal, mode, duration, null, schema())));
            EventHarness terminalHarness = eventHarness("r7-duration-" + mode.name().toLowerCase(), List.of(marker(
                    terminal, "r7-" + mode.name().toLowerCase() + "-duration", 0, duration, 0,
                    new ProceduralVisualEventPayload.TrailStart(id("trail")))), terminalSource);
            assertTrue(terminalHarness.runtime().evaluate(frame(
                    terminalHarness.instance(), terminalSource.advance(0.0D), List.of(), List.of())).published());
            ProceduralEvaluationResult terminalBeforeEnd = terminalHarness.runtime().evaluate(frame(
                    terminalHarness.instance(), terminalSource.advance(nextDown), List.of(), List.of()));
            assertTrue(terminalBeforeEnd.published());
            assertTrue(terminalBeforeEnd.snapshot().visualEvents().events().isEmpty(),
                    mode + " must not turn nextDown(duration) into a terminal marker event");
            AnimationV2EvaluationSnapshot terminalAtEnd = terminalSource.advance(exactResidual);
            ProceduralEvaluationResult terminalExactEnd = terminalHarness.runtime().evaluate(frame(
                    terminalHarness.instance(), terminalAtEnd, List.of(), List.of()));
            assertTrue(terminalExactEnd.published());
            assertEquals(1, terminalExactEnd.snapshot().visualEvents().events().size(),
                    mode + " must publish its duration marker only after the exact residual");
            assertEquals(terminal.resourceId(), terminalExactEnd.snapshot().visualEvents().events().getFirst()
                    .provenance().timelineId());
            assertExactDouble(duration, terminalAtEnd.playheads().get(EVENT_CONTROLLER).timeSeconds());
        }

        BlendAnimationKey intro = BlendAnimationKey.of("x3test", "r7-duration-intro");
        BlendAnimationKey active = BlendAnimationKey.of("x3test", "r7-duration-active");
        Map<BlendAnimationKey, AnimationV2ControllerState> nextStates = Map.of(
                intro, eventState(intro, AnimationV2PlaybackMode.ONCE, duration, active, schema()),
                active, eventState(active, AnimationV2PlaybackMode.HOLD, duration, null, schema()));
        List<ProceduralVisualEventMarker> nextMarkers = List.of(
                marker(intro, "r7-next-intro-duration", 0, duration, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker(active, "r7-next-active-residual", 1, postDurationResidual, 0,
                        new ProceduralVisualEventPayload.TrailStop(id("trail"))));
        AnimationV2InstanceRuntime nextSource = eventSource(schema(), intro, nextStates);
        EventHarness nextHarness = eventHarness("r7-duration-next", nextMarkers, nextSource);
        assertTrue(nextHarness.runtime().evaluate(frame(
                nextHarness.instance(), nextSource.advance(0.0D), List.of(), List.of())).published());
        ProceduralEvaluationResult nextBeforeEnd = nextHarness.runtime().evaluate(frame(
                nextHarness.instance(), nextSource.advance(nextDown), List.of(), List.of()));
        assertTrue(nextBeforeEnd.published());
        assertTrue(nextBeforeEnd.snapshot().visualEvents().events().isEmpty(),
                "automatic next must keep a strictly sub-duration source playhead and emit no terminal marker");
        AnimationV2EvaluationSnapshot nextAtEnd = nextSource.advance(exactResidual);
        ProceduralEvaluationResult nextExactEnd = nextHarness.runtime().evaluate(frame(
                nextHarness.instance(), nextAtEnd, List.of(), List.of()));
        assertTrue(nextExactEnd.published());
        assertEquals(active, nextAtEnd.playheads().get(EVENT_CONTROLLER).state());
        assertExactDouble(0.0D, nextAtEnd.playheads().get(EVENT_CONTROLLER).timeSeconds());
        assertEquals(1, nextExactEnd.snapshot().visualEvents().events().size());
        assertEquals(intro.resourceId(), nextExactEnd.snapshot().visualEvents().events().getFirst()
                .provenance().timelineId());

        AnimationV2InstanceRuntime nextUpSource = eventSource(schema(), intro, nextStates);
        EventHarness nextUpHarness = eventHarness("r7-duration-next-up", nextMarkers, nextUpSource);
        assertTrue(nextUpHarness.runtime().evaluate(frame(
                nextUpHarness.instance(), nextUpSource.advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot nextUpSnapshot = nextUpSource.advance(nextUp);
        ProceduralEvaluationResult nextUpResult = nextUpHarness.runtime().evaluate(frame(
                nextUpHarness.instance(), nextUpSnapshot, List.of(), List.of()));
        assertTrue(nextUpResult.published());
        assertEquals(active, nextUpSnapshot.playheads().get(EVENT_CONTROLLER).state());
        assertExactDouble(postDurationResidual, nextUpSnapshot.playheads().get(EVENT_CONTROLLER).timeSeconds());
        assertEquals(2, nextUpResult.snapshot().visualEvents().events().size(),
                "nextUp(duration) must carry its true residual into the automatic-next state");
        ResolvedProceduralVisualEvent residualEvent = nextUpResult.snapshot().visualEvents().events().stream()
                .filter(event -> event.id().equals(id("r7-next-active-residual"))).findFirst().orElseThrow();
        assertEquals(active.resourceId(), residualEvent.provenance().timelineId());
        assertEquals(1L, residualEvent.provenance().occurrence());
        assertEquals(nextUpSnapshot.revision(), residualEvent.provenance().sourceRevision());
        assertExactDouble(0.0D, residualEvent.provenance().frameStartInclusive());
        assertExactDouble(Math.nextUp(postDurationResidual), residualEvent.provenance().frameEndExclusive());
    }

    @Test
    void hugeFiniteLoopIdentityRemainsExactInRealX3ProvenanceAfterQuietArm() {
        double duration = Math.nextDown(Math.scalb(1.0D, -53));
        double terminal = Math.scalb(1.0D, -106);
        double markerTime = Math.nextUp(terminal);
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r8-exact-large-loop");
        AnimationV2InstanceRuntime source = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, duration, null, schema())));
        EventHarness harness = eventHarness("r8-exact-large-loop", List.of(marker(
                loop, "r8-exact-large-marker", 0, markerTime, 0,
                new ProceduralVisualEventPayload.TrailStart(id("trail")))), source);

        AnimationV2EvaluationSnapshot huge = source.advance(1.0D);
        AnimationV2ObserverTraversal.ControllerTraversal hugeTraversal = huge.observerTraversal()
                .controller(EVENT_CONTROLLER);
        assertSame(huge, source.latestSnapshot());
        assertExactDouble(terminal, huge.playheads().get(EVENT_CONTROLLER).timeSeconds());
        assertEquals(9_007_199_254_740_993L, hugeTraversal.continuity().terminalAnchor().loopEpoch());
        assertEquals(9_007_199_254_740_993L, hugeTraversal.continuity().terminalAnchor().occurrence());
        assertTrue(hugeTraversal.truncated());
        assertTrue(hugeTraversal.segments().isEmpty());

        AnimationV2EvaluationSnapshot quiet = source.advance(0.0D);
        ProceduralEvaluationResult armed = harness.runtime().evaluate(frame(
                harness.instance(), quiet, List.of(), List.of()));
        assertTrue(armed.published(), "a real X3 runtime must arm from the exact latest owner snapshot");
        assertTrue(armed.snapshot().visualEvents().events().isEmpty());

        AnimationV2EvaluationSnapshot crossing = source.advance(markerTime - terminal);
        ProceduralEvaluationResult result = harness.runtime().evaluate(frame(
                harness.instance(), crossing, List.of(), List.of()));
        assertTrue(result.published());
        assertEquals(1, result.snapshot().visualEvents().events().size());
        ResolvedProceduralVisualEvent event = result.snapshot().visualEvents().events().getFirst();
        assertEquals(crossing.revision(), event.provenance().sourceRevision());
        assertEquals(9_007_199_254_740_993L, event.provenance().loopEpoch());
        assertEquals(9_007_199_254_740_993L, event.provenance().occurrence());
        assertExactDouble(terminal, event.provenance().frameStartInclusive());
        assertExactDouble(Math.nextUp(markerTime), event.provenance().frameEndExclusive());
    }

    @Test
    void legacySnapshotConstructorRemainsTheOnlyPublicObserverConstructionBoundary() {
        AnimationV2EvaluationSnapshot legacy = new AnimationV2EvaluationSnapshot(1L, schema().restPose(), Map.of(), List.of());
        assertTrue(legacy.observerTraversal().controllers().isEmpty());
        for (Class<?> observerType : List.of(
                AnimationV2ObserverTraversal.class,
                AnimationV2ObserverTraversal.ControllerTraversal.class,
                AnimationV2ObserverTraversal.Continuity.class,
                AnimationV2ObserverTraversal.Anchor.class,
                AnimationV2ObserverTraversal.Publication.class,
                AnimationV2ObserverTraversal.Segment.class)) {
            assertEquals(0, observerType.getConstructors().length,
                    observerType.getSimpleName() + " must not expose a public mutable provenance constructor");
        }
    }

    @Test
    void armedVisualEventObserverCatchesUpBoundedSkippedX2PublicationsWithoutLossOrDuplication() {
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r6-skipped-publications");
        AnimationV2InstanceRuntime source = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness harness = eventHarness("r6-skipped-publications", List.of(
                marker(loop, "r6-first-skipped", 0, 0.25D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker(loop, "r6-second-skipped", 1, 0.55D, 0,
                        new ProceduralVisualEventPayload.TrailStop(id("trail"))),
                marker(loop, "r6-current", 2, 0.80D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail")))), source);

        assertTrue(harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot firstSkipped = source.advance(0.30D);
        AnimationV2EvaluationSnapshot secondSkipped = source.advance(0.30D);
        ProceduralEvaluationResult catchUp = harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.30D), List.of(), List.of()));

        assertTrue(catchUp.published(), "an armed X3 observer must catch up a bounded contiguous X2 history");
        assertEquals(Set.of(id("r6-first-skipped"), id("r6-second-skipped"), id("r6-current")),
                catchUp.snapshot().visualEvents().events().stream()
                        .map(ResolvedProceduralVisualEvent::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, catchUp.snapshot().visualEvents().events().size(), "each occurrence must publish exactly once");
        assertEquals(Set.of(firstSkipped.revision(), secondSkipped.revision(), source.latestSnapshot().revision()),
                catchUp.snapshot().visualEvents().events().stream()
                        .map(value -> value.provenance().sourceRevision()).collect(java.util.stream.Collectors.toSet()),
                "each replayed segment must retain its actual X2 publication revision");
    }

    @Test
    void skippedPublicationsAcrossControllersRemainCompleteAndCanonical() {
        BlendResourceId alphaController = id("r6-alpha-controller");
        BlendResourceId zetaController = id("r6-zeta-controller");
        BlendResourceId alphaLayer = id("r6-alpha-layer");
        BlendResourceId zetaLayer = id("r6-zeta-layer");
        BlendAnimationKey alphaTimeline = BlendAnimationKey.of("x3test", "r6-alpha-timeline");
        BlendAnimationKey zetaTimeline = BlendAnimationKey.of("x3test", "r6-zeta-timeline");
        BoneSchema schema = schema();
        AnimationV2LayerDefinition alphaLayerDefinition = new AnimationV2LayerDefinition(
                alphaLayer, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2LayerDefinition zetaLayerDefinition = new AnimationV2LayerDefinition(
                zetaLayer, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerDefinition alphaDefinition = new AnimationV2ControllerDefinition(
                alphaController, 0, List.of(alphaLayerDefinition), alphaTimeline, Map.of(alphaTimeline,
                eventState(alphaTimeline, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema, alphaLayer)));
        AnimationV2ControllerDefinition zetaDefinition = new AnimationV2ControllerDefinition(
                zetaController, 0, List.of(zetaLayerDefinition), zetaTimeline, Map.of(zetaTimeline,
                eventState(zetaTimeline, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema, zetaLayer)));
        AnimationV2InstanceRuntime source = new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(
                schema, List.of(zetaDefinition, alphaDefinition)));
        EventHarness harness = eventHarness("r6-multicontroller-skipped", List.of(
                new ProceduralVisualEventMarker(zetaController, zetaTimeline, 0, 0.55D,
                        id("r6-zeta-current"), 0, new ProceduralVisualEventPayload.TrailStop(id("trail"))),
                new ProceduralVisualEventMarker(alphaController, alphaTimeline, 0, 0.25D,
                        id("r6-alpha-skipped"), 0, new ProceduralVisualEventPayload.TrailStart(id("trail")))), source);

        assertTrue(harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot skipped = source.advance(0.30D);
        ProceduralEvaluationResult catchUp = harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.30D), List.of(), List.of()));

        assertTrue(catchUp.published(), "every armed controller must consume its own exact continuity path");
        assertEquals(List.of(id("r6-alpha-skipped"), id("r6-zeta-current")),
                catchUp.snapshot().visualEvents().events().stream().map(ResolvedProceduralVisualEvent::id).toList(),
                "canonical output must not depend on reversed controller or marker input order");
        assertEquals(Map.of(id("r6-alpha-skipped"), skipped.revision(),
                        id("r6-zeta-current"), source.latestSnapshot().revision()),
                catchUp.snapshot().visualEvents().events().stream().collect(java.util.stream.Collectors.toMap(
                        ResolvedProceduralVisualEvent::id,
                        event -> event.provenance().sourceRevision())),
                "each controller event must retain the publication revision that actually crossed its marker");
    }

    @Test
    void skippedObserverHistoryRetainsNoMovementRevisionsAndFailsClosedPastItsBound() {
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r6-history-bound");
        AnimationV2InstanceRuntime source = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness noMovementHarness = eventHarness("r6-history-no-movement", List.of(
                marker(loop, "r6-after-no-movement", 0, 0.25D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail")))), source);
        assertTrue(noMovementHarness.runtime().evaluate(frame(
                noMovementHarness.instance(), source.advance(0.0D), List.of(), List.of())).published());
        source.advance(0.0D);
        ProceduralEvaluationResult afterNoMovement = noMovementHarness.runtime().evaluate(frame(
                noMovementHarness.instance(), source.advance(0.30D), List.of(), List.of()));
        assertTrue(afterNoMovement.published());
        assertEquals(1, afterNoMovement.snapshot().visualEvents().events().size());

        AnimationV2InstanceRuntime boundedSource = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness boundedHarness = eventHarness("r6-history-over-budget", List.of(
                marker(loop, "r6-over-budget", 0, 0.50D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail")))), boundedSource);
        assertTrue(boundedHarness.runtime().evaluate(frame(
                boundedHarness.instance(), boundedSource.advance(0.0D), List.of(), List.of())).published());
        for (int index = 0; index <= AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE; index++) {
            boundedSource.advance(0.001D);
        }
        ProceduralEvaluationResult overBudget = boundedHarness.runtime().evaluate(frame(
                boundedHarness.instance(), boundedSource.latestSnapshot(), List.of(), List.of()));
        assertFalse(overBudget.published(), "a committed anchor outside the bounded owner history must fail closed");
        assertDiagnostic(overBudget, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE);
    }

    @Test
    void failedX3FrameDoesNotConsumeBoundedSkippedHistoryBeforeItsExactRetry() {
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r6-skipped-retry");
        AnimationV2InstanceRuntime source = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness harness = eventHarness("r6-skipped-retry", List.of(
                marker(loop, "r6-skipped-retry-marker", 0, 0.25D, 0,
                        new ProceduralVisualEventPayload.TrailStart(id("trail")))), source);
        assertTrue(harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.0D), List.of(), List.of())).published());
        source.advance(0.50D);
        AnimationV2EvaluationSnapshot current = source.advance(0.10D);

        ProceduralEvaluationResult failed = harness.runtime().evaluate(frame(harness.instance(), current, List.of(
                new ProceduralDirective(id("r6-bad-skipped-retry"), 0,
                        new ProceduralOperation.Offset(99, Vec3.ZERO, Quaternion.IDENTITY, 1.0F))), List.of()));
        assertFalse(failed.published());
        assertDiagnostic(failed, ProceduralDiagnosticCode.INVALID_TARGET);
        ProceduralEvaluationResult retry = harness.runtime().evaluate(frame(harness.instance(), current, List.of(), List.of()));
        assertTrue(retry.published());
        assertEquals(1, retry.snapshot().visualEvents().events().size(),
                "a failed frame must not consume the bounded continuity path or its replay identity");
    }

    @Test
    void commandDiscontinuityFailsClosedForAnArmedObserverAndFailedX3FrameCanRetryTheExactSnapshotWithoutConsumingEvents() {
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r5-command-loop");
        AnimationV2InstanceRuntime source = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        List<ProceduralVisualEventMarker> seekMarkers = List.of(
                marker(loop, "seeked-over", 0, 0.5D, 0, new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker(loop, "future-crossing", 1, 0.8D, 0, new ProceduralVisualEventPayload.TrailStop(id("trail"))));
        EventHarness harness = eventHarness("r5-command", seekMarkers, source);
        assertTrue(harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.0D), List.of(), List.of())).published());

        AnimationV2EvaluationSnapshot seek = source.advanceAtFrame(0.0D, List.of(
                new AnimationV2Command(EVENT_CONTROLLER, loop, 1L, 0.75D, 1.0D)));
        ProceduralEvaluationResult seekResult = harness.runtime().evaluate(frame(harness.instance(), seek, List.of(), List.of()));
        assertFalse(seekResult.published(), "an armed observation may not bridge a command seek discontinuity");
        assertDiagnostic(seekResult, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE);

        ProceduralRigRuntime resetAfterSeek = new ProceduralRigRuntime(eventPlan(seekMarkers), harness.instance(), source);
        assertTrue(resetAfterSeek.evaluate(frame(harness.instance(), seek, List.of(), List.of())).published(),
                "a newly constructed scope may explicitly arm at the post-command terminal without replaying history");
        ProceduralEvaluationResult forward = resetAfterSeek.evaluate(frame(
                harness.instance(), source.advance(0.10D), List.of(), List.of()));
        assertTrue(forward.published());
        assertEquals(List.of(id("future-crossing")), forward.snapshot().visualEvents().events().stream()
                .map(ResolvedProceduralVisualEvent::id).toList(),
                "only the real forward traversal after the seek may emit its marker");

        BlendAnimationKey changed = BlendAnimationKey.of("x3test", "r5-command-state-change");
        AnimationV2InstanceRuntime stateChangeSource = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema()),
                changed, eventState(changed, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        List<ProceduralVisualEventMarker> stateChangeMarkers = List.of(marker(
                changed, "state-change-forward", 0, 0.8D, 0,
                new ProceduralVisualEventPayload.TrailStart(id("trail"))));
        EventHarness stateChangeHarness = eventHarness("r5-command-state-change", stateChangeMarkers, stateChangeSource);
        assertTrue(stateChangeHarness.runtime().evaluate(frame(
                stateChangeHarness.instance(), stateChangeSource.advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot stateChange = stateChangeSource.advanceAtFrame(0.0D, List.of(
                new AnimationV2Command(EVENT_CONTROLLER, changed, 1L, 0.75D, 1.0D)));
        ProceduralEvaluationResult stateChangeResult = stateChangeHarness.runtime().evaluate(frame(
                stateChangeHarness.instance(), stateChange, List.of(), List.of()));
        assertFalse(stateChangeResult.published());
        assertDiagnostic(stateChangeResult, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE);
        ProceduralRigRuntime resetAfterStateChange = new ProceduralRigRuntime(
                eventPlan(stateChangeMarkers), stateChangeHarness.instance(), stateChangeSource);
        assertTrue(resetAfterStateChange.evaluate(frame(
                stateChangeHarness.instance(), stateChange, List.of(), List.of())).published());
        ProceduralEvaluationResult stateChangeForward = resetAfterStateChange.evaluate(frame(
                stateChangeHarness.instance(), stateChangeSource.advance(0.10D), List.of(), List.of()));
        assertTrue(stateChangeForward.published());
        assertEquals(1, stateChangeForward.snapshot().visualEvents().events().size());

        EventHarness retryHarness = eventHarness("r5-retry", List.of(marker(
                loop, "retry-marker", 0, 0.5D, 0, new ProceduralVisualEventPayload.TrailStart(id("trail")))),
                eventSource(schema(), loop, Map.of(loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema()))));
        assertTrue(retryHarness.runtime().evaluate(frame(
                retryHarness.instance(), retryHarness.source().advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot crossing = retryHarness.source().advance(0.75D);
        ProceduralEvaluationResult failed = retryHarness.runtime().evaluate(frame(retryHarness.instance(), crossing, List.of(
                new ProceduralDirective(id("bad-retry-directive"), 0,
                        new ProceduralOperation.Offset(99, Vec3.ZERO, Quaternion.IDENTITY, 1.0F))), List.of()));
        assertFalse(failed.published());
        assertDiagnostic(failed, ProceduralDiagnosticCode.INVALID_TARGET);
        ProceduralEvaluationResult retry = retryHarness.runtime().evaluate(frame(
                retryHarness.instance(), crossing, List.of(), List.of()));
        assertTrue(retry.published(), "a failed X3 frame must not consume its revision, traversal cursor, or replay identity");
        assertEquals(1, retry.snapshot().visualEvents().events().size());
    }

    @Test
    void identityOffsetPreservesLegalStrictV1ScaleAndUnderflowOverflowFailClosed() {
        for (Vec3 legalBaseScale : List.of(
                new Vec3(0.001F, 0.001F, 0.001F),
                new Vec3(20_000.0F, 20_000.0F, 20_000.0F),
                new Vec3(1.0F, 1.000005F, 1.0F),
                new Vec3(1.0E-7F, 1.09E-6F, 1.0E-7F))) {
            BoneSchema scaleSchema = new BoneSchema(List.of("root"), List.of(new Transform(
                    Vec3.ZERO, Quaternion.IDENTITY, legalBaseScale)));
            long generation = 30L + (long) (legalBaseScale.x() * 10.0F);
            ModelInstance scaleInstance = new ModelInstance(
                    BlendInstanceKey.ephemeral("x3test", "r5-scale-" + generation), MODEL, generation);
            ProceduralRigRuntime runtime = new ProceduralRigRuntime(new ProceduralRigPlan(
                    MODEL, generation, scaleSchema, new int[] {-1}, List.of(), List.of(), List.of(), List.of()), scaleInstance);
            ProceduralEvaluationResult result = runtime.evaluate(new ProceduralFrameInput(scaleInstance,
                    new AnimationV2EvaluationSnapshot(1L, scaleSchema.restPose(), Map.of(), List.of()), List.of(
                            new ProceduralDirective(id("identity-scale-" + generation), 0,
                                    new ProceduralOperation.Offset(0, Vec3.ZERO, Quaternion.IDENTITY, 1.0F))), List.of()));
            assertTrue(result.published(), "an identity multiplier must preserve every legal strict-v1 base scale");
            assertEquals(legalBaseScale, result.snapshot().localPose().transform(0).scale(),
                    "identity must preserve every accepted base-scale component exactly");
        }

        Vec3 nonUniformWithinStrictV1Tolerance = new Vec3(1.0F, 1.000005F, 1.0F);
        BoneSchema multipliedSchema = new BoneSchema(List.of("root"), List.of(new Transform(
                Vec3.ZERO, Quaternion.IDENTITY, nonUniformWithinStrictV1Tolerance)));
        ModelInstance multipliedInstance = new ModelInstance(
                BlendInstanceKey.ephemeral("x3test", "r6-axis-multiplier"), MODEL, 34L);
        ProceduralRigRuntime multipliedRuntime = new ProceduralRigRuntime(new ProceduralRigPlan(
                MODEL, 34L, multipliedSchema, new int[] {-1}, List.of(), List.of(), List.of(), List.of()), multipliedInstance);
        ProceduralEvaluationResult multiplied = multipliedRuntime.evaluate(new ProceduralFrameInput(multipliedInstance,
                new AnimationV2EvaluationSnapshot(1L, multipliedSchema.restPose(), Map.of(), List.of()), List.of(
                        new ProceduralDirective(id("r6-axis-multiplier"), 0,
                                new ProceduralOperation.Offset(0, Vec3.ZERO, Quaternion.IDENTITY, 2.0F))), List.of()));
        assertTrue(multiplied.published());
        assertEquals(new Vec3(2.0F, 2.00001F, 2.0F), multiplied.snapshot().localPose().transform(0).scale(),
                "non-identity multipliers must compose every scale axis independently");

        assertOffsetScaleFailure(Float.MIN_VALUE, ProceduralLimits.MIN_SCALE_MULTIPLIER);
        assertOffsetScaleFailure(Float.MAX_VALUE, ProceduralLimits.MAX_SCALE_MULTIPLIER);
    }

    @Test
    void attachmentGraphCountsRawDescriptorVisitsBeforeDedupAndNeverClaimsOnFailure() {
        ProceduralAttachmentDescriptor duplicateChild = attachment("r5-duplicate-child", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(CHILD_MODEL));
        AtomicInteger visits = new AtomicInteger();
        int supplied = ProceduralLimits.MAX_ATTACHMENT_GRAPH_RAW_DESCRIPTOR_VISITS + 1;
        Iterable<ProceduralAttachmentDescriptor> excessiveDuplicates = () -> new java.util.Iterator<>() {
            private int remaining = supplied;

            @Override
            public boolean hasNext() {
                return remaining > 0;
            }

            @Override
            public ProceduralAttachmentDescriptor next() {
                remaining--;
                visits.incrementAndGet();
                return duplicateChild;
            }
        };
        int claimsBefore = ProceduralAttachmentGenerationClaims.activeClaimCount();
        assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.compile(40L,
                Map.of(MODEL, excessiveDuplicates, CHILD_MODEL, List.of())));
        assertEquals(supplied, visits.get(), "the raw descriptor budget must reject before duplicate-edge dedup can hide it");
        assertEquals(claimsBefore, ProceduralAttachmentGenerationClaims.activeClaimCount(),
                "a rejected raw traversal must not leak a generation claim");
    }

    @Test
    void attachmentGraphStopsAWellBehavedInfiniteRawDescriptorSourceAtItsFixedBoundary() {
        ProceduralAttachmentDescriptor duplicateChild = attachment("r5-infinite-child", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(CHILD_MODEL));
        AtomicInteger visits = new AtomicInteger();
        Iterable<ProceduralAttachmentDescriptor> infiniteDuplicates = () -> new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public ProceduralAttachmentDescriptor next() {
                visits.incrementAndGet();
                return duplicateChild;
            }
        };
        int claimsBefore = ProceduralAttachmentGenerationClaims.activeClaimCount();

        assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.compile(41L,
                Map.of(MODEL, infiniteDuplicates, CHILD_MODEL, List.of())));
        assertEquals(ProceduralLimits.MAX_ATTACHMENT_GRAPH_RAW_DESCRIPTOR_VISITS + 1, visits.get(),
                "a normal infinite source must stop after the one descriptor that proves it exceeded the raw budget");
        assertEquals(claimsBefore, ProceduralAttachmentGenerationClaims.activeClaimCount());
    }

    @Test
    void visualEventObserverBoundsMultiLoopTraversalAndArmsLateObserversWithoutHistoryBurst() {
        BlendAnimationKey loop = BlendAnimationKey.of("x3test", "r5-bounded-loop");
        List<ProceduralVisualEventMarker> markers = List.of(marker(
                loop, "multi-loop", 0, 0.5D, 0, new ProceduralVisualEventPayload.TrailStart(id("trail"))));
        AnimationV2InstanceRuntime multiSource = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness multiHarness = eventHarness("r5-multi-loop", markers, multiSource);
        assertTrue(multiHarness.runtime().evaluate(frame(
                multiHarness.instance(), multiSource.advance(0.0D), List.of(), List.of())).published());
        ProceduralEvaluationResult multiLoop = multiHarness.runtime().evaluate(frame(
                multiHarness.instance(), multiSource.advance(3.25D), List.of(), List.of()));
        assertTrue(multiLoop.published());
        assertEquals(3, multiLoop.snapshot().visualEvents().events().size());
        assertEquals(List.of(0L, 1L, 2L), multiLoop.snapshot().visualEvents().events().stream()
                .map(event -> event.provenance().loopEpoch()).toList());
        assertEquals(List.of(0L, 1L, 2L), multiLoop.snapshot().visualEvents().events().stream()
                .map(event -> event.provenance().occurrence()).toList());

        AnimationV2InstanceRuntime source = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness harness = eventHarness("r5-late-observer", markers, source);

        AnimationV2EvaluationSnapshot delayed = source.advance(3.25D);
        ProceduralEvaluationResult armedLate = harness.runtime().evaluate(frame(harness.instance(), delayed, List.of(), List.of()));
        assertTrue(armedLate.published());
        assertEquals(0, armedLate.snapshot().visualEvents().events().size(),
                "the first observer only arms at the terminal playhead and never replays prior X2 history");
        ProceduralEvaluationResult afterArm = harness.runtime().evaluate(frame(
                harness.instance(), source.advance(0.75D), List.of(), List.of()));
        assertTrue(afterArm.published());
        assertEquals(1, afterArm.snapshot().visualEvents().events().size());

        AnimationV2InstanceRuntime boundedSource = eventSource(schema(), loop, Map.of(
                loop, eventState(loop, AnimationV2PlaybackMode.LOOP, 1.0D, null, schema())));
        EventHarness boundedHarness = eventHarness("r5-bounded-loop-truncated", markers, boundedSource);
        assertTrue(boundedHarness.runtime().evaluate(frame(
                boundedHarness.instance(), boundedSource.advance(0.0D), List.of(), List.of())).published());
        AnimationV2EvaluationSnapshot overBudget = boundedSource.advance(
                AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE + 1.0D);
        assertTrue(overBudget.diagnostics().stream().anyMatch(value -> value.code()
                == AnimationV2DiagnosticCode.OBSERVER_TRAVERSAL_TRUNCATED));
        ProceduralEvaluationResult rejected = boundedHarness.runtime().evaluate(frame(
                boundedHarness.instance(), overBudget, List.of(), List.of()));
        assertFalse(rejected.published(), "X3 must not publish a partial multi-loop observer path");
        assertDiagnostic(rejected, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE);
    }

    @Test
    void diagnosticOverflowCannotEraseTheOnlyError() {
        List<ProceduralVisualEventMarker> markers = new ArrayList<>();
        for (int index = 0; index < ProceduralLimits.MAX_DIAGNOSTIC_DETAILS; index++) {
            markers.add(marker("a-conflict-" + index, index, 0,
                    new ProceduralVisualEventPayload.TrailStart(id("trail"))));
            markers.add(marker("a-conflict-" + index, index, 0,
                    new ProceduralVisualEventPayload.TrailStop(id("trail"))));
        }
        markers.add(marker("m-normal-sound", 1000, 1,
                new ProceduralVisualEventPayload.Sound(id("sound"), 1.0F, 1.0F)));
        markers.add(marker("z-missing-socket", 1001, 2,
                new ProceduralVisualEventPayload.SocketEffect(id("effect"), id("missing"))));
        EventHarness harness = eventHarness("diagnostics", markers);
        assertTrue(harness.runtime().evaluate(frame(harness.instance(), harness.source().advance(0.0D), List.of(), List.of())).published());

        ProceduralEvaluationResult result = harness.runtime().evaluate(frame(
                harness.instance(), harness.source().advance(0.75D), List.of(), List.of()));

        assertFalse(result.published(), "a real marker crossing which observes a late visual-event ERROR must stay fail-closed");
        assertDiagnostic(result, ProceduralDiagnosticCode.VISUAL_EVENT_CONFLICT);
        assertDiagnostic(result, ProceduralDiagnosticCode.DIAGNOSTIC_OVERFLOW);
        assertEquals(ProceduralLimits.MAX_DIAGNOSTICS_PER_FRAME, result.diagnostics().size());
        assertEquals(ProceduralLimits.MAX_DIAGNOSTIC_DETAILS, result.diagnostics().stream()
                .filter(value -> value.severity() == ProceduralDiagnosticSeverity.ERROR).count());
        assertEquals(1, result.diagnostics().stream()
                .filter(value -> value.code() == ProceduralDiagnosticCode.DIAGNOSTIC_OVERFLOW).count());
    }

    @Test
    void canonicalDirectiveAndHookOrderingStayDeterministicAcrossTwoThousandPermutations() {
        List<ProceduralDirective> base = List.of(
                new ProceduralDirective(id("alpha"), 0, new ProceduralOperation.Offset(
                        1, new Vec3(1.0F, 0.0F, 0.0F), Quaternion.IDENTITY, 1.0F)),
                new ProceduralDirective(id("beta"), 1, new ProceduralOperation.Offset(
                        1, new Vec3(0.0F, 0.0F, 2.0F), Quaternion.IDENTITY, 1.0F)),
                new ProceduralDirective(id("gamma"), 2, new ProceduralOperation.BoneVisibility(1, true)),
                new ProceduralDirective(id("delta"), 3, new ProceduralOperation.SurfaceOverride(
                        new ProceduralSurfaceTarget.Mesh(MESH), new SemanticSurfaceOverride(id("texture"), id("material")))),
                new ProceduralDirective(id("epsilon"), 4, new ProceduralOperation.RotationOffset(
                        1, new Quaternion(0.0F, 0.0F, 0.25F, 1.0F))),
                new ProceduralDirective(id("zeta"), 5, new ProceduralOperation.SurfaceVisibility(
                        new ProceduralSurfaceTarget.Primitive(PRIMITIVE), true)),
                new ProceduralDirective(id("eta"), 6, new ProceduralOperation.Offset(
                        0, new Vec3(0.0F, 0.0F, 0.5F), Quaternion.IDENTITY, 1.0F)),
                new ProceduralDirective(id("theta"), 7, new ProceduralOperation.RotationOffset(
                        0, new Quaternion(0.0F, 0.125F, 0.0F, 1.0F))));
        String expected = null;
        int[] order = new int[base.size()];
        for (int index = 0; index < order.length; index++) {
            order[index] = index;
        }
        Set<String> observedCallerOrders = new HashSet<>();
        for (int iteration = 0; iteration < 2_000; iteration++) {
            List<ProceduralDirective> shuffled = new ArrayList<>(base.size());
            StringBuilder callerOrder = new StringBuilder();
            for (int index : order) {
                shuffled.add(base.get(index));
                callerOrder.append(index).append(',');
            }
            assertTrue(observedCallerOrders.add(callerOrder.toString()), "caller ordering must be distinct at " + iteration);
            List<ProceduralHook> hooks = (iteration & 1) == 0
                    ? List.of(hook("hook-b", 4, new ProceduralOperation.Offset(1, new Vec3(0.0F, 1.0F, 0.0F), Quaternion.IDENTITY, 1.0F)),
                            hook("hook-a", 4, new ProceduralOperation.Offset(1, new Vec3(0.0F, 0.0F, 1.0F), Quaternion.IDENTITY, 1.0F)))
                    : List.of(hook("hook-a", 4, new ProceduralOperation.Offset(1, new Vec3(0.0F, 0.0F, 1.0F), Quaternion.IDENTITY, 1.0F)),
                            hook("hook-b", 4, new ProceduralOperation.Offset(1, new Vec3(0.0F, 1.0F, 0.0F), Quaternion.IDENTITY, 1.0F)));
            ModelInstance actor = instance(3L, "actor");
            ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan(hooks), actor);
            ProceduralEvaluationResult result = runtime.evaluate(frame(actor, pose(10L), shuffled, List.of()));
            assertTrue(result.published());
            String digest = digest(result.snapshot());
            if (expected == null) {
                expected = digest;
            }
            assertEquals(expected, digest, "permutation " + iteration);
            if (iteration + 1 < 2_000) {
                assertTrue(nextPermutation(order), "eight directives must provide at least two thousand distinct permutations");
            }
        }
        assertEquals(2_000, observedCallerOrders.size());
    }

    @Test
    void hookConflictOverflowThrowableAndOwnerMisuseFailClosed() throws Exception {
        ProceduralHook conflictA = hook("conflict-a", 1, new ProceduralOperation.BoneVisibility(1, false));
        ProceduralHook conflictB = hook("conflict-b", 1, new ProceduralOperation.BoneVisibility(1, true));
        ProceduralRigRuntime conflictRuntime = new ProceduralRigRuntime(
                plan(List.of(conflictB, conflictA)), instance(3L, "actor"));
        ProceduralEvaluationResult base = conflictRuntime.evaluate(frame(instance(3L, "actor"), pose(1L), List.of(), List.of()));
        assertFalse(base.published());
        assertDiagnostic(base, ProceduralDiagnosticCode.HOOK_CONFLICT);

        ProceduralHook explosive = new ProceduralHook() {
            @Override
            public BlendResourceId id() {
                return ProceduralRigContractsTest.id("explosive");
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void contribute(ProceduralHookContext context, ProceduralHookCommandSink sink) {
                throw new AssertionError("hostile\nmessage\u0000must not leak");
            }
        };
        ProceduralRigRuntime exceptionRuntime = new ProceduralRigRuntime(plan(List.of(explosive)), instance(3L, "actor"));
        ProceduralEvaluationResult exception = exceptionRuntime.evaluate(frame(instance(3L, "actor"), pose(1L), List.of(), List.of()));
        assertFalse(exception.published());
        assertDiagnostic(exception, ProceduralDiagnosticCode.HOOK_FAILURE);
        assertFalse(exception.diagnostics().getFirst().message().contains("hostile"));

        ProceduralHook overflow = new ProceduralHook() {
            @Override
            public BlendResourceId id() {
                return ProceduralRigContractsTest.id("overflow");
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void contribute(ProceduralHookContext context, ProceduralHookCommandSink sink) {
                for (int index = 0; index <= ProceduralLimits.MAX_COMMANDS_PER_FRAME; index++) {
                    sink.emit(new ProceduralOperation.Offset(1, Vec3.ZERO, Quaternion.IDENTITY, 1.0F));
                }
            }
        };
        ProceduralRigRuntime overflowRuntime = new ProceduralRigRuntime(plan(List.of(overflow)), instance(3L, "actor"));
        ProceduralEvaluationResult overflowResult = overflowRuntime.evaluate(frame(instance(3L, "actor"), pose(1L), List.of(), List.of()));
        assertFalse(overflowResult.published());
        assertDiagnostic(overflowResult, ProceduralDiagnosticCode.COMMAND_OVERFLOW);

        ProceduralRigRuntime ownerRuntime = new ProceduralRigRuntime(plan(List.of()), instance(3L, "actor"));
        assertTrue(ownerRuntime.evaluate(frame(instance(3L, "actor"), pose(1L), List.of(), List.of())).published());
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                ownerRuntime.evaluate(frame(instance(3L, "actor"), pose(2L), List.of(), List.of()));
            } catch (Throwable throwable) {
                ownerFailure.set(throwable);
            }
        });
        other.start();
        other.join();
        ProceduralOwnerThreadViolation violation = assertThrows(ProceduralOwnerThreadViolation.class,
                () -> {
                    throw ownerFailure.get();
                });
        assertEquals(ProceduralDiagnosticCode.OWNER_THREAD_REJECTED, violation.diagnostic().code());
    }

    @Test
    void finalVisibilitySurfaceOverridesAndSocketQueriesUseTheFrozenFinalFrame() {
        ModelInstance instance = instance(3L, "actor");
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan(List.of()), instance);
        ProceduralEvaluationResult result = runtime.evaluate(frame(instance, pose(1L), List.of(
                new ProceduralDirective(id("hide-root"), 0, new ProceduralOperation.BoneVisibility(0, false)),
                new ProceduralDirective(id("mesh"), 1, new ProceduralOperation.SurfaceVisibility(
                        new ProceduralSurfaceTarget.Mesh(MESH), true)),
                new ProceduralDirective(id("primitive"), 1, new ProceduralOperation.SurfaceVisibility(
                        new ProceduralSurfaceTarget.Primitive(PRIMITIVE), false)),
                new ProceduralDirective(id("override"), 2, new ProceduralOperation.SurfaceOverride(
                        new ProceduralSurfaceTarget.Mesh(MESH), new SemanticSurfaceOverride(id("texture"), id("material"))))),
                List.of()));
        assertTrue(result.published());
        ProceduralFrameSnapshot snapshot = result.snapshot();
        assertEquals(List.of(false, false), snapshot.boneVisibility());
        assertEquals(Boolean.FALSE, snapshot.surfaceVisibility().get(new ProceduralSurfaceTarget.Mesh(MESH)));
        assertEquals(Boolean.FALSE, snapshot.surfaceVisibility().get(new ProceduralSurfaceTarget.Primitive(PRIMITIVE)));
        assertEquals(id("texture"), snapshot.surfaceOverrides().get(new ProceduralSurfaceTarget.Mesh(MESH)).textureId());
        assertEquals(ProceduralDiagnosticCode.HIDDEN_SOCKET,
                snapshot.resolveSocket(SocketQuery.of(instance, SOCKET)).diagnostic().orElseThrow().code());
        assertEquals(ProceduralDiagnosticCode.SOCKET_SCOPE_MISMATCH,
                snapshot.resolveSocket(SocketQuery.of(instance(3L, "other"), SOCKET)).diagnostic().orElseThrow().code());
        assertEquals(ProceduralDiagnosticCode.SOCKET_MISSING,
                snapshot.resolveSocket(SocketQuery.of(instance, id("missing"))).diagnostic().orElseThrow().code());
    }

    @Test
    void attachmentsAreSortedBoundedCycleSafeAndDeepFrozen() {
        ProceduralAttachmentDescriptor item = attachment("z-item", new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.Item(id("item")));
        ProceduralAttachmentDescriptor block = attachment("a-block", new ProceduralAttachmentAnchor.Socket(SOCKET),
                new ProceduralAttachmentPayload.Block(id("block")));
        ProceduralAttachmentDescriptor child = attachment("m-child", new ProceduralAttachmentAnchor.Bone(1),
                new ProceduralAttachmentPayload.ChildModel(CHILD_MODEL));
        ProceduralAttachmentGraph attachmentGraph = ProceduralAttachmentGraph.compile(3L,
                Map.of(MODEL, List.of(item, block, child), CHILD_MODEL, List.of()));
        ProceduralRigPlan attachmentPlan = new ProceduralRigPlan(
                MODEL, 3L, schema(), new int[] {-1, 0},
                List.of(new ProceduralSocketDefinition(SOCKET, 1, Transform.IDENTITY)),
                List.of(new ProceduralSurfaceDefinition(ProceduralSurfaceKind.MESH, MESH, 0)),
                List.of(new ProceduralSurfaceDefinition(ProceduralSurfaceKind.PRIMITIVE, PRIMITIVE, 1)),
                List.of(), List.of(item, block, child),
                attachmentGraph);
        ProceduralRigPlan childPlan = new ProceduralRigPlan(
                CHILD_MODEL, 3L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(), List.of(), attachmentGraph);
        try {
            ProceduralAttachmentGraph.publish(List.of(attachmentPlan, childPlan));
            ModelInstance attachmentInstance = instance(3L, "actor");
            ProceduralRigRuntime runtime = new ProceduralRigRuntime(attachmentPlan, attachmentInstance);
            ProceduralFrameSnapshot snapshot = runtime.evaluate(frame(attachmentInstance, pose(1L), List.of(), List.of())).snapshot();
            assertEquals(List.of(id("a-block"), id("m-child"), id("z-item")),
                    snapshot.attachments().stream().map(value -> value.descriptor().id()).toList());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.attachments().clear());
            ProceduralAttachmentDescriptor selfCycle = attachment("cycle", new ProceduralAttachmentAnchor.Bone(0),
                    new ProceduralAttachmentPayload.ChildModel(MODEL));
            assertThrows(IllegalArgumentException.class, () -> ProceduralAttachmentGraph.compile(3L, Map.of(MODEL, List.of(selfCycle))));
            assertEquals(1, attachmentPlan.attachmentGraph().maximumDepth());
            assertEquals(ProceduralLimits.MAX_ATTACHMENTS, attachmentList(ProceduralLimits.MAX_ATTACHMENTS).size());
            assertThrows(IllegalArgumentException.class, () -> new ProceduralRigPlan(
                    MODEL, 3L, schema(), new int[] {-1, 0}, List.of(), List.of(), List.of(), List.of(),
                    attachmentList(ProceduralLimits.MAX_ATTACHMENTS + 1)));
        } finally {
            attachmentGraph.close();
        }
    }

    @Test
    void differentCompleteGraphsCannotBothPublishForTheSameResourceGeneration() {
        long generation = 8_903L;
        BlendModelKey modelA = BlendModelKey.of("x3test", "generation-claim-a");
        BlendModelKey modelB = BlendModelKey.of("x3test", "generation-claim-b");
        ProceduralAttachmentDescriptor aToB = attachment(
                "generation-claim-a-to-b",
                new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(modelB));
        ProceduralAttachmentDescriptor bToA = attachment(
                "generation-claim-b-to-a",
                new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(modelA));

        ProceduralAttachmentGraph graphOne = ProceduralAttachmentGraph.compile(
                generation, Map.of(modelA, List.of(aToB), modelB, List.of()));
        ProceduralRigPlan graphOneA = attachmentPlan(modelA, generation, List.of(aToB), graphOne);
        ProceduralRigPlan graphOneB = attachmentPlan(modelB, generation, List.of(), graphOne);
        ProceduralAttachmentGraph graphTwo = ProceduralAttachmentGraph.compile(
                generation, Map.of(modelA, List.of(), modelB, List.of(bToA)));
        ProceduralRigPlan graphTwoA = attachmentPlan(modelA, generation, List.of(), graphTwo);
        ProceduralRigPlan graphTwoB = attachmentPlan(modelB, generation, List.of(bToA), graphTwo);
        try {
            ProceduralAttachmentGraph.publish(List.of(graphOneA, graphOneB));
            assertNotNull(new ProceduralRigRuntime(graphOneA, instance(modelA, generation, "graph-one-a")));
            assertThrows(IllegalStateException.class,
                    () -> ProceduralAttachmentGraph.publish(List.of(graphTwoA, graphTwoB)),
                    "one resource generation must have exactly one globally published attachment graph");
        } finally {
            graphOne.close();
            graphTwo.close();
        }
    }

    @Test
    void legacyRuntimeConstructorRejectsPlansWithVisualEventMarkers() {
        ProceduralRigPlan markerPlan = eventPlan(List.of(marker(
                "legacy-constructor-marker",
                0,
                0,
                new ProceduralVisualEventPayload.Sound(id("legacy-constructor-sound"), 1.0F, 1.0F))));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ProceduralRigRuntime(markerPlan, instance(3L, "legacy-constructor")),
                "the legacy constructor must not silently disable a non-empty marker catalog");
        assertEquals(
                "plans with visual event markers require the three-argument runtime constructor and exact X2 binding",
                failure.getMessage());
    }

    @Test
    void attachmentGenerationClaimIsIdempotentGenerationScopedAndExplicitlyReleased() {
        int before = ProceduralAttachmentGenerationClaims.activeClaimCount();
        AttachmentGraphFixture first = graphFixture(8_910L, "idempotent");
        AttachmentGraphFixture semanticallyEqualCopy = copyGraphFixture(first);
        AttachmentGraphFixture otherGeneration = graphFixture(8_911L, "other-generation");
        try {
            ProceduralAttachmentGraph.publish(first.plans());
            ProceduralAttachmentGraph.publish(first.plans());
            assertEquals(before + 1, ProceduralAttachmentGenerationClaims.activeClaimCount(),
                    "re-publishing the exact graph and complete plan identities must reuse one claim");
            assertThrows(IllegalStateException.class,
                    () -> ProceduralAttachmentGraph.publish(semanticallyEqualCopy.plans()),
                    "equal graph content cannot impersonate the one claimed graph identity");
            assertEquals(before + 1, ProceduralAttachmentGenerationClaims.activeClaimCount());

            ProceduralAttachmentGraph.publish(otherGeneration.plans());
            assertEquals(before + 2, ProceduralAttachmentGenerationClaims.activeClaimCount(),
                    "different resource generations may be active together");
        } finally {
            first.graph().close();
            first.graph().close();
            semanticallyEqualCopy.graph().close();
            otherGeneration.graph().close();
        }
        assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount(),
                "explicit close must release claims without GC or timing assumptions");
    }

    @Test
    void competingGraphsForOneGenerationLinearizeToExactlyOneWinnerWithoutDeadlock() throws Exception {
        int before = ProceduralAttachmentGenerationClaims.activeClaimCount();
        for (int round = 0; round < 16; round++) {
            long generation = 8_920L + round;
            AttachmentGraphFixture left = graphFixture(generation, "race-left-" + round);
            AttachmentGraphFixture right = graphFixture(generation, "race-right-" + round);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var leftResult = executor.submit(() -> publishAfterBarrier(left, ready, start));
                var rightResult = executor.submit(() -> publishAfterBarrier(right, ready, start));
                assertTrue(ready.await(5L, TimeUnit.SECONDS), "both publishers must reach the deterministic barrier");
                start.countDown();
                boolean leftPublished = leftResult.get(5L, TimeUnit.SECONDS);
                boolean rightPublished = rightResult.get(5L, TimeUnit.SECONDS);
                assertTrue(leftPublished ^ rightPublished,
                        "the generation registry must atomically select exactly one complete graph");
                assertEquals(before + 1, ProceduralAttachmentGenerationClaims.activeClaimCount());
            } finally {
                left.graph().close();
                right.graph().close();
            }
            assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount());
        }
    }

    @Test
    void graphRetirementIsTerminalAndSerializesRuntimePublicationWithClaimReuse() {
        long generation = 8_950L;
        int before = ProceduralAttachmentGenerationClaims.activeClaimCount();
        AttachmentGraphFixture retired = graphFixture(generation, "retired");
        ProceduralRigPlan retiredPlan = retired.plans().getFirst();
        BlendModelKey retiredModel = retiredPlan.modelKey();
        ModelInstance retiredInstance = instance(retiredModel, generation, "retired-runtime");
        ProceduralAttachmentGraph.publish(retired.plans());
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(retiredPlan, retiredInstance);
        ProceduralEvaluationResult published = runtime.evaluate(frame(retiredInstance, pose(1L), List.of(), List.of()));
        assertTrue(published.published());

        retired.graph().close();
        retired.graph().close();
        assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount());
        ProceduralEvaluationResult afterClose = runtime.evaluate(frame(retiredInstance, pose(2L), List.of(), List.of()));
        assertFalse(afterClose.published());
        assertSame(published.snapshot(), afterClose.snapshot());
        assertDiagnostic(afterClose, ProceduralDiagnosticCode.ATTACHMENT_GRAPH_RETIRED);
        assertThrows(IllegalStateException.class, runtime::latestSnapshot);
        assertThrows(IllegalStateException.class, () -> ProceduralAttachmentGraph.publish(retired.plans()));

        AttachmentGraphFixture replacement = graphFixture(generation, "replacement");
        try {
            ProceduralAttachmentGraph.publish(replacement.plans());
            assertEquals(before + 1, ProceduralAttachmentGenerationClaims.activeClaimCount(),
                    "a new complete graph may claim a generation only after the old graph retires");
        } finally {
            replacement.graph().close();
        }
        assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount());
    }

    @Test
    void retirementDuringEvaluationPreventsAnyLaterSnapshotCommit() throws Exception {
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        ProceduralHook blocking = new ProceduralHook() {
            @Override
            public BlendResourceId id() {
                return ProceduralRigContractsTest.id("retirement-race-hook");
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void contribute(ProceduralHookContext context, ProceduralHookCommandSink sink) {
                hookEntered.countDown();
                try {
                    if (!releaseHook.await(5L, TimeUnit.SECONDS)) {
                        throw new AssertionError("retirement race hook was not deterministically released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("retirement race hook was interrupted", exception);
                }
            }
        };
        ProceduralRigPlan racePlan = plan(8_960L, List.of(blocking));
        ModelInstance raceInstance = instance(8_960L, "retirement-race");
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(racePlan, raceInstance);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var evaluation = executor.submit(() -> runtime.evaluate(
                    frame(raceInstance, pose(1L), List.of(), List.of())));
            try {
                assertTrue(hookEntered.await(5L, TimeUnit.SECONDS));
                racePlan.attachmentGraph().close();
            } finally {
                releaseHook.countDown();
            }
            ProceduralEvaluationResult result = evaluation.get(5L, TimeUnit.SECONDS);
            assertFalse(result.published());
            assertTrue(result.latestSnapshot().isEmpty());
            assertDiagnostic(result, ProceduralDiagnosticCode.ATTACHMENT_GRAPH_RETIRED);
            assertThrows(IllegalStateException.class, runtime::latestSnapshot);
        }
    }

    @Test
    void failedPublicationConstructionAndCloseBeforePublishNeverLeakOrReactivateClaims() {
        int before = ProceduralAttachmentGenerationClaims.activeClaimCount();

        AttachmentGraphFixture incomplete = graphFixture(8_970L, "incomplete");
        assertThrows(IllegalArgumentException.class,
                () -> ProceduralAttachmentGraph.publish(List.of(incomplete.plans().getFirst())));
        assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount());
        ProceduralAttachmentGraph.publish(incomplete.plans());
        assertEquals(before + 1, ProceduralAttachmentGenerationClaims.activeClaimCount(),
                "a validation failure must leave the exact graph retryable");
        assertThrows(IllegalArgumentException.class, () -> new ProceduralRigRuntime(
                incomplete.plans().getFirst(),
                instance(incomplete.plans().getLast().modelKey(), 8_970L, "wrong-runtime-scope")));
        assertEquals(before + 1, ProceduralAttachmentGenerationClaims.activeClaimCount(),
                "a failed runtime constructor must not create or release a generation claim");
        incomplete.graph().close();
        assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount());

        AttachmentGraphFixture closedBeforePublish = graphFixture(8_971L, "closed-before-publish");
        closedBeforePublish.graph().close();
        closedBeforePublish.graph().close();
        assertThrows(IllegalStateException.class,
                () -> ProceduralAttachmentGraph.publish(closedBeforePublish.plans()));
        assertThrows(IllegalStateException.class, () -> new ProceduralRigRuntime(
                closedBeforePublish.plans().getFirst(),
                instance(closedBeforePublish.plans().getFirst().modelKey(), 8_971L, "closed-before-runtime")));
        assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount());
    }

    @Test
    void boundedGenerationClaimRegistryFailsClosedAndARejectedGraphRemainsRetryable() {
        int before = ProceduralAttachmentGenerationClaims.activeClaimCount();
        int available = ProceduralLimits.MAX_ACTIVE_ATTACHMENT_GRAPH_CLAIMS - before;
        assertTrue(available > 0, "the bounded registry must leave room for an isolated contract test");
        List<AttachmentGraphFixture> occupants = new ArrayList<>(available);
        AttachmentGraphFixture overflow = graphFixture(9_500_000L, "capacity-overflow");
        try {
            for (int index = 0; index < available; index++) {
                AttachmentGraphFixture fixture = graphFixture(9_000_000L + index, "capacity-" + index);
                occupants.add(fixture);
                ProceduralAttachmentGraph.publish(fixture.plans());
            }
            assertEquals(ProceduralLimits.MAX_ACTIVE_ATTACHMENT_GRAPH_CLAIMS,
                    ProceduralAttachmentGenerationClaims.activeClaimCount());
            assertThrows(IllegalStateException.class, () -> ProceduralAttachmentGraph.publish(overflow.plans()),
                    "registry capacity must fail closed rather than evict a still-active generation");
            assertEquals(ProceduralLimits.MAX_ACTIVE_ATTACHMENT_GRAPH_CLAIMS,
                    ProceduralAttachmentGenerationClaims.activeClaimCount());

            occupants.removeFirst().graph().close();
            ProceduralAttachmentGraph.publish(overflow.plans());
            assertEquals(ProceduralLimits.MAX_ACTIVE_ATTACHMENT_GRAPH_CLAIMS,
                    ProceduralAttachmentGenerationClaims.activeClaimCount(),
                    "capacity rejection must not poison the rejected graph's later exact retry");
        } finally {
            overflow.graph().close();
            for (AttachmentGraphFixture fixture : occupants) {
                fixture.graph().close();
            }
        }
        assertEquals(before, ProceduralAttachmentGenerationClaims.activeClaimCount());
    }

    @Test
    void legacyRuntimeConstructorStillAcceptsAnEmptyMarkerCatalog() {
        ProceduralRigPlan emptyMarkerPlan = eventPlan(List.of());
        assertNotNull(new ProceduralRigRuntime(emptyMarkerPlan, instance(3L, "legacy-empty-markers")));
    }

    @Test
    void allSevenVisualEventTypesHaveStableBatchDedupConflictOverflowAndSocketBinding() {
        LinkedHashMap<String, String> reverseCustomFields = new LinkedHashMap<>();
        reverseCustomFields.put("zeta", "last");
        reverseCustomFields.put("alpha", "first");
        List<ProceduralVisualEventMarker> markers = List.of(
                marker("sound", 0, 3, new ProceduralVisualEventPayload.Sound(id("sound-id"), 1.0F, 1.0F)),
                marker("particle", 1, 2, new ProceduralVisualEventPayload.Particle(id("particle-id"), 2, Transform.IDENTITY)),
                marker("shake", 2, 1, new ProceduralVisualEventPayload.CameraShake(1.0F, 0.5F)),
                marker("trail-start", 3, 4, new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                marker("trail-stop", 4, 5, new ProceduralVisualEventPayload.TrailStop(id("trail"))),
                marker("socket", 5, 0, new ProceduralVisualEventPayload.SocketEffect(id("effect"), SOCKET)),
                marker("custom", 6, 6, new ProceduralVisualEventPayload.CustomClientEvent(id("custom"), reverseCustomFields)),
                marker("custom", 6, 6, new ProceduralVisualEventPayload.CustomClientEvent(id("custom"),
                        Map.of("alpha", "first", "zeta", "last"))));
        EventHarness harness = eventHarness("all-seven", markers);
        assertTrue(harness.runtime().evaluate(frame(harness.instance(), harness.source().advance(0.0D), List.of(), List.of())).published());
        ProceduralEvaluationResult result = harness.runtime().evaluate(frame(
                harness.instance(), harness.source().advance(0.75D), List.of(), List.of()));
        assertTrue(result.published());
        assertEquals(Set.of(
                        ProceduralVisualEventType.SOUND, ProceduralVisualEventType.PARTICLE,
                        ProceduralVisualEventType.CAMERA_SHAKE, ProceduralVisualEventType.TRAIL_START,
                        ProceduralVisualEventType.TRAIL_STOP, ProceduralVisualEventType.SOCKET_EFFECT,
                        ProceduralVisualEventType.CUSTOM_CLIENT_EVENT),
                result.snapshot().visualEvents().events().stream().map(value -> value.payload().type()).collect(java.util.stream.Collectors.toSet()));
        ResolvedProceduralVisualEvent socketEvent = result.snapshot().visualEvents().events().stream()
                .filter(value -> value.payload().type() == ProceduralVisualEventType.SOCKET_EFFECT).findFirst().orElseThrow();
        assertNotNull(socketEvent.socketTransform());
        assertDiagnostic(result, ProceduralDiagnosticCode.VISUAL_EVENT_DEDUPLICATED);
        ResolvedProceduralVisualEvent customEvent = result.snapshot().visualEvents().events().stream()
                .filter(value -> value.payload().type() == ProceduralVisualEventType.CUSTOM_CLIENT_EVENT).findFirst().orElseThrow();
        ProceduralVisualEventPayload.CustomClientEvent custom =
                (ProceduralVisualEventPayload.CustomClientEvent) customEvent.payload();
        assertIterableEquals(List.of("alpha", "zeta"), custom.fields().keySet());
    }

    @Test
    void frozenSnapshotsCanBeReadConcurrentlyWithoutCallerOwnedCollections() throws Exception {
        ModelInstance snapshotInstance = instance(3L, "actor");
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan(List.of()), snapshotInstance);
        ProceduralFrameSnapshot snapshot = runtime.evaluate(frame(snapshotInstance, pose(1L), List.of(), List.of())).snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.modelTransforms().add(Transform.IDENTITY));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.surfaceVisibility().clear());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> readers = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                readers.add(() -> {
                    String digest = null;
                    for (int index = 0; index < 2_000; index++) {
                        String value = digest(snapshot);
                        if (digest == null) {
                            digest = value;
                        }
                        assertEquals(digest, value);
                    }
                    return digest;
                });
            }
            assertEquals(1, executor.invokeAll(readers).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).distinct().count());
        }
    }

    @Test
    void nonTopologicalParentSlotsAndHostileInputIterablesFailClosedWithoutLeakingState() {
        BoneSchema reversedSchema = new BoneSchema(
                List.of("child", "root"),
                List.of(
                        new Transform(new Vec3(0.0F, 0.0F, 1.0F), Quaternion.IDENTITY, Vec3.ONE),
                        new Transform(new Vec3(0.0F, 1.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE)));
        ModelInstance instance = instance(3L, "reversed-parent");
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(new ProceduralRigPlan(
                MODEL, 3L, reversedSchema, new int[] {1, -1},
                List.of(new ProceduralSocketDefinition(SOCKET, 0, Transform.IDENTITY)),
                List.of(), List.of(), List.of()), instance);
        AnimationV2EvaluationSnapshot inputPose = new AnimationV2EvaluationSnapshot(1L,
                reversedSchema.restPose(), Map.of(), List.of());
        ProceduralEvaluationResult first = runtime.evaluate(new ProceduralFrameInput(
                instance, inputPose,
                List.of(new ProceduralDirective(id("hide-root"), 0, new ProceduralOperation.BoneVisibility(1, false))),
                List.of()));
        assertTrue(first.published());
        assertEquals(new Vec3(0.0F, 1.0F, 1.0F), first.snapshot().modelTransforms().get(0).translation());
        assertEquals(List.of(false, false), first.snapshot().boneVisibility());

        Iterable<ProceduralDirective> hostile = () -> new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                throw new AssertionError("hostile iterator must be contained");
            }

            @Override
            public ProceduralDirective next() {
                throw new AssertionError("unreachable");
            }
        };
        ProceduralEvaluationResult rejected = runtime.evaluate(new ProceduralFrameInput(
                instance, new AnimationV2EvaluationSnapshot(2L,
                        reversedSchema.restPose(), Map.of(), List.of()),
                hostile, List.of()));
        assertFalse(rejected.published());
        assertSame(first.snapshot(), rejected.snapshot());
        assertDiagnostic(rejected, ProceduralDiagnosticCode.COMMAND_OVERFLOW);
    }

    private static ProceduralRigPlan plan(List<? extends ProceduralHook> hooks) {
        return plan(3L, hooks);
    }

    private static ProceduralRigPlan plan(long generation, List<? extends ProceduralHook> hooks) {
        return new ProceduralRigPlan(
                MODEL, generation, schema(), new int[] {-1, 0},
                List.of(new ProceduralSocketDefinition(SOCKET, 1, Transform.IDENTITY)),
                List.of(new ProceduralSurfaceDefinition(ProceduralSurfaceKind.MESH, MESH, 0)),
                List.of(new ProceduralSurfaceDefinition(ProceduralSurfaceKind.PRIMITIVE, PRIMITIVE, 1)), hooks);
    }

    private static BoneSchema schema() {
        return new BoneSchema(List.of("root", "tip"), List.of(Transform.IDENTITY,
                new Transform(new Vec3(0.0F, 1.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE)));
    }

    private static ModelInstance instance(long generation, String localId) {
        return new ModelInstance(BlendInstanceKey.ephemeral("session", localId), MODEL, generation);
    }

    private static ModelInstance instance(BlendModelKey modelKey, long generation, String localId) {
        return new ModelInstance(BlendInstanceKey.ephemeral("session", localId), modelKey, generation);
    }

    private static ProceduralRigPlan attachmentPlan(
            BlendModelKey modelKey,
            long generation,
            List<ProceduralAttachmentDescriptor> attachments,
            ProceduralAttachmentGraph attachmentGraph) {
        return new ProceduralRigPlan(
                modelKey, generation, schema(), new int[] {-1, 0},
                List.of(), List.of(), List.of(), List.of(), attachments, attachmentGraph);
    }

    private static AttachmentGraphFixture graphFixture(long generation, String suffix) {
        BlendModelKey owner = BlendModelKey.of("x3test", "claim-" + suffix + "-owner");
        BlendModelKey child = BlendModelKey.of("x3test", "claim-" + suffix + "-child");
        ProceduralAttachmentDescriptor edge = attachment(
                "claim-" + suffix + "-edge",
                new ProceduralAttachmentAnchor.Bone(0),
                new ProceduralAttachmentPayload.ChildModel(child));
        ProceduralAttachmentGraph graph = ProceduralAttachmentGraph.compile(
                generation, Map.of(owner, List.of(edge), child, List.of()));
        return new AttachmentGraphFixture(graph, List.of(
                attachmentPlan(owner, generation, List.of(edge), graph),
                attachmentPlan(child, generation, List.of(), graph)));
    }

    private static AttachmentGraphFixture copyGraphFixture(AttachmentGraphFixture source) {
        ProceduralRigPlan owner = source.plans().getFirst();
        ProceduralRigPlan child = source.plans().getLast();
        ProceduralAttachmentGraph graph = ProceduralAttachmentGraph.compile(
                owner.generation(), Map.of(owner.modelKey(), owner.attachments(), child.modelKey(), child.attachments()));
        return new AttachmentGraphFixture(graph, List.of(
                attachmentPlan(owner.modelKey(), owner.generation(), owner.attachments(), graph),
                attachmentPlan(child.modelKey(), child.generation(), child.attachments(), graph)));
    }

    private static boolean publishAfterBarrier(
            AttachmentGraphFixture fixture,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5L, TimeUnit.SECONDS), "publisher start barrier must be released");
        try {
            ProceduralAttachmentGraph.publish(fixture.plans());
            return true;
        } catch (IllegalStateException expectedGenerationConflict) {
            return false;
        }
    }

    private static AnimationV2EvaluationSnapshot pose(long revision) {
        return new AnimationV2EvaluationSnapshot(revision, new AnimationV2Pose(List.of(Transform.IDENTITY,
                new Transform(new Vec3(0.0F, 1.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE))), Map.of(), List.of());
    }

    private static EventHarness eventHarness(String localId, List<ProceduralVisualEventMarker> markers) {
        AnimationV2InstanceRuntime source = eventSource(schema());
        return eventHarness(localId, markers, source);
    }

    private static EventHarness eventHarness(
            String localId,
            List<ProceduralVisualEventMarker> markers,
            AnimationV2InstanceRuntime source) {
        ModelInstance instance = instance(3L, localId);
        ProceduralRigPlan plan = eventPlan(markers);
        return new EventHarness(instance, source, new ProceduralRigRuntime(plan, instance, source));
    }

    private static ProceduralRigPlan eventPlan(List<ProceduralVisualEventMarker> markers) {
        return new ProceduralRigPlan(
                MODEL, 3L, schema(), new int[] {-1, 0},
                List.of(new ProceduralSocketDefinition(SOCKET, 1, Transform.IDENTITY)),
                List.of(new ProceduralSurfaceDefinition(ProceduralSurfaceKind.MESH, MESH, 0)),
                List.of(new ProceduralSurfaceDefinition(ProceduralSurfaceKind.PRIMITIVE, PRIMITIVE, 1)),
                List.of(), List.of(), markers);
    }

    private static AnimationV2InstanceRuntime eventSource(BoneSchema schema) {
        return eventSource(schema, EVENT_TIMELINE, Map.of(EVENT_TIMELINE,
                eventState(EVENT_TIMELINE, AnimationV2PlaybackMode.HOLD, 1.0D, null, schema)));
    }

    private static AnimationV2InstanceRuntime eventSource(
            BoneSchema schema,
            BlendAnimationKey initial,
            Map<BlendAnimationKey, AnimationV2ControllerState> states) {
        AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                EVENT_LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2ControllerDefinition controller = new AnimationV2ControllerDefinition(
                EVENT_CONTROLLER, 0, List.of(layer), initial, states);
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(controller)));
    }

    private static AnimationV2ControllerState eventState(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double duration,
            BlendAnimationKey next,
            BoneSchema schema) {
        return eventState(key, mode, duration, next, schema, EVENT_LAYER);
    }

    private static AnimationV2ControllerState eventState(
            BlendAnimationKey key,
            AnimationV2PlaybackMode mode,
            double duration,
            BlendAnimationKey next,
            BoneSchema schema,
            BlendResourceId layerId) {
        AnimationV2Clip clip = new AnimationV2Clip(List.of(
                new AnimationV2Keyframe(0.0D, schema.restPose()),
                new AnimationV2Keyframe(duration, schema.restPose())));
        return new AnimationV2ControllerState(key, mode, 1.0D, 0.0D, next, Map.of(layerId, clip));
    }

    private static ProceduralVisualEventMarker marker(
            String eventPath,
            int markerIndex,
            int priority,
            ProceduralVisualEventPayload payload) {
        return marker(EVENT_TIMELINE, eventPath, markerIndex, 0.5D, priority, payload);
    }

    private static ProceduralVisualEventMarker marker(
            BlendAnimationKey timeline,
            String eventPath,
            int markerIndex,
            double timeSeconds,
            int priority,
            ProceduralVisualEventPayload payload) {
        return new ProceduralVisualEventMarker(
                EVENT_CONTROLLER, timeline, markerIndex, timeSeconds, id(eventPath), priority, payload);
    }

    private static void assertExactDouble(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                () -> "expected exact double " + Double.toHexString(expected)
                        + " but was " + Double.toHexString(actual));
    }

    private static void assertOffsetScaleFailure(float baseScale, float multiplier) {
        BoneSchema scaleSchema = new BoneSchema(List.of("root"), List.of(new Transform(
                Vec3.ZERO, Quaternion.IDENTITY, new Vec3(baseScale, baseScale, baseScale))));
        long generation = baseScale < 1.0F ? 32L : 33L;
        ModelInstance scaleInstance = new ModelInstance(
                BlendInstanceKey.ephemeral("x3test", "r5-failing-scale-" + generation), MODEL, generation);
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(new ProceduralRigPlan(
                MODEL, generation, scaleSchema, new int[] {-1}, List.of(), List.of(), List.of(), List.of()), scaleInstance);
        ProceduralEvaluationResult result = runtime.evaluate(new ProceduralFrameInput(scaleInstance,
                new AnimationV2EvaluationSnapshot(1L, scaleSchema.restPose(), Map.of(), List.of()), List.of(
                        new ProceduralDirective(id("failing-scale-" + generation), 0,
                                new ProceduralOperation.Offset(0, Vec3.ZERO, Quaternion.IDENTITY, multiplier))), List.of()));
        assertFalse(result.published(), "non-representable post-multiply scale must fail closed");
        assertDiagnostic(result, ProceduralDiagnosticCode.NONFINITE_INPUT);
    }

    private static ProceduralFrameInput frame(
            ModelInstance instance,
            AnimationV2EvaluationSnapshot pose,
            Iterable<ProceduralDirective> directives,
            Iterable<ProceduralVisualEvent> events) {
        return new ProceduralFrameInput(instance, pose, directives, events);
    }

    private static ProceduralHook hook(String suffix, int priority, ProceduralOperation operation) {
        return new ProceduralHook() {
            @Override
            public BlendResourceId id() {
                return ProceduralRigContractsTest.id(suffix);
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public void contribute(ProceduralHookContext context, ProceduralHookCommandSink sink) {
                sink.emit(operation);
            }
        };
    }

    private static ProceduralAttachmentDescriptor attachment(
            String suffix,
            ProceduralAttachmentAnchor anchor,
            ProceduralAttachmentPayload payload) {
        return new ProceduralAttachmentDescriptor(id(suffix), anchor, payload, Transform.IDENTITY, true);
    }

    private static List<ProceduralAttachmentDescriptor> attachmentList(int count) {
        List<ProceduralAttachmentDescriptor> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(attachment("attachment-" + index, new ProceduralAttachmentAnchor.Bone(0),
                    new ProceduralAttachmentPayload.Item(id("item"))));
        }
        return values;
    }

    private static BlendResourceId id(String suffix) {
        return BlendResourceId.of("x3test", suffix);
    }

    private record EventHarness(
            ModelInstance instance,
            AnimationV2InstanceRuntime source,
            ProceduralRigRuntime runtime) {
    }

    private record AttachmentGraphFixture(
            ProceduralAttachmentGraph graph,
            List<ProceduralRigPlan> plans) {
    }

    private static String digest(ProceduralFrameSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append(snapshot.modelInstance()).append(':').append(snapshot.sourceRevision());
        for (Transform transform : snapshot.modelTransforms()) {
            builder.append('|').append(transform.translation()).append(transform.rotation()).append(transform.scale());
        }
        builder.append(snapshot.boneVisibility()).append(snapshot.surfaceVisibility()).append(snapshot.surfaceOverrides());
        builder.append(snapshot.visualEvents().events());
        return builder.toString();
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) {
            pivot--;
        }
        if (pivot < 0) {
            return false;
        }
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) {
            successor--;
        }
        int temporary = values[pivot];
        values[pivot] = values[successor];
        values[successor] = temporary;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            temporary = values[left];
            values[left] = values[right];
            values[right] = temporary;
        }
        return true;
    }

    private static void assertDiagnostic(ProceduralEvaluationResult result, ProceduralDiagnosticCode code) {
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code() == code),
                () -> "missing " + code + " in " + result.diagnostics());
    }
}
