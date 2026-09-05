package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderMaterial;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical pre-publication assembler for X7 completed generation resource leaves.
 *
 * <p>The authoritative renderable universe is enumerated directly from one immutable
 * {@link ModelRegistryGeneration}; callers may submit preparation decisions, but may never
 * provide, shrink, or sign the universe itself. This class never calls a Minecraft device,
 * invokes allocation/upload, creates a transaction, or publishes a plan. Its sole output is an
 * explicit CPU-only composition, proven against the whole authoritative universe, or one
 * caller-owned complete aggregate for the identical generation object.</p>
 */
final class X7GenerationResourceBridge {
    private X7GenerationResourceBridge() {
    }

    /**
     * Captures the entire immutable renderable primitive universe owned by one exact generation.
     *
     * <p>The constructor is private and this factory accepts no caller-provided geometry list, so
     * a package peer cannot forge a complete subset. The inventory retains exact handle, render
     * handle, primitive, geometry, material, and LOD-route identities for later verification.</p>
     */
    static AuthoritativeGenerationInventory authoritativeInventory(ModelRegistryGeneration exactGeneration) {
        return AuthoritativeGenerationInventory.fromExactGeneration(exactGeneration);
    }

    /**
     * Freezes one preparation decision for every entry in the authoritative exact-generation
     * inventory. A missing, extra, duplicate, cross-generation, or identity-mismatched plan is
     * rejected before any resource attempt can be composed.
     */
    static FrozenSelectionSet freezeCanonicalSelections(
            AuthoritativeGenerationInventory authoritativeInventory,
            List<X7GenerationPerformancePlan.Prepared> preparedPlans) {
        AuthoritativeGenerationInventory checkedInventory = Objects.requireNonNull(
                authoritativeInventory, "authoritativeInventory");
        checkedInventory.requireAuthoritativeExactGeneration();

        IdentityHashMap<AuthoritativeGenerationInventory.AuthoritativeEntry, X7GenerationPerformancePlan.Prepared>
                plansByEntry = new IdentityHashMap<>();
        for (X7GenerationPerformancePlan.Prepared preparedPlan : Objects.requireNonNull(preparedPlans, "preparedPlans")) {
            X7GenerationPerformancePlan.Prepared checkedPlan = Objects.requireNonNull(preparedPlan, "prepared plan");
            if (checkedPlan.binding().generation() != checkedInventory.exactGeneration().generationId()) {
                throw new IllegalArgumentException("An authoritative X7 preparation binding crossed the exact generation");
            }
            AuthoritativeGenerationInventory.AuthoritativeEntry entry =
                    checkedInventory.findExactBinding(checkedPlan.binding());
            if (entry == null) {
                throw new IllegalArgumentException("A preparation binding has no authoritative X7 classification");
            }
            if (plansByEntry.put(entry, checkedPlan) != null) {
                throw new IllegalArgumentException("An authoritative X7 classification has duplicate preparation bindings");
            }
        }
        if (plansByEntry.size() != checkedInventory.entryCount()) {
            throw new IllegalArgumentException(
                    "Every authoritative exact-generation X7 classification requires one preparation binding");
        }

        List<FrozenSelection> selections = new ArrayList<>(checkedInventory.entryCount());
        for (AuthoritativeGenerationInventory.AuthoritativeEntry entry : checkedInventory.entriesInDeterministicOrder()) {
            X7GenerationPerformancePlan.Prepared preparedPlan = plansByEntry.get(entry);
            if (preparedPlan == null) {
                throw new IllegalArgumentException("An authoritative X7 classification was omitted from preparation");
            }
            selections.add(new FrozenSelection(checkedInventory, entry, preparedPlan));
        }
        return new FrozenSelectionSet(checkedInventory, selections);
    }

    static ResourceAttempt resourceAttempt(FrozenSelection selection, X7GpuResourceFactory.Attempt attempt) {
        return new ResourceAttempt(selection, attempt);
    }

