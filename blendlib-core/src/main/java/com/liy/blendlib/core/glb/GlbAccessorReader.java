package com.liy.blendlib.core.glb;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonBoolean;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounds-first reader for the one embedded GLB buffer accepted by v1.
 *
 * <p>All ranges are checked with {@code long} arithmetic before a byte buffer
 * is indexed. Therefore malformed layouts surface as controlled diagnostics
 * rather than NIO, array, or allocation exceptions.</p>
 */
public final class GlbAccessorReader {
    private static final int FLOAT = 5126;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;

    private final BlendResourceId modelKey;
    private final BlendResourceId resourceId;
    private final JsonObject root;
    private final byte[] binary;
    private final BlendAssetLimits limits;
    private final JsonArray bufferViews;
    private final JsonArray accessors;
    private final AccessorInfo[] infoCache;
    private final boolean[] declaredBoundsValidated;
    private final int declaredBufferLength;

    public GlbAccessorReader(
            BlendResourceId modelKey, BlendResourceId resourceId, GlbDocument document, BlendAssetLimits limits) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.root = Objects.requireNonNull(document, "document").json();
        this.binary = document.binaryCopy();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.bufferViews = array(required(root, "bufferViews", "/bufferViews"), "/bufferViews");
        this.accessors = array(required(root, "accessors", "/accessors"), "/accessors");
        if (accessors.size() > BlendAssetLimits.MAX_DECLARED_ACCESSORS) {
            throw limit("/accessors", "Accessor declaration limit exceeded");
        }
        this.infoCache = new AccessorInfo[accessors.size()];
        this.declaredBoundsValidated = new boolean[accessors.size()];
        JsonArray buffers = array(required(root, "buffers", "/buffers"), "/buffers");
        if (buffers.size() != 1) {
            throw invalidLayout("/buffers", "v1 requires exactly one embedded GLB buffer");
        }
        JsonObject buffer = object(buffers.get(0), "/buffers/0");
        if (buffer.containsKey("uri")) {
            throw invalidLayout("/buffers/0/uri", "External GLB buffers are not supported");
        }
        this.declaredBufferLength = nonNegativeInt(required(buffer, "byteLength", "/buffers/0/byteLength"), "/buffers/0/byteLength");
        if (declaredBufferLength > binary.length) {
            throw bounds("/buffers/0/byteLength", "Declared GLB buffer exceeds BIN chunk");
        }
    }

    public int accessorCount() {
        return accessors.size();
    }

    /** Returns a fully checked layout and verifies declared extrema without copying a typed result array. */
    public AccessorInfo info(int accessorIndex) {
        AccessorInfo result = layoutInfo(accessorIndex);
        validateDeclaredBoundsOnce(result);
        return result;
    }

    /**
     * Deterministically validates every declaration before the loader follows any accessor reference.
     * Layout and metadata shape are cached first; an aggregate limit is then checked before any BIN
     * extrema scan, so overlapping unused accessors cannot multiply work without a hard bound.
     */
    public void validateAll() {
        long boundComponents = 0L;
        long maximumBoundComponents = maximumDeclaredBoundComponents();
        for (int accessorIndex = 0; accessorIndex < accessors.size(); accessorIndex++) {
            AccessorInfo info = layoutInfo(accessorIndex);
            if (info.minimumValues().isEmpty() && info.maximumValues().isEmpty()) {
                continue;
            }
            long components = (long) info.count() * info.componentCount();
            try {
                boundComponents = Math.addExact(boundComponents, components);
            } catch (ArithmeticException exception) {
                throw limit(pointer(accessorIndex, "count"), "Accessor declared-bound validation budget exceeded");
            }
            if (boundComponents > maximumBoundComponents) {
                throw limit(pointer(accessorIndex, "count"), "Accessor declared-bound validation budget exceeded");
            }
        }
        for (int accessorIndex = 0; accessorIndex < accessors.size(); accessorIndex++) {
            validateDeclaredBoundsOnce(infoCache[accessorIndex]);
        }
    }

    private AccessorInfo layoutInfo(int accessorIndex) {
        if (accessorIndex < 0 || accessorIndex >= accessors.size()) {
            throw bounds("/accessors/" + accessorIndex, "GLB array index is outside allowed bounds");
        }
        AccessorInfo cached = infoCache[accessorIndex];
        if (cached != null) {
            return cached;
        }
        JsonObject accessor = object(at(accessors, accessorIndex, "/accessors"), "/accessors/" + accessorIndex);
        if (accessor.containsKey("sparse")) {
            throw invalidData("/accessors/" + accessorIndex + "/sparse", "Sparse accessors are not supported", null);
        }
        int viewIndex = nonNegativeInt(required(accessor, "bufferView", pointer(accessorIndex, "bufferView")), pointer(accessorIndex, "bufferView"));
        JsonObject view = object(at(bufferViews, viewIndex, "/bufferViews"), "/bufferViews/" + viewIndex);
        if (view.containsKey("extensions")) {
            JsonObject extensions = object(view.get("extensions"), "/bufferViews/" + viewIndex + "/extensions");
            if (extensions.size() > 0) {
                throw invalidData("/bufferViews/" + viewIndex + "/extensions", "Buffer-view extensions are not supported by v1", null);
            }
        }
        int bufferIndex = nonNegativeInt(required(view, "buffer", "/bufferViews/" + viewIndex + "/buffer"),
                "/bufferViews/" + viewIndex + "/buffer");
        if (bufferIndex != 0) {
            throw invalidLayout("/bufferViews/" + viewIndex + "/buffer", "v1 accepts only the embedded buffer 0");
        }
        int viewOffset = optionalNonNegativeInt(view, "byteOffset", 0, "/bufferViews/" + viewIndex + "/byteOffset");
        int viewLength = nonNegativeInt(required(view, "byteLength", "/bufferViews/" + viewIndex + "/byteLength"),
                "/bufferViews/" + viewIndex + "/byteLength");
        long viewEnd = (long) viewOffset + viewLength;
        if (viewEnd > declaredBufferLength || viewEnd > binary.length || viewEnd < viewOffset) {
            throw bounds("/bufferViews/" + viewIndex, "Buffer view exceeds the embedded buffer");
        }
        int count = positiveInt(required(accessor, "count", pointer(accessorIndex, "count")), pointer(accessorIndex, "count"));
        String type = string(required(accessor, "type", pointer(accessorIndex, "type")), pointer(accessorIndex, "type"));
        int componentCount = componentCount(type, pointer(accessorIndex, "type"));
        int componentType = nonNegativeInt(required(accessor, "componentType", pointer(accessorIndex, "componentType")),
                pointer(accessorIndex, "componentType"));
        int componentByteSize = componentByteSize(componentType, pointer(accessorIndex, "componentType"));
        int elementByteSize;
        try {
            elementByteSize = Math.multiplyExact(componentCount, componentByteSize);
        } catch (ArithmeticException exception) {
            throw invalidData(pointer(accessorIndex, "type"), "Accessor element size overflows", exception);
        }
        int accessorOffset = optionalNonNegativeInt(accessor, "byteOffset", 0, pointer(accessorIndex, "byteOffset"));
        int byteStride = optionalNonNegativeInt(view, "byteStride", elementByteSize, "/bufferViews/" + viewIndex + "/byteStride");
        if (byteStride < elementByteSize || byteStride > 252) {
            throw invalidData("/bufferViews/" + viewIndex + "/byteStride", "Accessor byteStride is invalid for v1", null);
        }
        if (viewOffset % componentByteSize != 0 || accessorOffset % componentByteSize != 0
                || byteStride % componentByteSize != 0) {
            throw invalidData("/accessors/" + accessorIndex,
                    "Buffer-view offset, accessor offset, and stride must align to the component size", null);
        }
        boolean normalized = optionalBoolean(accessor, "normalized", false, pointer(accessorIndex, "normalized"));
        if (normalized && (componentType == FLOAT || componentType == UNSIGNED_INT)) {
            throw invalidData(pointer(accessorIndex, "normalized"),
                    "normalized must not be true for FLOAT or UNSIGNED_INT accessors", null);
        }
        long first = (long) viewOffset + accessorOffset;
        long end = first;
        if (count > 0) {
            end = first + (long) (count - 1) * byteStride + elementByteSize;
        }
        if (first < viewOffset || first > viewEnd || end > viewEnd || end > binary.length || end < first) {
            throw bounds("/accessors/" + accessorIndex, "Accessor extends outside its buffer view");
        }
        List<Double> minimum = optionalBounds(accessor, accessorIndex, "min", componentCount, componentType);
        List<Double> maximum = optionalBounds(accessor, accessorIndex, "max", componentCount, componentType);
        for (int component = 0; component < componentCount; component++) {
            if (!minimum.isEmpty() && !maximum.isEmpty() && minimum.get(component) > maximum.get(component)) {
                throw invalidData(pointer(accessorIndex, "max") + "/" + component,
                        "Accessor maximum must not be less than its minimum", null);
            }
        }
        AccessorInfo result = new AccessorInfo(accessorIndex, count, type, componentType, normalized, componentCount,
                componentByteSize, elementByteSize, byteStride, (int) first, minimum, maximum);
        infoCache[accessorIndex] = result;
        return result;
    }

    /** Requires the two bounds arrays mandated for POSITION and animation-input accessors. */
    public AccessorInfo requireMinMax(int accessorIndex) {
        AccessorInfo info = info(accessorIndex);
        if (info.minimumValues().isEmpty()) {
            throw invalidData(pointer(accessorIndex, "min"), "Accessor min is required for this usage", null);
        }
        if (info.maximumValues().isEmpty()) {
            throw invalidData(pointer(accessorIndex, "max"), "Accessor max is required for this usage", null);
        }
        return info;
    }

    /** Rejects normalization for usages such as indices and JOINTS_0. */
    public AccessorInfo requireUnnormalized(int accessorIndex, String usage) {
        AccessorInfo info = info(accessorIndex);
        if (info.normalized()) {
            throw invalidData(pointer(accessorIndex, "normalized"), usage + " must not be normalized", null);
        }
        return info;
    }

    /** Reads finite floating-point elements with the exact expected glTF type. */
    public float[] readFloatElements(int accessorIndex, String expectedType) {
        AccessorInfo info = info(accessorIndex);
        requireType(info, expectedType);
        if (info.componentType() != FLOAT) {
            throw invalidData(pointer(accessorIndex, "componentType"), "Accessor must use FLOAT components", null);
        }
        long resultLength = (long) info.count() * info.componentCount();
        long maximumValues = Math.max((long) limits.maxVertices() * 4L, (long) limits.maxKeyframeSamples() * 4L);
        if (resultLength > maximumValues || resultLength > Integer.MAX_VALUE) {
            throw limit(pointer(accessorIndex, "count"), "Accessor value limit exceeded");
        }
        float[] result = new float[(int) resultLength];
        ByteBuffer buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        int output = 0;
        for (int element = 0; element < info.count(); element++) {
            int base = info.firstByteOffset() + element * info.byteStride();
            for (int component = 0; component < info.componentCount(); component++) {
                float value = buffer.getFloat(base + component * Float.BYTES);
                if (!Float.isFinite(value)) {
                    throw invalidData("/accessors/" + accessorIndex, "Accessor contains NaN or infinity", null);
                }
                result[output++] = value;
            }
        }
        return result;
    }

    /** Reads unsigned scalar data, including U16 and U32 index accessors. */
    public int[] readUnsignedElements(int accessorIndex, String expectedType) {
        AccessorInfo info = info(accessorIndex);
        requireType(info, expectedType);
        if (info.componentType() != UNSIGNED_BYTE && info.componentType() != UNSIGNED_SHORT && info.componentType() != UNSIGNED_INT) {
            throw invalidData(pointer(accessorIndex, "componentType"), "Accessor must use an unsigned integer component type", null);
        }
        return readUnsignedElements(accessorIndex, info);
    }

    /** Reads a strict v1 index accessor, which may only use U16 or U32 components. */
    public int[] readIndexElements(int accessorIndex) {
        AccessorInfo info = requireUnnormalized(accessorIndex, "Triangle index accessor");
        requireType(info, "SCALAR");
        if (info.componentType() != UNSIGNED_SHORT && info.componentType() != UNSIGNED_INT) {
            throw invalidData(pointer(accessorIndex, "componentType"), "Triangle indices must use U16 or U32 components", null);
        }
        return readUnsignedElements(accessorIndex, info);
    }

    private int[] readUnsignedElements(int accessorIndex, AccessorInfo info) {
        long resultLength = (long) info.count() * info.componentCount();
        long maximumValues = Math.max((long) limits.maxIndices(), (long) limits.maxVertices() * 4L);
        if (resultLength > maximumValues || resultLength > Integer.MAX_VALUE) {
            throw limit(pointer(accessorIndex, "count"), "Accessor element limit exceeded");
        }
        int[] result = new int[(int) resultLength];
        ByteBuffer buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        int output = 0;
        for (int element = 0; element < info.count(); element++) {
            int base = info.firstByteOffset() + element * info.byteStride();
            for (int component = 0; component < info.componentCount(); component++) {
                long unsigned = switch (info.componentType()) {
                    case UNSIGNED_BYTE -> Byte.toUnsignedInt(buffer.get(base + component));
                    case UNSIGNED_SHORT -> Short.toUnsignedInt(buffer.getShort(base + component * Short.BYTES));
                    case UNSIGNED_INT -> Integer.toUnsignedLong(buffer.getInt(base + component * Integer.BYTES));
                    default -> throw new AssertionError("validated component type");
                };
                if (unsigned > Integer.MAX_VALUE) {
                    throw invalidData("/accessors/" + accessorIndex, "Unsigned accessor value exceeds supported range", null);
                }
                result[output++] = (int) unsigned;
            }
        }
        return result;
    }

    /** Reads FLOAT or normalized unsigned weights into finite floats. */
    public float[] readWeightElements(int accessorIndex) {
        AccessorInfo info = info(accessorIndex);
        requireType(info, "VEC4");
        if (info.componentType() == FLOAT) {
            return readFloatElements(accessorIndex, "VEC4");
        }
        if (info.componentType() != UNSIGNED_BYTE && info.componentType() != UNSIGNED_SHORT) {
            throw invalidData(pointer(accessorIndex, "componentType"), "Weights must be FLOAT or normalized U8/U16", null);
        }
        if (!info.normalized()) {
            throw invalidData(pointer(accessorIndex, "normalized"), "Unsigned weight accessors must be normalized", null);
        }
        int[] integers = readUnsignedElements(accessorIndex, "VEC4");
        float divisor = info.componentType() == UNSIGNED_BYTE ? 255.0f : 65_535.0f;
        float[] result = new float[integers.length];
        for (int index = 0; index < integers.length; index++) {
            result[index] = integers[index] / divisor;
        }
        return result;
    }

    private JsonValue required(JsonObject object, String key, String location) {
        JsonValue value = object.get(key);
        if (value == null) {
            throw invalidLayout(location, "Required GLB field is missing");
        }
        return value;
    }

    private JsonValue at(JsonArray array, int index, String location) {
        if (index < 0 || index >= array.size()) {
            throw bounds(location + "/" + index, "GLB array index is outside allowed bounds");
        }
        return array.get(index);
    }

    private JsonObject object(JsonValue value, String location) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw invalidLayout(location, "Expected JSON object");
    }

    private JsonArray array(JsonValue value, String location) {
        if (value instanceof JsonArray array) {
            return array;
        }
        throw invalidLayout(location, "Expected JSON array");
    }

    private String string(JsonValue value, String location) {
        if (value instanceof JsonString string) {
            return string.value();
        }
        throw invalidData(location, "Expected JSON string", null);
    }

    private int nonNegativeInt(JsonValue value, String location) {
        if (!(value instanceof JsonNumber number)) {
            throw invalidData(location, "Expected integer JSON number", null);
        }
        try {
            int result = number.asIntExact();
            if (result < 0) {
                throw new IllegalArgumentException("negative");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw invalidData(location, "Expected non-negative 32-bit integer", exception);
        }
    }

    private int optionalNonNegativeInt(JsonObject object, String key, int fallback, String location) {
        return object.containsKey(key) ? nonNegativeInt(object.get(key), location) : fallback;
    }

    private int positiveInt(JsonValue value, String location) {
        int result = nonNegativeInt(value, location);
        if (result == 0) {
            throw invalidData(location, "Expected a positive 32-bit integer", null);
        }
        return result;
    }

    private boolean optionalBoolean(JsonObject object, String key, boolean fallback, String location) {
        if (!object.containsKey(key)) {
            return fallback;
        }
        if (object.get(key) instanceof JsonBoolean value) {
            return value.value();
        }
        throw invalidData(location, "Expected JSON boolean", null);
    }

    private int componentCount(String type, String location) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw invalidData(location, "Unsupported accessor type", null);
        };
    }

    private int componentByteSize(int componentType, String location) {
        return switch (componentType) {
            case UNSIGNED_BYTE -> 1;
            case UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT, FLOAT -> 4;
            default -> throw invalidData(location, "Unsupported accessor component type", null);
        };
    }

    private List<Double> optionalBounds(
            JsonObject accessor, int accessorIndex, String field, int componentCount, int componentType) {
        if (!accessor.containsKey(field)) {
            return List.of();
        }
        String location = pointer(accessorIndex, field);
        JsonArray values = arrayForData(accessor.get(field), location);
        if (values.size() != componentCount) {
            throw invalidData(location, "Accessor " + field + " length must equal its component count", null);
        }
        List<Double> result = new ArrayList<>(componentCount);
        for (int component = 0; component < componentCount; component++) {
            String componentLocation = location + "/" + component;
            JsonValue value = values.get(component);
            if (!(value instanceof JsonNumber number)) {
                throw invalidData(componentLocation, "Accessor bound must be a JSON number", null);
            }
            double parsed;
            try {
                parsed = number.asDouble();
            } catch (NumberFormatException exception) {
                throw invalidData(componentLocation, "Accessor bound must be a finite number", exception);
            }
            if (!Double.isFinite(parsed)) {
                throw invalidData(componentLocation, "Accessor bound must be finite", null);
            }
            if (componentType == FLOAT) {
                float rounded = (float) parsed;
                if (!Float.isFinite(rounded)) {
                    throw invalidData(componentLocation, "Accessor FLOAT bound must be finite and representable", null);
                }
                result.add((double) rounded);
                continue;
            }
            long maximum = switch (componentType) {
                case UNSIGNED_BYTE -> 0xFFL;
                case UNSIGNED_SHORT -> 0xFFFFL;
                case UNSIGNED_INT -> 0xFFFF_FFFFL;
                default -> throw new AssertionError("validated component type");
            };
            if (parsed < 0.0d || parsed > maximum || parsed != Math.rint(parsed)) {
                throw invalidData(componentLocation,
                        "Unsigned accessor bound must be an in-range integer value", null);
            }
            result.add(parsed);
        }
        return List.copyOf(result);
    }

    private JsonArray arrayForData(JsonValue value, String location) {
        if (value instanceof JsonArray array) {
            return array;
        }
        throw invalidData(location, "Expected JSON array", null);
    }

    private void validateDeclaredBoundsOnce(AccessorInfo info) {
        if (declaredBoundsValidated[info.index()]) {
            return;
        }
        if (info.minimumValues().isEmpty() && info.maximumValues().isEmpty()) {
            declaredBoundsValidated[info.index()] = true;
            return;
        }
        double[] actualMinimum = new double[info.componentCount()];
        double[] actualMaximum = new double[info.componentCount()];
        java.util.Arrays.fill(actualMinimum, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(actualMaximum, Double.NEGATIVE_INFINITY);
        ByteBuffer buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        for (int element = 0; element < info.count(); element++) {
            int base = Math.toIntExact((long) info.firstByteOffset() + (long) element * info.byteStride());
            for (int component = 0; component < info.componentCount(); component++) {
                int offset = base + component * info.componentByteSize();
                double value = switch (info.componentType()) {
                    case FLOAT -> buffer.getFloat(offset);
                    case UNSIGNED_BYTE -> Byte.toUnsignedInt(buffer.get(offset));
                    case UNSIGNED_SHORT -> Short.toUnsignedInt(buffer.getShort(offset));
                    case UNSIGNED_INT -> Integer.toUnsignedLong(buffer.getInt(offset));
                    default -> throw new AssertionError("validated component type");
                };
                if (!Double.isFinite(value)) {
                    throw invalidData("/accessors/" + info.index(), "Accessor contains NaN or infinity", null);
                }
                actualMinimum[component] = Math.min(actualMinimum[component], value);
                actualMaximum[component] = Math.max(actualMaximum[component], value);
            }
        }
        for (int component = 0; component < info.componentCount(); component++) {
            if (!info.minimumValues().isEmpty() && info.minimumValues().get(component) != actualMinimum[component]) {
                throw invalidData(pointer(info.index(), "min") + "/" + component,
                        "Accessor min does not match its binary data", null);
            }
            if (!info.maximumValues().isEmpty() && info.maximumValues().get(component) != actualMaximum[component]) {
                throw invalidData(pointer(info.index(), "max") + "/" + component,
                        "Accessor max does not match its binary data", null);
            }
        }
        declaredBoundsValidated[info.index()] = true;
    }

    private long maximumDeclaredBoundComponents() {
        return (long) limits.maxIndices()
                + (long) limits.maxVertices() * 16L
                + (long) limits.maxKeyframeSamples() * 4L
                + (long) limits.maxSkinJoints() * 16L;
    }

    private void requireType(AccessorInfo info, String expectedType) {
        if (!expectedType.equals(info.type())) {
            throw invalidData(pointer(info.index(), "type"), "Accessor type must be " + expectedType, null);
        }
    }

    private String pointer(int accessorIndex, String field) {
        return "/accessors/" + accessorIndex + "/" + field;
    }

    private BlendAssetLoadException bounds(String location, String message) {
        return failure(BlendDiagnosticCodes.GLB_014, location, message, null);
    }

    private BlendAssetLoadException invalidLayout(String location, String message) {
        return failure(BlendDiagnosticCodes.GLB_002, location, message, null);
    }

    private BlendAssetLoadException invalidData(String location, String message, Throwable cause) {
        return failure(BlendDiagnosticCodes.GLB_015, location, message, cause);
    }

    private BlendAssetLoadException limit(String location, String message) {
        return failure(BlendDiagnosticCodes.LIMIT_001, location, message, null);
    }

    private BlendAssetLoadException failure(String code, String location, String message, Throwable cause) {
        BlendDiagnostic diagnostic = BlendDiagnostic.error(code, modelKey, resourceId, location, message);
        return cause == null ? new BlendAssetLoadException(diagnostic) : new BlendAssetLoadException(diagnostic, cause);
    }
}
