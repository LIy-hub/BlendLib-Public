package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import java.util.ArrayList;
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
}