    /**
     * Builds the only leaf-to-policy association that D1 may retain for a canonical complete aggregate.
     *
     * <p>This method is intentionally called during lifecycle-record preallocation, before resource readiness.  It
     * walks the aggregate's serial leaf identities and the exact generation's authoritative source universe once,
     * then stores the actual {@link X7GenerationPerformancePlan.Published} objects in the record.  It creates no
     * global cache and is never a post-publication attachment path.  A synthetic test leaf or an unrecognised
     * canonical key simply receives no GPU-submission mapping and therefore remains CPU-only at submission time.</p>
     */
    static IdentityHashMap<CompletedGenerationResourceSet.ResourceLeaf, X7GenerationPerformancePlan.Published>
            publishExactLeafPolicies(
                    ModelRegistryGeneration exactGeneration, CompletedGenerationResourceSet exactSet) {
        ModelRegistryGeneration checkedGeneration = Objects.requireNonNull(exactGeneration, "exactGeneration");
        CompletedGenerationResourceSet checkedSet = Objects.requireNonNull(exactSet, "exactSet");
        if (checkedSet.exactGeneration() != checkedGeneration) {
            throw new IllegalArgumentException("Canonical policy publication requires the exact aggregate generation");
        }
        AuthoritativeGenerationInventory inventory = authoritativeInventory(checkedGeneration);
        IdentityHashMap<CompletedGenerationResourceSet.ResourceLeaf, X7GenerationPerformancePlan.Published> result =
                new IdentityHashMap<>();
        for (CompletedGenerationResourceSet.ResourceLeaf leaf : checkedSet.leavesInDeterministicOrder()) {
            X7GenerationPerformancePlan.Binding binding;
            try {
                binding = inventory.preparationBindingFor(leaf.key());
            } catch (IllegalArgumentException notAuthoritative) {
                // Generic test leaves and an incomplete/noncanonical future producer must never become a
                // submission authority merely because their value key happens to be structurally plausible.
                continue;
            }
            X7GenerationPerformancePlan.Published published = X7GenerationPerformancePlan
                    .prepare(binding, X7GenerationPerformancePlan.CapabilitySnapshot.gpuReady())
                    .publish();
            result.put(leaf, published);
        }
        return result;
    }

    /**
     * Verifies the proof-backed complete authoritative set before transferring successful leaves
     * into one aggregate. CPU-only is allowed only when the exact authoritative inventory has
     * classified zero entries for GPU ownership.
     */
    static Composition compose(FrozenSelectionSet frozenSelections, List<ResourceAttempt> resourceAttempts) {
        FrozenSelectionSet checkedSelections = Objects.requireNonNull(frozenSelections, "frozenSelections");
        checkedSelections.requireCompleteClassification();
        List<X7SharedGeometryResources> callerOwnedResources = new ArrayList<>();
        boolean transferredToAggregate = false;
        try {
            List<ResourceAttempt> attempts = copyAttempts(resourceAttempts, callerOwnedResources);
            Map<FrozenSelection, X7GpuResourceFactory.Attempt> bySelection = indexAttempts(checkedSelections, attempts);
            List<CompletedGenerationResourceSet.ResourceLeaf> leaves = new ArrayList<>();
            for (FrozenSelection selection : checkedSelections.selectionsInDeterministicOrder()) {
                X7GpuResourceFactory.Attempt attempt = bySelection.remove(selection);
                if (selection.isCpuFallback()) {
                    if (attempt != null) {
                        throw new IllegalArgumentException("A CPU fallback selection cannot own a GPU resource attempt");
                    }
                    continue;
                }
                if (attempt == null) {
                    throw new IllegalArgumentException("Every frozen GPU selection requires one completed resource attempt");
                }
                X7SharedGeometryResources resource = successfulResourceOrThrow(attempt);
                if (!selection.key().equals(resource.key())) {
                    throw new IllegalArgumentException("A completed X7 resource attempt does not match its authoritative full key");
                }
                leaves.add(resource);
            }
            if (!bySelection.isEmpty()) {
                throw new IllegalArgumentException("An X7 resource attempt has no frozen authoritative selection");
            }
            if (leaves.isEmpty()) {
                checkedSelections.requireZeroGpuSelections();
                return Composition.cpuOnly();
            }
            transferredToAggregate = true;
            return Composition.gpuAggregate(CompletedGenerationResourceSet.complete(checkedSelections.exactGeneration(), leaves));
        } catch (Throwable failure) {
            if (!transferredToAggregate) {
                rethrowAfterClosingResources(failure, callerOwnedResources);
            }
            CompletedGenerationResourceSet.throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }
    }

