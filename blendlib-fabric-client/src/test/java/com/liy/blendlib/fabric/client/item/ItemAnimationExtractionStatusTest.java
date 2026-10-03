package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemAnimationExtractionStatusTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("test:item");
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("test:idle");

    @Test void statusIsAnImmutableSemanticValueWithoutStacksOrRuntimeResources() {
        var type = ItemAnimationExtractionStatus.class;
        assertTrue(type.isRecord());
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(Arrays.stream(type.getDeclaredFields())
                .allMatch(field -> Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers())));
        assertEquals(List.of(BlendModelKey.class, BlendAnimationKey.class, long.class,
                        ItemAnimationExtractionStatus.Outcome.class, ItemAnimationExtractionStatus.Fallback.class, boolean.class),
                Arrays.stream(type.getRecordComponents()).map(component -> component.getType()).toList());
        var first = new ItemAnimationExtractionStatus(MODEL, IDLE, 7,
                ItemAnimationExtractionStatus.Outcome.ANIMATED, ItemAnimationExtractionStatus.Fallback.NONE, true);
        var equal = new ItemAnimationExtractionStatus(MODEL, IDLE, 7,
                ItemAnimationExtractionStatus.Outcome.ANIMATED, ItemAnimationExtractionStatus.Fallback.NONE, true);
        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, new ItemAnimationExtractionStatus(MODEL, IDLE, 7,
                ItemAnimationExtractionStatus.Outcome.ANIMATED, ItemAnimationExtractionStatus.Fallback.NONE, false));
    }

    @Test void constructorRejectsNullsNegativeGenerationsAndContradictoryFallbackClaims() {
        var animated = ItemAnimationExtractionStatus.Outcome.ANIMATED;
        var none = ItemAnimationExtractionStatus.Fallback.NONE;
        assertThrows(NullPointerException.class, () -> new ItemAnimationExtractionStatus(null, IDLE, 0, animated, none, false));
        assertThrows(NullPointerException.class, () -> new ItemAnimationExtractionStatus(MODEL, null, 0, animated, none, false));
        assertThrows(NullPointerException.class, () -> new ItemAnimationExtractionStatus(MODEL, IDLE, 0, null, none, false));
        assertThrows(NullPointerException.class, () -> new ItemAnimationExtractionStatus(MODEL, IDLE, 0, animated, null, false));
        assertThrows(IllegalArgumentException.class, () -> new ItemAnimationExtractionStatus(MODEL, IDLE, -1, animated, none, false));
        for (var outcome : ItemAnimationExtractionStatus.Outcome.values()) {
            for (var fallback : ItemAnimationExtractionStatus.Fallback.values()) {
                for (boolean current : new boolean[] {false, true}) {
                    if ((outcome == animated) == (fallback == none)) {
                        assertDoesNotThrow(() -> new ItemAnimationExtractionStatus(MODEL, IDLE, 0, outcome, fallback, current));
                    } else {
                        assertThrows(IllegalArgumentException.class,
                                () -> new ItemAnimationExtractionStatus(MODEL, IDLE, 0, outcome, fallback, current));
                    }
                }
            }
        }
    }
}
