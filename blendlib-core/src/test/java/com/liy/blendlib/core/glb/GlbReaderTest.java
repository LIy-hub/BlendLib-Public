package com.liy.blendlib.core.glb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.testsupport.P3FixtureCatalog;
import org.junit.jupiter.api.Test;

class GlbReaderTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("fixture:model");
    private static final BlendResourceId GLB_ID = BlendResourceId.parse("fixture:models3d/model.glb");

    @Test
    void readsStrictTwoChunkArchiveAndMapsHeaderAndLayoutFailuresExactly() {
        GlbReader reader = new GlbReader();
        GlbDocument document = reader.read(MODEL_KEY,
                new AssetBytes(GLB_ID, P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE)));
        assertEquals(104, document.binarySize());

        assertCode(reader, P3FixtureCatalog.GlbFixture.INVALID_HEADER, BlendDiagnosticCodes.GLB_001);
        assertCode(reader, P3FixtureCatalog.GlbFixture.DECLARED_LENGTH_MISMATCH, BlendDiagnosticCodes.GLB_001);
        assertCode(reader, P3FixtureCatalog.GlbFixture.CHUNK_OUT_OF_BOUNDS, BlendDiagnosticCodes.GLB_002);
    }

    private static void assertCode(GlbReader reader, P3FixtureCatalog.GlbFixture fixture, String expectedCode) {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                () -> reader.read(MODEL_KEY, new AssetBytes(GLB_ID, P3FixtureCatalog.glb(fixture))));
        assertEquals(expectedCode, exception.diagnostic().code());
    }
}