    private static List<ResourceAttempt> copyAttempts(
            List<ResourceAttempt> resourceAttempts, List<X7SharedGeometryResources> callerOwnedResources) {
        List<ResourceAttempt> attempts = new ArrayList<>();
        IdentityHashMap<X7SharedGeometryResources, Boolean> seenResources = new IdentityHashMap<>();
        for (ResourceAttempt attempt : Objects.requireNonNull(resourceAttempts, "resourceAttempts")) {
            ResourceAttempt checkedAttempt = Objects.requireNonNull(attempt, "resource attempt");
            attempts.add(checkedAttempt);
            X7SharedGeometryResources resource = checkedAttempt.attempt().resourcesOrNull();
            if (resource != null && seenResources.put(resource, Boolean.TRUE) == null) {
                callerOwnedResources.add(resource);
            }
        }
        return List.copyOf(attempts);
    }

    private static Map<FrozenSelection, X7GpuResourceFactory.Attempt> indexAttempts(
            FrozenSelectionSet selections, List<ResourceAttempt> attempts) {
        Map<FrozenSelection, X7GpuResourceFactory.Attempt> bySelection = new IdentityHashMap<>();
        for (ResourceAttempt resourceAttempt : attempts) {
            if (!selections.contains(resourceAttempt.selection())) {
                throw new IllegalArgumentException("An X7 resource attempt came from another authoritative selection set");
            }
            if (bySelection.put(resourceAttempt.selection(), resourceAttempt.attempt()) != null) {
                throw new IllegalArgumentException("A frozen X7 selection may own only one resource attempt");
            }
        }
        return bySelection;
    }

    private static X7SharedGeometryResources successfulResourceOrThrow(X7GpuResourceFactory.Attempt attempt) {
        if (attempt.outcome() == X7GpuResourceFactory.Attempt.Outcome.SUCCESS) {
            return Objects.requireNonNull(attempt.resourcesOrNull(), "successful X7 attempt resources");
        }
        X7GpuResourceFactory.Attempt.ResourceFailure failure = Objects.requireNonNull(
                attempt.failureOrNull(), "failed X7 resource attempt failure");
        CompletedGenerationResourceSet.throwUnchecked(failure.cause());
        throw new AssertionError("unreachable");
    }

    private static void rethrowAfterClosingResources(
            Throwable primaryFailure, List<X7SharedGeometryResources> callerOwnedResources) {
        CompletedGenerationResourceSet.CleanupFailureSelector cleanupFailures =
                new CompletedGenerationResourceSet.CleanupFailureSelector();
        for (X7SharedGeometryResources resource : callerOwnedResources) {
            try {
                resource.closeSynchronouslyAfterVerifiedD1Completion();
            } catch (Throwable closeFailure) {
                cleanupFailures.record(closeFailure);
            }
        }
        cleanupFailures.rethrowWithPrimary(primaryFailure);
    }

    /** Exact-generation-owned inventory whose universe can only be derived from registry contents. */
    static final class AuthoritativeGenerationInventory {
        private final ModelRegistryGeneration exactGeneration;
        private final Map<BlendModelKey, ModelHandle> exactHandles;
        private final List<AuthoritativeEntry> entries;
        private final List<X7GpuGenerationKey> keys;
        private final InventoryProof proof;

        private AuthoritativeGenerationInventory(
                ModelRegistryGeneration exactGeneration,
                Map<BlendModelKey, ModelHandle> exactHandles,
                List<AuthoritativeEntry> entries) {
            this.exactGeneration = Objects.requireNonNull(exactGeneration, "exactGeneration");
            this.exactHandles = Objects.requireNonNull(exactHandles, "exactHandles");
            this.entries = List.copyOf(entries);
            this.keys = this.entries.stream().map(AuthoritativeEntry::key).toList();
            this.proof = new InventoryProof(this.exactGeneration, this.exactHandles, this.entries, this.keys);
        }

