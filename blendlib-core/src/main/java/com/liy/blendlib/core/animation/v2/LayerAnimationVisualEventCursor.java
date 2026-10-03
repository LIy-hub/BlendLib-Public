package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import com.liy.blendlib.core.animation.runtime.SynchronizedVisualEventCursor;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Instance-local, descriptor-backed presentation observer of the real v2 owner traversal.
 * Obtain one from {@link ModelAnimationLayers#newVisualEventCursor(AnimationV2InstanceRuntime)} and discard it
 * with its owner on model, generation, or lifecycle changes. It has no playback engine or command authority.
 *
 * <p>Call on the owner's extraction thread for every publication, even when no handler is installed. First
 * observation and missing revisions silently establish a new baseline; history is never reconstructed. Commands,
 * rejected sequences, and truncated traces silently re-arm the affected controller. Intervals are start-exclusive
 * and end-inclusive, so time-zero markers are never synthesized at entries or wraps.</p>
 */
public final class LayerAnimationVisualEventCursor {
    private final AnimationV2InstanceRuntime runtime;
    private final Map<BlendAnimationKey, List<AnimationVisualEvent>> visualEvents;
    private long consumedRevision = -1L;

    LayerAnimationVisualEventCursor(
            AnimationV2InstanceRuntime runtime,
            AnimationV2InstancePlan plan,
            Map<BlendAnimationKey, List<AnimationVisualEvent>> visualEvents) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (runtime.plan() != Objects.requireNonNull(plan, "plan")) {
            throw new IllegalArgumentException("visual event cursor requires the exact model layer plan");
        }
        this.visualEvents = Objects.requireNonNull(visualEvents, "visualEvents");
    }

    /**
     * Consumes only the exact {@link AnimationV2InstanceRuntime#latestSnapshot()} object of this owner.
     * Foreign, fabricated, and stale snapshots throw without changing the cursor; repeated latest snapshots return
     * an empty list. The publication is committed before this method returns, so callback failures cannot replay it.
     *
     * <p>Only the latest {@link SynchronizedVisualEventCursor#MAX_CATCH_UP_SECONDS} clip-local seconds per controller
     * are considered. A zero sampled effective layer weight consumes its interval silently, with no later backfill.
     * If the total event count exceeds {@link BlendAssetLimits#MAX_VISUAL_EVENTS_PER_ADVANCE}, this entire publication
     * is dropped atomically and stays consumed. Returned events are immutable and ordered by controller evaluation
     * order, actual traversal, layer order, and descriptor marker time (stable for equal-time markers).</p>
     */
    public List<LayerAnimationVisualEvent> consume(AnimationV2EvaluationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot != runtime.latestSnapshot()) {
            throw new IllegalArgumentException("visual event snapshot must be the owner's exact latest publication");
        }
        long revision = snapshot.revision();
        if (revision == consumedRevision) {
            return List.of();
        }
        long previousRevision = consumedRevision;
        consumedRevision = revision;
        if (previousRevision < 0L || revision - previousRevision != 1L) {
            return List.of();
        }

        ArrayList<LayerAnimationVisualEvent> result = new ArrayList<>();
        for (AnimationV2ControllerDefinition controller : runtime.plan().controllers()) {
            var traversal = snapshot.observerTraversal().controller(controller.id());
            if (traversal == null || traversal.discontinuity() || traversal.truncated()) {
                continue;
            }
            List<AnimationV2ObserverTraversal.Segment> segments = traversal.segments();
            double remaining = SynchronizedVisualEventCursor.MAX_CATCH_UP_SECONDS;
            int firstSegment = segments.size();
            double firstStart = 0.0D;
            // Retain only the real tail of the trace, across loops and automatic next states alike.
            while (firstSegment > 0 && remaining > 0.0D) {
                var segment = segments.get(--firstSegment);
                double duration = segment.endInclusiveSeconds() - segment.startExclusiveSeconds();
                firstStart = duration > remaining
                        ? segment.endInclusiveSeconds() - remaining : segment.startExclusiveSeconds();
                remaining -= duration;
            }
            for (int index = firstSegment; index < segments.size(); index++) {
                var segment = segments.get(index);
                double start = index == firstSegment ? firstStart : segment.startExclusiveSeconds();
                List<AnimationVisualEvent> markers = visualEvents.getOrDefault(segment.timeline(), List.of());
                int firstMarker = firstMarkerAfter(markers, start);
                for (AnimationV2LayerDefinition layer : controller.layers()) {
                    float weight = snapshot.effectiveLayerWeights().getOrDefault(
                            new AnimationV2LayerWeights.Key(controller.id(), layer.id()), 0.0F);
                    if (weight <= 0.0F) {
                        continue;
                    }
                    for (int markerIndex = firstMarker; markerIndex < markers.size(); markerIndex++) {
                        AnimationVisualEvent marker = markers.get(markerIndex);
                        if (marker.timeSeconds() > segment.endInclusiveSeconds()) {
                            break;
                        }
                        if (result.size() == BlendAssetLimits.MAX_VISUAL_EVENTS_PER_ADVANCE) {
                            return List.of();
                        }
                        result.add(new LayerAnimationVisualEvent(controller.id(), layer.id(), segment.timeline(),
                                marker, segment.loopEpoch(), segment.occurrence(), weight));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static int firstMarkerAfter(List<AnimationVisualEvent> markers, double time) {
        int low = 0;
        int high = markers.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (markers.get(middle).timeSeconds() <= time) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
