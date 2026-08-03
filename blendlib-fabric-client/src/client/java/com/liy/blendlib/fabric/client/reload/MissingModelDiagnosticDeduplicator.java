package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Returns a missing model's primary diagnostic only once for the newest active resource generation and model key.
 *
 * <p>{@link ReloadDiagnosticsReporter} invokes this helper only after {@link ClientModelRegistry#publish} returns
 * its final active generation. A delayed stale candidate therefore cannot reintroduce development-log spam for the
 * active generation. The retained key set is cleared on every newer generation.</p>
 */
final class MissingModelDiagnosticDeduplicator {
    private long activeGeneration = -1L;
    private final Set<BlendModelKey> reportedKeys = new HashSet<>();

    /** Returns the diagnostic only for its first key/generation occurrence. */
    synchronized Optional<BlendDiagnostic> firstForGeneration(
            long generation, BlendModelKey modelKey, BlendDiagnostic primaryDiagnostic) {
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(primaryDiagnostic, "primaryDiagnostic");
        if (generation < activeGeneration) {
            return Optional.empty();
        }
        if (generation > activeGeneration) {
            activeGeneration = generation;
            reportedKeys.clear();
        }
        return reportedKeys.add(modelKey) ? Optional.of(primaryDiagnostic) : Optional.empty();
    }

    synchronized int reportedKeyCount() {
        return reportedKeys.size();
    }
}
