package com.liy.blendlib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlendInstanceKeyTest {
    @Test
    void variantsAreValueEqualOnlyInsideTheirTypedDomain() {
        BlendInstanceKey.Entity first = BlendInstanceKey.entity("connection-a", 42);
        BlendInstanceKey.Entity same = BlendInstanceKey.entity("connection-a", 42);
        BlendInstanceKey.Entity reconnect = BlendInstanceKey.entity("connection-b", 42);
        BlendInstanceKey.BlockEntity block = BlendInstanceKey.blockEntity(BlendResourceId.parse("minecraft:overworld"), 42L);
        BlendInstanceKey.Ephemeral ephemeral = BlendInstanceKey.ephemeral("connection-a", "42");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, reconnect);
        assertNotEquals(first, block);
        assertNotEquals(first, ephemeral);
        assertEquals(BlendInstanceKey.Item.STATELESS, BlendInstanceKey.item());
    }

    @Test
    void validatesSessionTokensAndEntityIds() {
        assertThrows(NullPointerException.class, () -> BlendInstanceKey.entity(null, 1));
        assertThrows(IllegalArgumentException.class, () -> BlendInstanceKey.entity(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> BlendInstanceKey.entity("session", -1));
        assertThrows(NullPointerException.class, () -> BlendInstanceKey.blockEntity(null, 0L));
        assertThrows(NullPointerException.class, () -> BlendInstanceKey.ephemeral(null, "effect"));
        assertThrows(IllegalArgumentException.class, () -> BlendInstanceKey.ephemeral("session", ""));
    }
}
