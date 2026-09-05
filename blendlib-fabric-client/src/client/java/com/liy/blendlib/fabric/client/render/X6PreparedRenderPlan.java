package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable X6 adapter plan tied to one published provider-generation lease and exact handle. */
public final class X6PreparedRenderPlan implements AutoCloseable {
    private static final Minecraft2612StaticRigidRenderBackend RENDER_TYPES = new Minecraft2612StaticRigidRenderBackend();

    private final X6VariantApplicationPlan variants;
    private final X6RenderLayerPlan layers;
    private final X6MaterialPlan materials;
    private final X6PreparedGeometryCatalog geometry;
    private final ModelRenderHandle boundHandle;
    private final int boundSkinnedMeshCount;
    private final List<X6MaterialProviderBinding> materialProviderBindings;
    private final List<X6PreparedDraw> baseDraws;
    private final List<X6PreparedLayerSubmission> layerSubmissions;
    private final X6LifecycleDrainBridge lifecycleDrain;
    private final boolean permitsFrozenCpuRoute;
    private boolean closeRequested;
    private int inFlightSubmissions;

    X6PreparedRenderPlan(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            ModelRenderHandle boundHandle,
            int boundSkinnedMeshCount,
            List<X6MaterialProviderBinding> materialProviderBindings,
            X6LifecycleDrainBridge lifecycleDrain) {
        this(
                variants,
                layers,
                materials,
                geometry,
                boundHandle,
                boundSkinnedMeshCount,
                materialProviderBindings,
                lifecycleDrain,
                true);
    }

    X6PreparedRenderPlan(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            ModelRenderHandle boundHandle,
            int boundSkinnedMeshCount,
            List<X6MaterialProviderBinding> materialProviderBindings,
            X6LifecycleDrainBridge lifecycleDrain,
            boolean permitsFrozenCpuRoute) {
        this.variants = Objects.requireNonNull(variants, "variants");
        this.layers = Objects.requireNonNull(layers, "layers");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.boundHandle = Objects.requireNonNull(boundHandle, "boundHandle");
        if (boundSkinnedMeshCount < -1 || geometry.skinned() != (boundSkinnedMeshCount >= 0)) {
            throw new X6PreparationException(
                    X6DiagnosticCode.GEOMETRY_MISMATCH,
                    "X6 bound snapshot shape must match its exact prepared geometry catalog");
        }
        this.boundSkinnedMeshCount = boundSkinnedMeshCount;
        this.materialProviderBindings = List.copyOf(Objects.requireNonNull(materialProviderBindings, "materialProviderBindings"));
        this.lifecycleDrain = Objects.requireNonNull(lifecycleDrain, "lifecycleDrain");
        this.permitsFrozenCpuRoute = permitsFrozenCpuRoute;
        if (!variants.modelKey().equals(layers.modelKey()) || variants.generation() != layers.generation()
                || !variants.modelKey().equals(materials.modelKey()) || variants.generation() != materials.generation()
                || !variants.modelKey().equals(geometry.modelKey()) || variants.generation() != geometry.generation()
                || !variants.modelKey().equals(boundHandle.modelKey()) || variants.generation() != boundHandle.generation()) {
            throw new X6PreparationException(
                    X6DiagnosticCode.GENERATION_MISMATCH,
                    "X6 variants, layers, materials, geometry, and bound handle must belong to one exact generation");
        }
        if (lifecycleDrain.generation() != variants.generation()) {
            throw new X6PreparationException(
                    X6DiagnosticCode.CAPABILITY_FAILURE,
                    "X6 provider lease must pin the exact render-plan generation");
        }
        this.baseDraws = prepareBaseDraws(variants, geometry);
        this.layerSubmissions = prepareLayerSubmissions(layers, baseDraws, geometry);
    }

    public BlendModelKey modelKey() {
        return variants.modelKey();
    }

    public long generation() {
        return variants.generation();
    }

    public X6VariantApplicationPlan variants() {
        return variants;
    }

    public X6RenderLayerPlan layers() {
        return layers;
    }

    /** Complete generation-matched material authority retained by this executable plan. */
    public X6MaterialPlan materials() {
        return materials;
    }

    /** Exact immutable handle identity validated before publication. */
    ModelRenderHandle boundHandle() {
        return boundHandle;
    }

    /** Frozen selected-provider/fallback choices; no provider object is exposed to submit. */
    public List<X6MaterialProviderBinding> materialProviderBindings() {
        return materialProviderBindings;
    }

    /** True after close was requested, even when owner-side physical lease drain is still pending. */
    public synchronized boolean isClosed() {
        return closeRequested;
    }

    /** Fails closed before submit when snapshot handle identity, generation, or lifecycle lease differs. */
    synchronized void requireCompatible(ModelRenderSnapshot snapshot) {
        requireCompatibleLocked(snapshot);
    }

    /**
     * Atomically validates and retains one primitive submit hold.
     *
     * <p>This is the submission linearization point: a close request that linearizes first makes
     * this reject, while a submit that linearizes first keeps the exact provider pin open until its
     * matching {@link #releaseSubmissionHold()}.</p>
     */
    void acquireSubmissionHold(ModelRenderSnapshot snapshot) {
        synchronized (this) {
            requireCompatibleLocked(snapshot);
            inFlightSubmissions++;
        }
    }

