package com.liy.blendlib.fabric.common.animation.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnimationIntentBoundaryTest {
    @Test
    void intentRecordCarriesOnlyTypedSemanticFields() {
        List<Class<?>> componentTypes = List.of(AnimationIntent.class.getRecordComponents()).stream()
                .map(RecordComponent::getType)
                .toList();
        assertEquals(List.of(
                AnimationIntentScope.class,
                BlendResourceId.class,
                BlendAnimationKey.class,
                long.class,
                long.class,
                float.class,
                AnimationIntentMode.class), componentTypes);
        assertTrue(AnimationIntentScope.class.isRecord());
        assertFalse(componentTypes.contains(Object.class));
    }

    @Test
    void semanticSourcesDoNotPullModelOrClientTypesIntoTheIntentBoundary() throws IOException {
        Path directory = Path.of(System.getProperty("blendlib.projectDir"), "src", "main", "java", "com", "liy",
                "blendlib", "fabric", "common", "animation", "v2");
        String text = Files.readString(directory.resolve("AnimationIntent.java"))
                + Files.readString(directory.resolve("AnimationIntentScope.java"));
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("matrix", "pose", "vertex", "glb", "net.minecraft.client", "modelasset")) {
            assertFalse(lower.contains(forbidden), () -> "intent boundary must not reference " + forbidden);
        }
        assertTrue(lower.contains("blendanimationkey"));
        assertTrue(lower.contains("blendinstancekey"));
    }

    @Test
    void commonIntentAndCoreCommandShareTheExactSemanticIdentifierBound() {
        BlendResourceId identifier256 = identifierOfLength(256);
        BlendResourceId identifier257 = identifierOfLength(257);
        BlendAnimationKey key256 = BlendAnimationKey.fromResourceId(identifier256);
        BlendAnimationKey key257 = BlendAnimationKey.fromResourceId(identifier257);
        AnimationIntentScope scope = new AnimationIntentScope("session", BlendResourceId.of("x2bound", "world"),
                BlendInstanceKey.item(), 0L);

        assertDoesNotThrow(() -> new AnimationIntent(scope, identifier256, key256, 0L, 0L, 1.0F,
                AnimationIntentMode.ONE_SHOT));
        assertDoesNotThrow(() -> new AnimationV2Command(identifier256, key256, 0L, 0.0D, 1.0D));
        assertThrows(IllegalArgumentException.class, () -> new AnimationIntent(scope, identifier257, key256,
                0L, 0L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertThrows(IllegalArgumentException.class, () -> new AnimationV2Command(identifier257, key256,
                0L, 0.0D, 1.0D));
        assertThrows(IllegalArgumentException.class, () -> new AnimationIntent(scope, identifier256, key257,
                0L, 0L, 1.0F, AnimationIntentMode.ONE_SHOT));
        assertThrows(IllegalArgumentException.class, () -> new AnimationV2Command(identifier256, key257,
                0L, 0.0D, 1.0D));
    }

    private static BlendResourceId identifierOfLength(int length) {
        return BlendResourceId.of("x", "a".repeat(length - 2));
    }
}
