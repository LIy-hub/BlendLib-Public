package com.liy.blendlib.fabric.client.render.x7gpu;

import java.util.List;
import java.util.Objects;

/** Immutable candidate-only batch plan; a future render-pass owner decides whether it can draw it. */
final class X7StaticBatchPlan {
    enum SubmissionKind {
        DRAW_MULTIPLE_INDEXED_CANDIDATE_WAITING,
        GPU_INSTANCING_WAITING
    }

    record Batch(X7StaticBatchKey key, List<String> instanceIds) {
        Batch {
            key = Objects.requireNonNull(key, "key");
            instanceIds = List.copyOf(Objects.requireNonNull(instanceIds, "instanceIds"));
            if (instanceIds.isEmpty()) {
                throw new IllegalArgumentException("X7 static batch must contain at least one instance");
            }
        }

        int instanceCount() {
            return instanceIds.size();
        }
    }

    private final List<Batch> batches;
    private final int distinctModelCount;
    private final int instanceCount;
    private final SubmissionKind submissionKind;

    X7StaticBatchPlan(List<Batch> batches, int distinctModelCount, int instanceCount, SubmissionKind submissionKind) {
        this.batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
        if (distinctModelCount < 0 || instanceCount < 0) {
            throw new IllegalArgumentException("X7 static batch counts must be non-negative");
        }
        this.distinctModelCount = distinctModelCount;
        this.instanceCount = instanceCount;
        this.submissionKind = Objects.requireNonNull(submissionKind, "submissionKind");
    }

    List<Batch> batches() {
        return batches;
    }

    int distinctModelCount() {
        return distinctModelCount;
    }

    int instanceCount() {
        return instanceCount;
    }

    SubmissionKind submissionKind() {
        return submissionKind;
    }
}
