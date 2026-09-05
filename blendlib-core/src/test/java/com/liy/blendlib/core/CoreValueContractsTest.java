package com.liy.blendlib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
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

    @Test
    void transformConstructorPublishesTheStableNormalizedQuaternionProjection() {
        Quaternion inBandPublicInput = new Quaternion(0.0F, 0.0F, 0.0F, Float.intBitsToFloat(0x3F800004));
        Quaternion stableProjection = inBandPublicInput.normalized();
        Transform published = new Transform(Vec3.ZERO, inBandPublicInput, Vec3.ONE);

        assertAllQuaternionBits(new int[] {0x00000000, 0x00000000, 0x00000000, 0x3F800000}, stableProjection);
        assertQuaternionBits(stableProjection, published.rotation(),
                "the public Transform constructor retains the parent normalized-value contract");
    }

    @Test
    void normalizedQuaternionTransformPublicationIsDeterministicAndMatchesTheStableProjectionAcrossFixedSeedAndSubnormalCases() {
        SplittableRandom random = new SplittableRandom(0x52_31_31_51_45_4e_44L);
        for (int index = 0; index < 100_000; index++) {
            float x = (float) random.nextDouble(-1.0D, 1.0D);
            float y = (float) random.nextDouble(-1.0D, 1.0D);
            float z = (float) random.nextDouble(-1.0D, 1.0D);
            float w = (float) random.nextDouble(0.25D, 1.0D);
            float subnormal = (index & 4) == 0 ? Float.MIN_VALUE : -Float.MIN_VALUE;
            switch (index & 3) {
                case 0 -> x = subnormal;
                case 1 -> y = subnormal;
                case 2 -> z = subnormal;
                default -> w = subnormal;
            }

            Quaternion canonicalInput = new Quaternion(x, y, z, w).normalized();
            Quaternion stableProjection = canonicalInput.normalized();
            Transform firstPublication = new Transform(Vec3.ZERO, canonicalInput, Vec3.ONE);
            Transform repeatedPublication = new Transform(Vec3.ZERO, canonicalInput, Vec3.ONE);
            assertQuaternionBits(stableProjection, firstPublication.rotation(), "stable projection case " + index);
            assertQuaternionBits(stableProjection, repeatedPublication.rotation(), "deterministic stable projection case " + index);
            assertEquals(firstPublication, repeatedPublication, "same public input must publish deterministically at case " + index);
        }
    }

    @Test
    void transformPublicationRetainsTheStableNormalizationGuardsSignsAndFarInputBehavior() {
        Quaternion obviousNonUnit = new Quaternion(0.0F, 0.0F, 0.0F, 2.0F);
        assertQuaternionBits(obviousNonUnit.normalized(), new Transform(Vec3.ZERO, obviousNonUnit, Vec3.ONE).rotation());
        assertThrows(IllegalArgumentException.class,
                () -> new Transform(Vec3.ZERO, new Quaternion(0.0F, 0.0F, 0.0F, 0.0F), Vec3.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Quaternion(Float.NaN, 0.0F, 0.0F, 1.0F));
        assertThrows(IllegalArgumentException.class, () -> new Quaternion(Float.NEGATIVE_INFINITY, 0.0F, 0.0F, 1.0F));
        assertThrows(NullPointerException.class, () -> new Transform(Vec3.ZERO, null, Vec3.ONE));

        Quaternion positive = new Quaternion(0.3F, -0.4F, 0.2F, 0.8F).normalized();
        Quaternion negative = new Quaternion(-positive.x(), -positive.y(), -positive.z(), -positive.w());
        Transform positiveTransform = new Transform(Vec3.ZERO, positive, Vec3.ONE);
        Transform negativeTransform = new Transform(Vec3.ZERO, negative, Vec3.ONE);
        assertQuaternionBits(positive.normalized(), positiveTransform.rotation());
        assertQuaternionBits(negative.normalized(), negativeTransform.rotation());
        assertNotEquals(positiveTransform.rotation(), negativeTransform.rotation(),
                "stable normalization retains q/-q distinction without a new hemisphere policy");

        Quaternion signedZero = new Quaternion(-0.0F, 0.0F, -0.0F, 1.0F);
        assertQuaternionBits(signedZero.normalized(), new Transform(Vec3.ZERO, signedZero, Vec3.ONE).rotation(),
                "signed-zero components follow the established Quaternion.normalized() projection");
    }

    private static Quaternion quaternion(int x, int y, int z, int w) {
        return new Quaternion(Float.intBitsToFloat(x), Float.intBitsToFloat(y), Float.intBitsToFloat(z),
                Float.intBitsToFloat(w));
    }

    private static void assertAllQuaternionBits(int[] expected, Quaternion actual) {
        assertAll(
                () -> assertEquals(expected[0], Float.floatToRawIntBits(actual.x())),
                () -> assertEquals(expected[1], Float.floatToRawIntBits(actual.y())),
                () -> assertEquals(expected[2], Float.floatToRawIntBits(actual.z())),
                () -> assertEquals(expected[3], Float.floatToRawIntBits(actual.w())));
    }

    private static void assertQuaternionBits(Quaternion expected, Quaternion actual) {
        assertQuaternionBits(expected, actual, "quaternion raw bits");
    }

    private static void assertQuaternionBits(Quaternion expected, Quaternion actual, String label) {
        assertAll(label,
                () -> assertEquals(Float.floatToRawIntBits(expected.x()), Float.floatToRawIntBits(actual.x())),
                () -> assertEquals(Float.floatToRawIntBits(expected.y()), Float.floatToRawIntBits(actual.y())),
                () -> assertEquals(Float.floatToRawIntBits(expected.z()), Float.floatToRawIntBits(actual.z())),
                () -> assertEquals(Float.floatToRawIntBits(expected.w()), Float.floatToRawIntBits(actual.w())));
    }
}
