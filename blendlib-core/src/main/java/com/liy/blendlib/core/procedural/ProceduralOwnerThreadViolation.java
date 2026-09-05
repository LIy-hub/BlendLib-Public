package com.liy.blendlib.core.procedural;

/**
 * Explicit owner-thread rejection with a machine-readable X3 diagnostic.
 *
 * <p>No frame is evaluated or published when this exception is raised.</p>
 */
public final class ProceduralOwnerThreadViolation extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    ProceduralOwnerThreadViolation() {
        super("BLENDLIB-X3 owner-thread rejection");
    }

    public ProceduralDiagnostic diagnostic() {
        return new ProceduralDiagnostic(
                ProceduralDiagnosticSeverity.ERROR,
                ProceduralDiagnosticCode.OWNER_THREAD_REJECTED,
                null,
                "procedural evaluation is restricted to its first owner thread");
    }
}
