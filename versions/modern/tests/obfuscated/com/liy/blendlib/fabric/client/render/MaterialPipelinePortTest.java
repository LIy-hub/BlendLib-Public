package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialPipelinePortTest {
    @BeforeAll static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void singleSidedCutoutRetainsVanillaBackFaceCulling() {
        var material = new RenderMaterial(BlendResourceId.parse("minecraft:textures/block/stone.png"),
                RenderLayer.CUTOUT, false, false, -1, false);
        assertTrue(new Minecraft2612StaticRigidRenderBackend().renderTypeFor(material).pipeline().isCull());
    }

    @Test void doubleSidedCutoutUsesTheActualNoCullPipeline() {
        var material = new RenderMaterial(BlendResourceId.parse("minecraft:textures/block/stone.png"),
                RenderLayer.CUTOUT, false, true, -1, false);
        assertFalse(new Minecraft2612StaticRigidRenderBackend().renderTypeFor(material).pipeline().isCull());
    }
}
