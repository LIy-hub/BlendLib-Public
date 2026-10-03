package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.item.ItemAnimationVisualEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, weak exact-identity measurement of real item callbacks. The live consumer uses
 * ItemStack keys; generic keys keep this helper headlessly testable without Minecraft startup.
 * All calls are on the owning client's extraction/command thread.
 */
public final class ExampleItemVisualEvents<K> {
    public static final int MAX_TRACKED_STACKS = 256;

    /** Retains only immutable marker information, never the stack or a loaded render handle. */
    public record Count(long callbacks, ItemAnimationVisualEvent last) { }

    private static final class Entry<K> {
        final WeakReference<K> identity;
        Count count;

        Entry(K identity, Count count) {
            this.identity = new WeakReference<>(identity);
            this.count = count;
        }
    }

    // Least recently delivered callback first. Inspection never refreshes retention.
    private final List<Entry<K>> entries = new ArrayList<>();

    /** Used as the registered item visual-event handler; inspection never calls this method. */
    public void accept(K stack, ItemAnimationVisualEvent event) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(event, "event");
        entries.removeIf(entry -> entry.identity.get() == null);
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            if (entry.identity.get() == stack) {
                entries.remove(index);
                entry.count = new Count(increment(entry.count.callbacks()), event);
                entries.add(entry);
                return;
            }
        }
        if (entries.size() == MAX_TRACKED_STACKS) entries.removeFirst();
        entries.add(new Entry<>(stack, new Count(1, event)));
    }

    /** Does not acquire playback, read its clock, consume events, purge or refresh identity LRU. */
    public Optional<Count> snapshot(K stack) {
        Objects.requireNonNull(stack, "stack");
        for (var entry : entries) {
            if (entry.identity.get() == stack) return Optional.of(entry.count);
        }
        return Optional.empty();
    }

    /** The example owns these measurements and clears them at client disconnect. */
    public void clear() { entries.clear(); }

    public static List<String> format(boolean enabled, Optional<Count> count) {
        Objects.requireNonNull(count, "count");
        if (!enabled) return List.of("Item visual callbacks disabled; restart with "
                + "-Dblendlib.examples.itemVisualEvents=true to enable the counter");
        if (count.isEmpty()) return List.of("No retained visual callbacks for this exact held stack "
                + "(new, unseen, copied or counter-evicted identity; extraction evidence only)");
        var value = count.orElseThrow();
        var last = value.last();
        return List.of("Item visual callbacks=" + value.callbacks()
                        + " (retained exact-stack counter; extraction only, not submit or gameplay)",
                "  lastModel=" + last.model() + " lastAnimation=" + last.animation()
                        + " generation=" + last.generation(),
                "  lastEvent=" + last.event().eventKey() + " clipSeconds="
                        + String.format(Locale.ROOT, "%.3f", last.event().timeSeconds())
                        + " (historical callback; reload does not reset this counter)");
    }

    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
}
