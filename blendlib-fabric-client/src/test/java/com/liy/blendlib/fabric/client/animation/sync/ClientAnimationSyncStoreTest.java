package com.liy.blendlib.fabric.client.animation.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import org.junit.jupiter.api.Test;

class ClientAnimationSyncStoreTest {
    private static final BlendResourceId OVERWORLD = BlendResourceId.parse("minecraft:overworld");
    private static final BlendResourceId NETHER = BlendResourceId.parse("minecraft:the_nether");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_test:idle");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("blendlib_test:attack");

    @Test
    void staleOutOfOrderSequenceNeverRewindsLatestState() {
        ClientAnimationSyncStore store = new ClientAnimationSyncStore();
        store.beginSession("connection-a", OVERWORLD);
        ClientAnimationTarget.EntityTarget target = new ClientAnimationTarget.EntityTarget(8);

        assertEquals(ClientAnimationSyncStore.ApplyResult.APPLIED,
                store.apply(command(target, ATTACK, 2L)));
        assertEquals(ClientAnimationSyncStore.ApplyResult.STALE_DROPPED,
                store.apply(command(target, IDLE, 1L)));
        assertEquals(ATTACK, store.entityState(8).orElseThrow().animationKey());
        assertEquals(2L, store.entityState(8).orElseThrow().sequence());
    }

    @Test
    void reconnectDimensionAndUnloadCleanupPreventEntityIdOrBlockPositionReuse() {
        ClientAnimationSyncStore store = new ClientAnimationSyncStore();
        store.beginSession("connection-a", OVERWORLD);
        store.apply(command(new ClientAnimationTarget.EntityTarget(12), IDLE, 1L));
        store.apply(command(new ClientAnimationTarget.BlockEntityTarget(OVERWORLD, 99L), IDLE, 1L));

        store.onEntityUnload(12);
        store.onBlockEntityUnload(OVERWORLD, 99L);
        assertTrue(store.entityState(12).isEmpty());
        assertTrue(store.blockEntityState(OVERWORLD, 99L).isEmpty());

        store.apply(command(new ClientAnimationTarget.EntityTarget(12), ATTACK, 3L));
        store.beginSession("connection-b", NETHER);
        assertTrue(store.entityState(12).isEmpty());
        assertTrue(store.blockEntityState(OVERWORLD, 99L).isEmpty());
        assertEquals(ClientAnimationSyncStore.ApplyResult.OUT_OF_SESSION_DROPPED,
                store.apply(command(new ClientAnimationTarget.BlockEntityTarget(OVERWORLD, 99L), ATTACK, 4L)));
        assertEquals(ClientAnimationSyncStore.ApplyResult.APPLIED,
                store.apply(command(new ClientAnimationTarget.BlockEntityTarget(NETHER, 99L), ATTACK, 4L)));
        assertFalse(store.blockEntityState(NETHER, 99L).isEmpty());
    }

    @Test
    void lateEntityUnloadAfterDisconnectIsNoOpAndDoesNotRecreateSessionState() {
        ClientAnimationSyncStore store = new ClientAnimationSyncStore();
        ClientAnimationTarget.EntityTarget target = new ClientAnimationTarget.EntityTarget(12);
        store.beginSession("connection-a", OVERWORLD);
        assertEquals(ClientAnimationSyncStore.ApplyResult.APPLIED, store.apply(command(target, IDLE, 1L)));

        store.disconnect();

        assertDoesNotThrow(() -> store.onEntityUnload(target.entityId()));
        assertEquals(0, store.size());
        assertTrue(store.connectionSession().isEmpty());
        assertTrue(store.activeDimension().isEmpty());
        assertTrue(store.entityState(target.entityId()).isEmpty());
        assertEquals(ClientAnimationSyncStore.ApplyResult.OUT_OF_SESSION_DROPPED,
                store.apply(command(target, ATTACK, 2L)));
    }

    private static ClientAnimationCommand command(ClientAnimationTarget target, BlendAnimationKey key, long sequence) {
        return new ClientAnimationCommand(target, new SyncedAnimationState(key, 0L, sequence, 1.0F, 0L, false));
    }
}
