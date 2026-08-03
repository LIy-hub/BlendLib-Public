package com.liy.blendlib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BlendModelKeyTest {
    @Test
    void parsesCanonicalExtensionFreeKeys() {
        BlendModelKey key = BlendModelKey.parse("blendlib:fixtures/rigid_model");

        assertEquals("blendlib", key.namespace());
        assertEquals("fixtures/rigid_model", key.path());
        assertEquals("blendlib:fixtures/rigid_model", key.value());
        assertEquals(key, BlendModelKey.of("blendlib", "fixtures/rigid_model"));
        assertEquals(key.hashCode(), BlendModelKey.of("blendlib", "fixtures/rigid_model").hashCode());
        assertNotEquals(key, BlendModelKey.parse("blendlib:fixtures/static_model"));
    }

    @Test
    void mapsOnlyToAndFromTheCanonicalDescriptorPath() {
        BlendModelKey key = BlendModelKey.parse("example:creatures/dragon");
        BlendResourceId descriptor = BlendResourceId.parse("example:blend_models/creatures/dragon.json");

        assertEquals(descriptor, key.descriptorResourceId());
        assertEquals(key, BlendModelKey.fromDescriptorResourceId(descriptor));
        assertEquals(key, BlendModelKey.fromResourceId(BlendResourceId.parse("example:creatures/dragon")));
    }

    @Test
    void rejectsInvalidTraversalAndFilenameBoundaries() {
        List<String> invalidKeys = List.of(
                "blendlib",
                ":model",
                "blendlib:",
                "BlendLib:model",
                "blendlib:folder/../escape",
                "blendlib:folder/./current",
                "blendlib:folder//double",
                "blendlib:model.json");

        for (String invalidKey : invalidKeys) {
            assertThrows(IllegalArgumentException.class, () -> BlendModelKey.parse(invalidKey), invalidKey);
        }

        List<BlendResourceId> invalidDescriptors = List.of(
                BlendResourceId.parse("blendlib:models3d/model.glb"),
                BlendResourceId.parse("blendlib:blend_models/model.glb"),
                BlendResourceId.parse("blendlib:blend_models/model.json.bak"),
                BlendResourceId.parse("blendlib:blend_models/.json"));

        for (BlendResourceId invalidDescriptor : invalidDescriptors) {
            assertThrows(IllegalArgumentException.class, () -> BlendModelKey.fromDescriptorResourceId(invalidDescriptor), invalidDescriptor.toString());
        }
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> BlendModelKey.parse(null));
        assertThrows(NullPointerException.class, () -> BlendModelKey.of(null, "model"));
        assertThrows(NullPointerException.class, () -> BlendModelKey.of("blendlib", null));
        assertThrows(NullPointerException.class, () -> BlendModelKey.fromResourceId(null));
        assertThrows(NullPointerException.class, () -> BlendModelKey.fromDescriptorResourceId(null));
    }
}
