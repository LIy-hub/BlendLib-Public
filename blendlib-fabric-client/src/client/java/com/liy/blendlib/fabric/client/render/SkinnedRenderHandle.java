package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable reload-time handle for a strict {@code skinned_v1} asset generation.
 *
 * <p>It owns only prepared source geometry, material routing, rest-node transforms, the core
 * asset's conservative all-clip culling envelope, and immutable generation metadata. CPU skinning
 * belongs to extraction, and render submit accepts only the resulting {@link SkinnedRenderSnapshot};
 * this handle performs no resource access or temporal bounds work after preparation.</p>
 */
public final class SkinnedRenderHandle implements ModelRenderHandle {
    private final BlendModelKey modelKey;
    private final long generation;
    private final List<Transform> nodeWorldTransforms;
    private final List<PreparedSkinnedRenderPrimitive> skinnedPrimitives;
    private final Bounds bounds;
    private final float unitsToBlocksScale;

    private SkinnedRenderHandle(
            BlendModelKey modelKey,
            long generation,
            List<Transform> nodeWorldTransforms,
            List<PreparedSkinnedRenderPrimitive> skinnedPrimitives,
            Bounds bounds,
            float unitsToBlocksScale) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.nodeWorldTransforms = List.copyOf(Objects.requireNonNull(nodeWorldTransforms, "nodeWorldTransforms"));
        this.skinnedPrimitives = List.copyOf(Objects.requireNonNull(skinnedPrimitives, "skinnedPrimitives"));
        if (this.skinnedPrimitives.isEmpty()) {
            throw new IllegalArgumentException("A skinned render handle needs at least one primitive");
        }
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.unitsToBlocksScale = unitsToBlocksScale;
    }

    /** Prepares one shareable CPU-skinning source handle from a strict loaded skinned-v1 asset. */
    public static SkinnedRenderHandle prepare(BlendModelKey modelKey, ModelAsset asset) {
        return prepare(modelKey, asset, MaterialRenderMapper.defaultResolver());
    }

    /** Internal reload-time preparation hook for the default material resolver seam. */
    static SkinnedRenderHandle prepare(
            BlendModelKey modelKey, ModelAsset asset, MaterialRenderMapper.MaterialResolver materialResolver) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(materialResolver, "materialResolver");
        if (!modelKey.resourceId().equals(asset.modelKey())) {
            throw new IllegalArgumentException("Render handle model key must match the loaded asset identity");
        }
        if (asset.profile() != ModelProfile.SKINNED_V1 || asset.skeleton() == null) {
            throw new IllegalArgumentException("P5 CPU skinning accepts only skinned-v1 assets with skeleton data");
        }

        List<Transform> transforms = StaticRigidRenderHandle.calculateWorldTransforms(asset.nodes());
        float unitsToBlocksScale = StaticRigidRenderHandle.unitsToBlocksScale(asset.unitsPerBlock());
        Bounds bounds = asset.bounds().transformed(StaticRigidRenderHandle.uniformScale(unitsToBlocksScale));
        Map<String, MaterialDefinition> materials = asset.materials();
        List<PreparedSkinnedRenderPrimitive> prepared = new ArrayList<>();
        for (ModelPrimitive primitive : asset.primitives()) {
            int nodeIndex = primitive.nodeIndex();
            if (nodeIndex < 0 || nodeIndex >= asset.nodes().size()) {
                throw new IllegalArgumentException("Skinned primitive references a missing node");
            }
            ModelNode node = asset.nodes().get(nodeIndex);
            int skinIndex = node.skinIndex();
            if (skinIndex < 0 || skinIndex >= asset.skeleton().skins().size()) {
                throw new IllegalArgumentException("Skinned primitive node must reference a decoded skin");
            }
            if (!primitive.geometry().skinned()) {
                throw new IllegalArgumentException("Skinned render handle requires JOINTS_0 and WEIGHTS_0 on every primitive");
            }
            MaterialDefinition definition = materials.get(primitive.geometry().materialSlot());
            if (definition == null) {
                throw new IllegalArgumentException("Model primitive material slot is absent from descriptor intent");
            }
            MaterialMapping mapping = materialResolver.resolve(definition);
            if (mapping instanceof MaterialMapping.Rejected rejected) {
                throw new UnsupportedRenderMaterialException(
                        modelKey, primitive.geometry().materialSlot(), rejected.reason(), rejected.message());
            }
            prepared.add(new PreparedSkinnedRenderPrimitive(
                    nodeIndex,
                    skinIndex,
                    PreparedSkinnedGeometry.prepare(primitive.geometry()),
                    ((MaterialMapping.Supported) mapping).material()));
        }
        return new SkinnedRenderHandle(modelKey, asset.generation(), transforms, prepared, bounds, unitsToBlocksScale);
    }

    @Override
    public BlendModelKey modelKey() {
        return modelKey;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

    @Override
    public float unitsToBlocksScale() {
        return unitsToBlocksScale;
    }

    /** Skinned handles have no static/rigid primitive list. */
    @Override
    public List<PreparedRenderPrimitive> primitives() {
        return List.of();
    }

    @Override
    public List<PreparedSkinnedRenderPrimitive> skinnedPrimitives() {
        return skinnedPrimitives;
    }

    @Override
    public boolean skinned() {
        return true;
    }

    @Override
    public Transform nodeTransform(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= nodeWorldTransforms.size()) {
            throw new IndexOutOfBoundsException("nodeIndex outside prepared skinned handle: " + nodeIndex);
        }
        return nodeWorldTransforms.get(nodeIndex);
    }

    @Override
    public boolean missingModel() {
        return false;
    }
}