        private static AuthoritativeGenerationInventory fromExactGeneration(ModelRegistryGeneration exactGeneration) {
            ModelRegistryGeneration checkedGeneration = Objects.requireNonNull(exactGeneration, "exactGeneration");
            Map<BlendModelKey, ModelHandle> exactHandles = checkedGeneration.handles();
            TreeMap<X7GpuGenerationKey, AuthoritativeEntry> entriesByKey =
                    new TreeMap<>(X7GpuGenerationKey.deterministicOrder());
            for (Map.Entry<BlendModelKey, ModelHandle> mapEntry : exactHandles.entrySet()) {
                BlendModelKey modelKey = Objects.requireNonNull(mapEntry.getKey(), "generation model key");
                ModelHandle handle = Objects.requireNonNull(mapEntry.getValue(), "generation model handle");
                ModelRenderHandle renderHandle = Objects.requireNonNull(handle.renderHandle(), "generation render handle");
                if (!modelKey.equals(handle.key())
                        || handle.generationId() != checkedGeneration.generationId()
                        || !modelKey.equals(renderHandle.modelKey())
                        || renderHandle.generation() != checkedGeneration.generationId()) {
                    throw new IllegalStateException("The exact generation has a mismatched renderable handle binding");
                }
                appendStaticEntries(checkedGeneration, entriesByKey, modelKey, handle, renderHandle);
                appendSkinnedEntries(checkedGeneration, entriesByKey, modelKey, handle, renderHandle);
            }
            return new AuthoritativeGenerationInventory(checkedGeneration, exactHandles, List.copyOf(entriesByKey.values()));
        }

        private static void appendStaticEntries(
                ModelRegistryGeneration generation,
                TreeMap<X7GpuGenerationKey, AuthoritativeEntry> entriesByKey,
                BlendModelKey modelKey,
                ModelHandle handle,
                ModelRenderHandle renderHandle) {
            List<PreparedRenderPrimitive> primitives = Objects.requireNonNull(
                    renderHandle.primitives(), "generation static primitives");
            for (int primitiveIndex = 0; primitiveIndex < primitives.size(); primitiveIndex++) {
                PreparedRenderPrimitive primitive = Objects.requireNonNull(
                        primitives.get(primitiveIndex), "generation static primitive");
                addEntry(entriesByKey, new AuthoritativeEntry(
                        generation,
                        modelKey,
                        handle,
                        renderHandle,
                        PrimitiveFamily.STATIC,
                        primitiveIndex,
                        primitive,
                        primitive.geometry(),
                        primitive.material(),
                        primitive,
                        "static/node-" + primitive.nodeIndex() + "/primitive-" + primitiveIndex));
            }
        }

        private static void appendSkinnedEntries(
                ModelRegistryGeneration generation,
                TreeMap<X7GpuGenerationKey, AuthoritativeEntry> entriesByKey,
                BlendModelKey modelKey,
                ModelHandle handle,
                ModelRenderHandle renderHandle) {
            List<PreparedSkinnedRenderPrimitive> primitives = Objects.requireNonNull(
                    renderHandle.skinnedPrimitives(), "generation skinned primitives");
            for (int primitiveIndex = 0; primitiveIndex < primitives.size(); primitiveIndex++) {
                PreparedSkinnedRenderPrimitive primitive = Objects.requireNonNull(
                        primitives.get(primitiveIndex), "generation skinned primitive");
                addEntry(entriesByKey, new AuthoritativeEntry(
                        generation,
                        modelKey,
                        handle,
                        renderHandle,
                        PrimitiveFamily.SKINNED,
                        primitiveIndex,
                        primitive,
                        primitive.geometry(),
                        primitive.material(),
                        primitive,
                        "skinned/node-" + primitive.nodeIndex() + "/skin-" + primitive.skinIndex()
                                + "/primitive-" + primitiveIndex));
            }
        }

        private static void addEntry(
                TreeMap<X7GpuGenerationKey, AuthoritativeEntry> entriesByKey, AuthoritativeEntry entry) {
            for (AuthoritativeEntry existing : entriesByKey.values()) {
                if (entry.hasSameExactBindingIdentity(existing)) {
                    throw new IllegalStateException(
                            "The exact generation has duplicate authoritative X7 primitive identities");
                }
            }
            if (entriesByKey.putIfAbsent(entry.key(), entry) != null) {
                throw new IllegalStateException("The exact generation has duplicate authoritative X7 full keys");
            }
        }