    /** Releases one admitted submit and signals the prebuilt lifecycle-owner task only after final drain. */
    void releaseSubmissionHold() {
        boolean requestDrain;
        synchronized (this) {
            if (inFlightSubmissions <= 0) {
                throw new IllegalStateException("X6 prepared render plan submit-hold underflow");
            }
            inFlightSubmissions--;
            requestDrain = closeRequested && inFlightSubmissions == 0;
        }
        if (requestDrain) {
            lifecycleDrain.requestDrain();
        }
    }

    private void requireCompatibleLocked(ModelRenderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!lifecycleDrain.permitsSubmission()) {
            throw new IllegalStateException(X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED.code()
                    + ": X6 lifecycle owner violated its pre-request drain contract; this plan is fail-closed");
        }
        if (closeRequested) {
            throw new IllegalStateException(X6DiagnosticCode.SNAPSHOT_LEASE_CLOSED.code()
                    + ": X6 prepared render plan lease is closed");
        }
        if (!modelKey().equals(snapshot.handle().modelKey()) || generation() != snapshot.generation()) {
            throw new IllegalArgumentException(X6DiagnosticCode.GENERATION_MISMATCH.code()
                    + ": X6 plan and render snapshot must match exact model and generation");
        }
        if (snapshot.handle() != boundHandle) {
            throw new IllegalArgumentException(X6DiagnosticCode.GEOMETRY_MISMATCH.code()
                    + ": X6 plan may submit only the exact handle validated during preparation");
        }
        if (boundSkinnedMeshCount >= 0) {
            SkinnedRenderSnapshot skinnedSnapshot = snapshot.skinnedRenderSnapshot();
            if (skinnedSnapshot == null || skinnedSnapshot.meshCount() != boundSkinnedMeshCount) {
                throw new IllegalArgumentException(X6DiagnosticCode.GEOMETRY_MISMATCH.code()
                        + ": X6 plan requires the exact prepared skinned snapshot shape");
            }
        }
    }

    List<X6PreparedDraw> baseDraws() {
        return baseDraws;
    }

    List<X6PreparedLayerSubmission> layerSubmissions() {
        return layerSubmissions;
    }

    /** Package-private lifecycle evidence seam; raw provider lease remains bridge-private. */
    X6LifecycleDrainBridge lifecycleDrain() {
        return lifecycleDrain;
    }

    /**
     * Mints one exact submitted-work child while this call's plan submission hold is still live.
     *
     * <p>This is deliberately package-private: callers receive only the existing close-only
     * receipt and cannot inspect D1 state. A future deferred endpoint must validate its exact
     * primitive, freeze all frame data, reserve queue capacity, and transfer the child atomically
     * before reporting CPU suppression.</p>
     */
    ClientGenerationLeaseBinding.DeferredSubmissionReceipt beginDeferredSubmission(
            ModelRenderSnapshot snapshot, X6DrawPrimitive draw) {
        Objects.requireNonNull(draw, "draw");
        synchronized (this) {
            requireCompatibleLocked(snapshot);
            if (inFlightSubmissions <= 0) {
                throw new IllegalStateException("X6 deferred submission requires one live plan submission hold");
            }
            boolean exactPlanDraw = baseDraws.stream().anyMatch(candidate -> candidate.draw() == draw)
                    || layerSubmissions.stream().anyMatch(candidate -> candidate.target() != null && candidate.target().draw() == draw);
            if (!exactPlanDraw) {
                throw new IllegalArgumentException("X6 deferred submission draw must be an exact prepared plan draw");
            }
        }
        return lifecycleDrain.beginDeferredSubmission(snapshot, draw);
    }

    /** Frozen managed-route primitive; per-frame visibility remains on each submitted snapshot. */
    boolean permitsFrozenCpuRoute() {
        return permitsFrozenCpuRoute;
    }

    /**
     * Requests release of only this plan's pin; X1 retirement waits for every admitted submit.
     *
     * <p>The method intentionally returns after the close request has linearized. It never waits
     * for callbacks because callers may invoke it reentrantly from one of those callbacks. Zero
     * hold and final-hold paths only signal a prebuilt lifecycle-owner task; neither path physically
     * closes the provider lease on this caller.</p>
     */
    @Override
    public void close() {
        boolean requestDrain;
        synchronized (this) {
            if (closeRequested) {
                return;
            }
            closeRequested = true;
            requestDrain = inFlightSubmissions == 0;
        }
        if (requestDrain) {
            lifecycleDrain.requestDrain();
        }
    }

    private static List<X6PreparedDraw> prepareBaseDraws(
            X6VariantApplicationPlan variants, X6PreparedGeometryCatalog geometry) {
        Map<BlendResourceId, X6PreparedDraw> byPart = new LinkedHashMap<>();
        for (X6DrawPrimitive draw : variants.draws()) {
            boolean knownBinding = geometry.bindings().values().stream().anyMatch(binding -> binding == draw.binding());
            if (!knownBinding || byPart.put(draw.partId(), new X6PreparedDraw(draw, renderType(draw.material(), draw.partId()))) != null) {
                throw new X6PreparationException(
                        X6DiagnosticCode.GEOMETRY_MISMATCH,
                        "X6 draw must retain one unique binding from its exact prepared geometry catalog");
            }
        }
        return List.copyOf(byPart.values());
    }

    private static List<X6PreparedLayerSubmission> prepareLayerSubmissions(
            X6RenderLayerPlan layers, List<X6PreparedDraw> baseDraws, X6PreparedGeometryCatalog geometry) {
        Map<BlendResourceId, X6PreparedDraw> visibleByPart = new LinkedHashMap<>();
        for (X6PreparedDraw draw : baseDraws) {
            visibleByPart.put(draw.draw().partId(), draw);
        }
        List<X6PreparedLayerSubmission> result = new ArrayList<>();
        for (X6ResolvedRenderLayer layer : layers.layers()) {
            if (layer.attachmentSnapshot().isPresent()) {
                result.add(new X6PreparedLayerSubmission(layer, null, null, null, null, Optional.empty()));
                continue;
            }
            X6PreparedDraw target = visibleByPart.get(layer.resolvedPartId().orElseThrow());
            // Visibility is final: hidden parts do not acquire a new layer-side draw later.
            if (target == null) {
                continue;
            }
            RenderMaterial material = finalLayerMaterial(layer, target.draw().material());
            X6LayerSemantics semantics = X6RenderLayerPlanner.finalSemantics(layer, material);
            Optional<X6BoneInfluenceSubset> boneSubset = boneSubset(layer, target, geometry);
            result.add(new X6PreparedLayerSubmission(
                    layer,
                    target,
                    material,
                    semantics,
                    renderType(material, layer.layerId()),
                    boneSubset));
        }
        return List.copyOf(result);
    }

    private static Optional<X6BoneInfluenceSubset> boneSubset(
            X6ResolvedRenderLayer layer, X6PreparedDraw target, X6PreparedGeometryCatalog geometry) {
        if (layer.type() != X6LayerType.PER_BONE_PART_TEXTURE) {
            return Optional.empty();
        }
        if (!(target.draw().binding() instanceof X6GeometryBinding.SkinnedBinding skinnedBinding)) {
            throw new X6PreparationException(
                    X6DiagnosticCode.LAYER_TARGET_MISSING,
                    "A per-bone X6 layer cannot target static or rigid geometry");
        }
        BlendResourceId boneId = layer.canonicalBoneId().orElseThrow(() -> new X6PreparationException(
                X6DiagnosticCode.LAYER_TARGET_MISSING,
                "A per-bone X6 layer lost its canonical bone target before final preparation"));
        X6SkinnedBoneBinding bone = geometry.skinnedBoneBinding(boneId).orElseThrow(() -> new X6PreparationException(
                X6DiagnosticCode.LAYER_TARGET_MISSING,
                "A per-bone X6 layer has no canonical skinned bone binding"));
        try {
            return Optional.of(X6BoneInfluenceSubset.prepare(boneId, bone, skinnedBinding.primitive()));
        } catch (IllegalArgumentException exception) {
            throw new X6PreparationException(
                    X6DiagnosticCode.LAYER_TARGET_MISSING,
                    "A per-bone X6 layer has no positive canonical joint influence in its final prepared mesh",
                    exception);
        }
    }

    /** Applies documented layer semantics after the final variant material is known. */
    private static RenderMaterial finalLayerMaterial(X6ResolvedRenderLayer layer, RenderMaterial finalDrawMaterial) {
        RenderMaterial source = layer.preparedMaterial().orElse(Objects.requireNonNull(finalDrawMaterial, "finalDrawMaterial"));
        return switch (layer.type()) {
            case GLOW -> new RenderMaterial(
                    source.textureId(), source.layer(), true, source.doubleSided(), source.argbTint(), source.missingModelMaterial());
            case OUTLINE -> new RenderMaterial(
                    source.textureId(), RenderLayer.CUTOUT, source.emissive(), true, source.argbTint(), source.missingModelMaterial());
            case SHADOW -> new RenderMaterial(
                    source.textureId(), RenderLayer.TRANSLUCENT, false, true, source.argbTint(), source.missingModelMaterial());
            case OVERLAY, DAMAGE_FLASH, SECONDARY_TEXTURE, PER_BONE_PART_TEXTURE -> source;
            case ATTACHMENT -> throw new X6PreparationException(
                    X6DiagnosticCode.LAYER_CONFLICT,
                    "Attachment material must not be resolved as geometry");
        };
    }

    private static net.minecraft.client.renderer.rendertype.RenderType renderType(RenderMaterial material, BlendResourceId subject) {
        try {
            return RENDER_TYPES.renderTypeFor(material);
        } catch (RuntimeException exception) {
            throw new X6PreparationException(
                    X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                    "X6 final material has no descriptor-equivalent public standard path for " + subject,
                    exception);
        }
    }
}
