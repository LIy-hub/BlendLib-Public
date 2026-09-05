package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerPlayhead;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerState;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.AnimationV2InstancePlan;
import com.liy.blendlib.core.animation.v2.AnimationV2InstanceRuntime;
import com.liy.blendlib.core.animation.v2.AnimationV2ObserverTraversal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Package-private bridge from one exact frozen X2 runtime to X3 visual event markers.
 *
 * <p>Unlike a caller-provided event/provenance tuple, the registry owns one immutable marker catalog, validates it
 * against the exact runtime plan during configuration, accepts only that runtime's current snapshot object, and
 * creates an opaque trusted resolution only from the bounded automatic traversal captured by that exact X2
 * publication. A command/rejection is an observer discontinuity, never a synthetic seek crossing. LOOP epochs and
 * repeated occurrences come from the owner runtime rather than caller-provided provenance.</p>
 */
final class ProceduralVisualEventTimelineRegistry {
    private static final Comparator<ProceduralVisualEventMarker> MARKER_ORDER = Comparator
            .comparing((ProceduralVisualEventMarker marker) -> marker.controllerId().value())
            .thenComparing(marker -> marker.timeline().value())
            .thenComparingInt(ProceduralVisualEventMarker::markerIndex)
            .thenComparingDouble(ProceduralVisualEventMarker::timeSeconds)
            .thenComparing(marker -> marker.eventId().value())
            .thenComparingInt(ProceduralVisualEventMarker::priority)
            .thenComparing(marker -> ProceduralVisualEventBatch.canonicalPayloadKey(marker.payload()));

    private final ProceduralRigPlan rigPlan;
    private final AnimationV2InstanceRuntime sourceRuntime;
    private final AnimationV2InstancePlan sourcePlan;
    private final Map<BlendResourceId, List<ProceduralVisualEventMarker>> markersByController;
    private final Object resolutionAuthority = new Object();
    /** Updated only after the containing X3 frame and replay fence are atomically published. */
    private Map<BlendResourceId, Observation> committedObservations = Map.of();

    private ProceduralVisualEventTimelineRegistry(
            ProceduralRigPlan rigPlan,
            AnimationV2InstanceRuntime sourceRuntime,
            Map<BlendResourceId, List<ProceduralVisualEventMarker>> markersByController) {
        this.rigPlan = rigPlan;
        this.sourceRuntime = sourceRuntime;
        this.sourcePlan = sourceRuntime.plan();
        this.markersByController = markersByController;
    }

    static ProceduralVisualEventTimelineRegistry freeze(
            ProceduralRigPlan rigPlan,
            AnimationV2InstanceRuntime sourceRuntime,
            List<ProceduralVisualEventMarker> markers) {
        Objects.requireNonNull(rigPlan, "rigPlan");
        Objects.requireNonNull(sourceRuntime, "sourceRuntime");
        Objects.requireNonNull(markers, "markers");
        AnimationV2InstancePlan sourcePlan = sourceRuntime.plan();
        if (!sourcePlan.boneSchema().names().equals(rigPlan.schema().names())
                || !sourcePlan.boneSchema().restPose().transforms().equals(rigPlan.schema().restPose().transforms())) {
            throw new IllegalArgumentException("event timeline runtime plan does not match the frozen X3 rig schema");
        }
        List<ProceduralVisualEventMarker> ordered = new ArrayList<>(markers.size());
        for (ProceduralVisualEventMarker marker : markers) {
            marker = Objects.requireNonNull(marker, "event marker");
            AnimationV2ControllerDefinition controller = sourcePlan.controller(marker.controllerId());
            AnimationV2ControllerState state = controller.state(marker.timeline());
            if (marker.timeSeconds() > state.durationSeconds()) {
                throw new IllegalArgumentException("event marker lies outside the declared active X2 timeline duration");
            }
            ordered.add(marker);
        }
        ordered.sort(MARKER_ORDER);
        LinkedHashMap<BlendResourceId, List<ProceduralVisualEventMarker>> byController = new LinkedHashMap<>();
        for (ProceduralVisualEventMarker marker : ordered) {
            byController.computeIfAbsent(marker.controllerId(), ignored -> new ArrayList<>()).add(marker);
        }
        LinkedHashMap<BlendResourceId, List<ProceduralVisualEventMarker>> frozen = new LinkedHashMap<>();
        byController.forEach((controller, controllerMarkers) -> frozen.put(controller, List.copyOf(controllerMarkers)));
        // `Map.copyOf` is deliberately not used here: marker-controller iteration is part of deterministic diagnostic
        // ordering, so retain the already canonical LinkedHashMap order from MARKER_ORDER.
        return new ProceduralVisualEventTimelineRegistry(
                rigPlan, sourceRuntime, Collections.unmodifiableMap(new LinkedHashMap<>(frozen)));
    }

    boolean matches(ProceduralRigPlan expectedPlan, AnimationV2InstanceRuntime expectedRuntime) {
        return rigPlan == expectedPlan && sourceRuntime == expectedRuntime;
    }

