package com.liy.blendlib.fabric.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import org.junit.jupiter.api.Test;

class ClientAnimationLifecycleBridgeTest {
    @Test
    void exposesOneRegistryAndDisconnectClearsInstanceControllerAndPoseCacheState() {
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(4);
        ClientAnimationInstanceRegistry registry = lifecycle.registry();
        assertSame(registry, lifecycle.registry());

        lifecycle.onPlayInit();
        BlendInstanceKey key = lifecycle.entityKey(41);
        registry.bind(key, ClientAnimationTestFixtures.MODEL, 3L, ClientAnimationTestFixtures.definition());
        PoseCacheKey poseKey = ClientAnimationTestFixtures.poseKey(key, 3L, 0L);
        registry.cachePose(poseKey, ClientAnimationTestFixtures.pose(2.0F));
        assertTrue(registry.cachedPose(poseKey).isPresent());
        assertEquals(1, registry.size());
        assertTrue(registry.poseCacheMetrics().hits() > 0L);

        lifecycle.onWorldDisconnect();

        assertEquals(0, registry.size());
        assertTrue(registry.find(key).isEmpty());
        assertTrue(registry.cachedPose(poseKey).isEmpty());
        assertEquals(new PoseCacheMetrics(0, 4, 0L, 0L, 0L), registry.poseCacheMetrics());
        assertThrows(IllegalStateException.class, () -> lifecycle.entityKey(41));
    }

    @Test
    void delegatesTypedEntityAndBlockEntityUnloadWithoutTouchingOtherInstanceDomains() {
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(8);
        ClientAnimationInstanceRegistry registry = lifecycle.registry();
        lifecycle.onPlayInit();
        BlendInstanceKey.Entity entity = lifecycle.entityKey(91);
        assertEquals(entity, lifecycle.activeEntityKey(91).orElseThrow());
        BlendInstanceKey.Entity retainedOtherSessionSameEntityId = new BlendInstanceKey.Entity("stale-session", 91);
        BlendInstanceKey.BlockEntity block = new BlendInstanceKey.BlockEntity(
                BlendResourceId.parse("minecraft:the_nether"), 55L);
        BlendInstanceKey item = BlendInstanceKey.item();

        for (BlendInstanceKey key : new BlendInstanceKey[]{entity, retainedOtherSessionSameEntityId, block, item}) {
            registry.bind(key, ClientAnimationTestFixtures.MODEL, 4L, ClientAnimationTestFixtures.definition());
            registry.cachePose(ClientAnimationTestFixtures.poseKey(key, 4L, 0L), ClientAnimationTestFixtures.pose(1.0F));
        }

        assertEquals(1, lifecycle.onEntityUnload(entity.entityId()));
        assertTrue(registry.find(entity).isEmpty());
        assertTrue(registry.find(retainedOtherSessionSameEntityId).isPresent());
        assertTrue(registry.find(block).isPresent());
        assertTrue(registry.find(item).isPresent());

        assertEquals(1, lifecycle.onBlockEntityUnload(block));
        assertTrue(registry.find(block).isEmpty());
        assertTrue(registry.find(item).isPresent());
    }

    @Test
    void entityUnloadPurgesEveryCachedRevisionThroughTheDisconnectRemovalPath() {
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(16);
        ClientAnimationInstanceRegistry registry = lifecycle.registry();
        lifecycle.onPlayInit();
        BlendInstanceKey.Entity unloadedEntity = lifecycle.entityKey(93);
        BlendInstanceKey.Entity retainedEntity = lifecycle.entityKey(94);
        registry.bind(unloadedEntity, ClientAnimationTestFixtures.MODEL, 4L, ClientAnimationTestFixtures.definition());
        registry.bind(retainedEntity, ClientAnimationTestFixtures.MODEL, 4L, ClientAnimationTestFixtures.definition());
        for (long revision = 0L; revision < 4L; revision++) {
            registry.cachePose(
                    ClientAnimationTestFixtures.poseKey(unloadedEntity, 4L, revision),
                    ClientAnimationTestFixtures.pose(revision + 1.0F));
        }
        PoseCacheKey retainedKey = ClientAnimationTestFixtures.poseKey(retainedEntity, 4L, 0L);
        registry.cachePose(retainedKey, ClientAnimationTestFixtures.pose(9.0F));
        assertTrue(registry.cachedPose(retainedKey).isPresent());

        assertEquals(1, lifecycle.onEntityUnload(unloadedEntity.entityId()));

        assertTrue(registry.find(unloadedEntity).isEmpty());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(unloadedEntity, 4L, 0L)).isEmpty());
        assertTrue(registry.cachedPose(retainedKey).isPresent());
        assertEquals(1, registry.poseCacheMetrics().size());
    }

    @Test
    void lateEntityUnloadAfterDisconnectReturnsZeroWithoutSynthesizingAStaleEntityKey() {
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(4);
        ClientAnimationInstanceRegistry registry = lifecycle.registry();
        lifecycle.onPlayInit();
        BlendInstanceKey.Entity entity = lifecycle.entityKey(63);
        registry.bind(entity, ClientAnimationTestFixtures.MODEL, 2L, ClientAnimationTestFixtures.definition());

        lifecycle.onWorldDisconnect();

        assertTrue(lifecycle.activeEntityKey(entity.entityId()).isEmpty());
        assertEquals(0, lifecycle.onEntityUnload(entity.entityId()));
        assertEquals(0, registry.size());
        assertThrows(IllegalStateException.class, () -> lifecycle.entityKey(entity.entityId()));
    }

    @Test
    void playInitRotatesTheConnectionScopedEntityKeyAndRetiresPriorRegistryState() {
        ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(4);
        ClientAnimationInstanceRegistry registry = lifecycle.registry();

        lifecycle.onPlayInit();
        BlendInstanceKey.Entity firstSession = lifecycle.entityKey(8);
        registry.bind(firstSession, ClientAnimationTestFixtures.MODEL, 1L, ClientAnimationTestFixtures.definition());
        registry.cachePose(ClientAnimationTestFixtures.poseKey(firstSession, 1L, 0L), ClientAnimationTestFixtures.pose(1.0F));

        lifecycle.onPlayInit();
        BlendInstanceKey.Entity secondSession = lifecycle.entityKey(8);
        assertNotEquals(firstSession, secondSession);
        assertEquals(0, registry.size());
        assertTrue(registry.cachedPose(ClientAnimationTestFixtures.poseKey(firstSession, 1L, 0L)).isEmpty());
    }
}
