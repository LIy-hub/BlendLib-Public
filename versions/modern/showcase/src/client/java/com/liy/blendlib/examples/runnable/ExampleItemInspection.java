package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.item.ItemAnimationObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Copyable pure presentation of read-only held-item observations. */
public final class ExampleItemInspection {
    private ExampleItemInspection() { }

    public static List<String> format(Optional<ItemAnimationObservation> observed) {
        if (observed.isEmpty()) return List.of("No retained playback for this exact held stack object; it may be"
                + " unseen, copied, released, disconnected or evicted. Status does not create playback");
        var status = observed.orElseThrow();
        var lines = new ArrayList<String>();
        lines.add("Item controls: animation=" + status.animation() + " mode=" + status.mode()
                + " playing=" + status.playing() + " speed=" + number(status.speed())
                + " storedSeconds=" + number(status.storedSeconds()) + " (not a live clock projection)");
        status.lastSample().ifPresentOrElse(sample -> lines.add("Last successful extraction: animation="
                + sample.animation() + " clipSeconds=" + number(sample.seconds())
                + " durationSeconds=" + number(sample.durationSeconds()) + " generation=" + sample.generation()
                + " currentGeneration=" + status.sampleCurrentGeneration()
                + " (historical; may differ from controls, does not prove submit)"),
                () -> lines.add("No successful extraction recorded; controls may exist before the first sample"));
        return List.copyOf(lines);
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
}
