package com.liy.blendlib.core.profile.experimental;

import java.util.Objects;

/** Structured diagnostic emitted by the proposed X9 validator. These codes are not v1 allocations. */
public record ExperimentalProfileDiagnostic(Severity severity, String code, String location, String message, String fallback) {
    public ExperimentalProfileDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code");
        location = Objects.requireNonNull(location, "location");
        message = Objects.requireNonNull(message, "message");
        fallback = fallback == null ? "" : fallback;
    }

    public enum Severity {
        WARN,
        ERROR
    }
}