    boolean accepts(AnimationV2EvaluationSnapshot evaluation) {
        return sourceRuntime.latestSnapshot() == evaluation;
    }

    Resolution resolve(AnimationV2EvaluationSnapshot evaluation, ProceduralDiagnosticCollector diagnostics) {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (!accepts(evaluation)) {
            diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                    rigPlan.modelKey().resourceId(), "X3 visual events require the exact current snapshot published by their bound X2 runtime");
            return Resolution.rejected();
        }
        LinkedHashMap<BlendResourceId, Observation> next = new LinkedHashMap<>(committedObservations);
        List<TrustedEvent> events = new ArrayList<>();
        AnimationV2ObserverTraversal observerTraversal = evaluation.observerTraversal();
        for (Map.Entry<BlendResourceId, List<ProceduralVisualEventMarker>> entry : markersByController.entrySet()) {
            BlendResourceId controllerId = entry.getKey();
            AnimationV2ControllerPlayhead playhead = evaluation.playheads().get(controllerId);
            if (playhead == null || !controllerId.equals(playhead.controllerId())) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "event marker catalog controller is absent from the exact current X2 playhead snapshot");
                return Resolution.rejected();
            }
            AnimationV2ControllerDefinition controller;
            AnimationV2ControllerState activeState;
            try {
                controller = sourcePlan.controller(controllerId);
                activeState = controller.state(playhead.state());
            } catch (RuntimeException exception) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "exact X2 playhead names an unknown controller or active timeline");
                return Resolution.rejected();
            }
            double activeTime = playhead.timeSeconds();
            if (!Double.isFinite(activeTime) || activeTime < 0.0D || activeTime > activeState.durationSeconds()) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "exact X2 playhead is outside the active frozen timeline bounds");
                return Resolution.rejected();
            }
            BlendAnimationKey activeTimeline = playhead.state();
            AnimationV2ObserverTraversal.ControllerTraversal controllerTraversal = observerTraversal.controller(controllerId);
            if (controllerTraversal == null || !controllerId.equals(controllerTraversal.controllerId())
                    || !activeTimeline.equals(controllerTraversal.terminalState())
                    || Double.compare(activeTime, controllerTraversal.terminalTimeSeconds()) != 0) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "exact X2 snapshot lacks a matching owner-produced observer terminal traversal");
                return Resolution.rejected();
            }
            AnimationV2ObserverTraversal.Continuity continuity = controllerTraversal.continuity();
            AnimationV2ObserverTraversal.Anchor terminalAnchor = continuity.terminalAnchor();
            if (!activeTimeline.equals(terminalAnchor.timeline())
                    || Double.compare(activeTime, terminalAnchor.timeSeconds()) != 0
                    || terminalAnchor.lastRevision() != evaluation.revision()) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "exact X2 observer continuity terminal anchor does not match this publication");
                return Resolution.rejected();
            }
            if (controllerTraversal.truncated()) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "bounded X2 observer traversal was truncated; X3 refuses to publish a partial event path");
                return Resolution.rejected();
            }

            Observation previous = committedObservations.get(controllerId);
            if (previous == null) {
                // A delayed first observer arms only at the exact owner terminal. Its earlier retained history remains
                // intentionally unavailable to this new X3 scope and cannot burst historical markers.
                next.put(controllerId, Observation.from(terminalAnchor));
                continue;
            }
            if (controllerTraversal.discontinuity()) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "X2 command or rejection discontinuity invalidated the armed X3 continuity anchor");
                return Resolution.rejected();
            }
            List<AnimationV2ObserverTraversal.Publication> publications = contiguousPublicationsAfter(previous, continuity);
            if (publications == null) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                        controllerId, "X2 no longer retains a bounded contiguous history from X3's exact committed observation");
                return Resolution.rejected();
            }
            for (AnimationV2ObserverTraversal.Publication publication : publications) {
                for (AnimationV2ObserverTraversal.Segment segment : publication.segments()) {
                    AnimationV2ControllerState segmentState;
                    try {
                        segmentState = controller.state(segment.timeline());
                    } catch (RuntimeException exception) {
                        diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                                controllerId, "owner-produced X2 observer segment names an unknown timeline");
                        return Resolution.rejected();
                    }
                    if (segment.startExclusiveSeconds() < 0.0D
                            || segment.endInclusiveSeconds() > segmentState.durationSeconds()) {
                        diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                                controllerId, "owner-produced X2 observer segment is outside its frozen timeline bounds");
                        return Resolution.rejected();
                    }
                    double intervalEndExclusive = Math.nextUp(segment.endInclusiveSeconds());
                    if (!Double.isFinite(intervalEndExclusive) || intervalEndExclusive <= segment.startExclusiveSeconds()) {
                        diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_PROVENANCE,
                                controllerId, "owner-produced X2 observer segment did not produce a finite nonempty event interval");
                        return Resolution.rejected();
                    }
                    for (ProceduralVisualEventMarker marker : entry.getValue()) {
                        if (!marker.timeline().equals(segment.timeline())
                                || marker.timeSeconds() <= segment.startExclusiveSeconds()
                                || marker.timeSeconds() > segment.endInclusiveSeconds()) {
                            continue;
                        }
                        ProceduralVisualEvent event = new ProceduralVisualEvent(marker.eventId(), marker.priority(),
                                new ProceduralVisualEventProvenance(controllerId, segment.timeline().resourceId(), segment.loopEpoch(),
                                        marker.markerIndex(), segment.occurrence(), publication.revision(),
                                        segment.startExclusiveSeconds(), intervalEndExclusive), marker.payload());
                        events.add(new TrustedEvent(event, resolutionAuthority));
                    }
                }
            }
            next.put(controllerId, Observation.from(terminalAnchor));
        }
        return new Resolution(List.copyOf(events), Map.copyOf(next), resolutionAuthority, true);
    }

    /**
     * Finds the exact committed X3 observation in the immutable X2 rolling history and returns every later real
     * publication. A null result is deliberately fail-closed: accepting the newest segment alone could permanently
     * lose a marker crossed by an X2 publication that X3 did not consume.
     */
    private static List<AnimationV2ObserverTraversal.Publication> contiguousPublicationsAfter(
            Observation observation,
            AnimationV2ObserverTraversal.Continuity continuity) {
        AnimationV2ObserverTraversal.Anchor terminal = continuity.terminalAnchor();
        if (matches(observation, terminal)) {
            return List.of();
        }
        List<AnimationV2ObserverTraversal.Publication> publications = continuity.publications();
        int first = -1;
        if (matches(observation, continuity.initialAnchor())) {
            first = 0;
        }
        for (int index = 0; index < publications.size(); index++) {
            if (matches(observation, publications.get(index).startAnchor())) {
                first = index;
                break;
            }
        }
        if (first < 0) {
            return null;
        }
        AnimationV2ObserverTraversal.Anchor cursor = first == 0
                ? continuity.initialAnchor()
                : publications.get(first).startAnchor();
        for (int index = first; index < publications.size(); index++) {
            AnimationV2ObserverTraversal.Publication publication = publications.get(index);
            if (!anchorsJoin(cursor, publication.startAnchor())) {
                return null;
            }
            cursor = publication.terminalAnchor();
        }
        if (!anchorsJoin(cursor, terminal)) {
            return null;
        }
        return List.copyOf(publications.subList(first, publications.size()));
    }

    private static boolean matches(Observation observation, AnimationV2ObserverTraversal.Anchor anchor) {
        return observation.timeline().equals(anchor.timeline())
                && Double.compare(observation.timeSeconds(), anchor.timeSeconds()) == 0
                && observation.loopEpoch() == anchor.loopEpoch()
                && observation.occurrence() == anchor.occurrence()
                && observation.sourceRevision() >= anchor.firstRevision()
                && observation.sourceRevision() <= anchor.lastRevision();
    }

    private static boolean anchorsJoin(
            AnimationV2ObserverTraversal.Anchor first,
            AnimationV2ObserverTraversal.Anchor second) {
        return first.timeline().equals(second.timeline())
                && Double.compare(first.timeSeconds(), second.timeSeconds()) == 0
                && first.loopEpoch() == second.loopEpoch()
                && first.occurrence() == second.occurrence()
                && first.firstRevision() <= second.lastRevision()
                && second.firstRevision() <= first.lastRevision();
    }

    void commit(Resolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (resolution.authority() != resolutionAuthority || !resolution.accepted()) {
            throw new IllegalArgumentException("untrusted or rejected visual event resolution cannot update the frozen registry");
        }
        committedObservations = resolution.nextObservations();
    }

    record TrustedEvent(ProceduralVisualEvent event, Object authority) {
        TrustedEvent {
            event = Objects.requireNonNull(event, "event");
            authority = Objects.requireNonNull(authority, "authority");
        }
    }

    record Resolution(
            List<TrustedEvent> events,
            Map<BlendResourceId, Observation> nextObservations,
            Object authority,
            boolean accepted) {
        Resolution {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            nextObservations = Map.copyOf(Objects.requireNonNull(nextObservations, "nextObservations"));
            authority = Objects.requireNonNull(authority, "authority");
        }

        static Resolution rejected() {
            return new Resolution(List.of(), Map.of(), RejectedAuthority.INSTANCE, false);
        }
    }

    /** Last successfully committed terminal anchor, including owner-issued occurrence and revision facts. */
    private record Observation(
            BlendAnimationKey timeline,
            double timeSeconds,
            long sourceRevision,
            long loopEpoch,
            long occurrence) {
        Observation {
            timeline = Objects.requireNonNull(timeline, "timeline");
            if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0D || sourceRevision < 0L
                    || loopEpoch < 0L || occurrence < 0L) {
                throw new IllegalArgumentException("frozen event observation time is invalid");
            }
        }

        private static Observation from(AnimationV2ObserverTraversal.Anchor anchor) {
            return new Observation(anchor.timeline(), anchor.timeSeconds(), anchor.lastRevision(),
                    anchor.loopEpoch(), anchor.occurrence());
        }
    }

    private enum RejectedAuthority {
        INSTANCE
    }
}
