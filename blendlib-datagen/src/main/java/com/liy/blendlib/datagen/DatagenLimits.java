package com.liy.blendlib.datagen;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen strict-v1 limits enforced before a datagen request can publish output.
 *
 * <p>This pure-Java copy intentionally mirrors the stable descriptor ceilings rather than
 * depending on a platform runtime. Values are checked both at immutable-spec construction and
 * across serialized UTF-8 output before the first file is written.</p>
 */
final class DatagenLimits {
    static final int MAX_DESCRIPTOR_SPECS = 4_096;
    static final int MAX_MATERIAL_SLOTS = 256;
    static final int MAX_SOCKETS = 512;
    static final int MAX_ANIMATION_STATES = 256;
    static final int MAX_VISUAL_EVENTS_PER_STATE = 4_096;
    static final int MAX_VISUAL_EVENTS_PER_DESCRIPTOR = 16_384;
    static final int MAX_VARIANTS = 512;
    static final int MAX_CAPABILITIES = 64;
    static final int MAX_VARIANT_SELECTORS = 64;
    static final int MAX_OUTPUT_FILES = 16_384;
    static final int MAX_OUTPUT_FILE_UTF8_BYTES = 64 * 1024;
    static final int MAX_TOTAL_OUTPUT_UTF8_BYTES = 64 * 1024 * 1024;

    private DatagenLimits() {
    }

    static void requireAtMost(String name, int count, int maximum) {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException(name + " must contain at most " + maximum + " entries");
        }
    }

    static void validateSerializedOutputs(Map<String, DatagenGenerator.OutputFile> outputs) {
        Objects.requireNonNull(outputs, "outputs");
        if (outputs.size() > MAX_OUTPUT_FILES) {
            throw new DatagenException(DatagenDiagnosticCode.OUTPUT_LIMIT_EXCEEDED,
                    "Generated output count exceeds " + MAX_OUTPUT_FILES);
        }
        long total = 0L;
        for (Map.Entry<String, DatagenGenerator.OutputFile> entry : outputs.entrySet()) {
            String path = Objects.requireNonNull(entry.getKey(), "output path");
            DatagenGenerator.OutputFile output = Objects.requireNonNull(entry.getValue(), "output");
            int bytes = output.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_OUTPUT_FILE_UTF8_BYTES) {
                throw new DatagenException(DatagenDiagnosticCode.OUTPUT_LIMIT_EXCEEDED,
                        "Generated output exceeds " + MAX_OUTPUT_FILE_UTF8_BYTES + " UTF-8 bytes: " + path);
            }
            total = Math.addExact(total, bytes);
            if (total > MAX_TOTAL_OUTPUT_UTF8_BYTES) {
                throw new DatagenException(DatagenDiagnosticCode.OUTPUT_LIMIT_EXCEEDED,
                        "Generated output total exceeds " + MAX_TOTAL_OUTPUT_UTF8_BYTES + " UTF-8 bytes");
            }
        }
    }
}
