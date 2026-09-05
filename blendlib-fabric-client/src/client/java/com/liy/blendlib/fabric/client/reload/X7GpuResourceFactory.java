package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/** Render-thread-only all-or-nothing resource transaction for the B1 typed GPU pair. */
final class X7GpuResourceFactory {
    private static final CleanupFailuresAllocationFailpoint NO_CLEANUP_FAILURES_ALLOCATION_FAILPOINT = () -> {
    };

    private X7GpuResourceFactory() {
    }

    /**
     * Attempts only resource creation. Backend selection and CPU fallback publication belong to
     * the separate canonical planning layer and a future integration owner, never to this package.
     */
    static Attempt prepare(
            X7GpuDevice device,
            X7GpuGenerationKey key,
            X7GeometryStaging staging) {
        return prepare(device, key, staging, X7SharedGeometryResources::new);
    }

    /** Package-private construction seam used only to exercise transaction abort ownership. */
    static Attempt prepare(
            X7GpuDevice device,
            X7GpuGenerationKey key,
            X7GeometryStaging staging,
            ResourceAssembler assembler) {
        X7GeometryStaging checkedStaging = Objects.requireNonNull(staging, "staging");
        try {
            return prepareOwnedStaging(
                    device, key, checkedStaging, assembler, NO_CLEANUP_FAILURES_ALLOCATION_FAILPOINT);
        } finally {
            checkedStaging.close();
        }
    }

    /** Package-private failpoint seam for the first transaction helper allocation. */
    static Attempt prepare(
            X7GpuDevice device,
            X7GpuGenerationKey key,
            X7GeometryStaging staging,
            ResourceAssembler assembler,
            CleanupFailuresAllocationFailpoint cleanupFailuresAllocationFailpoint) {
        X7GeometryStaging checkedStaging = Objects.requireNonNull(staging, "staging");
        try {
            return prepareOwnedStaging(device, key, checkedStaging, assembler, cleanupFailuresAllocationFailpoint);
        } finally {
            checkedStaging.close();
        }
    }

    private static Attempt prepareOwnedStaging(
            X7GpuDevice device,
            X7GpuGenerationKey key,
            X7GeometryStaging staging,
            ResourceAssembler assembler,
            CleanupFailuresAllocationFailpoint cleanupFailuresAllocationFailpoint) {
        X7GpuBuffer vertex = null;
        X7GpuBuffer index = null;
        // This exact pre-allocation seam and the ledger allocation are inside the staging owner's outer finally.
        Objects.requireNonNull(cleanupFailuresAllocationFailpoint, "cleanupFailuresAllocationFailpoint")
                .beforeCleanupFailuresAllocation();
        CleanupFailures cleanup = new CleanupFailures();
        Attempt.FailureStage stage = Attempt.FailureStage.RENDER_THREAD_ASSERTION;
        try {
            X7GpuDevice checkedDevice = Objects.requireNonNull(device, "device");
            X7GpuGenerationKey checkedKey = Objects.requireNonNull(key, "key");
            ResourceAssembler checkedAssembler = Objects.requireNonNull(assembler, "assembler");
            checkedDevice.assertOnRenderThread();

            stage = Attempt.FailureStage.VERTEX_ALLOCATION;
            vertex = Objects.requireNonNull(
                    checkedDevice.allocateVertexUpload(label(checkedKey, "vertex"), staging.vertexBytesForUpload()),
                    "X7 vertex buffer");

            stage = Attempt.FailureStage.INDEX_ALLOCATION;
            index = Objects.requireNonNull(
                    checkedDevice.allocateIndexUpload(label(checkedKey, "index"), staging.indexBytesForUpload()),
                    "X7 index buffer");

            stage = Attempt.FailureStage.RESOURCE_CONSTRUCTION;
            X7SharedGeometryResources resources = Objects.requireNonNull(
                    checkedAssembler.assemble(
                            checkedKey,
                            staging.vertexFormat(),
                            staging.indexType(),
                            X7PrimitiveMode.TRIANGLES,
                            staging.vertexCount(),
                            staging.indexCount(),
                            staging.vertexByteCount(),
                            staging.indexByteCount(),
                            vertex,
                            index),
                    "X7 shared geometry resources");
            return Attempt.completed(resources);
        } catch (Throwable failure) {
            closeAbortedPair(vertex, index, cleanup);
            if (failure instanceof Error error) {
                cleanup.addSuppressedTo(error);
                throw error;
            }
            Error cleanupError = cleanup.firstError();
            if (cleanupError != null) {
                addSuppressed(cleanupError, failure);
                cleanup.addSuppressedTo(cleanupError);
                throw cleanupError;
            }
            cleanup.addSuppressedTo(failure);
            return Attempt.resourceFailure(stage, failure, cleanup.primary());
        }
    }

