package com.liy.blendlib.core.animation.v2;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import com.liy.blendlib.core.descriptor.AnimationDefinition;
import com.liy.blendlib.core.descriptor.AnimationEventDefinition;
import com.liy.blendlib.core.descriptor.AnimationStateDefinition;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LayerAnimationVisualEventCursorTest {
    private static final BlendAnimationKey A = BlendAnimationKey.parse("events:a");
    private static final BlendAnimationKey B = BlendAnimationKey.parse("events:b");
    private static final BlendResourceId BASE = id("base");

    @Test
    void firstObservationIsSilentAndExactEndpointsAreConsumedOnce() {
        var layers = looping(1, markers(0, 0.25, 0.5, 0.75, 1));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        assertTrue(cursor.consume(runtime.advance(0.5)).isEmpty());
        var snapshot = runtime.advance(0.25);
        var events = cursor.consume(snapshot);
        assertEquals(List.of(0.75), times(events));
        assertEquals(BASE, events.getFirst().controllerId());
        assertEquals(BASE, events.getFirst().layerId());
        assertEquals(A, events.getFirst().animation());
        assertEquals(1.0F, events.getFirst().effectiveWeight());
        assertThrows(UnsupportedOperationException.class, () -> events.clear());
        assertTrue(cursor.consume(snapshot).isEmpty());
        assertTrue(cursor.consume(runtime.advance(0)).isEmpty());
        assertEquals(List.of(1.0), times(cursor.consume(runtime.advance(0.25))));
    }

    @Test
    void callbackFailureCannotReplayAlreadyReturnedEvents() {
        var layers = looping(1, markers(0.25, 0.5));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var snapshot = runtime.advance(0.25);
        assertThrows(IllegalStateException.class, () -> cursor.consume(snapshot).forEach(event -> {
            throw new IllegalStateException("consumer failed");
        }));
        assertTrue(cursor.consume(snapshot).isEmpty());
        assertEquals(List.of(0.5), times(cursor.consume(runtime.advance(0.25))));
    }

    @Test
    void cursorRequiresExactPlanAndRejectsForeignFabricatedAndStalePublications() {
        var layers = looping(1, markers(0.25, 0.5, 0.75));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        assertThrows(IllegalArgumentException.class,
                () -> layers.newVisualEventCursor(new AnimationV2InstanceRuntime(looping(1, markers(0.25)).plan())));
        var initial = runtime.latestSnapshot();
        cursor.consume(initial);
        var foreign = new AnimationV2InstanceRuntime(layers.plan()).advance(0.25);
        assertThrows(IllegalArgumentException.class, () -> cursor.consume(foreign));
        var latest = runtime.advance(0.25);
        var fabricated = new AnimationV2EvaluationSnapshot(
                latest.revision(), latest.pose(), latest.playheads(), latest.diagnostics());
        assertThrows(IllegalArgumentException.class, () -> cursor.consume(fabricated));
        assertThrows(IllegalArgumentException.class, () -> cursor.consume(initial));
        assertEquals(List.of(0.25), times(cursor.consume(latest)));
        var skipped = runtime.advance(0.25);
        var afterGap = runtime.advance(0.25);
        assertThrows(IllegalArgumentException.class, () -> cursor.consume(skipped));
        assertTrue(cursor.consume(afterGap).isEmpty());
        assertTrue(cursor.consume(afterGap).isEmpty());
        assertEquals(List.of(0.25), times(cursor.consume(runtime.advance(0.5))));
    }

    @Test
    void everyActualLoopOccurrenceIsReportedWithoutSyntheticTimeZero() {
        var layers = looping(0.25F, markers(0, 0.125, 0.25));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var result = cursor.consume(runtime.advance(0.875));
        assertEquals(List.of(0.125, 0.25, 0.125, 0.25, 0.125, 0.25, 0.125), times(result));
        assertEquals(List.of(0L, 0L, 1L, 1L, 2L, 2L, 3L),
                result.stream().map(LayerAnimationVisualEvent::loopEpoch).toList());
        assertEquals(List.of(0L, 0L, 1L, 1L, 2L, 2L, 3L),
                result.stream().map(LayerAnimationVisualEvent::occurrence).toList());
        assertEquals(List.of(0.25), times(cursor.consume(runtime.advance(0.125))));
    }

    @Test
    void automaticNextUsesActualStateMarkersAndDoesNotEmitOutgoingBlendMarkers() {
        var layers = layers(0.5F, Map.of(
                A.resourceId(), state(false, 1, 0, B, markers(0, 0.25, 0.5)),
                B.resourceId(), state(true, 1, 0.5, null, markers(0, 0.125, 0.25))), List.of(layer("base", 0, 1)));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var result = cursor.consume(runtime.advance(0.625));
        assertEquals(List.of(0.25, 0.5, 0.125), times(result));
        assertEquals(List.of(A, A, B), result.stream().map(LayerAnimationVisualEvent::animation).toList());
        assertEquals(List.of(0L, 0L, 1L), result.stream().map(LayerAnimationVisualEvent::occurrence).toList());
        assertEquals(List.of(0L, 0L, 0L), result.stream().map(LayerAnimationVisualEvent::loopEpoch).toList());
        var blended = runtime.advance(0.125);
        assertTrue(blended.playheads().get(BASE).transitionProgress() < 1);
        assertEquals(List.of(0.25), times(cursor.consume(blended)));
    }

    @Test
    void semanticSeekAndRestartSilentlyRearmInsteadOfReplayingMarkers() {
        var layers = looping(1, markers(0, 0.25, 0.5, 0.75, 1));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        assertTrue(cursor.consume(runtime.advanceAtFrame(0.5,
                List.of(new AnimationV2Command(BASE, A, 1, 0.5, 1)))).isEmpty());
        assertEquals(List.of(0.75), times(cursor.consume(runtime.advance(0.25))));
        assertTrue(cursor.consume(runtime.advanceAtFrame(0.25,
                List.of(new AnimationV2Command(BASE, A, 2, 0, 1)))).isEmpty());
        var restarted = cursor.consume(runtime.advance(0.25));
        assertEquals(List.of(0.25), times(restarted));
        assertTrue(restarted.getFirst().occurrence() > 0L);
    }

    @Test
    void discontinuityRearmsOnlyItsControllerAndSequenceRejectionIsSilent() {
        var other = id("other");
        var layers = layers(1, Map.of(A.resourceId(), state(true, 1, 0, null, markers(0.25, 0.5, 0.75))),
                List.of(layer("base", 0, 1), layer("other", 1, 1)));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var events = cursor.consume(runtime.advanceAtFrame(0.25,
                List.of(new AnimationV2Command(BASE, A, 1, 0.5, 1))));
        assertEquals(List.of(0.25), times(events));
        assertEquals(other, events.getFirst().controllerId());
        var rejected = runtime.advanceAtFrame(0.25, List.of(), List.of(new AnimationV2SequenceRejection(BASE, 1)));
        assertTrue(rejected.observerTraversal().controller(BASE).discontinuity());
        assertEquals(List.of(other), cursor.consume(rejected).stream().map(LayerAnimationVisualEvent::controllerId).toList());
    }

    @Test
    void zeroEffectiveWeightConsumesIntervalsWithoutBackfillAndSamplesCurrentPublication() {
        var layers = layers(1, Map.of(A.resourceId(), state(true, 1, 0, null, markers(0.25, 0.5, 0.75))),
                List.of(layer("base", 0, 0.5F), layer("disabled", 1, 0)));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var target = new AnimationV2LayerWeights.Key(BASE, BASE);
        assertTrue(cursor.consume(runtime.advanceWeightedAtFrame(0.25, List.of(),
                new AnimationV2LayerWeights(Map.of(target, 0.0F)))).isEmpty());
        var weighted = cursor.consume(runtime.advanceWeightedAtFrame(0.25, List.of(),
                new AnimationV2LayerWeights(Map.of(target, 0.5F))));
        assertEquals(List.of(0.5), times(weighted));
        assertEquals(0.25F, weighted.getFirst().effectiveWeight());
        var restored = cursor.consume(runtime.advance(0.25));
        assertEquals(List.of(0.75), times(restored));
        assertEquals(0.5F, restored.getFirst().effectiveWeight());
        assertEquals(0.25F, weighted.getFirst().effectiveWeight());
    }

    @Test
    void masksAndPriorityDoNotFilterEventsAndEachOwnerHasIndependentConsumption() {
        var masked = new ModelAnimationLayers.Layer(id("masked"), 0, AnimationV2LayerMode.OVERRIDE, 0.5F,
                List.of(new BoneMask.NamedWeight("root", 0)), A);
        var layers = layers(1, Map.of(A.resourceId(), state(true, 1, 0, null, markers(0.25))),
                List.of(layer("high", 5, 1), masked, layer("low", -1, 1)));
        var first = new AnimationV2InstanceRuntime(layers.plan());
        var second = new AnimationV2InstanceRuntime(layers.plan());
        var firstCursor = layers.newVisualEventCursor(first);
        var secondCursor = layers.newVisualEventCursor(second);
        firstCursor.consume(first.latestSnapshot());
        secondCursor.consume(second.latestSnapshot());
        var result = firstCursor.consume(first.advance(0.25));
        assertEquals(List.of(id("low"), id("masked"), id("high")),
                result.stream().map(LayerAnimationVisualEvent::layerId).toList());
        assertEquals(List.of(1F, 0.5F, 1F), result.stream().map(LayerAnimationVisualEvent::effectiveWeight).toList());
        assertEquals(result, secondCursor.consume(second.advance(0.25)));
    }

    @Test
    void catchUpIsLatestOneClipLocalSecondNotOneWallClockSecond() {
        var layers = layers(4, Map.of(A.resourceId(), state(true, 4, 0, null, markers(0.5, 1, 2, 2.5, 3, 3.5))),
                List.of(layer("base", 0, 1)));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        assertEquals(List.of(2.5, 3.0), times(cursor.consume(runtime.advance(0.75))));
        assertEquals(List.of(3.5), times(cursor.consume(runtime.advance(0.125))));
    }

    @Test
    void catchUpTailSpansRealLoopsAndStateTransitions() {
        var layers = looping(0.5F, markers(0, 0.125, 0.5));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var result = cursor.consume(runtime.advance(1.75));
        assertEquals(List.of(0.5, 0.125, 0.5, 0.125), times(result));
        assertEquals(List.of(1L, 2L, 2L, 3L), result.stream().map(LayerAnimationVisualEvent::loopEpoch).toList());

        var chained = layers(1, Map.of(
                A.resourceId(), state(false, 1, 0, B, markers(0.25, 0.5, 0.75, 1)),
                B.resourceId(), state(true, 1, 0, null, markers(0, 0.25, 0.5))), List.of(layer("base", 0, 1)));
        var nextRuntime = new AnimationV2InstanceRuntime(chained.plan());
        var nextCursor = chained.newVisualEventCursor(nextRuntime);
        nextCursor.consume(nextRuntime.latestSnapshot());
        var next = nextCursor.consume(nextRuntime.advance(1.5));
        assertEquals(List.of(0.75, 1.0, 0.25, 0.5), times(next));
        assertEquals(List.of(A, A, B, B), next.stream().map(LayerAnimationVisualEvent::animation).toList());
    }

    @Test
    void catchUpAllowanceIsIndependentForEachController() {
        var layers = layers(4, Map.of(A.resourceId(), state(true, 1, 0, null, markers(0.5, 1, 2, 2.5, 3))),
                List.of(layer("first", 0, 1), layer("second", 1, 1)));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        assertEquals(List.of(2.5, 3.0, 2.5, 3.0), times(cursor.consume(runtime.advance(3))));
    }

    @Test
    void truncatedTraversalSilentlyConsumesAndNextExactPublicationWorks() {
        var layers = looping(0.25F, markers(0, 0.125, 0.25));
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var truncated = runtime.advance(0.25 * (AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE + 1) + 0.125);
        assertTrue(truncated.observerTraversal().controller(BASE).truncated());
        assertTrue(cursor.consume(truncated).isEmpty());
        assertTrue(cursor.consume(truncated).isEmpty());
        assertEquals(List.of(0.25), times(cursor.consume(runtime.advance(0.125))));
    }

    @Test
    void totalPublicationBudgetAllowsExactLimitAndDropsOverflowAtomicallyAcrossLayers() {
        var dense = new ArrayList<AnimationEventDefinition>();
        for (int index = 0; index < BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE; index++) {
            dense.add(new AnimationEventDefinition(0.25, id("marker" + index)));
        }
        int exactLayerCount = BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE / dense.size();
        var layerList = new ArrayList<ModelAnimationLayers.Layer>();
        for (int index = 0; index <= exactLayerCount; index++) {
            layerList.add(layer("layer" + index, index, 1));
        }
        var layers = layers(1, Map.of(A.resourceId(), state(true, 1, 0, null, dense)), layerList);
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        var last = id("layer" + exactLayerCount);
        var exact = cursor.consume(runtime.advanceWeightedAtFrame(0.25, List.of(),
                new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(last, last), 0F))));
        assertEquals(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE, exact.size());
        cursor.consume(runtime.advance(0.75));
        var overflow = runtime.advance(0.25);
        assertTrue(cursor.consume(overflow).isEmpty());
        assertTrue(cursor.consume(overflow).isEmpty());
        assertTrue(cursor.consume(runtime.advance(0.25)).isEmpty());
    }

    @Test
    void equalTimeMarkersKeepDescriptorOrderAndEventsValidateTheirImmutableFacts() {
        var events = List.of(new AnimationEventDefinition(0.25, id("second")),
                new AnimationEventDefinition(0.125, id("first")), new AnimationEventDefinition(0.25, id("third")));
        var layers = looping(1, events);
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        cursor.consume(runtime.latestSnapshot());
        assertEquals(List.of(id("first"), id("second"), id("third")), cursor.consume(runtime.advance(0.25))
                .stream().map(event -> event.event().eventKey()).toList());
        var marker = new AnimationVisualEvent(0.25, id("marker"));
        assertThrows(IllegalArgumentException.class, () -> new LayerAnimationVisualEvent(BASE, BASE, A, marker, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LayerAnimationVisualEvent(BASE, BASE, A, marker, 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LayerAnimationVisualEvent(BASE, BASE, A, marker, 0, 0, Float.NaN));
        assertThrows(NullPointerException.class, () -> new LayerAnimationVisualEvent(BASE, BASE, A, null, 0, 0, 1));
    }

    private static ModelAnimationLayers looping(float duration, List<AnimationEventDefinition> markers) {
        return layers(duration, Map.of(A.resourceId(), state(true, 1, 0, null, markers)), List.of(layer("base", 0, 1)));
    }

    private static ModelAnimationLayers layers(float duration, Map<BlendResourceId, AnimationStateDefinition> states,
            List<ModelAnimationLayers.Layer> layers) {
        var clip = new AnimationClip("clip", List.of(new AnimationChannel(0, AnimationPath.TRANSLATION,
                Interpolation.LINEAR, new float[] {0, duration}, new float[] {0, 0, 0, 1, 0, 0})));
        var geometry = new MeshPrimitive("surface", new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1}, new float[] {0, 0, 1, 0, 0, 1},
                new int[] {0, 1, 2}, null, null);
        var asset = new ModelAsset(id("model"), id("model.json"), 1, ModelProfile.RIGID_V1, 1, Map.of(),
                new AnimationDefinition(A.resourceId(), states),
                List.of(new ModelNode(0, "root", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0), List.of(new ModelPrimitive(0, 0, 0, geometry)), null, List.of(clip), new SocketTable(Map.of()),
                new Bounds(Vec3.ZERO, Vec3.ZERO), List.of());
        return new ModelAnimationLayers(asset, layers);
    }

    private static AnimationStateDefinition state(boolean loop, double speed, double blend, BlendAnimationKey next,
            List<AnimationEventDefinition> markers) {
        return new AnimationStateDefinition("clip", loop, speed, blend, next == null ? null : next.resourceId(), markers);
    }

    private static ModelAnimationLayers.Layer layer(String name, int priority, float weight) {
        return new ModelAnimationLayers.Layer(id(name), priority, AnimationV2LayerMode.OVERRIDE, weight, List.of(), A);
    }

    private static List<AnimationEventDefinition> markers(double... times) {
        var events = new ArrayList<AnimationEventDefinition>();
        for (int index = 0; index < times.length; index++) {
            events.add(new AnimationEventDefinition(times[index], id("marker" + index)));
        }
        return events;
    }

    private static List<Double> times(List<LayerAnimationVisualEvent> events) {
        return events.stream().map(event -> event.event().timeSeconds()).toList();
    }

    private static BlendResourceId id(String name) { return BlendResourceId.of("events", name); }
}
