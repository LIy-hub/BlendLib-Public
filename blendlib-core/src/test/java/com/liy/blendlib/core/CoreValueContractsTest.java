package com.liy.blendlib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.Quaternion;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CoreValueContractsTest {
    @Test
    void assetBytesAndModelArraysAreDefensivelyImmutable() {
        byte[] source = {1, 2, 3};
        AssetBytes bytes = new AssetBytes(BlendResourceId.parse("fixture:models3d/model.glb"), source);
        source[0] = 9;
        assertEquals(1, bytes.copy()[0]);
        byte[] copy = bytes.copy();
        copy[1] = 9;
        assertEquals(2, bytes.copy()[1]);
        assertTrue(bytes.readOnlyBuffer().isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> bytes.readOnlyBuffer().put((byte) 4));

        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        MeshPrimitive primitive = new MeshPrimitive("Base", positions, new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2}, null, null);
        positions[0] = 99;
        assertEquals(0.0f, primitive.positions()[0]);
        float[] returned = primitive.positions();
        returned[0] = 42;
        assertEquals(0.0f, primitive.positions()[0]);
    }

    @Test
    void strictJsonRejectsMalformedUtf8DuplicateKeysAndInvalidExponent() {
        assertTrue(StrictJsonParser.parse("{\"a\":[1,true,null]}".getBytes(StandardCharsets.UTF_8)) instanceof JsonObject);
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse(new byte[] {(byte) 0xC3, (byte) 0x28}));
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("[1e+-2]"));
        StrictJsonParser.Limits lowLimits = new StrictJsonParser.Limits(4, 1, 1, 4, 8, 8, 128);
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("{\"a\":1,\"b\":2}", lowLimits));
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("[1,2]", lowLimits));
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("\"abcde\"", lowLimits));
    }

    @Test
    void strictJsonDefaultArrayLimitAcceptsTheBoundaryAndRejectsTheFirstExcessEntry() {
        String atLimit = zeroArray(16_384);
        assertEquals(16_384, ((JsonArray) StrictJsonParser.parse(atLimit)).size());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> StrictJsonParser.parse(zeroArray(16_385)));
        assertTrue(exception.getMessage().startsWith("JSON array entry count exceeds configured limit at JSON character "));
    }

    @Test
    void animationSamplingUsesLinearStepAndNormalizedQuaternionSlerp() {
        AnimationChannel linear = new AnimationChannel(0, AnimationPath.TRANSLATION, Interpolation.LINEAR,
                new float[] {0, 2}, new float[] {0, 0, 0, 4, 6, 8});
        assertEquals(2.0f, linear.sample(1.0f)[0]);
        assertEquals(3.0f, linear.sample(1.0f)[1]);
        assertEquals(4.0f, linear.sample(1.0f)[2]);

        AnimationChannel step = new AnimationChannel(0, AnimationPath.SCALE, Interpolation.STEP,
                new float[] {0, 1}, new float[] {1, 1, 1, 3, 3, 3});
        assertEquals(1.0f, step.sample(0.75f)[0]);

        AnimationChannel rotation = new AnimationChannel(0, AnimationPath.ROTATION, Interpolation.LINEAR,
                new float[] {0, 1}, new float[] {0, 0, 0, 1, 0, 1, 0, 0});
        float[] halfway = rotation.sample(0.5f);
        Quaternion quaternion = new Quaternion(halfway[0], halfway[1], halfway[2], halfway[3]);
        float norm = (float) Math.sqrt(quaternion.x() * quaternion.x() + quaternion.y() * quaternion.y()
                + quaternion.z() * quaternion.z() + quaternion.w() * quaternion.w());
        assertEquals(1.0f, norm, 1.0e-5f);
        assertFalse(Float.isNaN(halfway[1]));
    }

    private static String zeroArray(int count) {
        if (count == 0) {
            return "[]";
        }
        return "[" + "0,".repeat(count - 1) + "0]";
    }
}
