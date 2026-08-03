package com.liy.blendlib.fabric.client.api;

/**
 * Immutable, adapter-safe observation of the client animation runtime for diagnostics and
 * explicit benchmark capture.
 *
 * <p>The record exposes counts only. It never exposes mutable poses, model arrays, Minecraft
 * renderer objects, or cache entries.</p>
 */
public record ClientAnimationRuntimeMetrics(
        boolean available,
        int poseCacheEntries,
        int poseCacheCapacity,
        long poseCacheHits,
        long poseCacheMisses,
        long poseCacheEvictions,
        int trackedAnimationInstances,
        int preparedAnimationAssets) {
    public ClientAnimationRuntimeMetrics {
        if (available) {
            if (poseCacheCapacity <= 0 || poseCacheEntries < 0 || poseCacheEntries > poseCacheCapacity
                    || poseCacheHits < 0L || poseCacheMisses < 0L || poseCacheEvictions < 0L
                    || trackedAnimationInstances < 0 || preparedAnimationAssets < 0) {
                throw new IllegalArgumentException("client animation runtime metrics are invalid");
            }
        } else if (poseCacheEntries != 0 || poseCacheCapacity != 0 || poseCacheHits != 0L
                || poseCacheMisses != 0L || poseCacheEvictions != 0L || trackedAnimationInstances != 0
                || preparedAnimationAssets != 0) {
            throw new IllegalArgumentException("unavailable client animation metrics must be empty");
        }
    }

    /** Returns an explicit unavailable sentinel when no P5 runtime is installed. */
    public static ClientAnimationRuntimeMetrics unavailable() {
        return new ClientAnimationRuntimeMetrics(false, 0, 0, 0L, 0L, 0L, 0, 0);
    }
}
