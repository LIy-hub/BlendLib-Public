package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendInstanceKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedPoseCacheTest {
    @Test
    void evictsLeastRecentlyUsedEntryAndReportsDeterministicMetrics() {
        BoundedPoseCache cache = new BoundedPoseCache(2);
        BlendInstanceKey first = BlendInstanceKey.entity("cache-session", 1);
        BlendInstanceKey second = BlendInstanceKey.entity("cache-session", 2);
        BlendInstanceKey third = BlendInstanceKey.entity("cache-session", 3);
        PoseCacheKey firstKey = ClientAnimationTestFixtures.poseKey(first, 1L, 0L);
        PoseCacheKey secondKey = ClientAnimationTestFixtures.poseKey(second, 1L, 0L);
        PoseCacheKey thirdKey = ClientAnimationTestFixtures.poseKey(third, 1L, 0L);

        cache.put(firstKey, ClientAnimationTestFixtures.pose(1.0F));
        cache.put(secondKey, ClientAnimationTestFixtures.pose(2.0F));
        assertTrue(cache.find(firstKey).isPresent());
        cache.put(thirdKey, ClientAnimationTestFixtures.pose(3.0F));

        assertTrue(cache.find(secondKey).isEmpty());
        assertTrue(cache.find(firstKey).isPresent());
        assertTrue(cache.find(thirdKey).isPresent());
        assertEquals(new PoseCacheMetrics(2, 2, 3L, 1L, 1L), cache.metrics());
    }

    @Test
    void generationIsPartOfTheCacheIdentity() {
        BoundedPoseCache cache = new BoundedPoseCache(2);
        BlendInstanceKey key = BlendInstanceKey.entity("generation-session", 21);
        PoseCacheKey generationOne = ClientAnimationTestFixtures.poseKey(key, 1L, 7L);
        PoseCacheKey generationTwo = ClientAnimationTestFixtures.poseKey(key, 2L, 7L);

        var firstPose = ClientAnimationTestFixtures.pose(1.0F);
        var secondPose = ClientAnimationTestFixtures.pose(2.0F);
        cache.put(generationOne, firstPose);
        cache.put(generationTwo, secondPose);

        assertEquals(firstPose, cache.find(generationOne).orElseThrow());
        assertEquals(secondPose, cache.find(generationTwo).orElseThrow());
        assertEquals(2, cache.metrics().size());
    }

    @Test
    void modelIdentityIsPartOfTheCacheIdentity() {
        BoundedPoseCache cache = new BoundedPoseCache(2);
        BlendInstanceKey key = BlendInstanceKey.entity("model-session", 34);
        PoseCacheKey firstModel = ClientAnimationTestFixtures.poseKey(
                key, ClientAnimationTestFixtures.MODEL, 1L, 7L);
        PoseCacheKey secondModel = ClientAnimationTestFixtures.poseKey(
                key, ClientAnimationTestFixtures.ALTERNATE_MODEL, 1L, 7L);

        var firstPose = ClientAnimationTestFixtures.pose(1.0F);
        var secondPose = ClientAnimationTestFixtures.pose(2.0F);
        cache.put(firstModel, firstPose);
        cache.put(secondModel, secondPose);

        assertEquals(firstPose, cache.find(firstModel).orElseThrow());
        assertEquals(secondPose, cache.find(secondModel).orElseThrow());
        assertEquals(2, cache.metrics().size());
    }

    @Test
    void generationRetirementAndDisconnectResetKeepTheCacheWithinItsFixedCapacity() {
        BoundedPoseCache cache = new BoundedPoseCache(3);
        BlendInstanceKey firstOld = BlendInstanceKey.entity("retire-session", 1);
        BlendInstanceKey secondOld = BlendInstanceKey.entity("retire-session", 2);
        BlendInstanceKey firstActive = BlendInstanceKey.entity("retire-session", 3);
        BlendInstanceKey secondActive = BlendInstanceKey.entity("retire-session", 4);
        PoseCacheKey firstOldKey = ClientAnimationTestFixtures.poseKey(firstOld, 1L, 0L);
        PoseCacheKey secondOldKey = ClientAnimationTestFixtures.poseKey(secondOld, 1L, 0L);
        PoseCacheKey firstActiveKey = ClientAnimationTestFixtures.poseKey(firstActive, 2L, 0L);
        PoseCacheKey secondActiveKey = ClientAnimationTestFixtures.poseKey(secondActive, 2L, 0L);

        cache.put(firstOldKey, ClientAnimationTestFixtures.pose(1.0F));
        cache.put(secondOldKey, ClientAnimationTestFixtures.pose(2.0F));
        cache.put(firstActiveKey, ClientAnimationTestFixtures.pose(3.0F));
        assertEquals(3, cache.metrics().size());
        assertEquals(3, cache.metrics().capacity());

        assertEquals(2, cache.retireOtherGenerations(2L));
        assertTrue(cache.find(firstOldKey).isEmpty());
        assertTrue(cache.find(secondOldKey).isEmpty());
        assertTrue(cache.find(firstActiveKey).isPresent());
        assertEquals(1, cache.metrics().size());
        assertEquals(3, cache.metrics().capacity());

        cache.put(secondActiveKey, ClientAnimationTestFixtures.pose(4.0F));
        assertEquals(2, cache.metrics().size());
        cache.clearAndResetMetrics();
        assertEquals(new PoseCacheMetrics(0, 3, 0L, 0L, 0L), cache.metrics());
    }

    @Test
    void serializesAccessOrderLookupsWithRemovalOfAllCachedRevisionsForOneInstance() throws InterruptedException {
        int targetRevisionCount = 8_192;
        BoundedPoseCache cache = new BoundedPoseCache(targetRevisionCount + 2);
        BlendInstanceKey target = BlendInstanceKey.entity("disconnect-race-session", 1);
        BlendInstanceKey retainedFirst = BlendInstanceKey.entity("disconnect-race-session", 2);
        BlendInstanceKey retainedSecond = BlendInstanceKey.entity("disconnect-race-session", 3);
        PoseCacheKey retainedFirstKey = ClientAnimationTestFixtures.poseKey(retainedFirst, 1L, 0L);
        PoseCacheKey retainedSecondKey = ClientAnimationTestFixtures.poseKey(retainedSecond, 1L, 0L);
        cache.put(retainedFirstKey, ClientAnimationTestFixtures.pose(1.0F));
        cache.put(retainedSecondKey, ClientAnimationTestFixtures.pose(2.0F));
        for (long revision = 0L; revision < targetRevisionCount; revision++) {
            cache.put(
                    ClientAnimationTestFixtures.poseKey(target, 1L, revision),
                    ClientAnimationTestFixtures.pose(3.0F));
        }

        CountDownLatch readerWarmed = new CountDownLatch(1);
        AtomicBoolean removalFinished = new AtomicBoolean();
        AtomicInteger removed = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                while (!removalFinished.get()) {
                    cache.find(retainedFirstKey);
                    cache.find(retainedSecondKey);
                    readerWarmed.countDown();
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        }, "blendlib-pose-cache-reader");
        Thread remover = new Thread(() -> {
            try {
                removed.set(cache.removeInstance(target));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                removalFinished.set(true);
            }
        }, "blendlib-pose-cache-remover");

        reader.start();
        assertTrue(readerWarmed.await(5L, TimeUnit.SECONDS));
        remover.start();
        remover.join(5_000L);
        removalFinished.set(true);
        reader.join(5_000L);

        assertFalse(remover.isAlive());
        assertFalse(reader.isAlive());
        assertNull(failure.get());
        assertEquals(targetRevisionCount, removed.get());
        assertEquals(2, cache.metrics().size());
        assertTrue(cache.find(retainedFirstKey).isPresent());
        assertTrue(cache.find(retainedSecondKey).isPresent());
    }
}
