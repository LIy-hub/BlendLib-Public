package com.liy.blendlib.spi.experimental;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result from one non-hot controlled-provider lifecycle transition.
 *
 * @param stage attempted lifecycle stage
 * @param successful whether every invoked provider completed the stage
 * @param diagnostics isolated transition failures, or cumulative retire/close failures
 */
@ExperimentalBlendLibSpi
public record ProviderLifecycleResult(
        ProviderLifecycleStage stage,
        boolean successful,
        List<CapabilityDiagnostic> diagnostics) {

    /** Defensively copies immutable transition diagnostics. */
    public ProviderLifecycleResult {
        stage = Objects.requireNonNull(stage, "stage");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (successful && diagnostics.stream().anyMatch(value -> value.severity()
                == com.liy.blendlib.api.BlendDiagnosticSeverity.ERROR)) {
            throw new IllegalArgumentException("A successful lifecycle result cannot contain an error diagnostic");
        }
    }
}
