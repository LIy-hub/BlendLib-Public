package com.liy.blendlib.fabric.client.reload;

import java.util.List;
import java.util.Objects;

/** Deterministic distance policy with binding-scoped hysteresis. */
final class X7LodPolicy {
    /** One ordered LOD threshold and its lower exit threshold. */
    record Band(int level, double enterDistance, double exitDistance) {
        Band {
            if (level < 0 || !Double.isFinite(enterDistance) || !Double.isFinite(exitDistance)
                    || enterDistance < 0.0D || exitDistance < 0.0D || exitDistance > enterDistance) {
                throw new IllegalArgumentException("LOD band thresholds must be finite, ordered, and non-negative");
            }
        }
    }

    /** Exact model, generation, and material identity for LOD history ownership. */
    record Binding(long generation, Object modelIdentity, Object materialIdentity) {
        Binding {
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
            materialIdentity = Objects.requireNonNull(materialIdentity, "materialIdentity");
        }

        boolean sameIdentity(Binding other) {
            return generation == other.generation
                    && modelIdentity == other.modelIdentity
                    && materialIdentity == other.materialIdentity;
        }
    }

    /** Previous LOD selected for one exact binding. */
    record History(Binding binding, int level) {
        History {
            binding = Objects.requireNonNull(binding, "binding");
            if (level < 0) {
                throw new IllegalArgumentException("level must be non-negative");
            }
        }
    }

    /** Whether a LOD value was safely selected. */
    enum Disposition {
        SELECTED,
        REJECTED
    }

    /** Reason for a selected or rejected LOD result. */
    enum Reason {
        FRESH_DISTANCE,
        DISTANCE_TRANSITION,
        HYSTERESIS_HELD,
        INVALID_DISTANCE,
        INVALID_HISTORY
    }

    /** Immutable LOD result; a rejected result carries no reusable history. */
    record Selection(Disposition disposition, int level, History history, Reason reason) {
        Selection {
            disposition = Objects.requireNonNull(disposition, "disposition");
            reason = Objects.requireNonNull(reason, "reason");
            if (disposition == Disposition.SELECTED) {
                if (level < 0 || history == null || history.level() != level) {
                    throw new IllegalArgumentException("a selected LOD requires matching immutable history");
                }
            } else if (level != -1 || history != null) {
                throw new IllegalArgumentException("a rejected LOD cannot carry a level or history");
            }
        }

        static Selection selected(int level, History history, Reason reason) {
            return new Selection(Disposition.SELECTED, level, history, reason);
        }

        static Selection rejected(Reason reason) {
            return new Selection(Disposition.REJECTED, -1, null, reason);
        }
    }

    private final List<Band> bands;

    X7LodPolicy(List<Band> bands) {
        this.bands = List.copyOf(Objects.requireNonNull(bands, "bands"));
        if (this.bands.isEmpty()) {
            throw new IllegalArgumentException("at least one LOD band is required");
        }
        Band first = this.bands.getFirst();
        if (first.level() != 0 || Double.compare(first.enterDistance(), 0.0D) != 0
                || Double.compare(first.exitDistance(), 0.0D) != 0) {
            throw new IllegalArgumentException("the first LOD band must be level zero at distance zero");
        }
        for (int index = 1; index < this.bands.size(); index++) {
            Band previous = this.bands.get(index - 1);
            Band current = this.bands.get(index);
            if (current.level() != index
                    || current.enterDistance() <= previous.enterDistance()
                    || current.exitDistance() < previous.enterDistance()) {
                throw new IllegalArgumentException("LOD bands must be ordered with bounded hysteresis");
            }
        }
    }

    Selection select(Binding binding, double distance, History previous) {
        Binding checkedBinding = Objects.requireNonNull(binding, "binding");
        if (!Double.isFinite(distance) || distance < 0.0D) {
            return Selection.rejected(Reason.INVALID_DISTANCE);
        }
        if (previous != null && previous.binding().sameIdentity(checkedBinding) && previous.level() >= bands.size()) {
            return Selection.rejected(Reason.INVALID_HISTORY);
        }

        int distanceLevel = distanceLevel(distance);
        int selectedLevel = distanceLevel;
        Reason reason = Reason.FRESH_DISTANCE;
        if (previous != null && previous.binding().sameIdentity(checkedBinding)) {
            int previousLevel = previous.level();
            if (distanceLevel < previousLevel) {
                selectedLevel = retainedLevel(distance, distanceLevel, previousLevel);
                reason = selectedLevel == distanceLevel ? Reason.DISTANCE_TRANSITION : Reason.HYSTERESIS_HELD;
            } else {
                reason = Reason.DISTANCE_TRANSITION;
            }
        }
        return Selection.selected(selectedLevel, new History(checkedBinding, selectedLevel), reason);
    }

    private int distanceLevel(double distance) {
        int selected = 0;
        for (int index = 1; index < bands.size(); index++) {
            if (distance >= bands.get(index).enterDistance()) {
                selected = index;
            } else {
                break;
            }
        }
        return selected;
    }

    private int retainedLevel(double distance, int distanceLevel, int previousLevel) {
        int selected = previousLevel;
        while (selected > distanceLevel && distance < bands.get(selected).exitDistance()) {
            selected--;
        }
        return selected;
    }
}
