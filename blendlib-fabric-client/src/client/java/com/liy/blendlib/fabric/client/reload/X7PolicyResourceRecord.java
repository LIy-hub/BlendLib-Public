package com.liy.blendlib.fabric.client.reload;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The one D1-owned policy/resource association for an exact completed generation aggregate.
 *
 * <p>This is deliberately neither a registry cache nor a second lifecycle.  It is built while the
 * {@link ClientGenerationResourceOwner.LifecycleRecord} is preallocated, before that record becomes resource-ready,
 * and it is retained only by that record.  Both lookup keys and values are identity-bound: a later equal key or a
 * freshly reconstructed {@link X7GenerationPerformancePlan.Published} is not a submission authority.</p>
 */
final class X7PolicyResourceRecord {
    private ModelRegistryGeneration exactGeneration;
    private CompletedGenerationResourceSet exactSet;
    private final IdentityHashMap<CompletedGenerationResourceSet.ResourceLeaf, X7GenerationPerformancePlan.Published>
            publishedByExactLeaf;
    /** Exact-record fuse: a post-Stage-A pre-command failure permanently selects CPU for this D1 record. */
    private boolean futureGpuDisabled;
    private boolean terminallyCleared;

    private X7PolicyResourceRecord(
            ModelRegistryGeneration exactGeneration,
            CompletedGenerationResourceSet exactSet,
            IdentityHashMap<CompletedGenerationResourceSet.ResourceLeaf, X7GenerationPerformancePlan.Published>
                    publishedByExactLeaf) {
        this.exactGeneration = Objects.requireNonNull(exactGeneration, "exactGeneration");
        this.exactSet = Objects.requireNonNull(exactSet, "exactSet");
        if (exactSet.exactGeneration() != exactGeneration) {
            throw new IllegalArgumentException("Policy-resource record must retain the exact aggregate generation");
        }
        this.publishedByExactLeaf = new IdentityHashMap<>();
        IdentityHashMap<CompletedGenerationResourceSet.ResourceLeaf, Boolean> exactLeaves = new IdentityHashMap<>();
        for (CompletedGenerationResourceSet.ResourceLeaf exactLeaf : exactSet.leavesInDeterministicOrder()) {
            exactLeaves.put(exactLeaf, Boolean.TRUE);
        }
        for (Map.Entry<CompletedGenerationResourceSet.ResourceLeaf, X7GenerationPerformancePlan.Published> entry
                : Objects.requireNonNull(publishedByExactLeaf, "publishedByExactLeaf").entrySet()) {
            CompletedGenerationResourceSet.ResourceLeaf leaf = Objects.requireNonNull(entry.getKey(), "policy leaf");
            X7GenerationPerformancePlan.Published published = Objects.requireNonNull(entry.getValue(), "published plan");
            if (!exactLeaves.containsKey(leaf)) {
                throw new IllegalArgumentException("Policy-resource record cannot index a leaf outside its exact aggregate");
            }
            if (published.binding().generation() != exactGeneration.generationId()) {
                throw new IllegalArgumentException("Published policy crossed the exact aggregate generation");
            }
            if (this.publishedByExactLeaf.put(leaf, published) != null) {
                throw new IllegalArgumentException("One exact resource leaf may have only one published policy");
            }
        }
    }

    static X7PolicyResourceRecord forCanonicalCompleteSet(
            ModelRegistryGeneration exactGeneration, CompletedGenerationResourceSet exactSet) {
        ModelRegistryGeneration checkedGeneration = Objects.requireNonNull(exactGeneration, "exactGeneration");
        CompletedGenerationResourceSet checkedSet = Objects.requireNonNull(exactSet, "exactSet");
        IdentityHashMap<CompletedGenerationResourceSet.ResourceLeaf, X7GenerationPerformancePlan.Published> policies;
        try {
            policies = X7GenerationResourceBridge.publishExactLeafPolicies(checkedGeneration, checkedSet);
        } catch (RuntimeException unavailablePolicy) {
            // T2a3 is additive to the already accepted T2a2 ownership transaction.  A future incomplete/noncanonical
            // policy source must fail closed for submitted GPU work, not turn a previously valid physical aggregate
            // back into a publication failure or caller-owned resurrection path.
            policies = new IdentityHashMap<>();
        }
        return new X7PolicyResourceRecord(
                checkedGeneration,
                checkedSet,
                policies);
    }

