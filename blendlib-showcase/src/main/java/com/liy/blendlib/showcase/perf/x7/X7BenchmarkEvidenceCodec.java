package com.liy.blendlib.showcase.perf.x7;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict JSON codec with deterministic canonical serialization for X7 benchmark evidence.
 *
 * <p>The parser has no permissive JSON dependency: duplicate object keys, trailing input,
 * non-finite numbers, unknown schema fields, and malformed type conversions fail closed. The
 * serializer emits one stable field order, sorts unordered extension and artifact collections,
 * uses UTF-8, and does not read locale, timezone, or wall-clock state.</p>
 */
public final class X7BenchmarkEvidenceCodec {
    /** Bounded offline-document limits; they prevent parser resource exhaustion. */
    public static final int MAX_INPUT_BYTES = 1_048_576;
    public static final int MAX_INPUT_CHARS = 1_048_576;
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_TOKEN_COUNT = 50_000;
    /** Schema v1's top-level object has 14 fields; larger objects cannot be valid evidence. */
    public static final int MAX_OBJECT_ENTRIES = 14;
    public static final int MAX_ARRAY_ENTRIES = 4_096;
    public static final int MAX_STRING_CHARS = 16_384;
    public static final int MAX_NUMBER_CHARS = 128;

    private X7BenchmarkEvidenceCodec() {
    }

    /** Returns the exact canonical UTF-8 JSON representation used for reproducible hashes. */
    public static String canonicalJson(X7BenchmarkEvidence evidence) {
        X7BenchmarkEvidence checked = Objects.requireNonNull(evidence, "evidence");
        requireSemanticValidity(checked);
        return canonicalJsonUnchecked(checked);
    }

    /**
     * Bounded canonical pass used by the direct-construction validator to prove that the exact
     * writer/parser limits close over the whole aggregate document before public encoding starts.
     */
    static String canonicalLimitFailure(X7BenchmarkEvidence evidence) {
        try {
            canonicalJsonUnchecked(Objects.requireNonNull(evidence, "evidence"));
            return null;
        } catch (X7EvidenceFormatException exception) {
            return exception.getMessage();
        }
    }

    /**
     * Returns the exact UTF-8 size of an already-semantic-safe envelope without recursing back
     * through {@link X7BenchmarkEvidenceValidator#structuralErrors(X7BenchmarkEvidence)}.
     */
    static int canonicalUtf8Length(X7BenchmarkEvidence evidence) {
        return canonicalJsonUnchecked(Objects.requireNonNull(evidence, "evidence"))
                .getBytes(StandardCharsets.UTF_8)
                .length;
    }

