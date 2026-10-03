package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ItemAnimationObservationTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("test:idle");
    private static final BlendModelKey MODEL = BlendModelKey.parse("test:item");

    @Test void valuesContainOnlyImmutableSemanticKeysEnumsAndScalarSampleMetadata() {
        for (var type : new Class<?>[] {ItemAnimationObservation.class, ItemAnimationObservation.Sample.class}) {
            assertTrue(type.isRecord());
            assertTrue(Modifier.isFinal(type.getModifiers()));
            assertTrue(Arrays.stream(type.getDeclaredFields())
                    .allMatch(field -> Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers())));
        }
        assertEquals(Set.of(BlendAnimationKey.class, ItemAnimationPlayback.Mode.class,
                        double.class, boolean.class, Optional.class), componentTypes(ItemAnimationObservation.class));
        assertEquals(Set.of(BlendModelKey.class, BlendAnimationKey.class, long.class, double.class),
                componentTypes(ItemAnimationObservation.Sample.class));
        var sample = new ItemAnimationObservation.Sample(MODEL, IDLE, 3, 0.5, 1);
        var first = new ItemAnimationObservation(IDLE, ItemAnimationPlayback.Mode.LOOP, 1, true,
                0.5, Optional.of(sample), true);
        var equivalent = new ItemAnimationObservation(IDLE, ItemAnimationPlayback.Mode.LOOP, 1, true,
                0.5, Optional.of(new ItemAnimationObservation.Sample(MODEL, IDLE, 3, 0.5, 1)), true);
        assertEquals(first, equivalent);
        assertEquals(first.hashCode(), equivalent.hashCode());
    }

    @Test void constructorsRejectNullInvalidNumbersAndImpossibleCurrentSampleClaims() {
        assertThrows(NullPointerException.class, () -> observation(null, ItemAnimationPlayback.Mode.LOOP, 1, 0, Optional.empty(), false));
        assertThrows(NullPointerException.class, () -> observation(IDLE, null, 1, 0, Optional.empty(), false));
        assertThrows(NullPointerException.class, () -> observation(IDLE, ItemAnimationPlayback.Mode.LOOP, 1, 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> observation(IDLE, ItemAnimationPlayback.Mode.LOOP, 1, 0, Optional.empty(), true));
        assertThrows(NullPointerException.class, () -> new ItemAnimationObservation.Sample(null, IDLE, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new ItemAnimationObservation.Sample(MODEL, null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ItemAnimationObservation.Sample(MODEL, IDLE, -1, 0, 0));
        for (double invalid : new double[] {-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> observation(IDLE, ItemAnimationPlayback.Mode.LOOP, invalid, 0, Optional.empty(), false));
            assertThrows(IllegalArgumentException.class, () -> observation(IDLE, ItemAnimationPlayback.Mode.LOOP, 1, invalid, Optional.empty(), false));
            assertThrows(IllegalArgumentException.class, () -> new ItemAnimationObservation.Sample(MODEL, IDLE, 0, invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> new ItemAnimationObservation.Sample(MODEL, IDLE, 0, 0, invalid));
        }
        assertDoesNotThrow(() -> observation(IDLE, ItemAnimationPlayback.Mode.LOOP, 0, Double.MAX_VALUE, Optional.empty(), false));
        assertDoesNotThrow(() -> new ItemAnimationObservation.Sample(MODEL, IDLE, 0, 0, 0));
    }

    private static ItemAnimationObservation observation(BlendAnimationKey animation, ItemAnimationPlayback.Mode mode,
            double speed, double seconds, Optional<ItemAnimationObservation.Sample> sample, boolean current) {
        return new ItemAnimationObservation(animation, mode, speed, true, seconds, sample, current);
    }

    private static Set<Class<?>> componentTypes(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getType()).collect(Collectors.toSet());
    }
}
