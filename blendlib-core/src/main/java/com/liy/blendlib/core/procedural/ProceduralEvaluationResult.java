package com.liy.blendlib.core.procedural;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Outcome of one owner-thread attempt. Failed attempts never replace the last successful snapshot. */
public final class ProceduralEvaluationResult {
    private final boolean published;
    private final ProceduralFrameSnapshot snapshot;
    private final List<ProceduralDiagnostic> diagnostics;

    ProceduralEvaluationResult(boolean published, ProceduralFrameSnapshot snapshot, List<ProceduralDiagnostic> diagnostics) {
        this.published = published;
        this.snapshot = snapshot;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean published() {
        return published;
    }

    /** Returns the just-published snapshot or the previous successful snapshot after a contained failure. */
    public ProceduralFrameSnapshot snapshot() {
        if (snapshot == null) {
            throw new IllegalStateException("no successful procedural snapshot has been published");
        }
        return snapshot;
    }

    public Optional<ProceduralFrameSnapshot> latestSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    public List<ProceduralDiagnostic> diagnostics() {
        return diagnostics;
    }
}
