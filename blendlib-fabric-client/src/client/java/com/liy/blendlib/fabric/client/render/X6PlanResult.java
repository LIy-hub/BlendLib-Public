package com.liy.blendlib.fabric.client.render;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable fail-closed result of an X6 preparation operation. */
public record X6PlanResult<T>(Optional<T> plan, List<X6Diagnostic> diagnostics) {
    public X6PlanResult {
        plan = Objects.requireNonNull(plan, "plan");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (plan.isPresent() && diagnostics.stream().anyMatch(value -> value.severity() == X6DiagnosticSeverity.ERROR)) {
            throw new IllegalArgumentException("A plan cannot be published with an error diagnostic");
        }
    }

    /** Returns whether a complete immutable plan was prepared and may be published. */
    public boolean publishable() {
        return plan.isPresent();
    }

    static <T> X6PlanResult<T> success(T plan, List<X6Diagnostic> diagnostics) {
        return new X6PlanResult<>(Optional.of(Objects.requireNonNull(plan, "plan")), diagnostics);
    }

    static <T> X6PlanResult<T> failure(List<X6Diagnostic> diagnostics) {
        return new X6PlanResult<>(Optional.empty(), diagnostics);
    }
}
