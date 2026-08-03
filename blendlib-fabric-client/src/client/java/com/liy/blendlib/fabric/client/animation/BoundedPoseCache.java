package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Extraction-owned, access-order LRU cache for sampled local poses.
 *
 * <p>Every operation is synchronized because an access-order {@link LinkedHashMap#get(Object)}
 * structurally reorders a cache hit. A lifecycle removal can otherwise race that reorder while it
 * is iterating the same map during disconnect or entity unload.</p>
 */
public final class BoundedPoseCache {
    private final int capacity;
    private final LinkedHashMap<PoseCacheKey, LocalPose> entries;
    private long hits;
    private long misses;
    private long evictions;

    public BoundedPoseCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(Math.min(capacity, 16), 0.75F, true);
    }

    public synchronized Optional<LocalPose> find(PoseCacheKey key) {
        Objects.requireNonNull(key, "key");
        LocalPose pose = entries.get(key);
        if (pose == null) {
            misses++;
            return Optional.empty();
        }
        hits++;
        return Optional.of(pose);
    }

    public synchronized void put(PoseCacheKey key, LocalPose pose) {
        entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(pose, "pose"));
        evictIfOverCapacity();
    }

    /**
     * Removes every entry belonging to one fully typed instance key.
     *
     * @return number of discarded cache entries
     */
    public synchronized int removeInstance(BlendInstanceKey instanceKey) {
        Objects.requireNonNull(instanceKey, "instanceKey");
        int removed = 0;
        Iterator<Map.Entry<PoseCacheKey, LocalPose>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey().instanceKey().equals(instanceKey)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes entries that do not belong to the active generation.
     *
     * @return number of discarded cache entries
     */
    public synchronized int retireOtherGenerations(long activeGeneration) {
        if (activeGeneration < 0) {
            throw new IllegalArgumentException("activeGeneration must be non-negative");
        }
        int removed = 0;
        Iterator<Map.Entry<PoseCacheKey, LocalPose>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey().generation() != activeGeneration) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized PoseCacheMetrics metrics() {
        return new PoseCacheMetrics(entries.size(), capacity, hits, misses, evictions);
    }

    public synchronized void clearAndResetMetrics() {
        entries.clear();
        hits = 0;
        misses = 0;
        evictions = 0;
    }

    private void evictIfOverCapacity() {
        while (entries.size() > capacity) {
            Iterator<Map.Entry<PoseCacheKey, LocalPose>> iterator = entries.entrySet().iterator();
            iterator.next();
            iterator.remove();
            evictions++;
        }
    }
}
