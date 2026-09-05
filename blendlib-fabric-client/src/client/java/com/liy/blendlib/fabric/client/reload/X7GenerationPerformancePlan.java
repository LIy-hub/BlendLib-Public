package com.liy.blendlib.fabric.client.reload;

import java.util.Objects;

/**
 * Generation-frozen optional-backend decision made entirely before publication.
 *
 * <p>This adapter-private foundation records the outcome of bounded future capability, preparation,
 * and upload checks; it neither performs them nor creates an accelerated resource.</p>
 */
final class X7GenerationPerformancePlan {
    private X7GenerationPerformancePlan() {
    }

    /** Frozen backend intent selected during generation preparation. */
    enum BackendChoice {
        CPU,
        GPU_CANDIDATE
    }

    /** Why generation preparation selected the reliable CPU route. */
    enum FallbackReason {
        NONE,
        CAPABILITY_UNAVAILABLE,
        PREPARE_FAILED,
        UPLOAD_FAILED
    }

    /** Immutable observations supplied by a future bounded adapter preparation owner. */
    record CapabilitySnapshot(boolean gpuSkinningAvailable, boolean gpuPreparationAvailable, boolean gpuUploadAvailable) {
        static CapabilitySnapshot cpuOnly() {
            return new CapabilitySnapshot(false, false, false);
        }

        static CapabilitySnapshot gpuReady() {
            return new CapabilitySnapshot(true, true, true);
        }
    }

    /**
     * Exact X6-derived identities frozen before preparation.
     *
     * <p>A future owner must pass the exact prepared X6 handle/model/geometry/material-route and
     * selected LOD identities. This policy retains identities only; it owns no X6 resource or
     * secondary mutable render-plan state.</p>
     */
    record Binding(
            long generation,
            Object handleIdentity,
            Object modelIdentity,
            Object geometryIdentity,
            Object materialIdentity,
            Object lodIdentity) {
        Binding {
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            handleIdentity = Objects.requireNonNull(handleIdentity, "handleIdentity");
            modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
            geometryIdentity = Objects.requireNonNull(geometryIdentity, "geometryIdentity");
            materialIdentity = Objects.requireNonNull(materialIdentity, "materialIdentity");
            lodIdentity = Objects.requireNonNull(lodIdentity, "lodIdentity");
        }

        private boolean matches(
                long activeGeneration,
                Object submittedHandleIdentity,
                Object submittedModelIdentity,
                Object submittedGeometryIdentity,
                Object submittedMaterialIdentity,
                Object submittedLodIdentity) {
            return generation == activeGeneration
                    && handleIdentity == submittedHandleIdentity
                    && modelIdentity == submittedModelIdentity
                    && geometryIdentity == submittedGeometryIdentity
                    && materialIdentity == submittedMaterialIdentity
                    && lodIdentity == submittedLodIdentity;
        }
    }

    /**
     * Exact identities available to the D2b CPU-only route before any X6 geometry, material, or
     * LOD authority exists. This deliberately cannot stand in for {@link Binding}.
     */
    record CpuSubsetBinding(long generation, Object handleIdentity, Object sourceIdentity) {
        CpuSubsetBinding {
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            handleIdentity = Objects.requireNonNull(handleIdentity, "handleIdentity");
            sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        }

        private boolean matches(long activeGeneration, Object activeHandleIdentity, Object activeSourceIdentity) {
            return generation == activeGeneration
                    && handleIdentity == activeHandleIdentity
                    && sourceIdentity == activeSourceIdentity;
        }
    }

    /**
     * Creates the only pre-publication state. The private proof type prevents ordinary package-peer
     * Java source from manufacturing a candidate or published state from a {@link BackendChoice}
     * value.
     */
    static Prepared prepare(Binding binding, CapabilitySnapshot capabilities) {
        Binding checkedBinding = Objects.requireNonNull(binding, "binding");
        CapabilitySnapshot checkedCapabilities = Objects.requireNonNull(capabilities, "capabilities");
        if (!checkedCapabilities.gpuSkinningAvailable()) {
            return cpu(checkedBinding, FallbackReason.CAPABILITY_UNAVAILABLE);
        }
        if (!checkedCapabilities.gpuPreparationAvailable()) {
            return cpu(checkedBinding, FallbackReason.PREPARE_FAILED);
        }
        if (!checkedCapabilities.gpuUploadAvailable()) {
            return cpu(checkedBinding, FallbackReason.UPLOAD_FAILED);
        }
        return new Prepared(checkedBinding, DecisionProof.verifiedGpuCandidate());
    }

    /**
     * Freezes the only D2b route. It has no geometry/material/LOD inputs and therefore cannot
     * publish a GPU candidate or invoke the full X6 identity contract.
     */
    static CpuSubsetPrepared prepareCpuOnly(CpuSubsetBinding binding) {
        return new CpuSubsetPrepared(
                Objects.requireNonNull(binding, "binding"),
                DecisionProof.verifiedCpuFallback(FallbackReason.CAPABILITY_UNAVAILABLE));
    }

    private static Prepared cpu(Binding binding, FallbackReason reason) {
        return new Prepared(binding, DecisionProof.verifiedCpuFallback(reason));
    }

    /** Private proof generated only by the outer preparation owner. */
    private static final class DecisionProof {
        private final BackendChoice backend;
        private final FallbackReason fallbackReason;

