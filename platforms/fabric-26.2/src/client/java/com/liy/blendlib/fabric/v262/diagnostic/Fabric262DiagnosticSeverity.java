package com.liy.blendlib.fabric.v262.diagnostic;

/**
 * Severity assigned to a Fabric 26.2 adapter diagnostic.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. These values are local adapter
 * observability data and do not reinterpret the frozen core descriptor diagnostic taxonomy.</p>
 */
public enum Fabric262DiagnosticSeverity {
    /** An operation completed with a recoverable limitation or fallback. */
    WARNING,

    /** An operation could not complete and selected the explicit missing-model fallback. */
    ERROR
}
