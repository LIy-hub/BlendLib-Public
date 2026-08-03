package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.fail;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.testsupport.P3FixtureCatalog;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LoaderFuzzSmokeTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("fuzz:model");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("fuzz:blend_models/model.json");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("fuzz:models3d/model.glb");

    @Test
    void fixedSeedBoundedMutationsOnlySucceedOrReturnControlledDiagnostics() {
        ModelAssetLoader loader = new ModelAssetLoader();
        AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, ("""
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"fuzz:models3d/model.glb",
                 "materials":{"FixtureMaterial":{"base_color":"fuzz:textures/fuzz.png"}}}
                """).getBytes(StandardCharsets.UTF_8));
        SplittableRandom random = new SplittableRandom(0xB1E0D5EEDL);
        for (int caseIndex = 0; caseIndex < 512; caseIndex++) {
            byte[] bytes = P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE);
            int changes = 1 + random.nextInt(8);
            for (int change = 0; change < changes; change++) {
                int offset = random.nextInt(bytes.length);
                bytes[offset] ^= (byte) (1 + random.nextInt(255));
            }
            try {
                AssetBytes glb = new AssetBytes(MESH_ID, bytes);
                loader.load(MODEL_KEY, descriptor, id -> glb);
            } catch (BlendAssetLoadException expected) {
                // The expected malformed-input path is a stable structured diagnostic.
            } catch (ArrayIndexOutOfBoundsException | BufferUnderflowException | NegativeArraySizeException unexpected) {
                fail("Fuzz case " + caseIndex + " escaped as an unchecked parser/buffer error", unexpected);
            } catch (StackOverflowError | OutOfMemoryError unexpected) {
                fail("Fuzz case " + caseIndex + " escaped a bounded loader limit", unexpected);
            } catch (RuntimeException unexpected) {
                fail("Fuzz case " + caseIndex + " escaped without a BlendAssetLoadException", unexpected);
            }
        }

        // Exercise JSON decoding and chunk arithmetic at materially varied bounded sizes, not only
        // mutations of the tiny positive fixture. Each archive has a valid container header and an
        // arbitrary JSON payload, so it must either parse successfully or surface a controlled code.
        for (int caseIndex = 0; caseIndex < 128; caseIndex++) {
            int payloadLength = (1 + random.nextInt(64 * 1024 / 4)) * 4;
            byte[] bytes = new byte[20 + payloadLength];
            random.nextBytes(bytes);
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0, 0x46546C67).putInt(4, 2).putInt(8, bytes.length);
            header.putInt(12, payloadLength).putInt(16, 0x4E4F534A);
            try {
                AssetBytes glb = new AssetBytes(MESH_ID, bytes);
                loader.load(MODEL_KEY, descriptor, id -> glb);
            } catch (BlendAssetLoadException expected) {
                // Controlled diagnostic is the only malformed-input outcome.
            } catch (ArrayIndexOutOfBoundsException | BufferUnderflowException | NegativeArraySizeException unexpected) {
                fail("Variable-size fuzz case " + caseIndex + " escaped as an unchecked parser/buffer error", unexpected);
            } catch (StackOverflowError | OutOfMemoryError unexpected) {
                fail("Variable-size fuzz case " + caseIndex + " escaped a bounded loader limit", unexpected);
            } catch (RuntimeException unexpected) {
                fail("Variable-size fuzz case " + caseIndex + " escaped without a BlendAssetLoadException", unexpected);
            }
        }
    }
}