        ModelRegistryGeneration exactGeneration() {
            return exactGeneration;
        }

        List<X7GpuGenerationKey> keysInDeterministicOrder() {
            return keys;
        }

        X7GenerationPerformancePlan.Binding preparationBindingFor(X7GpuGenerationKey key) {
            X7GpuGenerationKey checkedKey = Objects.requireNonNull(key, "key");
            for (AuthoritativeEntry entry : entries) {
                if (entry.key().equals(checkedKey)) {
                    return entry.binding();
                }
            }
            throw new IllegalArgumentException("The key is not in this authoritative exact-generation inventory");
        }

        private int entryCount() {
            return entries.size();
        }

        private List<AuthoritativeEntry> entriesInDeterministicOrder() {
            return entries;
        }

        private AuthoritativeEntry findExactBinding(X7GenerationPerformancePlan.Binding binding) {
            AuthoritativeEntry match = null;
            for (AuthoritativeEntry entry : entries) {
                if (!entry.matches(binding)) {
                    continue;
                }
                if (match != null) {
                    throw new IllegalStateException("The authoritative exact generation has ambiguous primitive identities");
                }
                match = entry;
            }
            return match;
        }

        private void requireAuthoritativeExactGeneration() {
            proof.requireFor(this);
        }

        private enum PrimitiveFamily {
            STATIC,
            SKINNED
        }

        /** One private entry rooted in a concrete immutable registry primitive. */
        private static final class AuthoritativeEntry {
            private final BlendModelKey modelKey;
            private final ModelHandle handleIdentity;
            private final ModelRenderHandle modelIdentity;
            private final PrimitiveFamily family;
            private final int primitiveIndex;
            private final Object primitiveIdentity;
            private final Object geometryIdentity;
            private final RenderMaterial materialIdentity;
            private final Object lodIdentity;
            private final X7GenerationPerformancePlan.Binding binding;
            private final X7GpuGenerationKey key;

            private AuthoritativeEntry(
                    ModelRegistryGeneration generation,
                    BlendModelKey modelKey,
                    ModelHandle handleIdentity,
                    ModelRenderHandle modelIdentity,
                    PrimitiveFamily family,
                    int primitiveIndex,
                    Object primitiveIdentity,
                    Object geometryIdentity,
                    RenderMaterial materialIdentity,
                    Object lodIdentity,
                    String geometryId) {
                this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
                this.handleIdentity = Objects.requireNonNull(handleIdentity, "handleIdentity");
                this.modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
                this.family = Objects.requireNonNull(family, "family");
                if (primitiveIndex < 0) {
                    throw new IllegalArgumentException("primitiveIndex must be non-negative");
                }
                this.primitiveIndex = primitiveIndex;
                this.primitiveIdentity = Objects.requireNonNull(primitiveIdentity, "primitiveIdentity");
                this.geometryIdentity = Objects.requireNonNull(geometryIdentity, "geometryIdentity");
                this.materialIdentity = Objects.requireNonNull(materialIdentity, "materialIdentity");
                this.lodIdentity = Objects.requireNonNull(lodIdentity, "lodIdentity");
                this.binding = new X7GenerationPerformancePlan.Binding(
                        generation.generationId(),
                        this.handleIdentity,
                        this.modelIdentity,
                        this.geometryIdentity,
                        this.materialIdentity,
                        this.lodIdentity);
                this.key = new X7GpuGenerationKey(
                        generation.generationId(),
                        this.modelKey.value(),
                        Objects.requireNonNull(geometryId, "geometryId"),
                        materialRoute(this.materialIdentity),
                        0,
                        X7GpuVertexFormat.POSITION_NORMAL_UV_F32,
                        X7PrimitiveMode.TRIANGLES,
                        X7GpuIndexType.UINT32_LE);
            }

            private X7GpuGenerationKey key() {
                return key;
            }

            private X7GenerationPerformancePlan.Binding binding() {
                return binding;
            }