    private static String canonicalJsonUnchecked(X7BenchmarkEvidence checked) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.fieldNumber("schema_version", checked.schemaVersion());
        writer.fieldString("capture_class", checked.captureClass().name());
        writer.fieldString("gate_status", checked.gateStatus().name());
        writer.fieldString("capture_id", checked.captureId());
        writer.name("source_revision");
        writeRevision(writer, checked.sourceRevision());
        writer.name("scenario");
        writeScenario(writer, checked.scenario());
        writer.name("environment");
        writeEnvironment(writer, checked.environment());
        writer.name("backend");
        writeBackend(writer, checked.backend());
        writer.name("sampling");
        writeSampling(writer, checked.sampling());
        writer.name("metrics");
        writeMetrics(writer, checked.metrics());
        writer.fieldString("allocation_evidence_key", checked.allocationEvidenceKey());
        writer.name("artifact_manifest");
        writeArtifactManifest(writer, checked.artifactManifest());
        writer.name("required_extensions");
        writeSortedStrings(writer, checked.requiredExtensions());
        writer.name("optional_extensions");
        writeSortedStrings(writer, checked.optionalExtensions());
        writer.endObject();
        return writer.finish();
    }

    /** Hashes the canonical UTF-8 bytes, not an implementation-specific pretty-printed form. */
    public static String canonicalSha256(X7BenchmarkEvidence evidence) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
        byte[] encoded = canonicalJson(evidence).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(digest.digest(encoded));
    }

    /**
     * Parses only the canonical schema-v1 UTF-16 form. Parsing is also semantic validation: an
     * object cannot be decoded and then accidentally consumed before its scenario, paths,
     * extensions, ranges, or ordering have been rejected.
     */
    public static X7BenchmarkEvidence parse(String json) {
        String checkedJson = Objects.requireNonNull(json, "json");
        verifyInputLimitsAndUnicode(checkedJson);
        Object parsed = new StrictJsonReader(checkedJson).parseDocument();
        X7BenchmarkEvidence evidence = readEvidence(object(parsed, "$"));
        requireSemanticValidity(evidence);
        String canonical = canonicalJson(evidence);
        if (!checkedJson.equals(canonical)) {
            throw error("X7 evidence JSON must use canonical field and collection ordering");
        }
        return evidence;
    }

    /** Parses strict UTF-8 bytes with the same byte and character limits as the String entry point. */
    public static X7BenchmarkEvidence parseUtf8(byte[] jsonBytes) {
        byte[] checked = Objects.requireNonNull(jsonBytes, "jsonBytes");
        if (checked.length > MAX_INPUT_BYTES) {
            throw error("X7 evidence input exceeds the byte limit");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(checked))
                    .toString();
            return parse(decoded);
        } catch (CharacterCodingException exception) {
            throw error("X7 evidence input is not well-formed UTF-8");
        }
    }

    /**
     * Package-private hostile JSON primitive for bounded retained receipt validation. It shares
     * the evidence reader's UTF-8, duplicate-key, nesting, token, and trailing-data checks, but
     * permits the receipt's separately bounded object/array cardinalities.
     */
    static Object parseStrictJsonUtf8(byte[] jsonBytes, int maxObjectEntries, int maxArrayEntries) {
        byte[] checked = Objects.requireNonNull(jsonBytes, "jsonBytes");
        if (checked.length > MAX_INPUT_BYTES || maxObjectEntries <= 0 || maxObjectEntries > MAX_ARRAY_ENTRIES
                || maxArrayEntries <= 0 || maxArrayEntries > MAX_ARRAY_ENTRIES) {
            throw error("strict retained JSON exceeds a bounded parser limit");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(checked))
                    .toString();
            verifyInputLimitsAndUnicode(decoded);
            return new StrictJsonReader(decoded, maxObjectEntries, maxArrayEntries).parseDocument();
        } catch (CharacterCodingException exception) {
            throw error("strict retained JSON is not well-formed UTF-8");
        }
    }

    private static void requireSemanticValidity(X7BenchmarkEvidence evidence) {
        List<String> errors = X7BenchmarkEvidenceValidator.structuralErrors(evidence);
        if (!errors.isEmpty()) {
            throw error("X7 evidence semantic validation failed: " + errors.getFirst());
        }
    }

    private static void verifyInputLimitsAndUnicode(String value) {
        if (value.length() > MAX_INPUT_CHARS) {
            throw error("X7 evidence input exceeds the character limit");
        }
        long utf8Bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw error("X7 evidence input contains an unpaired high surrogate");
                }
                utf8Bytes += 4L;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw error("X7 evidence input contains an unpaired low surrogate");
            } else if (current <= 0x7f) {
                utf8Bytes++;
            } else if (current <= 0x7ff) {
                utf8Bytes += 2L;
            } else {
                utf8Bytes += 3L;
            }
            if (utf8Bytes > MAX_INPUT_BYTES) {
                throw error("X7 evidence input exceeds the byte limit");
            }
        }
    }

    private static void writeRevision(JsonWriter writer, X7BenchmarkEvidence.SourceRevision revision) {
        writer.beginObject();
        writer.fieldString("commit", revision.commit());
        writer.fieldString("tree", revision.tree());
        writer.endObject();
    }

    private static void writeScenario(JsonWriter writer, X7BenchmarkEvidence.FrozenScenario scenario) {
        writer.beginObject();
        writer.fieldString("scenario_format", scenario.scenarioFormat());
        writer.fieldString("rigid_model_key", scenario.rigidModelKey());
        writer.fieldString("skinned_model_key", scenario.skinnedModelKey());
        writer.fieldNumber("rigid_instances", scenario.rigidInstances());
        writer.fieldNumber("rigid_triangles_per_instance", scenario.rigidTrianglesPerInstance());
        writer.fieldNumber("rigid_vertices_per_instance", scenario.rigidVerticesPerInstance());
        writer.fieldNumber("skinned_instances", scenario.skinnedInstances());
        writer.fieldNumber("skinned_triangles_per_instance", scenario.skinnedTrianglesPerInstance());
        writer.fieldNumber("skinned_vertices_per_instance", scenario.skinnedVerticesPerInstance());
        writer.fieldNumber("skinned_joints_per_instance", scenario.skinnedJointsPerInstance());
        writer.fieldNumber("total_triangles", scenario.totalTriangles());
        writer.endObject();
    }

    private static void writeEnvironment(JsonWriter writer, X7BenchmarkEvidence.RuntimeEnvironment environment) {
        writer.beginObject();
        writer.fieldString("java_version", environment.javaVersion());
        writer.fieldString("os_name", environment.osName());
        writer.fieldString("os_version", environment.osVersion());
        writer.fieldString("minecraft_version", environment.minecraftVersion());
        writer.fieldString("fabric_loader_version", environment.fabricLoaderVersion());
        writer.fieldString("fabric_api_version", environment.fabricApiVersion());
        writer.fieldString("gpu_name", environment.gpuName());
        writer.fieldString("gpu_vendor", environment.gpuVendor());
        writer.fieldString("gpu_driver", environment.gpuDriver());
        writer.fieldString("shaderpack", environment.shaderpack());
        writer.fieldString("iris_status", environment.irisStatus());
        writer.fieldString("sodium_status", environment.sodiumStatus());
        writer.fieldBoolean("environment_verified", environment.environmentVerified());
        writer.endObject();
    }

    private static void writeBackend(JsonWriter writer, X7BenchmarkEvidence.BackendSelection backend) {
        writer.beginObject();
        writer.fieldString("backend_class", backend.backendClass().name());
        writer.fieldString("backend_name", backend.backendName());
        writer.fieldString("capability", backend.capability());
        writer.fieldString("fallback_state", backend.fallbackState().name());
        writer.fieldBoolean("backend_verified", backend.backendVerified());
        writer.endObject();
    }

    private static void writeSampling(JsonWriter writer, X7BenchmarkEvidence.Sampling sampling) {
        writer.beginObject();
        writer.fieldNumber("warmup_frames", sampling.warmupFrames());
        writer.fieldNumber("sample_frames", sampling.sampleFrames());
        writer.fieldNumber("reload_generation", sampling.reloadGeneration());
        writer.endObject();
    }

    private static void writeMetrics(JsonWriter writer, X7BenchmarkEvidence.Metrics metrics) {
        writer.beginObject();
        writer.name("fps");
        writePercentiles(writer, metrics.fps());
        writer.name("frame_time_ms");
        writePercentiles(writer, metrics.frameTimeMillis());
        writer.name("cpu_render_ms");
        writePercentiles(writer, metrics.cpuRenderMillis());
        writer.name("allocation_bytes");
        writePercentiles(writer, metrics.allocationBytes());
        writer.endObject();
    }

    private static void writePercentiles(JsonWriter writer, X7BenchmarkEvidence.Percentiles percentiles) {
        writer.beginObject();
        writer.fieldNumber("p50", percentiles.p50());
        writer.fieldNumber("p95", percentiles.p95());
        writer.fieldNumber("p99", percentiles.p99());
        writer.endObject();
    }

    private static void writeArtifactManifest(JsonWriter writer, X7BenchmarkEvidence.ArtifactManifest manifest) {
        writer.beginObject();
        writer.fieldString("manifest_path", manifest.manifestPath());
        writer.name("entries");
        writer.beginArray();
        for (X7BenchmarkEvidence.Artifact artifact : manifest.canonicalEntries()) {
            writer.arrayObjectStart();
            writer.fieldString("key", artifact.key());
            writer.fieldString("path", artifact.path());
            writer.fieldString("sha256", artifact.sha256());
            writer.fieldNumber("byte_count", artifact.byteCount());
            writer.fieldString("kind", artifact.kind().name());
            writer.endObject();
        }
        writer.endArray();
        writer.endObject();
    }

    private static void writeSortedStrings(JsonWriter writer, List<String> values) {
        writer.beginArray();
        values.stream().sorted().forEach(writer::arrayString);
        writer.endArray();
    }

    private static X7BenchmarkEvidence readEvidence(Map<String, Object> root) {
        requireExactKeys(root, "$",
                "schema_version",
                "capture_class",
                "gate_status",
                "capture_id",
                "source_revision",
                "scenario",
                "environment",
                "backend",
                "sampling",
                "metrics",
                "allocation_evidence_key",
                "artifact_manifest",
                "required_extensions",
                "optional_extensions");
        return new X7BenchmarkEvidence(
                intNumber(root, "schema_version", "$"),
                enumValue(X7BenchmarkEvidence.CaptureClass.class, string(root, "capture_class", "$"), "$.capture_class"),
                enumValue(X7BenchmarkEvidence.GateStatus.class, string(root, "gate_status", "$"), "$.gate_status"),
                string(root, "capture_id", "$"),
                readRevision(object(root.get("source_revision"), "$.source_revision")),
                readScenario(object(root.get("scenario"), "$.scenario")),
                readEnvironment(object(root.get("environment"), "$.environment")),
                readBackend(object(root.get("backend"), "$.backend")),
                readSampling(object(root.get("sampling"), "$.sampling")),
                readMetrics(object(root.get("metrics"), "$.metrics")),
                string(root, "allocation_evidence_key", "$"),
                readArtifactManifest(object(root.get("artifact_manifest"), "$.artifact_manifest")),
                stringList(root.get("required_extensions"), "$.required_extensions"),
                stringList(root.get("optional_extensions"), "$.optional_extensions"));
    }

    private static X7BenchmarkEvidence.SourceRevision readRevision(Map<String, Object> object) {
        requireExactKeys(object, "$.source_revision", "commit", "tree");
        return new X7BenchmarkEvidence.SourceRevision(
                string(object, "commit", "$.source_revision"),
                string(object, "tree", "$.source_revision"));
    }

    private static X7BenchmarkEvidence.FrozenScenario readScenario(Map<String, Object> object) {
        requireExactKeys(object, "$.scenario",
                "scenario_format",
                "rigid_model_key",
                "skinned_model_key",
                "rigid_instances",
                "rigid_triangles_per_instance",
                "rigid_vertices_per_instance",
                "skinned_instances",
                "skinned_triangles_per_instance",
                "skinned_vertices_per_instance",
                "skinned_joints_per_instance",
                "total_triangles");
        return new X7BenchmarkEvidence.FrozenScenario(
                string(object, "scenario_format", "$.scenario"),
                string(object, "rigid_model_key", "$.scenario"),
                string(object, "skinned_model_key", "$.scenario"),
                intNumber(object, "rigid_instances", "$.scenario"),
                intNumber(object, "rigid_triangles_per_instance", "$.scenario"),
                intNumber(object, "rigid_vertices_per_instance", "$.scenario"),
                intNumber(object, "skinned_instances", "$.scenario"),
                intNumber(object, "skinned_triangles_per_instance", "$.scenario"),
                intNumber(object, "skinned_vertices_per_instance", "$.scenario"),
                intNumber(object, "skinned_joints_per_instance", "$.scenario"),
                intNumber(object, "total_triangles", "$.scenario"));
    }

    private static X7BenchmarkEvidence.RuntimeEnvironment readEnvironment(Map<String, Object> object) {
        requireExactKeys(object, "$.environment",
                "java_version",
                "os_name",
                "os_version",
                "minecraft_version",
                "fabric_loader_version",
                "fabric_api_version",
                "gpu_name",
                "gpu_vendor",
                "gpu_driver",
                "shaderpack",
                "iris_status",
                "sodium_status",
                "environment_verified");
        return new X7BenchmarkEvidence.RuntimeEnvironment(
                string(object, "java_version", "$.environment"),
                string(object, "os_name", "$.environment"),
                string(object, "os_version", "$.environment"),
                string(object, "minecraft_version", "$.environment"),
                string(object, "fabric_loader_version", "$.environment"),
                string(object, "fabric_api_version", "$.environment"),
                string(object, "gpu_name", "$.environment"),
                string(object, "gpu_vendor", "$.environment"),
                string(object, "gpu_driver", "$.environment"),
                string(object, "shaderpack", "$.environment"),
                string(object, "iris_status", "$.environment"),
                string(object, "sodium_status", "$.environment"),
                bool(object, "environment_verified", "$.environment"));
    }

    private static X7BenchmarkEvidence.BackendSelection readBackend(Map<String, Object> object) {
        requireExactKeys(object, "$.backend",
                "backend_class", "backend_name", "capability", "fallback_state", "backend_verified");
        return new X7BenchmarkEvidence.BackendSelection(
                enumValue(X7BenchmarkEvidence.BackendClass.class,
                        string(object, "backend_class", "$.backend"), "$.backend.backend_class"),
                string(object, "backend_name", "$.backend"),
                string(object, "capability", "$.backend"),
                enumValue(X7BenchmarkEvidence.FallbackState.class,
                        string(object, "fallback_state", "$.backend"), "$.backend.fallback_state"),
                bool(object, "backend_verified", "$.backend"));
    }

    private static X7BenchmarkEvidence.Sampling readSampling(Map<String, Object> object) {
        requireExactKeys(object, "$.sampling", "warmup_frames", "sample_frames", "reload_generation");
        return new X7BenchmarkEvidence.Sampling(
                intNumber(object, "warmup_frames", "$.sampling"),
                intNumber(object, "sample_frames", "$.sampling"),
                longNumber(object, "reload_generation", "$.sampling"));
    }

    private static X7BenchmarkEvidence.Metrics readMetrics(Map<String, Object> object) {
        requireExactKeys(object, "$.metrics", "fps", "frame_time_ms", "cpu_render_ms", "allocation_bytes");
        return new X7BenchmarkEvidence.Metrics(
                readPercentiles(object(object.get("fps"), "$.metrics.fps"), "$.metrics.fps"),
                readPercentiles(object(object.get("frame_time_ms"), "$.metrics.frame_time_ms"), "$.metrics.frame_time_ms"),
                readPercentiles(object(object.get("cpu_render_ms"), "$.metrics.cpu_render_ms"), "$.metrics.cpu_render_ms"),
                readPercentiles(object(object.get("allocation_bytes"), "$.metrics.allocation_bytes"),
                        "$.metrics.allocation_bytes"));
    }

    private static X7BenchmarkEvidence.Percentiles readPercentiles(Map<String, Object> object, String path) {
        requireExactKeys(object, path, "p50", "p95", "p99");
        return new X7BenchmarkEvidence.Percentiles(
                doubleNumber(object, "p50", path),
                doubleNumber(object, "p95", path),
                doubleNumber(object, "p99", path));
    }

    private static X7BenchmarkEvidence.ArtifactManifest readArtifactManifest(Map<String, Object> object) {
        requireExactKeys(object, "$.artifact_manifest", "manifest_path", "entries");
        List<Object> rawEntries = array(object.get("entries"), "$.artifact_manifest.entries");
        List<X7BenchmarkEvidence.Artifact> entries = new ArrayList<>(rawEntries.size());
        for (int index = 0; index < rawEntries.size(); index++) {
            Map<String, Object> entry = object(rawEntries.get(index), "$.artifact_manifest.entries[" + index + "]");
            requireExactKeys(entry, "$.artifact_manifest.entries[" + index + "]",
                    "key", "path", "sha256", "byte_count", "kind");
            entries.add(new X7BenchmarkEvidence.Artifact(
                    string(entry, "key", "$.artifact_manifest.entries[" + index + "]"),
                    string(entry, "path", "$.artifact_manifest.entries[" + index + "]"),
                    string(entry, "sha256", "$.artifact_manifest.entries[" + index + "]"),
                    longNumber(entry, "byte_count", "$.artifact_manifest.entries[" + index + "]"),
                    enumValue(X7BenchmarkEvidence.ArtifactKind.class,
                            string(entry, "kind", "$.artifact_manifest.entries[" + index + "]"),
                            "$.artifact_manifest.entries[" + index + "].kind")));
        }
        return new X7BenchmarkEvidence.ArtifactManifest(
                string(object, "manifest_path", "$.artifact_manifest"),
                entries);
    }

    private static Map<String, Object> object(Object value, String path) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw error(path + " object key is not a string");
                }
                result.put(key, entry.getValue());
            }
            return result;
        }
        throw error(path + " must be an object");
    }

    private static List<Object> array(Object value, String path) {
        if (value instanceof List<?> raw) {
            return new ArrayList<>(raw);
        }
        throw error(path + " must be an array");
    }

    private static String string(Map<String, Object> object, String key, String path) {
        Object value = required(object, key, path);
        if (value instanceof String text) {
            return text;
        }
        throw error(path + "." + key + " must be a string");
    }

    private static boolean bool(Map<String, Object> object, String key, String path) {
        Object value = required(object, key, path);
        if (value instanceof Boolean result) {
            return result;
        }
        throw error(path + "." + key + " must be a boolean");
    }

    private static int intNumber(Map<String, Object> object, String key, String path) {
        try {
            return decimal(object, key, path).intValueExact();
        } catch (ArithmeticException exception) {
            throw error(path + "." + key + " must be an exact int");
        }
    }

    private static long longNumber(Map<String, Object> object, String key, String path) {
        try {
            return decimal(object, key, path).longValueExact();
        } catch (ArithmeticException exception) {
            throw error(path + "." + key + " must be an exact long");
        }
    }

    private static double doubleNumber(Map<String, Object> object, String key, String path) {
        double result = decimal(object, key, path).doubleValue();
        if (!Double.isFinite(result)) {
            throw error(path + "." + key + " must be finite");
        }
        return result;
    }

    private static BigDecimal decimal(Map<String, Object> object, String key, String path) {
        Object value = required(object, key, path);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        throw error(path + "." + key + " must be a JSON number");
    }

    private static Object required(Map<String, Object> object, String key, String path) {
        if (!object.containsKey(key)) {
            throw error(path + " is missing required key " + key);
        }
        return object.get(key);
    }

    private static List<String> stringList(Object value, String path) {
        List<Object> raw = array(value, path);
        List<String> result = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            if (!(raw.get(index) instanceof String text)) {
                throw error(path + "[" + index + "] must be a string");
            }
            result.add(text);
        }
        return result;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String path) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw error(path + " has an unsupported enum value");
        }
    }

    private static void requireExactKeys(Map<String, Object> object, String path, String... expectedKeys) {
        Set<String> expected = Set.of(expectedKeys);
        if (!object.keySet().equals(expected)) {
            throw error(path + " contains missing or unknown keys");
        }
    }

    static X7EvidenceFormatException error(String message) {
        return new X7EvidenceFormatException(message);
    }

    /** Parse error type intentionally has no recovery path. */
    public static final class X7EvidenceFormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private X7EvidenceFormatException(String message) {
            super(message);
        }
    }

    /**
     * Canonical double notation shared with the raw-sample artifact. Plain notation remains
     * pleasant for ordinary measurements, while the bounded exponent form prevents valid finite
     * IEEE values such as {@link Double#MAX_VALUE} from producing a token the reader rejects.
     */
    static String canonicalNumber(double value) {
        if (!Double.isFinite(value)) {
            throw error("canonical JSON cannot encode NaN or infinity");
        }
        if (value == 0.0d) {
            return "0";
        }
        BigDecimal normalized = BigDecimal.valueOf(value).stripTrailingZeros();
        String plain = normalized.toPlainString();
        String result = plain.length() <= MAX_NUMBER_CHARS ? plain : normalized.toString();
        if (result.length() > MAX_NUMBER_CHARS) {
            throw error("canonical JSON number exceeds the character limit");
        }
        return result;
    }

    /**
     * A bounded output writer. Direct record construction cannot make the writer allocate an
     * unbounded aggregate document: every emitted character and UTF-8 byte is checked against
     * the same limits used by the strict reader.
     */
    private static final class JsonWriter {
        private final StringBuilder output = new StringBuilder(2_048);
        private final Deque<Container> containers = new ArrayDeque<>();
        private int characters;
        private int utf8Bytes;
        private char pendingHighSurrogate;

        private void beginObject() {
            append('{');
            containers.push(new Container(true));
        }

        private void endObject() {
            requireContainer(true);
            containers.pop();
            append('}');
        }

        private void beginArray() {
            append('[');
            containers.push(new Container(false));
        }

        private void endArray() {
            requireContainer(false);
            containers.pop();
            append(']');
        }

        private void name(String name) {
            Container container = requireContainer(true);
            if (!container.first) {
                append(',');
            }
            container.first = false;
            quoted(name);
            append(':');
        }

        private void fieldString(String name, String value) {
            name(name);
            quoted(value);
        }

        private void fieldNumber(String name, long value) {
            name(name);
            append(Long.toString(value));
        }

        private void fieldNumber(String name, double value) {
            name(name);
            number(value);
        }

        private void fieldBoolean(String name, boolean value) {
            name(name);
            append(Boolean.toString(value));
        }

        private void arrayObjectStart() {
            arrayElement();
            beginObject();
        }

        private void arrayString(String value) {
            arrayElement();
            quoted(value);
        }

        private void arrayElement() {
            Container container = requireContainer(false);
            if (!container.first) {
                append(',');
            }
            container.first = false;
        }

        private void quoted(String value) {
            String checked = Objects.requireNonNull(value, "JSON string");
            X7BenchmarkEvidence.requireWellFormedUnicode(checked, "JSON string");
            append('"');
            for (int index = 0; index < checked.length(); index++) {
                char character = checked.charAt(index);
                switch (character) {
                    case '"' -> append("\\\"");
                    case '\\' -> append("\\\\");
                    case '\b' -> append("\\b");
                    case '\f' -> append("\\f");
                    case '\n' -> append("\\n");
                    case '\r' -> append("\\r");
                    case '\t' -> append("\\t");
                    default -> {
                        if (character < 0x20) {
                            append("\\u");
                            append(Character.forDigit((character >>> 12) & 0xf, 16));
                            append(Character.forDigit((character >>> 8) & 0xf, 16));
                            append(Character.forDigit((character >>> 4) & 0xf, 16));
                            append(Character.forDigit(character & 0xf, 16));
                        } else {
                            append(character);
                        }
                    }
                }
            }
            append('"');
        }

        private void number(double value) {
            append(canonicalNumber(value));
        }

        private void append(String text) {
            for (int index = 0; index < text.length(); index++) {
                append(text.charAt(index));
            }
        }

        private void append(char value) {
            if (++characters > MAX_INPUT_CHARS) {
                throw error("canonical evidence exceeds the character limit");
            }
            if (Character.isHighSurrogate(value)) {
                if (pendingHighSurrogate != 0) {
                    throw error("canonical evidence contains an unpaired high surrogate");
                }
                pendingHighSurrogate = value;
            } else if (Character.isLowSurrogate(value)) {
                if (pendingHighSurrogate == 0) {
                    throw error("canonical evidence contains an unpaired low surrogate");
                }
                pendingHighSurrogate = 0;
                addUtf8Bytes(4);
            } else {
                if (pendingHighSurrogate != 0) {
                    throw error("canonical evidence contains an unpaired high surrogate");
                }
                addUtf8Bytes(value <= 0x7f ? 1 : value <= 0x7ff ? 2 : 3);
            }
            output.append(value);
        }

        private void addUtf8Bytes(int count) {
            if (utf8Bytes > MAX_INPUT_BYTES - count) {
                throw error("canonical evidence exceeds the byte limit");
            }
            utf8Bytes += count;
        }

        private Container requireContainer(boolean object) {
            Container container = containers.peek();
            if (container == null || container.object != object) {
                throw new IllegalStateException("JSON writer container mismatch");
            }
            return container;
        }

        private String finish() {
            if (!containers.isEmpty()) {
                throw new IllegalStateException("JSON writer has unclosed containers");
            }
            if (pendingHighSurrogate != 0) {
                throw error("canonical evidence contains an unpaired high surrogate");
            }
            return output.toString();
        }

        private static final class Container {
            private final boolean object;
            private boolean first = true;

            private Container(boolean object) {
                this.object = object;
            }
        }
    }

    /** Minimal strict JSON reader sufficient for schema documents and deliberately duplicate-key hostile. */
    private static final class StrictJsonReader {
        private final String input;
        private final int maxObjectEntries;
        private final int maxArrayEntries;
        private int position;
        private int nestingDepth;
        private int tokenCount;

        private StrictJsonReader(String input) {
            this(input, MAX_OBJECT_ENTRIES, MAX_ARRAY_ENTRIES);
        }

        private StrictJsonReader(String input, int maxObjectEntries, int maxArrayEntries) {
            this.input = input;
            this.maxObjectEntries = maxObjectEntries;
            this.maxArrayEntries = maxArrayEntries;
        }

        private Object parseDocument() {
            skipWhitespace();
            Object value = value();
            skipWhitespace();
            if (position != input.length()) {
                throw error("trailing data after JSON document at offset " + position);
            }
            return value;
        }

        private Object value() {
            if (position >= input.length()) {
                throw error("unexpected end of JSON input");
            }
            consumeToken();
            return switch (input.charAt(position)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            enterContainer();
            skipWhitespace();
            Map<String, Object> values = new LinkedHashMap<>();
            if (consume('}')) {
                exitContainer();
                return values;
            }
            int entries = 0;
            while (true) {
                if (++entries > maxObjectEntries) {
                    throw error("JSON object exceeds the entry limit");
                }
                skipWhitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw error("object key must be a string at offset " + position);
                }
                consumeToken();
                String key = string();
                if (values.containsKey(key)) {
                    throw error("duplicate JSON object key: " + key);
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                values.put(key, value());
                skipWhitespace();
                if (consume('}')) {
                    exitContainer();
                    return values;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            enterContainer();
            skipWhitespace();
            List<Object> values = new ArrayList<>();
            if (consume(']')) {
                exitContainer();
                return values;
            }
            int entries = 0;
            while (true) {
                if (++entries > maxArrayEntries) {
                    throw error("JSON array exceeds the entry limit");
                }
                skipWhitespace();
                values.add(value());
                skipWhitespace();
                if (consume(']')) {
                    exitContainer();
                    return values;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (position < input.length()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return builder.toString();
                }
                if (character == '\\') {
                    if (position >= input.length()) {
                        throw error("unterminated JSON escape");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> appendEscapedUnicode(builder);
                        default -> throw error("invalid JSON escape at offset " + (position - 1));
                    }
                } else if (character < 0x20) {
                    throw error("unescaped control character in JSON string");
                } else {
                    builder.append(character);
                }
                if (builder.length() > MAX_STRING_CHARS) {
                    throw error("JSON string exceeds the character limit");
                }
            }
            throw error("unterminated JSON string");
        }

        private void appendEscapedUnicode(StringBuilder builder) {
            char first = unicodeEscape();
            if (Character.isHighSurrogate(first)) {
                if (!consume('\\') || !consume('u')) {
                    throw error("high surrogate escape must be followed by a low surrogate escape");
                }
                char second = unicodeEscape();
                if (!Character.isLowSurrogate(second)) {
                    throw error("high surrogate escape must be followed by a low surrogate escape");
                }
                builder.append(first).append(second);
                return;
            }
            if (Character.isLowSurrogate(first)) {
                throw error("low surrogate escape must follow a high surrogate escape");
            }
            builder.append(first);
        }

        private char unicodeEscape() {
            if (position + 4 > input.length()) {
                throw error("short JSON unicode escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) {
                    throw error("invalid JSON unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private BigDecimal number() {
            int start = position;
            consume('-');
            if (consume('0')) {
                // A following digit is rejected below instead of accepting an octal-looking literal.
            } else {
                requireDigits();
            }
            if (consume('.')) {
                requireDigits();
            }
            if (consume('e') || consume('E')) {
                consume('+');
                consume('-');
                requireDigits();
            }
            String token = input.substring(start, position);
            if (token.length() > MAX_NUMBER_CHARS) {
                throw error("JSON number exceeds the character limit");
            }
            try {
                return new BigDecimal(token);
            } catch (NumberFormatException exception) {
                throw error("invalid JSON number at offset " + start);
            }
        }

        private Object literal(String expected, Object value) {
            if (!input.startsWith(expected, position)) {
                throw error("invalid JSON literal at offset " + position);
            }
            position += expected.length();
            return value;
        }

        private void requireDigits() {
            int start = position;
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("expected JSON digit at offset " + position);
            }
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "' at offset " + position);
            }
        }

        private void skipWhitespace() {
            while (position < input.length()) {
                char character = input.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                    return;
                }
                position++;
            }
        }

        private void consumeToken() {
            if (++tokenCount > MAX_TOKEN_COUNT) {
                throw error("JSON token count exceeds the limit");
            }
        }

        private void enterContainer() {
            if (++nestingDepth > MAX_NESTING_DEPTH) {
                throw error("JSON nesting depth exceeds the limit");
            }
        }

        private void exitContainer() {
            nestingDepth--;
        }
    }
}
