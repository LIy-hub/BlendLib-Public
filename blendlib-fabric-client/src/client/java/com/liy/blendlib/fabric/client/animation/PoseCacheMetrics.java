package com.liy.blendlib.fabric.client.animation;

/**
 * Immutable observations for a bounded pose cache.
 */
public record PoseCacheMetrics(int size, int capacity, long hits, long misses, long evictions) {
    public PoseCacheMetrics {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (size < 0 || size > capacity) {
            throw new IllegalArgumentException("size must be within cache capacity");
        }
        if (hits < 0 || misses < 0 || evictions < 0) {
            throw new IllegalArgumentException("metrics must be non-negative");
        }
    }
}
