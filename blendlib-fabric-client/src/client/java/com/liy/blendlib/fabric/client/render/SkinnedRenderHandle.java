package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable reload-time handle for a strict {@code skinned_v1} asset generation.
 *
 * <p>It owns only prepared source geometry, material routing, rest-node transforms, and immutable
 * generation metadata. CPU skinning belongs to extraction, and render submit accepts only the
 * resulting {@link SkinnedRenderSnapshot}; this handle performs no resource access or animation
 * work after preparation.</p>
 */
public final class SkinnedRenderHandle implements ModelRenderHandle {
    private final BlendModelKey modelKey;
    private final long generation;
    private final List<Transform> nodeWorldTransforms;
    private final List<PreparedSkinnedRenderPrimitive> skinnedPrimitives;
    private final int[][] skinJointNodeIndexes;
    private final List<String> materialSlots;
    private final NamedSkinCatalog namedSkins;
    private final Bounds bounds;
    private final float unitsToBlocksScale;

    private SkinnedRenderHandle(
            BlendModelKey modelKey,
            long generation,
            List<Transform> nodeWorldTransforms,
            List<PreparedSkinnedRenderPrimitive> skinnedPrimitives,
            int[][] skinJointNodeIndexes,
            List<String> materialSlots,
            java.util.Set<String> authoredSlots,
            Bounds bounds,
            float unitsToBlocksScale,
            Map<BlendResourceId, Map<String, BlendResourceId>> validDefinitions,
            Map<BlendResourceId, String> invalidDiagnostics) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.generation = generation;
        this.nodeWorldTransforms = List.copyOf(Objects.requireNonNull(nodeWorldTransforms, "nodeWorldTransforms"));
        this.skinnedPrimitives = List.copyOf(Objects.requireNonNull(skinnedPrimitives, "skinnedPrimitives"));
        this.skinJointNodeIndexes = copySkinJointNodeIndexes(skinJointNodeIndexes);
        if (this.skinnedPrimitives.isEmpty()) {
            throw new IllegalArgumentException("A skinned render handle needs at least one primitive");
        }
        this.materialSlots = List.copyOf(materialSlots);
        this.namedSkins = NamedSkinCatalog.prepare(this.skinnedPrimitives.stream().map(PreparedSkinnedRenderPrimitive::material).toList(),
                this.materialSlots, authoredSlots, validDefinitions, invalidDiagnostics);
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
        return prepareWithSkins(modelKey, asset, materialResolver, Map.of(), Map.of());
    }

    /** Prepares named texture skins without additional resource I/O or geometry copies. */
    public static SkinnedRenderHandle prepareWithSkins(
            BlendModelKey modelKey, ModelAsset asset,
            Map<BlendResourceId, Map<String, BlendResourceId>> validDefinitions,
            Map<BlendResourceId, String> invalidDiagnostics) {
        return prepareWithSkins(modelKey, asset, MaterialRenderMapper.defaultResolver(), validDefinitions, invalidDiagnostics);
    }

    static SkinnedRenderHandle prepareWithSkins(
            BlendModelKey modelKey, ModelAsset asset, MaterialRenderMapper.MaterialResolver materialResolver,
            Map<BlendResourceId, Map<String, BlendResourceId>> validDefinitions,
            Map<BlendResourceId, String> invalidDiagnostics) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(materialResolver, "materialResolver");
        if (!modelKey.resourceId().equals(asset.modelKey())) {
            throw new IllegalArgumentException("Render handle model key must match the loaded asset identity");
        }
        if (!asset.profile().skinned() || asset.skeleton() == null) {
            throw new IllegalArgumentException("P5 CPU skinning accepts only skinned-v1 assets with skeleton data");
        }

        List<Transform> transforms = StaticRigidRenderHandle.calculateWorldTransforms(asset.nodes());
        int[][] skinJointNodeIndexes = prepareSkinJointNodeIndexes(asset);
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
        return new SkinnedRenderHandle(
                modelKey, asset.generation(), transforms, prepared, skinJointNodeIndexes, asset.primitives().stream().map(p -> p.geometry().materialSlot()).toList(), asset.materials().keySet(), bounds, unitsToBlocksScale, validDefinitions, invalidDiagnostics);
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
    public NamedSkinCatalog namedSkins() { return namedSkins; }

    @Override
    public List<String> materialSlots() { return materialSlots; }

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

    /**
     * Returns the exact canonical node retained for one prepared skin joint.
     *
     * <p>This package-private lookup exposes no mutable skeleton data. X6 uses it only while
     * binding a catalog to this exact reload-time handle, before provider pinning; submit never
     * resolves bone identities.</p>
     */
    int skinJointNodeIndex(int skinIndex, int jointIndex) {
        if (skinIndex < 0 || skinIndex >= skinJointNodeIndexes.length) {
            throw new IndexOutOfBoundsException("skinIndex outside prepared skinned handle: " + skinIndex);
        }
        int[] joints = skinJointNodeIndexes[skinIndex];
        if (jointIndex < 0 || jointIndex >= joints.length) {
            throw new IndexOutOfBoundsException("jointIndex outside prepared skinned handle skin: " + jointIndex);
        }
        return joints[jointIndex];
    }

    @Override
    public boolean missingModel() {
        return false;
    }

    private static int[][] prepareSkinJointNodeIndexes(ModelAsset asset) {
        List<Skin> skins = asset.skeleton().skins();
        int[][] result = new int[skins.size()][];
        for (int skinIndex = 0; skinIndex < skins.size(); skinIndex++) {
            List<Integer> joints = skins.get(skinIndex).joints();
            int[] nodes = new int[joints.size()];
            for (int jointIndex = 0; jointIndex < joints.size(); jointIndex++) {
                int nodeIndex = Objects.requireNonNull(joints.get(jointIndex), "skin joint node index");
                if (nodeIndex < 0 || nodeIndex >= asset.nodes().size()) {
                    throw new IllegalArgumentException("Skinned handle skin joint references a missing node");
                }
                nodes[jointIndex] = nodeIndex;
            }
            result[skinIndex] = nodes;
        }
        return result;
    }

    private static int[][] copySkinJointNodeIndexes(int[][] source) {
        Objects.requireNonNull(source, "skinJointNodeIndexes");
        int[][] copy = new int[source.length][];
        for (int skinIndex = 0; skinIndex < source.length; skinIndex++) {
            int[] joints = Objects.requireNonNull(source[skinIndex], "skin joint node indexes");
            if (joints.length == 0) {
                throw new IllegalArgumentException("Prepared skinned handle may not retain an empty skin joint table");
            }
            copy[skinIndex] = java.util.Arrays.copyOf(joints, joints.length);
        }
        return copy;
    }
}
