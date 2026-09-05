package com.liy.blendlib.showcase.perf.x7;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict structural reader for the existing P7 completed-capture report. */
final class X7P7CaptureReport {
    private static final int MAX_BYTES = X7BenchmarkEvidenceCodec.MAX_INPUT_BYTES;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_OBJECT_ENTRIES = 32;
    private static final int MAX_STRING_CHARS = X7BenchmarkEvidenceCodec.MAX_STRING_CHARS;
    private static final int MAX_NUMBER_CHARS = X7BenchmarkEvidenceCodec.MAX_NUMBER_CHARS;
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "format", "status", "gate", "gate_reason", "jfr_file", "warmup_frames", "sample_frames",
            "rigid_submits_per_sample", "skinned_submits_per_sample", "client_conditions_at_capture_start",
            "frame_time_nanos", "animation_preparation_nanos", "submit_cpu_nanos", "jfr_allocation_bytes",
            "cache_peaks", "model_handles");
    private static final Set<String> CLIENT_CONDITION_KEYS = Set.of(
            "framebuffer_width", "framebuffer_height", "render_target_width", "render_target_height",
            "required_aspect_ratio", "fov_degrees", "fov_effect_scale", "dynamic_fov_disabled",
            "configured_render_distance_chunks", "effective_render_distance_chunks",
            "required_minimum_render_distance_chunks", "contract_satisfied");
    private static final Set<String> PERCENTILE_KEYS = Set.of("p50", "p95");
    private static final Set<String> CACHE_KEYS = Set.of(
            "pose_entries", "pose_capacity", "tracked_animation_instances", "prepared_animation_assets");
    private static final Set<String> HANDLE_KEYS = Set.of("peak_total", "peak_missing");

    private X7P7CaptureReport() {
    }

    static Report parse(byte[] bytes) {
        byte[] checked = Objects.requireNonNull(bytes, "bytes");
        if (checked.length > MAX_BYTES) {
            throw X7BenchmarkEvidenceCodec.error("P7 capture report exceeds the byte limit");
        }
        String input = decodeUtf8(checked);
        Map<String, Object> root = object(new Reader(input).document(), "$report");
        requireExact(root, "$report", TOP_LEVEL_KEYS);
        Map<String, Object> conditions = object(value(root, "client_conditions_at_capture_start", "$report"),
                "$report.client_conditions_at_capture_start");
        requireExact(conditions, "$report.client_conditions_at_capture_start", CLIENT_CONDITION_KEYS);
        Map<String, Object> frame = percentiles(root, "frame_time_nanos");
        Map<String, Object> animation = percentiles(root, "animation_preparation_nanos");
        Map<String, Object> submit = percentiles(root, "submit_cpu_nanos");
        Map<String, Object> allocation = percentiles(root, "jfr_allocation_bytes");
        Map<String, Object> cache = object(value(root, "cache_peaks", "$report"), "$report.cache_peaks");
        requireExact(cache, "$report.cache_peaks", CACHE_KEYS);
        Map<String, Object> handles = object(value(root, "model_handles", "$report"), "$report.model_handles");
        requireExact(handles, "$report.model_handles", HANDLE_KEYS);

        return new Report(
                text(root, "format", "$report"),
                text(root, "status", "$report"),
                text(root, "gate", "$report"),
                text(root, "gate_reason", "$report"),
                text(root, "jfr_file", "$report"),
                exactLong(root, "warmup_frames", "$report"),
                exactLong(root, "sample_frames", "$report"),
                exactLong(root, "rigid_submits_per_sample", "$report"),
                exactLong(root, "skinned_submits_per_sample", "$report"),
                new ClientConditions(
                        exactLong(conditions, "framebuffer_width", "$report.client_conditions_at_capture_start"),
                        exactLong(conditions, "framebuffer_height", "$report.client_conditions_at_capture_start"),
                        exactLong(conditions, "render_target_width", "$report.client_conditions_at_capture_start"),
                        exactLong(conditions, "render_target_height", "$report.client_conditions_at_capture_start"),
                        text(conditions, "required_aspect_ratio", "$report.client_conditions_at_capture_start"),
                        exactLong(conditions, "fov_degrees", "$report.client_conditions_at_capture_start"),
                        decimal(conditions, "fov_effect_scale", "$report.client_conditions_at_capture_start").doubleValue(),
                        bool(conditions, "dynamic_fov_disabled", "$report.client_conditions_at_capture_start"),
                        exactLong(conditions, "configured_render_distance_chunks", "$report.client_conditions_at_capture_start"),
                        exactLong(conditions, "effective_render_distance_chunks", "$report.client_conditions_at_capture_start"),
                        exactLong(conditions, "required_minimum_render_distance_chunks", "$report.client_conditions_at_capture_start"),
                        bool(conditions, "contract_satisfied", "$report.client_conditions_at_capture_start")),
                pair(frame, "$report.frame_time_nanos"),
                pair(animation, "$report.animation_preparation_nanos"),
                pair(submit, "$report.submit_cpu_nanos"),
                pair(allocation, "$report.jfr_allocation_bytes"));
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw X7BenchmarkEvidenceCodec.error("P7 capture report is not well-formed UTF-8");
        }
    }

    private static Map<String, Object> percentiles(Map<String, Object> root, String name) {
        Map<String, Object> result = object(value(root, name, "$report"), "$report." + name);
        requireExact(result, "$report." + name, PERCENTILE_KEYS);
        return result;
    }

    private static Pair pair(Map<String, Object> object, String path) {
        return new Pair(exactLong(object, "p50", path), exactLong(object, "p95", path));
    }

    private static Object value(Map<String, Object> object, String key, String path) {
        if (!object.containsKey(key)) {
            throw X7BenchmarkEvidenceCodec.error(path + " is missing " + key);
        }
        return object.get(key);
    }

    private static Map<String, Object> object(Object value, String path) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw X7BenchmarkEvidenceCodec.error(path + " key is not a string");
                }
                result.put(key, entry.getValue());
            }
            return result;
        }
        throw X7BenchmarkEvidenceCodec.error(path + " must be an object");
    }

    private static void requireExact(Map<String, Object> object, String path, Set<String> expected) {
        if (!object.keySet().equals(expected)) {
            throw X7BenchmarkEvidenceCodec.error(path + " has missing or unknown fields");
        }
    }

    private static String text(Map<String, Object> object, String key, String path) {
        Object value = value(object, key, path);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw X7BenchmarkEvidenceCodec.error(path + "." + key + " must be a non-blank string");
    }

    private static boolean bool(Map<String, Object> object, String key, String path) {
        Object value = value(object, key, path);
        if (value instanceof Boolean result) {
            return result;
        }
        throw X7BenchmarkEvidenceCodec.error(path + "." + key + " must be a boolean");
    }

    private static BigDecimal decimal(Map<String, Object> object, String key, String path) {
        Object value = value(object, key, path);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        throw X7BenchmarkEvidenceCodec.error(path + "." + key + " must be a number");
    }

    private static long exactLong(Map<String, Object> object, String key, String path) {
        try {
            return decimal(object, key, path).longValueExact();
        } catch (ArithmeticException exception) {
            throw X7BenchmarkEvidenceCodec.error(path + "." + key + " must be an exact long");
        }
    }

    record Report(
            String format,
            String status,
            String gate,
            String gateReason,
            String jfrFile,
            long warmupFrames,
            long sampleFrames,
            long rigidSubmitsPerSample,
            long skinnedSubmitsPerSample,
            ClientConditions clientConditions,
            Pair frameTimeNanos,
            Pair animationPreparationNanos,
            Pair submitCpuNanos,
            Pair allocationBytes) {
    }

    record ClientConditions(
            long framebufferWidth,
            long framebufferHeight,
            long renderTargetWidth,
            long renderTargetHeight,
            String requiredAspectRatio,
            long fovDegrees,
            double fovEffectScale,
            boolean dynamicFovDisabled,
            long configuredRenderDistanceChunks,
            long effectiveRenderDistanceChunks,
            long requiredMinimumRenderDistanceChunks,
            boolean contractSatisfied) {
    }

    record Pair(long p50, long p95) {
    }

    /** Duplicate-key hostile object-only JSON reader for the frozen P7 report shape. */
    private static final class Reader {
        private final String input;
        private int position;
        private int depth;

        private Reader(String input) {
            this.input = input;
        }

        private Object document() {
            whitespace();
            Object result = value();
            whitespace();
            if (position != input.length()) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report has trailing JSON input");
            }
            return result;
        }

        private Object value() {
            if (position >= input.length()) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report ends unexpectedly");
            }
            return switch (input.charAt(position)) {
                case '{' -> object();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            if (++depth > MAX_DEPTH) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report exceeds nesting depth");
            }
            whitespace();
            Map<String, Object> values = new LinkedHashMap<>();
            if (consume('}')) {
                depth--;
                return values;
            }
            while (true) {
                if (values.size() >= MAX_OBJECT_ENTRIES) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report object exceeds field limit");
                }
                whitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report object key must be a string");
                }
                String key = string();
                if (values.containsKey(key)) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report has a duplicate key");
                }
                whitespace();
                expect(':');
                whitespace();
                values.put(key, value());
                whitespace();
                if (consume('}')) {
                    depth--;
                    return values;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder output = new StringBuilder();
            while (position < input.length()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return output.toString();
                }
                if (character == '\\') {
                    if (position >= input.length()) {
                        throw X7BenchmarkEvidenceCodec.error("P7 capture report has an unterminated escape");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"' -> output.append('"');
                        case '\\' -> output.append('\\');
                        case '/' -> output.append('/');
                        case 'b' -> output.append('\b');
                        case 'f' -> output.append('\f');
                        case 'n' -> output.append('\n');
                        case 'r' -> output.append('\r');
                        case 't' -> output.append('\t');
                        case 'u' -> appendUnicode(output);
                        default -> throw X7BenchmarkEvidenceCodec.error("P7 capture report has an invalid escape");
                    }
                } else if (character < 0x20) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report has malformed string Unicode");
                } else if (Character.isHighSurrogate(character)) {
                    if (position >= input.length() || !Character.isLowSurrogate(input.charAt(position))) {
                        throw X7BenchmarkEvidenceCodec.error("P7 capture report has an unpaired high surrogate");
                    }
                    output.append(character).append(input.charAt(position++));
                } else if (Character.isLowSurrogate(character)) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report has an unpaired low surrogate");
                } else {
                    output.append(character);
                }
                if (output.length() > MAX_STRING_CHARS) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report string exceeds limit");
                }
            }
            throw X7BenchmarkEvidenceCodec.error("P7 capture report has an unterminated string");
        }

        private void appendUnicode(StringBuilder output) {
            char first = unicodeEscape();
            if (Character.isHighSurrogate(first)) {
                if (!consume('\\') || !consume('u')) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report has an unpaired high surrogate");
                }
                char second = unicodeEscape();
                if (!Character.isLowSurrogate(second)) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report has an unpaired high surrogate");
                }
                output.append(first).append(second);
            } else if (Character.isLowSurrogate(first)) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report has an unpaired low surrogate");
            } else {
                output.append(first);
            }
        }

        private char unicodeEscape() {
            if (position + 4 > input.length()) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report has a short Unicode escape");
            }
            int result = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) {
                    throw X7BenchmarkEvidenceCodec.error("P7 capture report has an invalid Unicode escape");
                }
                result = (result << 4) | digit;
            }
            return (char) result;
        }

        private BigDecimal number() {
            int start = position;
            consume('-');
            if (consume('0')) {
                // The caller rejects a following digit by requiring a separator.
            } else {
                digits();
            }
            if (consume('.')) {
                digits();
            }
            if (consume('e') || consume('E')) {
                consume('+');
                consume('-');
                digits();
            }
            String token = input.substring(start, position);
            if (token.length() > MAX_NUMBER_CHARS) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report number exceeds limit");
            }
            try {
                return new BigDecimal(token);
            } catch (NumberFormatException exception) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report has an invalid number");
            }
        }

        private Object literal(String expected, Object value) {
            if (!input.startsWith(expected, position)) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report has an invalid literal");
            }
            position += expected.length();
            return value;
        }

        private void digits() {
            int start = position;
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw X7BenchmarkEvidenceCodec.error("P7 capture report expected a digit");
            }
        }

        private void whitespace() {
            while (position < input.length()) {
                char character = input.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                    return;
                }
                position++;
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
                throw X7BenchmarkEvidenceCodec.error("P7 capture report expected '" + expected + "'");
            }
        }
    }
}