            private boolean matches(X7GenerationPerformancePlan.Binding candidate) {
                return candidate.generation() == binding.generation()
                        && candidate.handleIdentity() == handleIdentity
                        && candidate.modelIdentity() == modelIdentity
                        && candidate.geometryIdentity() == geometryIdentity
                        && candidate.materialIdentity() == materialIdentity
                        && candidate.lodIdentity() == lodIdentity;
            }

            private boolean hasSameExactBindingIdentity(AuthoritativeEntry other) {
                return handleIdentity == other.handleIdentity
                        && modelIdentity == other.modelIdentity
                        && geometryIdentity == other.geometryIdentity
                        && materialIdentity == other.materialIdentity
                        && lodIdentity == other.lodIdentity;
            }

            private boolean stillMatchesSource(
                    ModelRegistryGeneration exactGeneration, Map<BlendModelKey, ModelHandle> exactHandles) {
                if (exactGeneration.handles() != exactHandles
                        || exactHandles.get(modelKey) != handleIdentity
                        || !modelKey.equals(handleIdentity.key())
                        || handleIdentity.generationId() != exactGeneration.generationId()
                        || handleIdentity.renderHandle() != modelIdentity
                        || !modelKey.equals(modelIdentity.modelKey())
                        || modelIdentity.generation() != exactGeneration.generationId()) {
                    return false;
                }
                return switch (family) {
                    case STATIC -> staticPrimitiveStillMatches();
                    case SKINNED -> skinnedPrimitiveStillMatches();
                };
            }

            private boolean staticPrimitiveStillMatches() {
                List<PreparedRenderPrimitive> primitives = modelIdentity.primitives();
                if (primitives == null || primitiveIndex >= primitives.size()) {
                    return false;
                }
                PreparedRenderPrimitive primitive = primitives.get(primitiveIndex);
                return primitive == primitiveIdentity
                        && primitive.geometry() == geometryIdentity
                        && primitive.material() == materialIdentity
                        && primitive == lodIdentity;
            }

            private boolean skinnedPrimitiveStillMatches() {
                List<PreparedSkinnedRenderPrimitive> primitives = modelIdentity.skinnedPrimitives();
                if (primitives == null || primitiveIndex >= primitives.size()) {
                    return false;
                }
                PreparedSkinnedRenderPrimitive primitive = primitives.get(primitiveIndex);
                return primitive == primitiveIdentity
                        && primitive.geometry() == geometryIdentity
                        && primitive.material() == materialIdentity
                        && primitive == lodIdentity;
            }

            private static String materialRoute(RenderMaterial material) {
                return "render/" + material.layer().name()
                        + "/" + material.textureId().value()
                        + "/emissive-" + material.emissive()
                        + "/double-sided-" + material.doubleSided()
                        + "/tint-" + Integer.toUnsignedString(material.argbTint(), 16)
                        + "/missing-" + material.missingModelMaterial();
            }
        }

        /** Private non-forgeable witness that inventory entries still match the exact generation. */
        private static final class InventoryProof {
            private final ModelRegistryGeneration exactGeneration;
            private final Map<BlendModelKey, ModelHandle> exactHandles;
            private final List<AuthoritativeEntry> entries;
            private final List<X7GpuGenerationKey> keys;

            private InventoryProof(
                    ModelRegistryGeneration exactGeneration,
                    Map<BlendModelKey, ModelHandle> exactHandles,
                    List<AuthoritativeEntry> entries,
                    List<X7GpuGenerationKey> keys) {
                this.exactGeneration = exactGeneration;
                this.exactHandles = exactHandles;
                this.entries = List.copyOf(entries);
                this.keys = List.copyOf(keys);
            }

            private void requireFor(AuthoritativeGenerationInventory candidate) {
                if (candidate.exactGeneration != exactGeneration
                        || candidate.exactHandles != exactHandles
                        || candidate.entries.size() != entries.size()
                        || candidate.keys.size() != keys.size()
                        || exactGeneration.handles() != exactHandles) {
                    throw new IllegalStateException("The authoritative X7 inventory no longer belongs to its exact generation");
                }
                for (int index = 0; index < entries.size(); index++) {
                    AuthoritativeEntry entry = candidate.entries.get(index);
                    if (entry != entries.get(index)
                            || !keys.get(index).equals(candidate.keys.get(index))
                            || !entry.stillMatchesSource(exactGeneration, exactHandles)) {
                        throw new IllegalStateException(
                                "The authoritative X7 inventory no longer matches exact generation renderable contents");
                    }
                }
            }
        }
    }

