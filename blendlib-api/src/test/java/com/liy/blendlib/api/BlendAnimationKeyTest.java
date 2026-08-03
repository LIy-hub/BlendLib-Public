package com.liy.blendlib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlendAnimationKeyTest {
    @Test
    void parsesAndRoundTripsPureSemanticKeys() {
        BlendAnimationKey key = BlendAnimationKey.parse("example:combat/attack");

        assertEquals("example", key.namespace());
        assertEquals("combat/attack", key.path());
        assertEquals("example:combat/attack", key.value());
        assertEquals(key, BlendAnimationKey.of("example", "combat/attack"));
        assertEquals(key, BlendAnimationKey.fromResourceId(BlendResourceId.parse("example:combat/attack")));
        assertEquals(key.hashCode(), BlendAnimationKey.of("example", "combat/attack").hashCode());
        assertNotEquals(key, BlendAnimationKey.parse("example:combat/idle"));
    }

    @Test
    void preservesResourceIdValidationAndRejectsNullWrapping() {
        assertThrows(IllegalArgumentException.class, () -> BlendAnimationKey.parse("Example:attack"));
        assertThrows(IllegalArgumentException.class, () -> BlendAnimationKey.parse("example:../attack"));
        assertThrows(NullPointerException.class, () -> BlendAnimationKey.parse(null));
        assertThrows(NullPointerException.class, () -> BlendAnimationKey.of(null, "attack"));
        assertThrows(NullPointerException.class, () -> BlendAnimationKey.fromResourceId(null));
    }
}
