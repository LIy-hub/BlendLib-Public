package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.render.BlendLibModelSkins;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/** Startup definitions and pure extraction-time name selection for two shared authored textures. */
public final class ExampleNamedSkins {
    public static final BlendResourceId EMBER = id("ember");
    public static final BlendResourceId FROST = id("frost");
    public static final BlendResourceId EMBER_TEXTURE = id("textures/skins/ember.png");
    public static final BlendResourceId FROST_TEXTURE = id("textures/skins/frost.png");

    private ExampleNamedSkins() { }

    /** Called once during client initialization, before the first model reload. */
    public static void register() {
        BlendLibModelSkins.register(ExampleContent.APPEARANCE_ACTOR_MODEL, definitions(false));
        BlendLibModelSkins.register(ExampleLocomotionScene.MODEL, definitions(false));
        BlendLibModelSkins.register(ExampleBlendSpaceScene.MODEL, definitions(false));
        BlendLibModelSkins.register(ExampleDirectionalScene.MODEL, definitions(false));
        BlendLibModelSkins.register(ExampleContent.APPEARANCE_WAND_MODEL, definitions(true));
    }

    /** Exact authored slots from the committed two-primitive actor and wand GLBs. */
    public static Map<BlendResourceId, Map<String, BlendResourceId>> definitions(boolean wand) {
        String body = wand ? ExampleItemMaterialAppearance.BODY_SLOT : ExampleMaterialAppearance.BODY_SLOT;
        String accessory = wand ? ExampleItemMaterialAppearance.ACCESSORY_SLOT : ExampleMaterialAppearance.ACCESSORY_SLOT;
        return Map.of(EMBER, Map.of(body, EMBER_TEXTURE, accessory, FROST_TEXTURE),
                FROST, Map.of(body, FROST_TEXTURE, accessory, EMBER_TEXTURE));
    }

    public static Optional<BlendResourceId> select(ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        return forName(name == null ? null : name.getString());
    }

    /** The optional suffix is delegated to the independent RGB/visibility example. */
    public static Optional<BlendResourceId> forName(String customName) {
        if (customName == null) return Optional.empty();
        String name = customName.toLowerCase(Locale.ROOT);
        if (name.equals("ember") || name.startsWith("ember ")) return Optional.of(EMBER);
        if (name.equals("frost") || name.startsWith("frost ")) return Optional.of(FROST);
        return Optional.empty();
    }

    /** Plain Orange/Blue names retain their old behavior; a skin prefix is opt-in only. */
    public static String appearanceName(String customName) {
        if (forName(customName).isEmpty()) return customName;
        int separator = customName.indexOf(' ');
        return separator < 0 ? null : customName.substring(separator + 1);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse(ExampleContent.MOD_ID + ":" + path);
    }
}
