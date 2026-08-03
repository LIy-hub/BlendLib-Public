package com.liy.blendlib.fabric.client.animation.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import org.junit.jupiter.api.Test;

class UnknownTargetQueueTest {
    private static final ClientAnimationTarget.EntityTarget TARGET = new ClientAnimationTarget.EntityTarget(4);
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_test:idle");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("blendlib_test:attack");

    @Test
    void newerCommandReplacesOlderWaitAndAppliesWhenTargetAppears() {
        UnknownTargetQueue queue = new UnknownTargetQueue(2, 4L);
        queue.enqueue(command(IDLE, 1L), 0L);
        queue.enqueue(command(ATTACK, 2L), 1L);

        UnknownTargetQueue.RetryResult retry = queue.retry(2L, target -> target.equals(TARGET));

        assertEquals(1, retry.readyCommands().size());
        assertEquals(ATTACK, retry.readyCommands().getFirst().animation().animationKey());
        assertEquals(0, retry.expiredCommands());
        assertEquals(0, queue.size());
    }

    @Test
    void unknownTargetExpiresAfterBoundedShortTtl() {
        UnknownTargetQueue queue = new UnknownTargetQueue(2, 3L);
        queue.enqueue(command(IDLE, 1L), 0L);

        assertTrue(queue.retry(2L, ignored -> false).readyCommands().isEmpty());
        UnknownTargetQueue.RetryResult expired = queue.retry(3L, ignored -> false);

        assertEquals(1, expired.expiredCommands());
        assertEquals(1L, queue.expiredDrops());
        assertEquals(0, queue.size());
    }

    private static ClientAnimationCommand command(BlendAnimationKey key, long sequence) {
        return new ClientAnimationCommand(TARGET, new SyncedAnimationState(key, 0L, sequence, 1.0F, 0L, false));
    }
}
