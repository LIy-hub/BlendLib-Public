package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import java.util.ArrayList;
import java.lang.ref.Reference;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ItemAnimationInstancesTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("test:idle");

    @Test void identicalContentsRemainIndependentWhileSameObjectSharesAllContexts() {
        var instances = new ItemAnimationInstances(4, () -> 0L, ignored -> { });
        Object first = new String("same item and components");
        Object copy = new String("same item and components");
        var gui = instances.get(first, IDLE);
        var hand = instances.get(first, IDLE);
        var copied = instances.get(copy, IDLE);
        assertSame(gui, hand);
        assertNotEquals(gui.key(), copied.key());
        gui.playback().pause().seek(2);
        assertEquals(2, hand.playback().sample(10));
        assertEquals(0, copied.playback().sample(10));
    }

    @Test void lruEvictionAndReleaseRetireRuntimeKeysAndNewEntriesGetNewIdentities() {
        var retired = new ArrayList<BlendInstanceKey>();
        var instances = new ItemAnimationInstances(2, () -> 0L, retired::add);
        Object a = new Object(), b = new Object(), c = new Object();
        var first = instances.get(a, IDLE);
        var second = instances.get(b, IDLE);
        instances.get(a, IDLE); // A is most recently used.
        instances.get(c, IDLE);
        assertEquals(2, instances.size());
        assertEquals(second.key(), retired.getFirst());
        instances.release(a);
        assertEquals(first.key(), retired.get(1));
        assertNotEquals(first.key(), instances.get(a, IDLE).key());
        instances.clear();
        assertEquals(0, instances.size());
        assertNotEquals(first.key(), instances.get(a, IDLE).key());
    }

    @Test void peekingNeverCreatesAnEntryReadsTheClockOrRefreshesAccessOrder() {
        var retired = new ArrayList<BlendInstanceKey>();
        var reads = new AtomicInteger();
        var instances = new ItemAnimationInstances(2, () -> { reads.incrementAndGet(); return 0L; }, retired::add);
        Object unseen = new Object(), a = new Object(), b = new Object(), c = new Object();
        assertNull(instances.peek(unseen));
        assertEquals(0, reads.get());
        assertEquals(0, instances.size());
        var first = instances.get(a, IDLE);
        var second = instances.get(b, IDLE);
        assertEquals(2, reads.get());
        for (int index = 0; index < 10; index++) {
            assertSame(first, instances.peek(a));
            assertEquals(0, instances.peek(a).playback().observe(-1).storedSeconds());
            assertNull(instances.peek(unseen));
        }
        assertEquals(2, reads.get());
        assertTrue(retired.isEmpty());
        instances.get(c, IDLE);
        assertEquals(java.util.List.of(first.key()), retired, "observation must not protect A from LRU eviction");
        assertNull(instances.peek(a));
        assertSame(second, instances.peek(b));
    }

    @Test void peekingDoesNotPurgeQueuedWeakIdentitiesOrRetireTheirRuntimeState() throws Exception {
        var retired = new ArrayList<BlendInstanceKey>();
        var instances = new ItemAnimationInstances(2, () -> 0L, retired::add);
        Object expired = new Object(), retained = new Object();
        var expiredEntry = instances.get(expired, IDLE);
        var liveEntry = instances.get(retained, IDLE);
        var field = ItemAnimationInstances.class.getDeclaredField("entries");
        field.setAccessible(true);
        var entries = (Map<?, ?>) field.get(instances);
        Reference<?> weak = (Reference<?>) entries.keySet().iterator().next();
        weak.clear();
        assertTrue(weak.enqueue(), "deterministic weak-queue simulation, without relying on GC timing");
        assertNull(instances.peek(expired));
        assertSame(liveEntry, instances.peek(retained));
        assertTrue(retired.isEmpty());
        assertEquals(2, entries.size(), "peek must not drain the weak queue");
        assertEquals(1, instances.size(), "ordinary maintenance still purges the queued identity");
        assertEquals(java.util.List.of(expiredEntry.key()), retired);
    }

    @Test void peekUsesExactIdentityAndReleaseAndClearLeaveOldControlsDetached() {
        var retired = new ArrayList<BlendInstanceKey>();
        var instances = new ItemAnimationInstances(4, () -> 0L, retired::add);
        Object stack = new String("contents"), copy = new String("contents");
        var original = instances.get(stack, IDLE);
        assertSame(original, instances.peek(stack));
        assertNull(instances.peek(copy));
        original.playback().pause().seek(3);
        var snapshot = instances.peek(stack).playback().observe(-1);
        instances.release(stack);
        assertNull(instances.peek(stack));
        assertEquals(java.util.List.of(original.key()), retired);
        instances.release(stack);
        assertEquals(1, retired.size(), "release is idempotent");
        var replacement = instances.get(stack, IDLE);
        original.playback().seek(9);
        assertEquals(0, instances.peek(stack).playback().observe(-1).storedSeconds());
        assertEquals(3, snapshot.storedSeconds());
        instances.clear();
        assertNull(instances.peek(stack));
        assertNull(instances.peek(copy));
        assertEquals(java.util.List.of(original.key(), replacement.key()), retired);
        assertThrows(NullPointerException.class, () -> instances.peek(null));
    }

}