    static final class FrozenSelectionSet {
        private final AuthoritativeGenerationInventory authoritativeInventory;
        private final ModelRegistryGeneration exactGeneration;
        private final List<FrozenSelection> selections;
        private final IdentityHashMap<FrozenSelection, Boolean> containedSelections;
        private final CompleteClassificationProof classificationProof;

        private FrozenSelectionSet(AuthoritativeGenerationInventory authoritativeInventory, List<FrozenSelection> selections) {
            this.authoritativeInventory = Objects.requireNonNull(authoritativeInventory, "authoritativeInventory");
            this.authoritativeInventory.requireAuthoritativeExactGeneration();
            this.exactGeneration = authoritativeInventory.exactGeneration();
            this.selections = List.copyOf(selections);
            if (this.selections.size() != authoritativeInventory.entryCount()) {
                throw new IllegalArgumentException("Frozen X7 selections must cover the whole authoritative inventory");
            }
            this.containedSelections = new IdentityHashMap<>();
            int gpuSelectionCount = 0;
            List<AuthoritativeGenerationInventory.AuthoritativeEntry> expectedEntries = new ArrayList<>(this.selections.size());
            for (int index = 0; index < this.selections.size(); index++) {
                FrozenSelection selection = this.selections.get(index);
                AuthoritativeGenerationInventory.AuthoritativeEntry expectedEntry =
                        authoritativeInventory.entriesInDeterministicOrder().get(index);
                if (selection.authoritativeInventory() != authoritativeInventory
                        || selection.authoritativeEntry() != expectedEntry
                        || containedSelections.put(selection, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException("Frozen X7 selections must be exact, ordered, and identity-unique");
                }
                expectedEntries.add(expectedEntry);
                if (!selection.isCpuFallback()) {
                    gpuSelectionCount++;
                }
            }
            this.classificationProof = new CompleteClassificationProof(
                    authoritativeInventory,
                    expectedEntries,
                    this.selections.stream().map(FrozenSelection::key).toList(),
                    gpuSelectionCount);
        }

        ModelRegistryGeneration exactGeneration() {
            return exactGeneration;
        }

        List<FrozenSelection> selectionsInDeterministicOrder() {
            return selections;
        }

        private boolean contains(FrozenSelection selection) {
            return containedSelections.containsKey(selection);
        }

        private void requireCompleteClassification() {
            classificationProof.requireFor(this);
        }

        private void requireZeroGpuSelections() {
            if (classificationProof.gpuSelectionCount != 0) {
                throw new IllegalStateException("CPU-only composition requires a complete classification with zero GPU selections");
            }
        }

        /** Private proof is created only after authoritative inventory and exact preparation coverage agree. */
        private static final class CompleteClassificationProof {
            private final AuthoritativeGenerationInventory authoritativeInventory;
            private final List<AuthoritativeGenerationInventory.AuthoritativeEntry> authoritativeEntries;
            private final List<X7GpuGenerationKey> canonicalKeys;
            private final int gpuSelectionCount;

            private CompleteClassificationProof(
                    AuthoritativeGenerationInventory authoritativeInventory,
                    List<AuthoritativeGenerationInventory.AuthoritativeEntry> authoritativeEntries,
                    List<X7GpuGenerationKey> canonicalKeys,
                    int gpuSelectionCount) {
                this.authoritativeInventory = authoritativeInventory;
                this.authoritativeEntries = List.copyOf(authoritativeEntries);
                this.canonicalKeys = List.copyOf(canonicalKeys);
                this.gpuSelectionCount = gpuSelectionCount;
            }

            private void requireFor(FrozenSelectionSet candidate) {
                authoritativeInventory.requireAuthoritativeExactGeneration();
                if (candidate.authoritativeInventory != authoritativeInventory
                        || candidate.exactGeneration != authoritativeInventory.exactGeneration()
                        || candidate.selections.size() != canonicalKeys.size()
                        || authoritativeEntries.size() != canonicalKeys.size()) {
                    throw new IllegalStateException("Frozen X7 authoritative classification proof no longer matches its selection set");
                }
                int observedGpuSelections = 0;
                for (int index = 0; index < candidate.selections.size(); index++) {
                    FrozenSelection selection = candidate.selections.get(index);
                    if (selection.authoritativeEntry() != authoritativeEntries.get(index)
                            || !canonicalKeys.get(index).equals(selection.key())
                            || selection.exactGeneration() != candidate.exactGeneration) {
                        throw new IllegalStateException("Frozen X7 authoritative classification proof has an exact-binding mismatch");
                    }
                    if (!selection.isCpuFallback()) {
                        observedGpuSelections++;
                    }
                }
                if (observedGpuSelections != gpuSelectionCount) {
                    throw new IllegalStateException("Frozen X7 authoritative classification proof has a route mismatch");
                }
            }
        }
    }

