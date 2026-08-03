package com.liy.blendlib.api;

/** Severity assigned to a public BlendLib diagnostic. */
public enum BlendDiagnosticSeverity {
    /** Informational state that does not alter publication or fallback behavior. */
    INFO,

    /** Explicit, safe fallback or non-fatal compatibility information. */
    WARNING,

    /** A fail-closed condition that prevents the affected operation from publishing. */
    ERROR
}
