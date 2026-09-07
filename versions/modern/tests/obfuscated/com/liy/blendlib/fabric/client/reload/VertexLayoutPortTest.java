package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VertexLayoutPortTest {
    @Test void olderVertexApiPreservesPackedPositionNormalUvLayout() {
        var format = StaticDirectPipeline.buildCandidate().getVertexFormat();
        assertEquals(32, format.getVertexSize());
        assertEquals(0, format.getOffset(VertexFormatElement.POSITION));
        assertEquals(12, format.getOffset(StaticDirectPipeline.F32_NORMAL));
        assertEquals(24, format.getOffset(VertexFormatElement.UV0));
    }
}