    static final class FrozenSelection {
        private final AuthoritativeGenerationInventory authoritativeInventory;
        private final ModelRegistryGeneration exactGeneration;
        private final AuthoritativeGenerationInventory.AuthoritativeEntry authoritativeEntry;
        private final X7GenerationPerformancePlan.Prepared preparedPlan;

        private FrozenSelection(
                AuthoritativeGenerationInventory authoritativeInventory,
                AuthoritativeGenerationInventory.AuthoritativeEntry authoritativeEntry,
                X7GenerationPerformancePlan.Prepared preparedPlan) {
            this.authoritativeInventory = Objects.requireNonNull(authoritativeInventory, "authoritativeInventory");
            this.exactGeneration = authoritativeInventory.exactGeneration();
            this.authoritativeEntry = Objects.requireNonNull(authoritativeEntry, "authoritativeEntry");
            this.preparedPlan = Objects.requireNonNull(preparedPlan, "preparedPlan");
            if (preparedPlan.binding().generation() != exactGeneration.generationId()
                    || !authoritativeEntry.matches(preparedPlan.binding())) {
                throw new IllegalArgumentException("A frozen X7 selection must bind one exact authoritative generation entry");
            }
        }

        X7GpuGenerationKey key() {
            return authoritativeEntry.key();
        }

        boolean isCpuFallback() {
            return preparedPlan.backend() == X7GenerationPerformancePlan.BackendChoice.CPU;
        }

        private AuthoritativeGenerationInventory authoritativeInventory() {
            return authoritativeInventory;
        }

        private AuthoritativeGenerationInventory.AuthoritativeEntry authoritativeEntry() {
            return authoritativeEntry;
        }

        private ModelRegistryGeneration exactGeneration() {
            return exactGeneration;
        }
    }

    static final class ResourceAttempt {
        private final FrozenSelection selection;
        private final X7GpuResourceFactory.Attempt attempt;

        private ResourceAttempt(FrozenSelection selection, X7GpuResourceFactory.Attempt attempt) {
            this.selection = Objects.requireNonNull(selection, "selection");
            this.attempt = Objects.requireNonNull(attempt, "attempt");
        }

        FrozenSelection selection() {
            return selection;
        }

        X7GpuResourceFactory.Attempt attempt() {
            return attempt;
        }
    }

    static final class Composition {
        enum Route {
            CPU_ONLY,
            GPU_COMPLETE
        }

        private final Route route;
        private final CompletedGenerationResourceSet aggregate;

        private Composition(Route route, CompletedGenerationResourceSet aggregate) {
            this.route = Objects.requireNonNull(route, "route");
            this.aggregate = aggregate;
            if ((route == Route.GPU_COMPLETE) != (aggregate != null)) {
                throw new IllegalArgumentException("X7 bridge route and aggregate must agree");
            }
        }

        static Composition cpuOnly() {
            return new Composition(Route.CPU_ONLY, null);
        }

        static Composition gpuAggregate(CompletedGenerationResourceSet aggregate) {
            return new Composition(Route.GPU_COMPLETE, Objects.requireNonNull(aggregate, "aggregate"));
        }

        Route route() {
            return route;
        }

        CompletedGenerationResourceSet aggregateOrNull() {
            return aggregate;
        }
    }
}
