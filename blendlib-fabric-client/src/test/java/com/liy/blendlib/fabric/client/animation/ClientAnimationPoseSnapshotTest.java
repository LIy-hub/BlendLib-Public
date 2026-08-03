package com.liy.blendlib.fabric.client.animation;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Transform;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAnimationPoseSnapshotTest {
    @Test
    void preparePoseSnapshotUsesTheBoundedCacheAndDoesNotResampleOnHit() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(2);
        BlendInstanceKey key = BlendInstanceKey.entity("pose-snapshot-hit", 14);
        ClientAnimationInstance instance = registry.bind(
                key, ClientAnimationTestFixtures.MODEL, 3L, ClientAnimationTestFixtures.definition());
        PoseCacheKey poseKey = ClientAnimationTestFixtures.poseKey(key, 3L, 9L);
        instance.advance(0.25D);

        ClientAnimationPoseSnapshot first = registry.preparePoseSnapshot(poseKey, ClientAnimationTestFixtures.sampler());
        instance.advance(0.25D);
        ClientAnimationPoseSnapshot hit = registry.preparePoseSnapshot(poseKey, ClientAnimationTestFixtures.sampler());

        assertNotSame(first, hit);
        assertSame(first.localPose(), hit.localPose());
        assertSame(first.localPose(), instance.latestPose().orElseThrow());
        assertEquals(0.25F, hit.localPose().transform(0).translation().x(), 0.000001F);
        assertEquals(new PoseCacheMetrics(1, 2, 1L, 1L, 0L), registry.poseCacheMetrics());
    }

    @Test
    void evictedPoseSnapshotRemainsImmutableWhileTheLruCacheStaysBounded() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(1);
        BlendInstanceKey key = BlendInstanceKey.entity("pose-snapshot-lru", 15);
        registry.bind(key, ClientAnimationTestFixtures.MODEL, 4L, ClientAnimationTestFixtures.definition());
        PoseCacheKey firstKey = ClientAnimationTestFixtures.poseKey(key, 4L, 1L);
        PoseCacheKey secondKey = ClientAnimationTestFixtures.poseKey(key, 4L, 2L);

        ClientAnimationPoseSnapshot first = registry.preparePoseSnapshot(firstKey, ClientAnimationTestFixtures.sampler());
        ClientAnimationPoseSnapshot second = registry.preparePoseSnapshot(secondKey, ClientAnimationTestFixtures.sampler());

        assertNotSame(first.localPose(), second.localPose());
        assertEquals(new PoseCacheMetrics(1, 1, 0L, 2L, 1L), registry.poseCacheMetrics());
        assertTrue(registry.cachedPose(firstKey).isEmpty());
        assertTrue(registry.cachedPose(secondKey).isPresent());
        registry.requireCurrentPoseSnapshot(first);
        assertEquals(0.0F, first.localPose().transform(0).translation().x(), 0.000001F);
    }

    @Test
    void snapshotsRejectStaleStateModelAndGenerationBindings() {
        BlendInstanceKey key = BlendInstanceKey.entity("pose-snapshot-stale", 16);

        ClientAnimationInstanceRegistry stateRegistry = new ClientAnimationInstanceRegistry(2);
        ClientAnimationInstance stateInstance = stateRegistry.bind(
                key, ClientAnimationTestFixtures.MODEL, 5L, ClientAnimationTestFixtures.twoStateDefinition());
        ClientAnimationPoseSnapshot stateSnapshot = stateRegistry.preparePoseSnapshot(
                ClientAnimationTestFixtures.poseKey(key, 5L, 3L), ClientAnimationTestFixtures.sampler());
        stateInstance.controller().trigger(ClientAnimationTestFixtures.WALK);
        assertThrows(IllegalArgumentException.class, () -> stateRegistry.requireCurrentPoseSnapshot(stateSnapshot));

        ClientAnimationInstanceRegistry modelRegistry = new ClientAnimationInstanceRegistry(2);
        modelRegistry.bind(key, ClientAnimationTestFixtures.MODEL, 5L, ClientAnimationTestFixtures.definition());
        ClientAnimationPoseSnapshot modelSnapshot = modelRegistry.preparePoseSnapshot(
                ClientAnimationTestFixtures.poseKey(key, 5L, 4L), ClientAnimationTestFixtures.sampler());
        modelRegistry.bind(key, ClientAnimationTestFixtures.ALTERNATE_MODEL, 5L, ClientAnimationTestFixtures.definition());
        assertThrows(IllegalArgumentException.class, () -> modelRegistry.requireCurrentPoseSnapshot(modelSnapshot));

        ClientAnimationInstanceRegistry generationRegistry = new ClientAnimationInstanceRegistry(2);
        generationRegistry.bind(key, ClientAnimationTestFixtures.MODEL, 5L, ClientAnimationTestFixtures.definition());
        ClientAnimationPoseSnapshot generationSnapshot = generationRegistry.preparePoseSnapshot(
                ClientAnimationTestFixtures.poseKey(key, 5L, 5L), ClientAnimationTestFixtures.sampler());
        generationRegistry.bind(key, ClientAnimationTestFixtures.MODEL, 6L, ClientAnimationTestFixtures.definition());
        assertThrows(IllegalArgumentException.class, () -> generationRegistry.requireCurrentPoseSnapshot(generationSnapshot));
    }

    @Test
    void snapshotRetainsOnlyImmutableCorePoseStateAndRejectsWrongCurrentStateBeforeSampling() {
        BlendInstanceKey key = BlendInstanceKey.entity("pose-snapshot-immutable", 17);
        PoseCacheKey poseKey = ClientAnimationTestFixtures.poseKey(key, 7L, 6L);
        Map<Integer, Transform> callerOwned = new LinkedHashMap<>();
        callerOwned.put(0, Transform.IDENTITY);
        LocalPose pose = new LocalPose(callerOwned);
        ClientAnimationPoseSnapshot snapshot = ClientAnimationPoseSnapshot.from(poseKey, pose);
        callerOwned.put(1, Transform.IDENTITY);

        assertSame(pose, snapshot.localPose());
        assertFalse(snapshot.localPose().transforms().containsKey(1));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.localPose().transforms().put(2, Transform.IDENTITY));

        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(2);
        registry.bind(key, ClientAnimationTestFixtures.MODEL, 7L, ClientAnimationTestFixtures.definition());
        PoseCacheKey wrongState = new PoseCacheKey(
                key,
                ClientAnimationTestFixtures.MODEL,
                7L,
                ClientAnimationTestFixtures.WALK,
                7L);
        assertThrows(IllegalArgumentException.class,
                () -> registry.preparePoseSnapshot(wrongState, ClientAnimationTestFixtures.sampler()));
        assertEquals(new PoseCacheMetrics(0, 2, 0L, 0L, 0L), registry.poseCacheMetrics());
    }
}
