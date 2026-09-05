package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.List;

/** Owner-thread collector that bounds hostile diagnostics before snapshot publication. */
final class ProceduralDiagnosticCollector {
    private final List<ProceduralDiagnostic> values = new ArrayList<>();
    private boolean truncated;
    private boolean hasErrorEver;

    void add(ProceduralDiagnosticSeverity severity, ProceduralDiagnosticCode code, BlendResourceId identity, String message) {
        if (severity == ProceduralDiagnosticSeverity.ERROR) {
            hasErrorEver = true;
        }
        if (values.size() < ProceduralLimits.MAX_DIAGNOSTIC_DETAILS) {
            values.add(new ProceduralDiagnostic(severity, code, identity, message));
            return;
        }
        if (severity == ProceduralDiagnosticSeverity.ERROR) {
            replaceOneNonError(new ProceduralDiagnostic(severity, code, identity, message));
        }
        if (!truncated && values.size() < ProceduralLimits.MAX_DIAGNOSTICS_PER_FRAME) {
            truncated = true;
            values.add(new ProceduralDiagnostic(
                    ProceduralDiagnosticSeverity.WARN,
                    ProceduralDiagnosticCode.DIAGNOSTIC_OVERFLOW,
                    null,
                    "procedural diagnostics exceeded the frozen frame budget"));
        }
    }

    void exception(ProceduralDiagnosticCode code, BlendResourceId identity, Throwable throwable) {
        String type = throwable == null ? "unknown" : throwable.getClass().getName();
        add(ProceduralDiagnosticSeverity.ERROR, code, identity, "contained callback failure: "
                + ProceduralSupport.boundedText(type, 96));
    }

    List<ProceduralDiagnostic> snapshot() {
        return List.copyOf(values);
    }

    boolean hasErrors() {
        return hasErrorEver;
    }

    private void replaceOneNonError(ProceduralDiagnostic replacement) {
        for (int index = values.size() - 1; index >= 0; index--) {
            if (values.get(index).severity() != ProceduralDiagnosticSeverity.ERROR
                    && values.get(index).code() != ProceduralDiagnosticCode.DIAGNOSTIC_OVERFLOW) {
                values.set(index, replacement);
                return;
            }
        }
    }
}
