package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ItemAnimationVisualEventContractTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("item_event_contract:idle");
    private static final BlendModelKey MODEL = BlendModelKey.parse("item_event_contract:model");

    @Test void registrationIsAdditiveAndConflictsNeverReplaceTheInstalledHandler() {
        var binding = new BlendLibItemBinding(Identifier.parse("item_event_contract:registered"), MODEL,
                Identifier.withDefaultNamespace("item/stick"));
        ItemAnimationVisualEventHandler handler = (stack, event) -> { };
        BlendLibItemAnimations.register(binding, IDLE);
        BlendLibItemAnimations.register(binding, IDLE, handler);
        BlendLibItemAnimations.register(binding, IDLE, handler);
        BlendLibItemAnimations.register(binding, IDLE);
        assertThrows(IllegalStateException.class, () -> BlendLibItemAnimations.register(binding, IDLE, (stack, event) -> { }));
        assertThrows(IllegalStateException.class, () -> BlendLibItemAnimations.register(binding,
                BlendAnimationKey.parse("item_event_contract:other"), handler));
        assertThrows(IllegalStateException.class, () -> BlendLibItemAnimations.register(new BlendLibItemBinding(
                binding.itemId(), BlendModelKey.parse("item_event_contract:other"), binding.baseModelId()), IDLE, handler));
        assertThrows(NullPointerException.class, () -> BlendLibItemAnimations.register(binding, IDLE, null));
        assertThrows(NullPointerException.class, () -> BlendLibItemAnimations.register(null, IDLE, handler));
        assertThrows(NullPointerException.class, () -> BlendLibItemAnimations.register(binding, null, handler));
        BlendLibItemAnimations.register(binding, IDLE, handler);
        assertEquals(binding, BlendLibItemModelBindings.find(binding.itemId()).orElseThrow());
    }

    @Test void eventMetadataIsImmutableAndValidatesItsInputs() {
        var marker = new AnimationVisualEvent(.5, BlendResourceId.parse("item_event_contract:marker"));
        var event = new ItemAnimationVisualEvent(MODEL, IDLE, 0, marker);
        assertEquals(event, new ItemAnimationVisualEvent(MODEL, IDLE, 0, marker));
        assertTrue(Arrays.stream(ItemAnimationVisualEvent.class.getDeclaredFields()).allMatch(field ->
                Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers())));
        assertThrows(NullPointerException.class, () -> new ItemAnimationVisualEvent(null, IDLE, 0, marker));
        assertThrows(NullPointerException.class, () -> new ItemAnimationVisualEvent(MODEL, null, 0, marker));
        assertThrows(NullPointerException.class, () -> new ItemAnimationVisualEvent(MODEL, IDLE, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ItemAnimationVisualEvent(MODEL, IDLE, -1, marker));
    }
}