        private DecisionProof(BackendChoice backend, FallbackReason fallbackReason) {
            this.backend = Objects.requireNonNull(backend, "backend");
            this.fallbackReason = Objects.requireNonNull(fallbackReason, "fallbackReason");
            if ((backend == BackendChoice.GPU_CANDIDATE) != (fallbackReason == FallbackReason.NONE)) {
                throw new IllegalArgumentException("backend choice and fallback reason must agree");
            }
        }

        private static DecisionProof verifiedGpuCandidate() {
            return new DecisionProof(BackendChoice.GPU_CANDIDATE, FallbackReason.NONE);
        }

        private static DecisionProof verifiedCpuFallback(FallbackReason reason) {
            if (reason == FallbackReason.NONE) {
                throw new IllegalArgumentException("CPU fallback requires a failure reason");
            }
            return new DecisionProof(BackendChoice.CPU, reason);
        }
    }

    /** Immutable pre-publication result that may only become an equivalent published plan. */
    static final class Prepared {
        private final Binding binding;
        private final DecisionProof proof;

        private Prepared(Binding binding, DecisionProof proof) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.proof = Objects.requireNonNull(proof, "proof");
        }

        Binding binding() {
            return binding;
        }

        BackendChoice backend() {
            return proof.backend;
        }

        FallbackReason fallbackReason() {
            return proof.fallbackReason;
        }

        Published publish() {
            return new Published(binding, proof);
        }
    }

    /** Immutable pre-publication CPU-subset result with no general capability entry point. */
    static final class CpuSubsetPrepared {
        private final CpuSubsetBinding binding;
        private final DecisionProof proof;

        private CpuSubsetPrepared(CpuSubsetBinding binding, DecisionProof proof) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.proof = Objects.requireNonNull(proof, "proof");
            if (proof.backend != BackendChoice.CPU || proof.fallbackReason != FallbackReason.CAPABILITY_UNAVAILABLE) {
                throw new IllegalArgumentException("D2b CPU-subset preparation requires the reliable CPU fallback");
            }
        }

        CpuSubsetBinding binding() {
            return binding;
        }

        BackendChoice backend() {
            return proof.backend;
        }

        FallbackReason fallbackReason() {
            return proof.fallbackReason;
        }

        CpuSubsetPublished publish() {
            return new CpuSubsetPublished(binding, proof);
        }
    }

    /** Immutable CPU route consumed only through its exact subset identity check. */
    static final class CpuSubsetPublished {
        private final CpuSubsetBinding binding;
        private final DecisionProof proof;

        private CpuSubsetPublished(CpuSubsetBinding binding, DecisionProof proof) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.proof = Objects.requireNonNull(proof, "proof");
            if (proof.backend != BackendChoice.CPU || proof.fallbackReason != FallbackReason.CAPABILITY_UNAVAILABLE) {
                throw new IllegalArgumentException("D2b CPU-subset publication requires the reliable CPU fallback");
            }
        }

        CpuSubsetBinding binding() {
            return binding;
        }

        BackendChoice backend() {
            return proof.backend;
        }

        FallbackReason fallbackReason() {
            return proof.fallbackReason;
        }

        /** Rejects a stale/cross-handle/cross-source read without any submit-time policy choice. */
        void requireCurrentCpuBinding(long activeGeneration, Object activeHandleIdentity, Object activeSourceIdentity) {
            Objects.requireNonNull(activeHandleIdentity, "activeHandleIdentity");
            Objects.requireNonNull(activeSourceIdentity, "activeSourceIdentity");
            if (!binding.matches(activeGeneration, activeHandleIdentity, activeSourceIdentity)) {
                throw new IllegalStateException(
                        "published D2b CPU route does not match the current generation, handle, or source identity");
            }
        }
    }

    /** Immutable generation-pinned plan consumed by a future submit owner. */
    static final class Published {
        private final Binding binding;
        private final DecisionProof proof;

        private Published(Binding binding, DecisionProof proof) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.proof = Objects.requireNonNull(proof, "proof");
        }

        Binding binding() {
            return binding;
        }

        BackendChoice backend() {
            return proof.backend;
        }

        FallbackReason fallbackReason() {
            return proof.fallbackReason;
        }

        /**
         * Rejects submit unless every identity retained through prepare and publish is still exact.
         * The method has no backend argument, so publication cannot be switched during submit.
         */
        void requireCurrentSubmitBinding(
                long activeGeneration,
                Object submittedHandleIdentity,
                Object submittedModelIdentity,
                Object submittedGeometryIdentity,
                Object submittedMaterialIdentity,
                Object submittedLodIdentity) {
            Objects.requireNonNull(submittedHandleIdentity, "submittedHandleIdentity");
            Objects.requireNonNull(submittedModelIdentity, "submittedModelIdentity");
            Objects.requireNonNull(submittedGeometryIdentity, "submittedGeometryIdentity");
            Objects.requireNonNull(submittedMaterialIdentity, "submittedMaterialIdentity");
            Objects.requireNonNull(submittedLodIdentity, "submittedLodIdentity");
            if (!binding.matches(
                    activeGeneration,
                    submittedHandleIdentity,
                    submittedModelIdentity,
                    submittedGeometryIdentity,
                    submittedMaterialIdentity,
                    submittedLodIdentity)) {
                throw new IllegalStateException(
                        "published X7 performance plan does not match the current generation, handle, model, geometry, material, or LOD");
            }
        }
    }
}