    private static void closeAbortedPair(X7GpuBuffer vertex, X7GpuBuffer index, CleanupFailures failures) {
        closeAborted(index, failures);
        closeAborted(vertex, failures);
    }

    private static void closeAborted(X7GpuBuffer buffer, CleanupFailures failures) {
        if (buffer == null) {
            return;
        }
        try {
            // The transaction owns this exact acquired handle. Do not call a throwable isClosed gate.
            buffer.close();
        } catch (Throwable failure) {
            failures.record(failure);
        }
    }

    private static void addSuppressed(Throwable target, Throwable suppressed) {
        if (suppressed != null && target != suppressed) {
            target.addSuppressed(suppressed);
        }
    }

    private static String label(X7GpuGenerationKey key, String part) {
        return "blendlib-x7/" + key.generation() + "/" + key.modelId() + "/" + key.geometryId() + "/" + part;
    }

    @FunctionalInterface
    interface ResourceAssembler {
        X7SharedGeometryResources assemble(
                X7GpuGenerationKey key,
                X7GpuVertexFormat vertexFormat,
                X7GpuIndexType indexType,
                X7PrimitiveMode primitiveMode,
                int vertexCount,
                int indexCount,
                int vertexBytes,
                int indexBytes,
                X7GpuBuffer vertexBuffer,
                X7GpuBuffer indexBuffer);
    }

    @FunctionalInterface
    interface CleanupFailuresAllocationFailpoint {
        void beforeCleanupFailuresAllocation();
    }

    /** A completed resource attempt, deliberately unrelated to canonical backend/fallback policy. */
    static final class Attempt {
        enum Outcome {
            SUCCESS,
            RESOURCE_FAILURE
        }

        enum FailureStage {
            RENDER_THREAD_ASSERTION,
            VERTEX_ALLOCATION,
            INDEX_ALLOCATION,
            RESOURCE_CONSTRUCTION
        }

        static final class ResourceFailure {
            private final FailureStage stage;
            private final Throwable cause;
            private final Throwable cleanup;

            private ResourceFailure(FailureStage stage, Throwable cause, Throwable cleanup) {
                this.stage = Objects.requireNonNull(stage, "stage");
                this.cause = Objects.requireNonNull(cause, "cause");
                this.cleanup = cleanup;
            }

            FailureStage stage() {
                return stage;
            }

            Throwable cause() {
                return cause;
            }

            Throwable cleanupOrNull() {
                return cleanup;
            }
        }

        private final Outcome outcome;
        private final X7SharedGeometryResources resources;
        private final ResourceFailure failure;

        private Attempt(Outcome outcome, X7SharedGeometryResources resources, ResourceFailure failure) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.resources = resources;
            this.failure = failure;
            if ((outcome == Outcome.SUCCESS) != (resources != null)) {
                throw new IllegalArgumentException("X7 resource attempt success must own one complete resource pair");
            }
            if ((outcome == Outcome.RESOURCE_FAILURE) != (failure != null)) {
                throw new IllegalArgumentException("X7 resource attempt failure must retain its transaction failure");
            }
        }

        /** Package-private completed-attempt seam used by canonical aggregate validation tests. */
        static Attempt completed(X7SharedGeometryResources resources) {
            return new Attempt(Outcome.SUCCESS, Objects.requireNonNull(resources, "resources"), null);
        }

        private static Attempt resourceFailure(FailureStage stage, Throwable cause, Throwable cleanup) {
            return new Attempt(Outcome.RESOURCE_FAILURE, null, new ResourceFailure(stage, cause, cleanup));
        }

        Outcome outcome() {
            return outcome;
        }

        X7SharedGeometryResources resourcesOrNull() {
            return resources;
        }

        ResourceFailure failureOrNull() {
            return failure;
        }
    }

    private static final class CleanupFailures {
        private Throwable first;
        private Throwable second;
        private Error firstError;

        void record(Throwable failure) {
            Throwable checkedFailure = Objects.requireNonNull(failure, "failure");
            if (first == null) {
                first = checkedFailure;
            } else if (second == null) {
                second = checkedFailure;
            } else {
                throw new IllegalStateException("X7 upload transaction closed more than two owned buffers");
            }
            if (firstError == null && checkedFailure instanceof Error error) {
                firstError = error;
            }
        }

        Error firstError() {
            return firstError;
        }

        Throwable primary() {
            if (first != null && second != null) {
                addSuppressed(first, second);
            }
            return first;
        }

        void addSuppressedTo(Throwable target) {
            addSuppressed(target, first);
            addSuppressed(target, second);
        }
    }
}
