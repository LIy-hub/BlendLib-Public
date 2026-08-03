package com.liy.blendlib.core.glb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlbAccessorReaderTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("accessor:model");
    private static final BlendResourceId GLB_ID = BlendResourceId.parse("accessor:models3d/model.glb");

    @Test
    void acceptsAndRetainsExactFloatBoundsFromTheBinaryFixture() {
        GlbAccessorReader reader = reader(
                "{\"bufferView\":0,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\","
                        + "\"min\":[-1.5,0,2.25],\"max\":[4,3,8]}",
                floats(-1.5f, 3.0f, 2.25f, 4.0f, 0.0f, 8.0f));

        AccessorInfo info = reader.requireMinMax(0);

        assertEquals(List.of(-1.5d, 0.0d, 2.25d), info.minimumValues());
        assertEquals(List.of(4.0d, 3.0d, 8.0d), info.maximumValues());
        assertEquals(6, reader.readFloatElements(0, "VEC3").length);
        assertThrows(UnsupportedOperationException.class, () -> info.minimumValues().add(1.0d));
    }

    @Test
    void rejectsForbiddenNormalizedComponentTypesAtTheNormalizedPointer() {
        assertFailure("/accessors/0/normalized", accessor(5126, 1, "SCALAR", ",\"normalized\":true"), floats(1.0f));
        assertFailure("/accessors/0/normalized", accessor(5125, 1, "SCALAR", ",\"normalized\":true"), ints(1));
        assertFailure("/accessors/0/normalized", accessor(5126, 1, "SCALAR", ",\"normalized\":1"), floats(1.0f));

        assertEquals(true, reader(accessor(5123, 1, "SCALAR", ",\"normalized\":true"), shorts(1)).info(0).normalized());
    }

    @Test
    void enforcesUnsignedWeightNormalizationAtItsOwnField() {
        GlbAccessorReader valid = reader(accessor(5121, 1, "VEC4", ",\"normalized\":true"), bytes(255, 0, 0, 0));
        assertEquals(1.0f, valid.readWeightElements(0)[0]);

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                () -> reader(accessor(5121, 1, "VEC4", ""), bytes(255, 0, 0, 0)).readWeightElements(0));
        assertEquals(BlendDiagnosticCodes.GLB_015, exception.diagnostic().code());
        assertEquals("/accessors/0/normalized", exception.diagnostic().location());
    }

    @Test
    void rejectsMalformedBoundShapeTypeFinitenessAndOrderingWithExactPointers() {
        byte[] values = floats(1.0f, 2.0f, 3.0f);
        assertFailure("/accessors/0/min", accessor(5126, 1, "VEC3", ",\"min\":0"), values);
        assertFailure("/accessors/0/min", accessor(5126, 1, "VEC3", ",\"min\":[1,2]"), values);
        assertFailure("/accessors/0/min/1", accessor(5126, 1, "VEC3", ",\"min\":[1,\"two\",3]"), values);
        assertFailure("/accessors/0/min/0", accessor(5126, 1, "VEC3", ",\"min\":[1e999,2,3]"), values);
        assertFailure("/accessors/0/max/1",
                accessor(5126, 1, "VEC3", ",\"min\":[1,3,3],\"max\":[1,2,3]"), values);
        assertFailure("/accessors/0/min/0", accessor(5123, 1, "SCALAR", ",\"min\":[1.5]"), shorts(1));
        assertFailure("/accessors/0/max/0", accessor(5121, 1, "SCALAR", ",\"max\":[256]"), bytes(1));
        assertFailure("/accessors/0/count", accessor(5126, 0, "SCALAR", ""), new byte[0]);
    }

    @Test
    void rejectsDeclaredBoundsThatDoNotMatchDecodedBinaryExtrema() {
        byte[] values = floats(-2.0f, 4.0f, 3.0f, 8.0f);
        assertFailure("/accessors/0/min/0",
                accessor(5126, 2, "VEC2", ",\"min\":[-1,4],\"max\":[3,8]"), values);
        assertFailure("/accessors/0/max/1",
                accessor(5126, 2, "VEC2", ",\"min\":[-2,4],\"max\":[3,9]"), values);
    }

    @Test
    void requiresBothBoundsOnlyWhenTheAccessorUsageMandatesThem() {
        GlbAccessorReader optional = reader(accessor(5126, 1, "SCALAR", ""), floats(0.0f));
        assertEquals(false, optional.info(0).hasMinMax());
        assertRequiredFailure(optional, "/accessors/0/min");

        GlbAccessorReader missingMax = reader(
                accessor(5126, 1, "SCALAR", ",\"min\":[0]"), floats(0.0f));
        assertRequiredFailure(missingMax, "/accessors/0/max");
    }

    @Test
    void allAccessorPreflightIsCachedAndRejectsAggregateDeclaredBoundWorkBeforeScanning() {
        byte[] values = new byte[20 * Float.BYTES];
        String first = accessor(5126, 20, "SCALAR", ",\"min\":[0],\"max\":[0]");
        String second = accessor(5126, 20, "SCALAR", ",\"min\":[0],\"max\":[0]");
        BlendAssetLimits tiny = new BlendAssetLimits(1_024, 1, 1, 1, 1, 1, 1, 1, 1, 1.0, 1, 1);
        GlbAccessorReader reader = readerWithAccessors(first + "," + second, values, tiny);

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, reader::validateAll);

        assertEquals(BlendDiagnosticCodes.LIMIT_001, exception.diagnostic().code());
        assertEquals("/accessors/1/count", exception.diagnostic().location());

        GlbAccessorReader cached = reader(accessor(5126, 1, "SCALAR", ",\"min\":[0],\"max\":[0]"), floats(0.0f));
        cached.validateAll();
        assertSame(cached.info(0), cached.info(0));

        assertEquals(BlendAssetLimits.MAX_DECLARED_ACCESSORS,
                directReaderWithRepeatedAccessors(BlendAssetLimits.MAX_DECLARED_ACCESSORS).accessorCount());
        BlendAssetLoadException tooMany = assertThrows(BlendAssetLoadException.class,
                () -> directReaderWithRepeatedAccessors(BlendAssetLimits.MAX_DECLARED_ACCESSORS + 1));
        assertEquals(BlendDiagnosticCodes.LIMIT_001, tooMany.diagnostic().code());
        assertEquals("/accessors", tooMany.diagnostic().location());
    }

    private static void assertRequiredFailure(GlbAccessorReader reader, String location) {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, () -> reader.requireMinMax(0));
        assertEquals(BlendDiagnosticCodes.GLB_015, exception.diagnostic().code());
        assertEquals(location, exception.diagnostic().location());
    }

    private static void assertFailure(String location, String accessorJson, byte[] binary) {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, () -> reader(accessorJson, binary).info(0));
        assertEquals(BlendDiagnosticCodes.GLB_015, exception.diagnostic().code());
        assertEquals(location, exception.diagnostic().location());
    }

    private static String accessor(int componentType, int count, String type, String additionalFields) {
        return "{\"bufferView\":0,\"componentType\":" + componentType + ",\"count\":" + count
                + ",\"type\":\"" + type + "\"" + additionalFields + "}";
    }

    private static GlbAccessorReader reader(String accessorJson, byte[] binary) {
        return readerWithAccessors(accessorJson, binary, BlendAssetLimits.DEFAULT);
    }

    private static GlbAccessorReader readerWithAccessors(
            String accessorJson, byte[] binary, BlendAssetLimits limits) {
        String json = "{\"buffers\":[{\"byteLength\":" + binary.length + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + binary.length + "}],"
                + "\"accessors\":[" + accessorJson + "]}";
        JsonObject root = (JsonObject) StrictJsonParser.parse(json);
        return new GlbAccessorReader(MODEL_KEY, GLB_ID, new GlbDocument(root, binary), limits);
    }

    private static GlbAccessorReader directReaderWithRepeatedAccessors(int count) {
        byte[] binary = floats(0.0f);
        String templateJson = "{\"buffers\":[{\"byteLength\":4}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":4}],"
                + "\"accessors\":[]}";
        JsonObject template = (JsonObject) StrictJsonParser.parse(templateJson);
        JsonObject accessor = (JsonObject) StrictJsonParser.parse(
                "{\"bufferView\":0,\"componentType\":5126,\"count\":1,\"type\":\"SCALAR\"}");
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>(template.values());
        values.put("accessors", new JsonArray(Collections.nCopies(count, accessor)));
        return new GlbAccessorReader(MODEL_KEY, GLB_ID,
                new GlbDocument(new JsonObject(values), binary), BlendAssetLimits.DEFAULT);
    }

    private static byte[] floats(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static byte[] ints(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    private static byte[] shorts(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
