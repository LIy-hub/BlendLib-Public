package com.liy.blendlib.fabric.client.item;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Runs against each generated legacy port, including both older binding overrides. */
class LegacyNamedSkinBindingTest {
    @Test void bothCallbacksSurvivePlainRegistrationsAndRejectConflictingReplacement() throws Exception {
        var binding = new BlendLibItemBinding(ResourceLocation.fromNamespaceAndPath("legacy_skins", "marker"),
                BlendModelKey.parse("legacy_skins:model"), ResourceLocation.withDefaultNamespace("item/stick"));
        BlendLibItemMaterialAppearance appearance = stack -> Map.of();
        BlendLibItemSkinSelector skin = stack -> Optional.empty();
        BlendLibItemModelBindings.register(binding, appearance);
        BlendLibItemModelBindings.registerWithSkin(binding, skin);
        BlendLibItemModelBindings.register(binding);
        BlendLibItemModelBindings.registerWithSkin(binding, appearance, skin);
        assertEquals(binding, BlendLibItemModelBindings.find(binding.itemId()).orElseThrow());
        assertThrows(IllegalStateException.class, () -> BlendLibItemModelBindings.registerWithSkin(binding, stack -> Optional.empty()));
        assertThrows(IllegalStateException.class, () -> BlendLibItemModelBindings.register(binding, stack -> Map.of()));
        var field = BlendLibItemModelBindings.class.getDeclaredField("BINDINGS");
        field.setAccessible(true);
        var registration = ((Map<?, ?>) field.get(null)).get(binding.itemId());
        var appearanceAccessor = registration.getClass().getDeclaredMethod("appearance");
        var skinAccessor = registration.getClass().getDeclaredMethod("skin");
        appearanceAccessor.setAccessible(true);
        skinAccessor.setAccessible(true);
        assertSame(appearance, appearanceAccessor.invoke(registration));
        assertSame(skin, skinAccessor.invoke(registration));
    }

    @Test void nativeSpecialRendererRetainsOldAndNewConstructorDescriptors() throws Exception {
        String minecraft = System.getProperty("blendlib.test.minecraft");
        if (java.util.Set.of("1.21.1", "1.21.2", "1.21.3").contains(minecraft)) return;
        var renderer = Class.forName("com.liy.blendlib.fabric.client.item.BlendLibItemSpecialRenderer");
        var unbaked = Class.forName("com.liy.blendlib.fabric.client.item.BlendLibItemSpecialRenderer$Unbaked");
        for (var type : new Class<?>[]{renderer, unbaked}) {
            assertNotNull(type.getDeclaredConstructor(BlendLibItemBinding.class));
            assertNotNull(type.getDeclaredConstructor(BlendLibItemBinding.class, BlendLibItemMaterialAppearance.class));
            assertNotNull(type.getDeclaredConstructor(BlendLibItemBinding.class, BlendLibItemMaterialAppearance.class, BlendLibItemSkinSelector.class));
        }
    }
}
