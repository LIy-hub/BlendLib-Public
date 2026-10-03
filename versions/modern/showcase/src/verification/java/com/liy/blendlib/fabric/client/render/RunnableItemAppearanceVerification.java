package com.liy.blendlib.fabric.client.render;

/** Package bridge for assertions against captured per-primitive appearance, never a shipped class. */
public final class RunnableItemAppearanceVerification {
    private RunnableItemAppearanceVerification() { }

    public static void verify(ModelRenderSnapshot authored, ModelRenderSnapshot orange, ModelRenderSnapshot blue) {
        require(orange.materialAppearance(0).rgbTint() == 0xff8844, "orange body capture");
        require(blue.materialAppearance(0).rgbTint() == 0x4488ff, "blue body capture");
        require(orange.materialAppearance(1).visible() && !blue.materialAppearance(1).visible(),
                "independent accessory visibility");
        var restored = blue.withMaterialAppearance(java.util.Map.of());
        require(restored.materialAppearance(1).visible(), "empty selection restores authored accessory");
        require(!blue.materialAppearance(1).visible() && orange.materialAppearance(1).visible(),
                "retained snapshots do not change after another instance or recapture");
        require(authored.materialAppearance(0).rgbTint() == 0xffffff && authored.materialAppearance(1).visible(),
                "base snapshot remains authored");
        require(authored.handle() == orange.handle() && orange.handle() == blue.handle(),
                "appearance never copies geometry or prepared handles");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
