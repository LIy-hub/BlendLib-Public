package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Weak identity keys plus a hard LRU bound; never compares item contents or item types. */
final class ItemAnimationInstances {
    record Entry(BlendInstanceKey key, ItemAnimationPlayback playback) { }
    private final int capacity;
    private final LongSupplier clock;
    private final Consumer<BlendInstanceKey> retire;
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final LinkedHashMap<IdentityReference, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private String session = UUID.randomUUID().toString();
    private long nextId;

    ItemAnimationInstances(int capacity, LongSupplier clock, Consumer<BlendInstanceKey> retire) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retire = Objects.requireNonNull(retire, "retire");
    }

    Entry get(Object stack, BlendAnimationKey animation) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(animation, "animation");
        purge();
        Entry found = entries.get(new IdentityReference(stack, null));
        if (found != null) return found;
        if (entries.size() == capacity) {
            var oldest = entries.entrySet().iterator();
            retire.accept(oldest.next().getValue().key());
            oldest.remove();
        }
        Entry created = new Entry(BlendInstanceKey.ephemeral("blendlib:item:" + session, Long.toString(nextId++)),
                new ItemAnimationPlayback(animation, clock));
        entries.put(new IdentityReference(stack, queue), created);
        return created;
    }

    /** Bounded identity scan: LinkedHashMap.get would refresh access order. No purge or retirement. */
    Entry peek(Object stack) {
        Objects.requireNonNull(stack, "stack");
        for (var entry : entries.entrySet()) {
            if (entry.getKey().get() == stack) return entry.getValue();
        }
        return null;
    }

    void release(Object stack) {
        Entry removed = entries.remove(new IdentityReference(Objects.requireNonNull(stack, "stack"), null));
        if (removed != null) retire.accept(removed.key());
        purge();
    }

    void clear() {
        entries.values().forEach(entry -> retire.accept(entry.key()));
        entries.clear();
        session = UUID.randomUUID().toString();
        nextId = 0;
        while (queue.poll() != null) { /* Drain obsolete keys. */ }
    }

    int size() { purge(); return entries.size(); }

    private void purge() {
        IdentityReference reference;
        while ((reference = (IdentityReference) queue.poll()) != null) {
            Entry removed = entries.remove(reference);
            if (removed != null) retire.accept(removed.key());
        }
    }

    private static final class IdentityReference extends WeakReference<Object> {
        private final int hash;
        IdentityReference(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof IdentityReference reference && get() != null && get() == reference.get();
        }
    }
}
