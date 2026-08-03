package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prebuilt static/rigid handle for one immutable core asset generation.
 *
 * <p>Geometry arrays are copied exactly once here, during handle creation. The core asset already
 * carries its load-time conservative all-clip culling envelope, so handle preparation and submit
 * perform no animation sampling or temporal bounds work.</p>
 */
public final class StaticRigidRenderHandle implements ModelRenderHandle {
    private final BlendModelKey modelKey;
    private final ModelAsset asset;
    private final List<Transform> nodeWorldTransforms;
    private final List<PreparedRenderPrimitive> primitives;
    private final Bounds bounds;
    private final float unitsToBlocksScale;

    private StaticRigidRenderHandle(
            BlendModelKey modelKey,
            ModelAsset asset,
            List<Transform> nodeWorldTransforms,
            List<PreparedRenderPrimitive> primitives,
            Bounds bounds,
            float unitsToBlocksScale) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.asset = Objects.requireNonNull(asset, "asset");
        this.nodeWorldTransforms = List.copyOf(nodeWorldTransforms);
        this.primitives = List.copyOf(primitives);
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.unitsToBlocksScale = unitsToBlocksScale;
        if (this.primitives.isEmpty()) {
            throw new IllegalArgumentException("A static/rigid render handle needs at least one primitive");
        }
    }

    /** Prepares one shareable backend handle from a loaded rigid-v1 asset. */
    public static StaticRigidRenderHandle prepare(BlendModelKey modelKey, ModelAsset asset) {
        return prepare(modelKey, asset, MaterialRenderMapper.defaultResolver());
    }

    /** Internal reload-time preparation hook for the default material resolver seam. */
    static StaticRigidRenderHandle prepare(
            BlendModelKey modelKey, ModelAsset asset, MaterialRenderMapper.MaterialResolver materialResolver) {
        Objects.requireNonNull(modelKey, "modelKey");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(materialResolver, "materialResolver");
        if (!modelKey.resourceId().equals(asset.modelKey())) {
            throw new IllegalArgumentException("Render handle model key must match the loaded asset identity");
        }
        if (asset.profile() != ModelProfile.RIGID_V1 || asset.skeleton() != null) {
            throw new IllegalArgumentException("P4 static/rigid backend accepts only rigid-v1 assets without skeleton data");
        }
        List<Transform> transforms = calculateWorldTransforms(asset.nodes());
        float unitsToBlocksScale = unitsToBlocksScale(asset.unitsPerBlock());
        Bounds bounds = asset.bounds().transformed(uniformScale(unitsToBlocksScale));
        Map<String, MaterialDefinition> materials = asset.materials();
        List<PreparedRenderPrimitive> prepared = new ArrayList<>();
        for (ModelPrimitive primitive : asset.primitives()) {
            if (primitive.nodeIndex() < 0 || primitive.nodeIndex() >= transforms.size()) {
                throw new IllegalArgumentException("Model primitive references a missing node transform");
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
            RenderMaterial material = ((MaterialMapping.Supported) mapping).material();
            prepared.add(new PreparedRenderPrimitive(primitive.nodeIndex(), StaticGeometry.copyOf(primitive.geometry()), material));
        }
        return new StaticRigidRenderHandle(modelKey, asset, transforms, prepared, bounds, unitsToBlocksScale);
    }

    @Override
    public BlendModelKey modelKey() {
        return modelKey;
    }

    @Override
    public long generation() {
        return asset.generation();
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

    @Override
    public float unitsToBlocksScale() {
        return unitsToBlocksScale;
    }

    @Override
    public List<PreparedRenderPrimitive> primitives() {
        return primitives;
    }

    @Override
    public Transform nodeTransform(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= nodeWorldTransforms.size()) {
            throw new IndexOutOfBoundsException("nodeIndex outside prepared static/rigid handle: " + nodeIndex);
        }
        return nodeWorldTransforms.get(nodeIndex);
    }

    @Override
    public boolean missingModel() {
        return false;
    }

    /** Shared reload-time hierarchy preparation for strict static, rigid, and skinned handles. */
    static List<Transform> calculateWorldTransforms(List<ModelNode> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("A static/rigid render asset must contain nodes");
        }
        int[] parents = new int[nodes.size()];
        Arrays.fill(parents, -1);
        for (int index = 0; index < nodes.size(); index++) {
            ModelNode node = nodes.get(index);
            if (node.index() != index) {
                throw new IllegalArgumentException("Model nodes must be indexed contiguously for a prepared render handle");
            }
            for (int child : node.children()) {
                if (child < 0 || child >= nodes.size() || parents[child] != -1) {
                    throw new IllegalArgumentException("Model nodes must form a valid single-parent hierarchy");
                }
                parents[child] = index;
            }
        }
        Transform[] world = new Transform[nodes.size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int root = 0; root < nodes.size(); root++) {
            if (parents[root] == -1) {
                world[root] = nodes.get(root).localTransform();
                pending.addLast(root);
            }
        }
        while (!pending.isEmpty()) {
            int parent = pending.removeFirst();
            for (int child : nodes.get(parent).children()) {
                if (world[child] != null) {
                    throw new IllegalArgumentException("Model nodes must be acyclic before render-handle preparation");
                }
                world[child] = world[parent].compose(nodes.get(child).localTransform());
                pending.addLast(child);
            }
        }
        for (Transform transform : world) {
            if (transform == null) {
                throw new IllegalArgumentException("Model nodes must form an acyclic rooted forest");
            }
        }
        return List.copyOf(Arrays.asList(world));
    }

    /** Converts a validated descriptor unit value once while a render handle is prepared. */
    static float unitsToBlocksScale(double unitsPerBlock) {
        double scale = 1.0d / unitsPerBlock;
        float result = (float) scale;
        if (!Double.isFinite(scale) || !Float.isFinite(result) || result <= 0.0f) {
            throw new IllegalArgumentException("units_per_block cannot be represented as a positive finite render scale");
        }
        return result;
    }

    /** Creates the finite uniform model-unit conversion used for prepared bounds. */
    static Transform uniformScale(float scale) {
        return new Transform(Vec3.ZERO, Quaternion.IDENTITY, new Vec3(scale, scale, scale));
    }
}
