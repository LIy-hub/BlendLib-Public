package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.examples.runnable.ExampleItemMaterialAppearance;
import com.liy.blendlib.examples.runnable.ExampleMaterialAppearance;
import com.liy.blendlib.examples.runnable.ExampleNamedSkins;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

/** Test-only package bridge against actual packaged GLBs and the live pure selection helpers. */
public final class RunnableNamedSkinVerification {
    private RunnableNamedSkinVerification() { }

    public static void verify(ModelAsset actor, ModelAsset wand) {
        verifyTextures();
        verifyModel(actor, false);
        verifyModel(wand, true);
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            require(ExampleNamedSkins.forName("FROST Blue bare").equals(Optional.of(ExampleNamedSkins.FROST)),
                    "named selection is locale independent");
        } finally { Locale.setDefault(previous); }
        require(ExampleNamedSkins.forName(null).isEmpty() && ExampleNamedSkins.forName("Orange").isEmpty()
                && ExampleNamedSkins.forName("Emberish").isEmpty(), "unnamed and unrecognized names retain authored textures");
        require(ExampleNamedSkins.appearanceName("Blue bare").equals("Blue bare")
                && ExampleNamedSkins.appearanceName("Ember") == null,
                "appearance suffix parsing preserves existing names and identity defaults");
        System.out.println("Verified named-skin PNGs, live actor/wand selectors, real slots, shared CPU capture, appearance composition and fallback");
    }

    private static void verifyModel(ModelAsset asset, boolean wand) {
        var definitions = ExampleNamedSkins.definitions(wand);
        String body = wand ? ExampleItemMaterialAppearance.BODY_SLOT : ExampleMaterialAppearance.BODY_SLOT;
        String accessory = wand ? ExampleItemMaterialAppearance.ACCESSORY_SLOT : ExampleMaterialAppearance.ACCESSORY_SLOT;
        require(asset.primitives().stream().map(p -> p.geometry().materialSlot()).toList().equals(List.of(body, accessory)),
                "skin slots match the actual two GLB primitives");
        require(definitions.values().stream().allMatch(slots -> slots.keySet().equals(java.util.Set.of(body, accessory))),
                "both skins replace the exact body/accessory pair");
        try { definitions.clear(); throw new AssertionError("mutable skin definitions"); }
        catch (UnsupportedOperationException expected) { }
        try { definitions.get(ExampleNamedSkins.EMBER).clear(); throw new AssertionError("mutable skin replacements"); }
        catch (UnsupportedOperationException expected) { }
        var key = BlendModelKey.parse(asset.modelKey().value());
        var handle = SkinnedRenderHandle.prepareWithSkins(key, asset, definitions, Map.of());
        require(handle.namedSkins().materials().size() == 2 && handle.namedSkins().diagnostics().isEmpty(),
                "actual named definitions compile without unknown-slot diagnostics");
        var transforms = new HashMap<Integer, Transform>();
        asset.nodes().forEach(node -> transforms.put(node.index(), node.localTransform()));
        var nodes = NodePalette.from(new LocalPose(transforms), asset.nodes());
        var outputs = handle.skinnedPrimitives().stream().map(primitive -> CpuSkinner.skin(primitive.geometry(),
                SkinPalette.from(asset.skeleton().skins().get(primitive.skinIndex()), nodes))).toList();
        var captured = SkinnedRenderSnapshot.capture(handle, outputs);
        var base = ModelRenderSnapshot.skinned(handle, Transform.IDENTITY, 0, 0, 0xffffffff,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true), captured);
        var ember = base.withSkin(ExampleNamedSkins.forName("Ember"));
        var frost = base.withSkin(ExampleNamedSkins.forName("Frost"));
        require(ember.handle() == frost.handle() && ember.skinnedRenderSnapshot() == frost.skinnedRenderSnapshot(),
                "different instances share the exact prepared handle and captured geometry");
        for (int i = 0; i < 2; i++) {
            var authored = handle.skinnedPrimitives().get(i).material();
            require(ember.material(i, authored).textureId().equals(i == 0 ? ExampleNamedSkins.EMBER_TEXTURE : ExampleNamedSkins.FROST_TEXTURE),
                    "ember captures opposite body/accessory textures");
            require(frost.material(i, authored).textureId().equals(i == 0 ? ExampleNamedSkins.FROST_TEXTURE : ExampleNamedSkins.EMBER_TEXTURE),
                    "frost captures its own textures without changing ember");
            require(base.material(i, authored).equals(authored), "base snapshot stays authored");
        }
        String suffix = ExampleNamedSkins.appearanceName("Frost Blue bare");
        var appearance = wand ? ExampleItemMaterialAppearance.forName(suffix) : ExampleMaterialAppearance.forName(suffix);
        var composed = frost.withMaterialAppearance(appearance).withLighting(10, 20).withAttachments(List.of());
        require(composed.selectedSkin().equals(Optional.of(ExampleNamedSkins.FROST)) && composed.skinDiagnostic().isEmpty()
                && composed.materialAppearance(0).rgbTint() == 0x4488ff && !composed.materialAppearance(1).visible(),
                "RGB and visibility layer over named textures and survive snapshot copies");
        require(composed.culling().equals(base.culling()) && composed.skinnedRenderSnapshot() == captured,
                "appearance does not change conservative bounds or captured skinning");
        var unknown = BlendResourceId.parse("blendlib_runnable_examples:unregistered");
        var fallback = ember.withSkin(Optional.of(unknown));
        require(fallback.selectedSkin().equals(Optional.of(unknown)) && fallback.skinDiagnostic().isPresent(),
                "unknown selection exposes requested name and captured fallback diagnostic");
        for (int i = 0; i < 2; i++) {
            var authored = handle.skinnedPrimitives().get(i).material();
            require(fallback.material(i, authored).equals(authored), "unknown skin fallback is atomic");
        }
        var restored = ember.withSkin(Optional.empty());
        require(restored.selectedSkin().isEmpty() && restored.skinDiagnostic().isEmpty(), "empty selection clears skin capture");
    }

    private static void verifyTextures() {
        Integer emberPixel = null;
        for (var texture : List.of(ExampleNamedSkins.EMBER_TEXTURE, ExampleNamedSkins.FROST_TEXTURE)) {
            String path = "/assets/" + texture.namespace() + "/" + texture.path();
            try (var input = RunnableNamedSkinVerification.class.getResourceAsStream(path)) {
                require(input != null, "named texture is present in the packaged example JAR: " + path);
                var image = ImageIO.read(input);
                require(image != null && image.getWidth() == 8 && image.getHeight() == 8, "authored skin texture decodes as 8x8 PNG");
                for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                    require((image.getRGB(x, y) >>> 24) == 255, "texture-only demo keeps every pixel opaque");
                }
                int pixel = image.getRGB(0, 0);
                if (emberPixel == null) emberPixel = pixel;
                else require(emberPixel != pixel, "skins contain visibly distinct authored colors");
            } catch (IOException failure) { throw new AssertionError("Cannot decode packaged skin texture", failure); }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