    synchronized boolean ownsExactly(ModelRegistryGeneration generation, CompletedGenerationResourceSet resourceSet) {
        return !terminallyCleared && exactGeneration == generation && exactSet == resourceSet;
    }

    synchronized ExactLeafPolicy findExactLeafPolicy(
            ModelRegistryGeneration generation,
            Object handleIdentity,
            Object modelIdentity,
            Object geometryIdentity,
            Object materialIdentity,
            Object lodIdentity) {
        if (terminallyCleared || exactGeneration != generation || exactSet == null) {
            throw new IllegalStateException("The exact D1 policy-resource record is no longer retained");
        }
        if (futureGpuDisabled) {
            throw new IllegalStateException("The exact D1 policy-resource record has disabled future GPU submission");
        }
        ExactLeafPolicy match = null;
        for (Map.Entry<CompletedGenerationResourceSet.ResourceLeaf, X7GenerationPerformancePlan.Published> entry
                : publishedByExactLeaf.entrySet()) {
            X7GenerationPerformancePlan.Published published = entry.getValue();
            X7GenerationPerformancePlan.Binding binding = published.binding();
            if (binding.generation() != generation.generationId()
                    || binding.handleIdentity() != handleIdentity
                    || binding.modelIdentity() != modelIdentity
                    || binding.geometryIdentity() != geometryIdentity
                    || binding.materialIdentity() != materialIdentity
                    || binding.lodIdentity() != lodIdentity) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException("One submitted draw ambiguously matches multiple exact D1 policy leaves");
            }
            match = new ExactLeafPolicy(entry.getKey(), published);
        }
        if (match == null) {
            throw new IllegalStateException("No exact D1 policy leaf authorizes this submitted draw");
        }
        return match;
    }

    synchronized int mappedLeafCount() {
        return publishedByExactLeaf.size();
    }

    synchronized boolean isTerminallyCleared() {
        return terminallyCleared;
    }

    /**
     * Idempotently fuses this exact D1 record to the reliable CPU route for all later Stage-A attempts.
     *
     * <p>The bit is retained by this record rather than an adapter cache, so a target/pipeline/device/pass
     * pre-command failure cannot disable an equal-but-different generation or survive this record's terminal
     * removal. The return value is true only for the one false-to-true transition.</p>
     */
    synchronized boolean disableFutureGpu() {
        if (terminallyCleared || futureGpuDisabled) {
            return false;
        }
        futureGpuDisabled = true;
        return true;
    }

    /** True when this exact retained record may no longer authorize a newly admitted GPU child. */
    synchronized boolean isFutureGpuDisabled() {
        return !terminallyCleared && futureGpuDisabled;
    }

    /** Called only from the lifecycle record's terminal removal action after physical leaves have closed. */
    synchronized void clearAfterTerminalRecordRemoval() {
        if (terminallyCleared) {
            return;
        }
        publishedByExactLeaf.clear();
        exactGeneration = null;
        exactSet = null;
        futureGpuDisabled = true;
        terminallyCleared = true;
    }

    record ExactLeafPolicy(
            CompletedGenerationResourceSet.ResourceLeaf exactLeaf,
            X7GenerationPerformancePlan.Published published) {
        ExactLeafPolicy {
            exactLeaf = Objects.requireNonNull(exactLeaf, "exactLeaf");
            published = Objects.requireNonNull(published, "published");
        }
    }
}
