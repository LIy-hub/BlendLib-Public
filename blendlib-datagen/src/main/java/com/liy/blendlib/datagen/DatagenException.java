package com.liy.blendlib.datagen;

import java.util.Objects;

/**
 * Fail-closed exception carrying a structured pure-Java data-generation code.
 *
 * <p><strong>Stable boundary:</strong> consumers can react to {@link #code()} without parsing
 * messages. This exception has no Minecraft, Fabric, NeoForge, Blender, or runtime renderer type.</p>
 */
@SuppressWarnings("serial")
public final class DatagenException extends IllegalStateException {
    private final DatagenDiagnosticCode code;

    /**
     * Creates a coded data-generation failure.
     *
     * @param code machine-readable error code
     * @param message bounded explanation
     */
    public DatagenException(DatagenDiagnosticCode code, String message) {
        super(Objects.requireNonNull(code, "code").value() + ": " + requireMessage(message));
        this.code = code;
    }

    /**
     * Creates a coded data-generation failure retaining a local I/O cause.
     *
     * @param code machine-readable error code
     * @param message bounded explanation
     * @param cause local cause
     */
    public DatagenException(DatagenDiagnosticCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(code, "code").value() + ": " + requireMessage(message), cause);
        this.code = code;
    }

    /**
     * Returns the structured data-generation error code.
     *
     * @return non-null stable code
     */
    public DatagenDiagnosticCode code() {
        return code;
    }

    private static String requireMessage(String message) {
        String checked = Objects.requireNonNull(message, "message");
        if (checked.isBlank() || checked.length() > 512) {
            throw new IllegalArgumentException("message must be non-blank and at most 512 characters");
        }
        return checked;
    }
}
