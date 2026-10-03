package com.liy.blendlib.fixture.fabric;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.entity.BlendEntityRendererBuilder;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemMaterialAppearance;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import com.liy.blendlib.fabric.client.item.BlendLibItemSkinSelector;
import com.liy.blendlib.fabric.client.render.BlendLibModelSkins;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** Compile-only public client consumer, compiled against the real 26.3 adapter by the modern build. */
public final class NamedSkinConsumerFixture {
    private NamedSkinConsumerFixture() { }

    public static void defineAtStartup(BlendModelKey model,
            Map<BlendResourceId, Map<String, BlendResourceId>> skins) {
        BlendLibModelSkins.register(model, skins);
    }

    public static <E extends Entity> BlendEntityRendererBuilder<E> configureEntity(
            BlendEntityRendererBuilder<E> builder, BlendResourceId skin) {
        return builder.skin((entity, request) -> Optional.of(skin));
    }

    public static void configureItem(BlendLibItemBinding binding, BlendLibItemSkinSelector selector) {
        BlendLibItemModelBindings.registerWithSkin(binding, selector);
    }

    public static void configureItemAppearance(BlendLibItemBinding binding,
            BlendLibItemMaterialAppearance appearance, BlendLibItemSkinSelector selector) {
        BlendLibItemModelBindings.registerWithSkin(binding, appearance, selector);
    }

    /** The observer copies only diagnostic values; it does not retain stacks or snapshots. */
    public static BlendLibItemSkinSelector observing(BlendResourceId skin,
            BiConsumer<Optional<BlendResourceId>, Optional<String>> observer) {
        return new BlendLibItemSkinSelector() {
            @Override public Optional<BlendResourceId> select(ItemStack stack) { return Optional.of(skin); }
            @Override public void captured(ItemStack stack, ModelRenderSnapshot snapshot) {
                observer.accept(snapshot.selectedSkin(), snapshot.skinDiagnostic());
            }
        };
    }
}
