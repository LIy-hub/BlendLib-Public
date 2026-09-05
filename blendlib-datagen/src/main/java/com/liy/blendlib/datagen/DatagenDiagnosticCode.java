package com.liy.blendlib.datagen;

/**
 * Machine-readable error codes for the independent pure-Java BlendLib data generator.
 *
 * <p><strong>Stable boundary:</strong> codes describe authoring/output failures only. The module
 * has no Minecraft platform dependency and never runs in the BlendLib rendering hot path.</p>
 */
public enum DatagenDiagnosticCode {
    /** Output root or a requested generated path falls outside the approved output tree. */
    PATH_TRAVERSAL("BLENDLIB-DATAGEN-001"),

    /** A descriptor or sidecar specification violates the generator's strict authoring contract. */
    INVALID_SPECIFICATION("BLENDLIB-DATAGEN-002"),

    /** Two generated declarations would write the same logical output path. */
    DUPLICATE_OUTPUT("BLENDLIB-DATAGEN-003"),

    /** A UTF-8/LF payload could not be staged or moved atomically. */
    ATOMIC_WRITE_FAILURE("BLENDLIB-DATAGEN-004"),

    /** Filesystem creation or output I/O failed before a complete artifact could be published. */
    OUTPUT_IO_FAILURE("BLENDLIB-DATAGEN-005"),

    /** Immutable input or serialized output exceeded a frozen strict-v1/datagen safety limit. */
    OUTPUT_LIMIT_EXCEEDED("BLENDLIB-DATAGEN-006");

    private final String value;

    DatagenDiagnosticCode(String value) {
        this.value = value;
    }

    /**
     * Returns the stable text code for build logs and integration reports.
     *
     * @return non-blank code text
     */
    public String value() {
        return value;
    }
}
