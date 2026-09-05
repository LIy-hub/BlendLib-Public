package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, bounded observer-only record of automatic controller time traversal in one published X2 snapshot.
 *
 * <p>The runtime creates this value only after it has advanced its owner-local playheads. Semantic commands and
 * sequence rejections are represented as a discontinuity instead of a synthetic crossing, so observers cannot replay
 * skipped history from a seek or state change. This type exposes no mutable playhead, command ingress, or callback
 * capability.</p>
 */
public final class AnimationV2ObserverTraversal {
    private static final AnimationV2ObserverTraversal EMPTY = new AnimationV2ObserverTraversal(Map.of());

    private final Map<BlendResourceId, ControllerTraversal> controllers;

    private AnimationV2ObserverTraversal(Map<BlendResourceId, ControllerTraversal> controllers) {
        this.controllers = controllers;
    }

    static AnimationV2ObserverTraversal empty() {
        return EMPTY;
    }

    static AnimationV2ObserverTraversal freeze(Map<BlendResourceId, ControllerTraversal> source) {
        Objects.requireNonNull(source, "source");
        LinkedHashMap<BlendResourceId, ControllerTraversal> copied = new LinkedHashMap<>();
        for (Map.Entry<BlendResourceId, ControllerTraversal> entry : source.entrySet()) {
            BlendResourceId controllerId = Objects.requireNonNull(entry.getKey(), "controllerId");
            ControllerTraversal traversal = Objects.requireNonNull(entry.getValue(), "controllerTraversal");
            AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "observer controller id");
            if (!controllerId.equals(traversal.controllerId())) {
                throw new IllegalArgumentException("observer traversal map key does not match its controller id");
            }
            if (copied.putIfAbsent(controllerId, traversal) != null) {
                throw new IllegalArgumentException("duplicate observer controller traversal");
            }
        }
        return copied.isEmpty()
                ? EMPTY
                : new AnimationV2ObserverTraversal(Collections.unmodifiableMap(new LinkedHashMap<>(copied)));
    }

    /** Deterministic immutable traces, in the frozen X2 controller evaluation order. */
    public Map<BlendResourceId, ControllerTraversal> controllers() {
        return controllers;
    }

    /** Returns the trace for one frozen controller, or {@code null} when this snapshot has no such observer trace. */
    public ControllerTraversal controller(BlendResourceId controllerId) {
        return controllers.get(Objects.requireNonNull(controllerId, "controllerId"));
    }

    /** Immutable terminal controller state plus the actual automatic traversal that reached it in one owner frame. */
    public static final class ControllerTraversal {
        private final BlendResourceId controllerId;
        private final BlendAnimationKey terminalState;
        private final double terminalTimeSeconds;
        private final boolean discontinuity;
        private final boolean truncated;
        private final List<Segment> segments;
        private final Continuity continuity;

        ControllerTraversal(
                BlendResourceId controllerId,
                BlendAnimationKey terminalState,
                double terminalTimeSeconds,
                boolean discontinuity,
                boolean truncated,
                List<Segment> segments,
                Continuity continuity) {
            this.controllerId = Objects.requireNonNull(controllerId, "controllerId");
            this.terminalState = Objects.requireNonNull(terminalState, "terminalState");
            AnimationV2Limits.requireCanonicalIdLength(controllerId.value(), "observer controller id");
            AnimationV2Limits.requireCanonicalIdLength(terminalState.value(), "observer terminal state");
            if (!Double.isFinite(terminalTimeSeconds) || terminalTimeSeconds < 0.0D) {
                throw new IllegalArgumentException("observer terminal time must be finite and non-negative");
            }
            List<Segment> copied = List.copyOf(Objects.requireNonNull(segments, "segments"));
            if (copied.size() > AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE) {
                throw new IllegalArgumentException("observer traversal segment count exceeds the fixed per-controller bound");
            }
            long previousOccurrence = -1L;
            long previousLoopEpoch = -1L;
            for (Segment segment : copied) {
                segment = Objects.requireNonNull(segment, "observer segment");
                if (segment.occurrence() <= previousOccurrence || segment.loopEpoch() < previousLoopEpoch) {
                    throw new IllegalArgumentException("observer segments must have strictly increasing occurrences and monotonic loop epochs");
                }
                previousOccurrence = segment.occurrence();
                previousLoopEpoch = segment.loopEpoch();
            }
            if ((discontinuity || truncated) && !copied.isEmpty()) {
                throw new IllegalArgumentException("observer discontinuity or truncation cannot retain partial traversal segments");
            }
            this.terminalTimeSeconds = terminalTimeSeconds;
            this.discontinuity = discontinuity;
            this.truncated = truncated;
            this.segments = copied;
            this.continuity = Objects.requireNonNull(continuity, "continuity");
            Anchor terminalAnchor = continuity.terminalAnchor();
            if (!terminalState.equals(terminalAnchor.timeline())
                    || Double.compare(terminalTimeSeconds, terminalAnchor.timeSeconds()) != 0) {
                throw new IllegalArgumentException("observer continuity terminal anchor does not match terminal playhead facts");
            }
        }

        public BlendResourceId controllerId() {
            return controllerId;
        }

        public BlendAnimationKey terminalState() {
            return terminalState;
        }

        public double terminalTimeSeconds() {
            return terminalTimeSeconds;
        }

        /** True when a command/rejection moved the playhead; observers must re-arm without producing a crossing. */
        public boolean discontinuity() {
            return discontinuity;
        }

        /** True when a hard observer traversal budget prevented a complete exact trace. */
        public boolean truncated() {
            return truncated;
        }

        /** Actual forward timeline segments; each repeated state occurrence has a unique monotonic identity. */
        public List<Segment> segments() {
            return segments;
        }

        /**
         * Immutable bounded owner history ending at this exact publication. It contains only real automatic forward
         * segments plus revision-contiguous terminal anchors, so a bound X3 consumer can either locate its committed
         * observation exactly or fail closed instead of silently dropping an intervening crossing.
         */
        public Continuity continuity() {
            return continuity;
        }
    }

    /** Immutable terminal fact range shared by adjacent no-movement X2 publications. */
    public static final class Anchor {
        private final BlendAnimationKey timeline;
        private final double timeSeconds;
        private final long loopEpoch;
        private final long occurrence;
        private final long firstRevision;
        private final long lastRevision;

        Anchor(
                BlendAnimationKey timeline,
                double timeSeconds,
                long loopEpoch,
                long occurrence,
                long firstRevision,
                long lastRevision) {
            this.timeline = Objects.requireNonNull(timeline, "timeline");
            AnimationV2Limits.requireCanonicalIdLength(timeline.value(), "observer anchor timeline");
            if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0D
                    || loopEpoch < 0L || occurrence < 0L
                    || firstRevision < 0L || lastRevision < firstRevision) {
                throw new IllegalArgumentException("observer continuity anchor is invalid");
            }
            this.timeSeconds = timeSeconds;
            this.loopEpoch = loopEpoch;
            this.occurrence = occurrence;
            this.firstRevision = firstRevision;
            this.lastRevision = lastRevision;
        }

        public BlendAnimationKey timeline() {
            return timeline;
        }

        public double timeSeconds() {
            return timeSeconds;
        }

        public long loopEpoch() {
            return loopEpoch;
        }

        public long occurrence() {
            return occurrence;
        }

        public long firstRevision() {
            return firstRevision;
        }

        public long lastRevision() {
            return lastRevision;
        }
    }

    /** One exact owner publication containing one or more real forward segments. */
    public static final class Publication {
        private final long revision;
        private final Anchor startAnchor;
        private final Anchor terminalAnchor;
        private final List<Segment> segments;

        Publication(long revision, Anchor startAnchor, Anchor terminalAnchor, List<Segment> segments) {
            if (revision < 1L) {
                throw new IllegalArgumentException("observer continuation publication revision must be positive");
            }
            this.startAnchor = Objects.requireNonNull(startAnchor, "startAnchor");
            this.terminalAnchor = Objects.requireNonNull(terminalAnchor, "terminalAnchor");
            if (startAnchor.lastRevision() != revision - 1L
                    || terminalAnchor.firstRevision() != revision
                    || terminalAnchor.lastRevision() != revision) {
                throw new IllegalArgumentException("observer continuation publication does not prove adjacent revisions");
            }
            List<Segment> copied = List.copyOf(Objects.requireNonNull(segments, "segments"));
            if (copied.isEmpty() || copied.size() > AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE) {
                throw new IllegalArgumentException("observer continuation publication must retain a bounded non-empty traversal");
            }
            this.revision = revision;
            this.segments = copied;
        }

        public long revision() {
            return revision;
        }

        public Anchor startAnchor() {
            return startAnchor;
        }

        public Anchor terminalAnchor() {
            return terminalAnchor;
        }

        public List<Segment> segments() {
            return segments;
        }
    }

    /**
     * Rolling immutable continuation with at most the fixed X2 observer segment budget. Constructors remain
     * package-private so callers cannot fabricate owner provenance.
     */
    public static final class Continuity {
        private final Anchor initialAnchor;
        private final List<Publication> publications;
        private final Anchor terminalAnchor;

        Continuity(Anchor initialAnchor, List<Publication> publications, Anchor terminalAnchor) {
            this.initialAnchor = Objects.requireNonNull(initialAnchor, "initialAnchor");
            this.terminalAnchor = Objects.requireNonNull(terminalAnchor, "terminalAnchor");
            List<Publication> copied = List.copyOf(Objects.requireNonNull(publications, "publications"));
            int segmentCount = 0;
            Anchor previous = initialAnchor;
            for (Publication publication : copied) {
                publication = Objects.requireNonNull(publication, "publication");
                if (!sameFacts(previous, publication.startAnchor())
                        || !rangesOverlap(previous, publication.startAnchor())) {
                    throw new IllegalArgumentException("observer continuation publications are not anchor-contiguous");
                }
                segmentCount = Math.addExact(segmentCount, publication.segments().size());
                previous = publication.terminalAnchor();
            }
            if (segmentCount > AnimationV2Limits.MAX_OBSERVER_TRAVERSAL_SEGMENTS_PER_ADVANCE
                    || !sameFacts(previous, terminalAnchor)
                    || !rangesOverlap(previous, terminalAnchor)) {
                throw new IllegalArgumentException("observer continuation exceeds its bound or lacks a terminal anchor");
            }
            this.publications = copied;
        }

        public Anchor initialAnchor() {
            return initialAnchor;
        }

        public List<Publication> publications() {
            return publications;
        }

        public Anchor terminalAnchor() {
            return terminalAnchor;
        }

        private static boolean sameFacts(Anchor first, Anchor second) {
            return first.timeline().equals(second.timeline())
                    && Double.compare(first.timeSeconds(), second.timeSeconds()) == 0
                    && first.loopEpoch() == second.loopEpoch()
                    && first.occurrence() == second.occurrence();
        }

        private static boolean rangesOverlap(Anchor first, Anchor second) {
            return first.firstRevision() <= second.lastRevision()
                    && second.firstRevision() <= first.lastRevision();
        }
    }

    /** One non-empty forward interval within a real controller state occurrence. */
    public static final class Segment {
        private final BlendAnimationKey timeline;
        private final long loopEpoch;
        private final long occurrence;
        private final double startExclusiveSeconds;
        private final double endInclusiveSeconds;

        Segment(
                BlendAnimationKey timeline,
                long loopEpoch,
                long occurrence,
                double startExclusiveSeconds,
                double endInclusiveSeconds) {
            this.timeline = Objects.requireNonNull(timeline, "timeline");
            AnimationV2Limits.requireCanonicalIdLength(timeline.value(), "observer timeline");
            if (loopEpoch < 0L || occurrence < 0L
                    || !Double.isFinite(startExclusiveSeconds) || !Double.isFinite(endInclusiveSeconds)
                    || startExclusiveSeconds < 0.0D || endInclusiveSeconds <= startExclusiveSeconds) {
                throw new IllegalArgumentException("observer traversal segment is not a finite non-empty forward interval");
            }
            this.loopEpoch = loopEpoch;
            this.occurrence = occurrence;
            this.startExclusiveSeconds = startExclusiveSeconds;
            this.endInclusiveSeconds = endInclusiveSeconds;
        }

        public BlendAnimationKey timeline() {
            return timeline;
        }

        /** Monotonic controller-local loop epoch. It increments only when a LOOP state wraps. */
        public long loopEpoch() {
            return loopEpoch;
        }

        /** Monotonic controller-local state occurrence; automatic entries, wraps, and command re-arms never reuse it. */
        public long occurrence() {
            return occurrence;
        }

        public double startExclusiveSeconds() {
            return startExclusiveSeconds;
        }

        public double endInclusiveSeconds() {
            return endInclusiveSeconds;
        }
    }
}
