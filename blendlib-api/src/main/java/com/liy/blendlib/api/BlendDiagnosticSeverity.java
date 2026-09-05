package com.liy.blendlib.api;

/**
 * Severity assigned to a public BlendLib diagnostic.
 *
 * <p>Severity describes whether the affected semantic operation may continue; it is not a
 * renderer decision, a network authority marker, or a request to infer a nearby visual fallback.
 * Consumers should preserve the code and bounded message alongside this value when presenting a
 * diagnostic to a user or integration owner.</p>
 */
public enum BlendDiagnosticSeverity {
    /** Informational state that does not alter publication or fallback behavior. */
    INFO,

    /** Explicit, safe fallback or non-fatal compatibility information. */
    WARNING,

    /** A fail-closed condition that prevents the affected operation from publishing. */
    ERROR
}
