package com.liy.blendlib.spi.experimental;

import java.util.Objects;

/**
 * Immutable context given to a controlled provider at a non-hot lifecycle stage.
 *
 * @param generation explicit resource-generation scope
 * @param plan frozen immutable capability plan
 * @param stage current lifecycle stage
 */
@ExperimentalBlendLibSpi
public record ProviderLifecycleContext(long generation, CapabilityPlan plan, ProviderLifecycleStage stage) {
    /** Validates generation, frozen plan identity, and lifecycle stage. */
    public ProviderLifecycleContext {
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        plan = Objects.requireNonNull(plan, "plan");
        stage = Objects.requireNonNull(stage, "stage");
        if (plan.generation() != generation) {
            throw new IllegalArgumentException("plan generation must match lifecycle context generation");
        }
    }
}
