package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable D1-carried X7 state for one published model generation.
 *
 * <p>The current production candidate deliberately contains only real loaded LOD0 handles and
 * their reliable CPU plans. It has no mutable registry, resource provider, pass, GPU object, or
 * future-LOD placeholder. A later LOD/GPU implementation must replace this whole transaction
 * payload before D1 adoption rather than attach mutable state after publication.</p>
 */
final class X7PublishedGenerationProjection {
    /** Exact count-only usage of the real loaded LOD0 assets retained by this projection. */
    record GenerationUsage(long models, long primitives, long bones, long vertices) {
        GenerationUsage {
            requireNonNegative(models, "models");
            requireNonNegative(primitives, "primitives");
            requireNonNegative(bones, "bones");
            requireNonNegative(vertices, "vertices");
        }
    }

    private final long generationId;
    private final Map<BlendModelKey, Family> lodZeroFamilies;
    private final GenerationUsage generationUsage;

    private X7PublishedGenerationProjection(
            long generationId, Map<BlendModelKey, Family> lodZeroFamilies, GenerationUsage generationUsage) {
        if (generationId < 0L) {
            throw new IllegalArgumentException("generationId must be non-negative");
        }
        this.generationId = generationId;
        this.lodZeroFamilies = Map.copyOf(Objects.requireNonNull(lodZeroFamilies, "lodZeroFamilies"));
        this.generationUsage = Objects.requireNonNull(generationUsage, "generationUsage");
    }

    /**
     * Freezes only the exact loaded LOD0 entries already present in the immutable candidate.
     * Missing handles retain the existing missing-model route and therefore have no X7 admission
     * family to upgrade later.
     */
    static X7PublishedGenerationProjection cpuOnly(ModelRegistryGeneration candidate) {
        ModelRegistryGeneration checkedCandidate = Objects.requireNonNull(candidate, "candidate");
        Map<BlendModelKey, Family> families = new LinkedHashMap<>();
        checkedCandidate.handles().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> {
                    ModelHandle handle = entry.getValue();
                    if (!handle.missing()) {
                        families.put(entry.getKey(), new Family(entry.getKey(), handle));
                    }
                });
        return new X7PublishedGenerationProjection(
                checkedCandidate.generationId(), families, calculateUsage(checkedCandidate));
    }

    long generationId() {
        return generationId;
    }

    GenerationUsage generationUsage() {
        return generationUsage;
    }

    int familyCount() {
        return lodZeroFamilies.size();
    }

    /** Rejects a transaction that tries to splice a projection from another candidate. */
    void requireExactCandidate(ModelRegistryGeneration candidate) {
        ModelRegistryGeneration checkedCandidate = Objects.requireNonNull(candidate, "candidate");
        if (checkedCandidate.generationId() != generationId) {
            throw new IllegalArgumentException("X7 projection generation does not match its transaction candidate");
        }
        int loadedFamilies = 0;
        for (Map.Entry<BlendModelKey, ModelHandle> entry : checkedCandidate.handles().entrySet()) {
            ModelHandle handle = entry.getValue();
            Family family = lodZeroFamilies.get(entry.getKey());
            if (handle.missing()) {
                if (family != null) {
                    throw new IllegalArgumentException("A missing candidate handle cannot retain an X7 LOD0 family");
                }
                continue;
            }
            loadedFamilies++;
            if (family == null || !family.matches(entry.getKey(), handle)) {
                throw new IllegalArgumentException("X7 projection does not retain the exact candidate LOD0 handle");
            }
        }
        if (loadedFamilies != lodZeroFamilies.size()) {
            throw new IllegalArgumentException("X7 projection contains a LOD0 family outside its transaction candidate");
        }
    }

    /**
     * Produces the only current candidate frame-shaped projection. It intentionally reports that
     * no verified frame producer exists; no binding creation is counted as a completed frame.
     */
    X7PreparedFrameProjection prepareUnavailableFrame(
            ClientModelView sourceView, ModelRenderSnapshot snapshot) {
        ClientModelView checkedSourceView = Objects.requireNonNull(sourceView, "sourceView");
        ModelRenderSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (checkedSourceView.generationId() != generationId || checkedSnapshot.generation() != generationId) {
            throw new IllegalStateException("X7 LOD0 projection belongs to another generation");
        }
        Family family = lodZeroFamilies.get(checkedSourceView.key());
        if (family == null) {
            throw new IllegalStateException("X7 CPU projection has no loaded LOD0 family for the source model");
        }
        return family.prepareUnavailableFrame(checkedSourceView, checkedSnapshot);
    }

    private static GenerationUsage calculateUsage(ModelRegistryGeneration candidate) {
        Map<ModelAsset, Boolean> countedAssets = new IdentityHashMap<>();
        long models = 0L;
        long primitives = 0L;
        long bones = 0L;
        long vertices = 0L;
        for (ModelHandle handle : candidate.handles().values()) {
            if (!(handle instanceof LoadedModelHandle loaded) || countedAssets.put(loaded.asset(), Boolean.TRUE) != null) {
                continue;
            }
            ModelAsset asset = loaded.asset();
            models = Math.addExact(models, 1L);
            primitives = Math.addExact(primitives, asset.primitives().size());
            Set<Integer> joints = new HashSet<>();
            if (asset.skeleton() != null) {
                asset.skeleton().skins().forEach(skin -> joints.addAll(skin.joints()));
            }
            bones = Math.addExact(bones, joints.size());
            for (ModelPrimitive primitive : asset.primitives()) {
                vertices = Math.addExact(vertices, primitive.geometry().vertexCount());
            }
        }
        return new GenerationUsage(models, primitives, bones, vertices);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    /** One exact real LOD0 handle. There is intentionally no synthetic higher-level handle. */
    private static final class Family {
        private final BlendModelKey key;
        private final ModelHandle modelHandle;
        private final ModelRenderHandle renderHandle;
        private final X7GenerationPerformancePlan.CpuSubsetPublished cpuRoute;

        private Family(BlendModelKey key, ModelHandle modelHandle) {
            this.key = Objects.requireNonNull(key, "key");
            this.modelHandle = Objects.requireNonNull(modelHandle, "modelHandle");
            this.renderHandle = modelHandle.renderHandle();
            if (!key.equals(modelHandle.key()) || modelHandle.missing()) {
                throw new IllegalArgumentException("An X7 LOD0 family requires one exact loaded model handle");
            }
            this.cpuRoute = X7GenerationPerformancePlan.prepareCpuOnly(
                    new X7GenerationPerformancePlan.CpuSubsetBinding(
                            modelHandle.generationId(), renderHandle, modelHandle))
                    .publish();
        }

        private boolean matches(BlendModelKey expectedKey, ModelHandle expectedHandle) {
            return key.equals(expectedKey)
                    && modelHandle == expectedHandle
                    && renderHandle == expectedHandle.renderHandle();
        }

        private X7PreparedFrameProjection prepareUnavailableFrame(
                ClientModelView sourceView, ModelRenderSnapshot snapshot) {
            if (!key.equals(sourceView.key())
                    || sourceView.renderHandle() != renderHandle
                    || snapshot.handle() != renderHandle) {
                throw new IllegalArgumentException("X7 CPU projection requires the exact source and snapshot LOD0 handle");
            }
            cpuRoute.requireCurrentCpuBinding(snapshot.generation(), snapshot.handle(), modelHandle);
            return X7PreparedFrameProjection.unavailable(sourceView, snapshot, modelHandle, cpuRoute);
        }
    }
}
