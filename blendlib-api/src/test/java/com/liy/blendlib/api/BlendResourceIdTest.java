package com.liy.blendlib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BlendResourceIdTest {
    @Test
    void parsesAndRoundTripsCanonicalValues() {
        BlendResourceId id = BlendResourceId.parse("blendlib:models3d/dragon.glb");

        assertEquals("blendlib", id.namespace());
        assertEquals("models3d/dragon.glb", id.path());
        assertEquals("blendlib:models3d/dragon.glb", id.value());
        assertEquals(id.value(), id.toString());
        assertEquals(id, BlendResourceId.of("blendlib", "models3d/dragon.glb"));
        assertEquals(id.hashCode(), BlendResourceId.of("blendlib", "models3d/dragon.glb").hashCode());
        assertNotEquals(id, BlendResourceId.parse("blendlib:models3d/other.glb"));
    }

    @Test
    void rejectsNonCanonicalValues() {
        List<String> invalidValues = List.of(
                "blendlib",
                ":path",
                "blendlib:",
                "blendlib:path:extra",
                "BlendLib:path",
                "blendlib:Uppercase",
                "blendlib:with space",
                "blendlib:/leading",
                "blendlib:trailing/",
                "blendlib:double//slash",
                "blendlib:folder/../escape",
                "blendlib:folder/./current",
                "blendlib:windows\\path");

        for (String invalidValue : invalidValues) {
            assertThrows(IllegalArgumentException.class, () -> BlendResourceId.parse(invalidValue), invalidValue);
        }
    }

    @Test
    void rejectsNullValuesAndComponents() {
        assertThrows(NullPointerException.class, () -> BlendResourceId.parse(null));
        assertThrows(NullPointerException.class, () -> BlendResourceId.of(null, "path"));
        assertThrows(NullPointerException.class, () -> BlendResourceId.of("blendlib", null));
    }
}
