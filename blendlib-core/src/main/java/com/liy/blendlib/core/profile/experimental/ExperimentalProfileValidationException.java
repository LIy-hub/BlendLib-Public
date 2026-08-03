package com.liy.blendlib.core.profile.experimental;

import java.util.Objects;

/** Controlled validation failure for an X9 experimental profile candidate. */
@SuppressWarnings("serial")
public final class ExperimentalProfileValidationException extends RuntimeException {
    private final ExperimentalProfileDiagnostic diagnostic;

    public ExperimentalProfileValidationException(ExperimentalProfileDiagnostic diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").code() + " at " + diagnostic.location() + ": " + diagnostic.message());
        this.diagnostic = diagnostic;
    }

    public ExperimentalProfileDiagnostic diagnostic() {
        return diagnostic;
    }
}
