package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.LayerAnimationVisualEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, actor-owned measurement of real visual-event callbacks. No entity references,
 * static identity cache, gameplay effects, network data, or Minecraft client types are retained.
 * All calls are on the owning client's extraction/command thread.
 */
public final class ExampleLayerVisualEvents {
    public static final int MAX_TRACKED_PAIRS = 8;

    private record Pair(BlendResourceId controller, BlendResourceId layer) { }

    /** The last callback for a pair is immutable; prior inspection snapshots remain stable. */
    public record Count(long callbacks, LayerAnimationVisualEvent last) { }

    public record Snapshot(long callbacks, long untrackedPairCallbacks, List<Count> pairs) {
        public Snapshot { pairs = List.copyOf(pairs); }
    }

    private final Map<Pair, Count> pairs = new LinkedHashMap<>();
    private long callbacks;
    private long untrackedPairCallbacks;

    /** Called only by onAnimationLayerVisualEvent, never by pose evaluation or inspection. */
    public void accept(LayerAnimationVisualEvent event) {
        Objects.requireNonNull(event, "event");
        callbacks = increment(callbacks);
        var pair = new Pair(event.controllerId(), event.layerId());
        var previous = pairs.get(pair);
        if (previous != null) {
            pairs.put(pair, new Count(increment(previous.callbacks()), event));
        } else if (pairs.size() < MAX_TRACKED_PAIRS) {
            pairs.put(pair, new Count(1, event));
        } else {
            untrackedPairCallbacks = increment(untrackedPairCallbacks);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(callbacks, untrackedPairCallbacks, List.copyOf(pairs.values()));
    }

    /** Read-only callback evidence, deliberately separate from the latest sampled pose. */
    public static List<String> format(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> lines = new ArrayList<>();
        lines.add("Layer visual callbacks=" + snapshot.callbacks()
                + " (client actor-lifetime total; not server gameplay or final bone influence)");
        for (var count : snapshot.pairs()) {
            var event = count.last();
            lines.add("  controller=" + event.controllerId() + " layer=" + event.layerId()
                    + " callbacks=" + count.callbacks());
            lines.add("    lastAnimation=" + event.animation() + " event=" + event.event().eventKey()
                    + " clipSeconds=" + number(event.event().timeSeconds())
                    + " loopEpoch=" + event.loopEpoch() + " occurrence=" + event.occurrence()
                    + " effectiveWeight=" + number(event.effectiveWeight()));
        }
        if (snapshot.untrackedPairCallbacks() > 0) {
            lines.add("  Untracked pair callbacks=" + snapshot.untrackedPairCallbacks()
                    + " (retaining at most " + MAX_TRACKED_PAIRS + " controller/layer pairs)");
        }
        return List.copyOf(lines);
    }

    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
}
