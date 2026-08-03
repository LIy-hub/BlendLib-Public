package com.liy.blendlib.fabric.common.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServerAnimationStateRegistryTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_test:idle");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("blendlib_test:attack");
    private static final BlendResourceId OVERWORLD = BlendResourceId.parse("minecraft:overworld");

    @Test
    void persistentTrackingReplayKeepsPersistentSemanticStateButReceivesFreshSequenceAfterTransient() {
        ServerAnimationStateRegistry registry = new ServerAnimationStateRegistry();
        UUID entity = UUID.randomUUID();

        SyncedAnimationState idle = registry.setPersistentEntity(entity, IDLE, 10L, 1.0F, 4L);
        SyncedAnimationState attack = registry.triggerEntity(entity, ATTACK, 20L, 1.0F, 5L);
        SyncedAnimationState replay = registry.replayPersistentEntity(entity).orElseThrow();

        assertEquals(1L, idle.sequence());
        assertEquals(2L, attack.sequence());
        assertFalse(attack.persistent());
        assertEquals(IDLE, replay.animationKey());
        assertEquals(10L, replay.startGameTick());
        assertTrue(replay.persistent());
        assertEquals(3L, replay.sequence());
        assertEquals(4L, replay.seed());
    }

    @Test
    void blockTargetsAreDimensionScopedAndCleanupDoesNotCrossDimensions() {
        ServerAnimationStateRegistry registry = new ServerAnimationStateRegistry();
        BlendResourceId nether = BlendResourceId.parse("minecraft:the_nether");

        registry.setPersistentBlockEntity(OVERWORLD, 17L, IDLE, 4L, 1.0F, 1L);
        registry.setPersistentBlockEntity(nether, 17L, ATTACK, 5L, 1.0F, 2L);

        assertEquals(1, registry.persistentBlocksIn(OVERWORLD).size());
        assertEquals(1, registry.persistentBlocksIn(nether).size());
        registry.clearDimension(OVERWORLD);
        assertEquals(0, registry.persistentBlocksIn(OVERWORLD).size());
        assertEquals(1, registry.persistentBlocksIn(nether).size());
        assertTrue(registry.replayPersistentBlockEntity(nether, 17L).isPresent());
    }

    @Test
    void noPersistentStateMeansTrackingStartHasNothingToReplay() {
        ServerAnimationStateRegistry registry = new ServerAnimationStateRegistry();
        UUID entity = UUID.randomUUID();

        registry.triggerEntity(entity, ATTACK, 20L, 1.0F, 5L);

        assertTrue(registry.replayPersistentEntity(entity).isEmpty());
    }

    @Test
    void entityAndServerLifecycleCleanupReleaseAllRetainedTargets() {
        ServerAnimationStateRegistry registry = new ServerAnimationStateRegistry();
        UUID entity = UUID.randomUUID();
        registry.setPersistentEntity(entity, IDLE, 1L, 1.0F, 0L);
        registry.setPersistentBlockEntity(OVERWORLD, 8L, IDLE, 1L, 1.0F, 0L);

        registry.clearEntity(entity);
        assertEquals(0, registry.entityTargetCount());
        assertEquals(1, registry.blockEntityTargetCount());

        registry.clearAll();
        assertEquals(0, registry.entityTargetCount());
        assertEquals(0, registry.blockEntityTargetCount());
    }
}
