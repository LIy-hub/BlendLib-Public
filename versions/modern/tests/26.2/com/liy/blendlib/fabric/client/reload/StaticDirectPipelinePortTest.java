package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.PrimitiveTopology;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaticDirectPipelinePortTest {
    @Test void retainsPositionNormalUvByteLayoutOnNewVertexApi() {
        var pipeline = StaticDirectPipeline.buildCandidate();
        var format = pipeline.getVertexFormatBinding(0);
        assertEquals(32, format.getVertexSize());
        assertEquals(0, format.getElement("Position").offset());
        assertEquals(12, format.getElement("Normal").offset());
        assertEquals(24, format.getElement("UV0").offset());
        assertEquals(PrimitiveTopology.TRIANGLES, pipeline.getPrimitiveTopology());
    }
}
