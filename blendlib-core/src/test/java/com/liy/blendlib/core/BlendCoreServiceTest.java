package com.liy.blendlib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.liy.blendlib.api.BlendResourceId;
import org.junit.jupiter.api.Test;

class BlendCoreServiceTest {
    @Test
    void remainsAPlatformFreeMarkerService() {
        BlendResourceId id = BlendResourceId.parse("blendlib:marker");

        assertEquals("blendlib-core-p1", BlendCoreService.marker());
        assertSame(id, BlendCoreService.retain(id));
    }
}
