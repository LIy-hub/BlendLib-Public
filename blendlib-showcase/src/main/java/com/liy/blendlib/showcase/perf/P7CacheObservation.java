package com.liy.blendlib.showcase.perf;

/** Immutable cache and animation-owner observations captured beside a real frame sample. */
public record P7CacheObservation(
        int poseCacheEntries,
        int poseCacheCapacity,
        long poseCacheHits,
        long poseCacheMisses,
        long poseCacheEvictions,
        int trackedAnimationInstances,
        int preparedAnimationAssets) {
    public P7CacheObservation {
        if (poseCacheCapacity <= 0 || poseCacheEntries < 0 || poseCacheEntries > poseCacheCapacity
                || poseCacheHits < 0L || poseCacheMisses < 0L || poseCacheEvictions < 0L
                || trackedAnimationInstances < 0 || preparedAnimationAssets < 0) {
            throw new IllegalArgumentException("P7 cache observation is out of bounds");
        }
    }
}
