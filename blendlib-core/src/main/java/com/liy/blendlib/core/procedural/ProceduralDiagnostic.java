package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Immutable bounded diagnostic emitted by an X3 evaluation attempt. */
public record ProceduralDiagnostic(
        ProceduralDiagnosticSeverity severity,
        ProceduralDiagnosticCode code,
        BlendResourceId identity,
        String message) {

    public ProceduralDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code");
        message = ProceduralSupport.boundedText(Objects.requireNonNull(message, "message"),
                ProceduralLimits.MAX_MESSAGE_UTF16_CODE_UNITS);
    }
}
