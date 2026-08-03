package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAnimationInstanceRegistryTest {
    @Test
    void sampleAndCacheMissSamplesOnceAndStoresTheLatestPose() throws IOException {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        BlendInstanceKey key = BlendInstanceKey.entity("sample-cache-session", 81);
        ClientAnimationInstance instance = registry.bind(
                key, ClientAnimationTestFixtures.MODEL, 3L, ClientAnimationTestFixtures.definition());
        instance.advance(0.25D);
        PoseCacheKey poseKey = ClientAnimationTestFixtures.poseKey(key, 3L, 17L);
        PoseSampler sampler = ClientAnimationTestFixtures.sampler();

        LocalPose sampled = registry.sampleAndCache(poseKey, sampler);

        assertEquals(0.25F, sampled.transform(0).translation().x(), 0.000001F);
        assertSame(sampled, instance.latestPose().orElseThrow());
        assertEquals(new PoseCacheMetrics(1, 4, 0L, 1L, 0L), registry.poseCacheMetrics());
        assertSame(sampled, registry.cachedPose(poseKey).orElseThrow());
        String source = sampleAndCacheSource();
        assertEquals(1, occurrences(source, ".sample("));
        assertTrue(source.contains("poseCache.find(key).orElseGet"));
        assertTrue(source.indexOf("current.controller().currentState()") < source.indexOf("poseCache.find(key)"));
        assertTrue(source.indexOf("poseCache.find(key)") < source.indexOf(".sample("));
    }

    @Test
    void sampleAndCacheHitDoesNotResampleAndRefreshesLatestPose() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        BlendInstanceKey key = BlendInstanceKey.entity("sample-cache-hit-session", 82);
        ClientAnimationInstance instance = registry.bind(
                key, ClientAnimationTestFixtures.MODEL, 3L, ClientAnimationTestFixtures.definition());
        PoseCacheKey poseKey = ClientAnimationTestFixtures.poseKey(key, 3L, 18L);
        PoseSampler sampler = ClientAnimationTestFixtures.sampler();
        instance.advance(0.25D);
        LocalPose cached = registry.sampleAndCache(poseKey, sampler);
        instance.rememberPose(ClientAnimationTestFixtures.pose(99.0F));
        instance.advance(0.25D);

        LocalPose hit = registry.sampleAndCache(poseKey, sampler);

        assertSame(cached, hit);
        assertSame(cached, instance.latestPose().orElseThrow());
        assertEquals(0.25F, hit.transform(0).translation().x(), 0.000001F);
        assertEquals(new PoseCacheMetrics(1, 4, 1L, 1L, 0L), registry.poseCacheMetrics());
    }

    @Test
    void sampleAndCacheRejectsStateAndBindingMismatchesBeforeSampling() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        BlendInstanceKey key = BlendInstanceKey.entity("sample-cache-reject-session", 83);
        ClientAnimationInstance instance = registry.bind(
                key, ClientAnimationTestFixtures.MODEL, 3L, ClientAnimationTestFixtures.definition());
        PoseSampler sampler = ClientAnimationTestFixtures.sampler();
        PoseCacheKey wrongState = new PoseCacheKey(
                key,
                ClientAnimationTestFixtures.MODEL,
                3L,
                BlendAnimationKey.parse("blendlib:other_state"),
                19L);
        PoseCacheKey wrongModel = ClientAnimationTestFixtures.poseKey(
                key, ClientAnimationTestFixtures.ALTERNATE_MODEL, 3L, 20L);
        PoseCacheKey wrongGeneration = ClientAnimationTestFixtures.poseKey(key, 4L, 21L);

        assertThrows(IllegalArgumentException.class, () -> registry.sampleAndCache(wrongState, sampler));
        assertThrows(IllegalArgumentException.class, () -> registry.sampleAndCache(wrongModel, sampler));
        assertThrows(IllegalArgumentException.class, () -> registry.sampleAndCache(wrongGeneration, sampler));
        assertTrue(instance.latestPose().isEmpty());
        assertEquals(new PoseCacheMetrics(0, 4, 0L, 0L, 0L), registry.poseCacheMetrics());
    }

    @Test
    void entityIdsFromDifferentSessionsHaveIndependentControllersAndPoses() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        BlendInstanceKey firstSession = BlendInstanceKey.entity("first-session", 73);
        BlendInstanceKey secondSession = BlendInstanceKey.entity("second-session", 73);

        ClientAnimationInstance first = registry.bind(
                firstSession, ClientAnimationTestFixtures.MODEL, 1L, ClientAnimationTestFixtures.definition());
        ClientAnimationInstance second = registry.bind(
                secondSession, ClientAnimationTestFixtures.MODEL, 1L, ClientAnimationTestFixtures.definition());
        assertNotSame(first, second);
        assertSame(first, registry.bind(
                firstSession, ClientAnimationTestFixtures.MODEL, 1L, ClientAnimationTestFixtures.definition()));

        first.advance(0.25D);
        second.advance(0.75D);
        assertEquals(0.25D, first.controller().currentTimeSeconds(), 0.000001D);
        assertEquals(0.75D, second.controller().currentTimeSeconds(), 0.000001D);

        LocalPose firstPose = ClientAnimationTestFixtures.pose(1.0F);
        LocalPose secondPose = ClientAnimationTestFixtures.pose(2.0F);
        first.rememberPose(firstPose);
        second.rememberPose(secondPose);
        assertEquals(firstPose, first.latestPose().orElseThrow());
        assertEquals(secondPose, second.latestPose().orElseThrow());

        registry.cachePose(ClientAnimationTestFixtures.poseKey(firstSession, 1L, 0L), firstPose);
        registry.cachePose(ClientAnimationTestFixtures.poseKey(secondSession, 1L, 0L), secondPose);
        assertEquals(firstPose, registry.cachedPose(ClientAnimationTestFixtures.poseKey(firstSession, 1L, 0L)).orElseThrow());
        assertEquals(secondPose, registry.cachedPose(ClientAnimationTestFixtures.poseKey(secondSession, 1L, 0L)).orElseThrow());
        assertEquals(2, registry.size());
    }

    @Test
    void modelRebindWithinOneGenerationReplacesControllerAndRejectsStalePoseKeys() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        BlendInstanceKey key = BlendInstanceKey.entity("model-rebind-session", 51);
        BlendModelKey firstModel = ClientAnimationTestFixtures.MODEL;
        BlendModelKey secondModel = ClientAnimationTestFixtures.ALTERNATE_MODEL;

        ClientAnimationInstance first = registry.bind(key, firstModel, 5L, ClientAnimationTestFixtures.definition());
        first.rememberPose(ClientAnimationTestFixtures.pose(1.0F));
        PoseCacheKey firstPoseKey = ClientAnimationTestFixtures.poseKey(key, firstModel, 5L, 0L);
        registry.cachePose(firstPoseKey, ClientAnimationTestFixtures.pose(1.0F));
        assertSame(first, registry.bind(key, firstModel, 5L, ClientAnimationTestFixtures.definition()));

        ClientAnimationInstance rebound = registry.bind(key, secondModel, 5L, ClientAnimationTestFixtures.definition());
        assertNotSame(first, rebound);
        assertEquals(secondModel, rebound.modelKey());
        assertTrue(rebound.latestPose().isEmpty());
        assertTrue(registry.cachedPose(firstPoseKey).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> registry.cachePose(firstPoseKey, ClientAnimationTestFixtures.pose(2.0F)));

        PoseCacheKey secondPoseKey = ClientAnimationTestFixtures.poseKey(key, secondModel, 5L, 0L);
        registry.cachePose(secondPoseKey, ClientAnimationTestFixtures.pose(2.0F));
        assertTrue(registry.cachedPose(secondPoseKey).isPresent());
        assertSame(rebound, registry.bind(key, secondModel, 5L, ClientAnimationTestFixtures.definition()));
    }

    @Test
    void generationRebindRemovalAndWorldDisconnectRetireStateAndCache() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        BlendInstanceKey first = BlendInstanceKey.entity("shared-session", 11);
        BlendInstanceKey second = BlendInstanceKey.entity("shared-session", 12);
        ClientAnimationInstance oldFirst = registry.bind(
                first, ClientAnimationTestFixtures.MODEL, 1L, ClientAnimationTestFixtures.definition());
        registry.bind(second, ClientAnimationTestFixtures.MODEL, 1L, ClientAnimationTestFixtures.definition());
        PoseCacheKey firstGenerationOne = ClientAnimationTestFixtures.poseKey(first, 1L, 0L);
        PoseCacheKey secondGenerationOne = ClientAnimationTestFixtures.poseKey(second, 1L, 0L);
        registry.cachePose(firstGenerationOne, ClientAnimationTestFixtures.pose(1.0F));
        registry.cachePose(secondGenerationOne, ClientAnimationTestFixtures.pose(2.0F));

        ClientAnimationInstance reboundFirst = registry.bind(
                first, ClientAnimationTestFixtures.MODEL, 2L, ClientAnimationTestFixtures.definition());
        assertNotSame(oldFirst, reboundFirst);
        assertEquals(2L, reboundFirst.generation());
        assertTrue(registry.cachedPose(firstGenerationOne).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> registry.cachePose(firstGenerationOne, ClientAnimationTestFixtures.pose(3.0F)));

        assertEquals(1, registry.retireOtherGenerations(2L));
        assertFalse(registry.find(second).isPresent());
        assertTrue(registry.cachedPose(secondGenerationOne).isEmpty());

        PoseCacheKey firstGenerationTwo = ClientAnimationTestFixtures.poseKey(first, 2L, 0L);
        registry.cachePose(firstGenerationTwo, ClientAnimationTestFixtures.pose(4.0F));
        assertTrue(registry.remove(first));
        assertEquals(0, registry.size());
        assertTrue(registry.cachedPose(firstGenerationTwo).isEmpty());

        registry.bind(first, ClientAnimationTestFixtures.MODEL, 2L, ClientAnimationTestFixtures.definition());
        registry.cachePose(firstGenerationTwo, ClientAnimationTestFixtures.pose(5.0F));
        assertTrue(registry.cachedPose(firstGenerationTwo).isPresent());
        assertTrue(registry.poseCacheMetrics().hits() > 0L);
        registry.onWorldDisconnect();
        assertEquals(0, registry.size());
        assertEquals(new PoseCacheMetrics(0, 4, 0L, 0L, 0L), registry.poseCacheMetrics());
    }

    @Test
    void lruCapacityGenerationRetirementAndDisconnectDoNotRetainPoseCacheState() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(2);
        BlendInstanceKey first = BlendInstanceKey.entity("p7-cache-session", 1);
        BlendInstanceKey second = BlendInstanceKey.entity("p7-cache-session", 2);
        BlendInstanceKey third = BlendInstanceKey.entity("p7-cache-session", 3);

        for (BlendInstanceKey key : new BlendInstanceKey[] {first, second, third}) {
            registry.bind(key, ClientAnimationTestFixtures.MODEL, 1L, ClientAnimationTestFixtures.definition());
            registry.cachePose(
                    ClientAnimationTestFixtures.poseKey(key, 1L, 0L), ClientAnimationTestFixtures.pose(1.0F));
        }

        assertEquals(3, registry.size());
        assertEquals(2, registry.poseCacheMetrics().size());
        assertEquals(2, registry.poseCacheMetrics().capacity());
        assertEquals(1L, registry.poseCacheMetrics().evictions());

        assertEquals(3, registry.retireOtherGenerations(2L));
        assertEquals(0, registry.size());
        assertEquals(0, registry.poseCacheMetrics().size());
        assertEquals(2, registry.poseCacheMetrics().capacity());

        registry.bind(first, ClientAnimationTestFixtures.MODEL, 2L, ClientAnimationTestFixtures.definition());
        registry.cachePose(ClientAnimationTestFixtures.poseKey(first, 2L, 0L), ClientAnimationTestFixtures.pose(2.0F));
        assertEquals(1, registry.size());
        assertEquals(1, registry.poseCacheMetrics().size());
        registry.onWorldDisconnect();

        assertEquals(0, registry.size());
        assertEquals(new PoseCacheMetrics(0, 2, 0L, 0L, 0L), registry.poseCacheMetrics());
    }

    @Test
    void entityAndBlockEntityUnloadRemoveOnlyMatchingTypedInstancesAndTheirPoses() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(16);
        BlendInstanceKey.Entity unloadedEntity = new BlendInstanceKey.Entity("active-session", 71);
        BlendInstanceKey.Entity retainedOtherSessionSameEntityId = new BlendInstanceKey.Entity("stale-session", 71);
        BlendInstanceKey.Entity retainedEntity = new BlendInstanceKey.Entity("active-session", 72);
        BlendInstanceKey.BlockEntity unloadedBlock = new BlendInstanceKey.BlockEntity(
                BlendResourceId.parse("minecraft:overworld"), 100L);
        BlendInstanceKey.BlockEntity retainedBlockAtOtherPosition = new BlendInstanceKey.BlockEntity(
                BlendResourceId.parse("minecraft:overworld"), 101L);
        BlendInstanceKey.BlockEntity retainedBlockInOtherDimension = new BlendInstanceKey.BlockEntity(
                BlendResourceId.parse("minecraft:the_nether"), 100L);
        BlendInstanceKey item = BlendInstanceKey.item();
        BlendInstanceKey ephemeral = BlendInstanceKey.ephemeral("active-session", "trail");

        BlendInstanceKey[] keys = {
                unloadedEntity,
                retainedOtherSessionSameEntityId,
                retainedEntity,
                unloadedBlock,
                retainedBlockAtOtherPosition,
                retainedBlockInOtherDimension,
                item,
                ephemeral
        };
        for (int index = 0; index < keys.length; index++) {
            registry.bind(keys[index], ClientAnimationTestFixtures.MODEL, 3L, ClientAnimationTestFixtures.definition());
            registry.cachePose(ClientAnimationTestFixtures.poseKey(keys[index], 3L, 0L),
                    ClientAnimationTestFixtures.pose(index + 1.0F));
        }

        assertEquals(1, registry.removeUnloadedEntity(unloadedEntity));
        assertTrue(registry.find(unloadedEntity).isEmpty());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(unloadedEntity, 3L, 0L)).isEmpty());
        assertTrue(registry.find(retainedOtherSessionSameEntityId).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(retainedOtherSessionSameEntityId, 3L, 0L)).isPresent());
        assertTrue(registry.find(retainedEntity).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(retainedEntity, 3L, 0L)).isPresent());
        assertTrue(registry.find(unloadedBlock).isPresent());
        assertTrue(registry.find(retainedBlockAtOtherPosition).isPresent());
        assertTrue(registry.find(retainedBlockInOtherDimension).isPresent());
        assertTrue(registry.find(item).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(item, 3L, 0L)).isPresent());
        assertTrue(registry.find(ephemeral).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(ephemeral, 3L, 0L)).isPresent());

        assertEquals(1, registry.removeUnloadedBlockEntity(unloadedBlock));
        assertTrue(registry.find(unloadedBlock).isEmpty());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(unloadedBlock, 3L, 0L)).isEmpty());
        assertTrue(registry.find(retainedBlockAtOtherPosition).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(retainedBlockAtOtherPosition, 3L, 0L)).isPresent());
        assertTrue(registry.find(retainedBlockInOtherDimension).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(retainedBlockInOtherDimension, 3L, 0L)).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(item, 3L, 0L)).isPresent());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(ephemeral, 3L, 0L)).isPresent());
        assertEquals(6, registry.size());

        registry.onWorldDisconnect();
        assertEquals(0, registry.size());
        assertEquals(new PoseCacheMetrics(0, 16, 0L, 0L, 0L), registry.poseCacheMetrics());
    }

    private static String sampleAndCacheSource() throws IOException {
        Path sourcePath = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "fabric", "client", "animation",
                "ClientAnimationInstanceRegistry.java");
        String source = Files.readString(sourcePath);
        int start = source.indexOf("public LocalPose sampleAndCache(");
        int end = source.indexOf("\n    public void cachePose(", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("sampleAndCache source boundary was not found");
        }
        return source.substring(start, end);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
