package com.liy.blendlib.fabric.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class X7PipelinePortTest {
    @Test void keepsUnusedNormalBytesInStaticProbeLayout() {
        assertLayout(X7Minecraft2612StaticPipeline.buildProbePipeline());
    }

    @Test void keepsTexelSkinningSourceLayout() {
        assertLayout(X7Minecraft2612SkinnedPipeline.buildCandidate());
    }

    private static void assertLayout(RenderPipeline pipeline) {
        var format = pipeline.getVertexFormatBinding(0);
        assertEquals(32, format.getVertexSize());
        assertEquals(0, format.getElement("Position").offset());
        assertEquals(24, format.getElement("UV0").offset());
        assertEquals(PrimitiveTopology.TRIANGLES, pipeline.getPrimitiveTopology());
    }
}
