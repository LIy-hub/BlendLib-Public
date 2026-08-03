package com.liy.blendlib.fabric.client.animation.extract;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.ClientAnimationInstanceRegistry;
import com.liy.blendlib.fabric.client.animation.ClientAnimationPoseSnapshot;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.SkinnedRenderHandle;
import com.liy.blendlib.fabric.client.render.SkinnedRenderSnapshot;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extraction-only P5 bridge from a current controller pose to a captured strict-v1 render frame.
 *
 * <p>The bridge is intentionally outside the pure animation package. It validates the current
 * instance binding before deriving the default-scene canonical palette. Skinned assets capture
 * CPU output here; rigid assets freeze their canonical palette through the narrow render snapshot
 * factory. Neither branch owns resource lookup, parsing, platform state, or rendering invocation.</p>
 */
public final class ClientSkinnedExtractionBridge {
    private ClientSkinnedExtractionBridge() {
    }

    /**
     * Builds one immutable frame from a current instance pose and one already-published strict-v1
     * animated handle.
     *
     * <p>Controller advancement and sampling happen before this call. The resulting frame is the
     * only object a later render adapter needs to consume.</p>
     */
    public static ClientSkinnedExtractionFrame extract(
            ClientAnimationInstanceRegistry instances,
            LoadedModelHandle loaded,
            ClientAnimationPoseSnapshot poseSnapshot,
            SkinnedExtractionRequest request) {
        ClientAnimationInstanceRegistry checkedInstances = Objects.requireNonNull(instances, "instances");
        LoadedModelHandle checkedLoaded = Objects.requireNonNull(loaded, "loaded");
        ClientAnimationPoseSnapshot checkedPose = Objects.requireNonNull(poseSnapshot, "poseSnapshot");
        SkinnedExtractionRequest checkedRequest = Objects.requireNonNull(request, "request");

        checkedInstances.requireCurrentPoseSnapshot(checkedPose);
        if (!checkedLoaded.key().equals(checkedPose.modelKey()) || checkedLoaded.generationId() != checkedPose.generation()) {
            throw new IllegalArgumentException("pose snapshot does not match the loaded model generation");
        }
        if (checkedLoaded.renderHandle() instanceof StaticRigidRenderHandle) {
            return ClientRigidExtractionBridge.extract(checkedInstances, checkedLoaded, checkedPose, checkedRequest);
        }
        if (!(checkedLoaded.renderHandle() instanceof SkinnedRenderHandle skinnedHandle)) {
            throw new IllegalArgumentException("loaded model does not carry a P5 skinned render handle");
        }

        ModelAsset asset = checkedLoaded.asset();
        if (asset.skeleton() == null) {
            throw new IllegalArgumentException("skinned render handle requires a loaded skeleton");
        }
        NodePalette canonicalPalette = NodePalette.fromCanonicalScene(
                checkedPose.localPose(), asset.nodes(), asset.defaultSceneRoots());
        List<CpuSkinnedMesh> outputs = skinPreparedPrimitives(asset, skinnedHandle, canonicalPalette);
        SkinnedRenderSnapshot captured = SkinnedRenderSnapshot.capture(skinnedHandle, outputs);
        ModelRenderSnapshot renderSnapshot = ModelRenderSnapshot.skinned(
                skinnedHandle,
                checkedRequest.rootTransform(),
                checkedRequest.packedLight(),
                checkedRequest.packedOverlay(),
                checkedRequest.tintArgb(),
                checkedRequest.visibility(),
                checkedRequest.culling(),
                captured);
        return new ClientSkinnedExtractionFrame(renderSnapshot, socketTransforms(asset, canonicalPalette));
    }

    private static List<CpuSkinnedMesh> skinPreparedPrimitives(
            ModelAsset asset,
            SkinnedRenderHandle handle,
            NodePalette canonicalPalette) {
        List<CpuSkinnedMesh> outputs = new ArrayList<>(handle.skinnedPrimitives().size());
        for (PreparedSkinnedRenderPrimitive primitive : handle.skinnedPrimitives()) {
            if (primitive.skinIndex() < 0 || primitive.skinIndex() >= asset.skeleton().skins().size()) {
                throw new IllegalArgumentException("prepared skinned primitive references an absent skin");
            }
            SkinPalette palette = SkinPalette.from(asset.skeleton().skins().get(primitive.skinIndex()), canonicalPalette);
            outputs.add(CpuSkinner.skin(primitive.geometry(), palette));
        }
        return List.copyOf(outputs);
    }

    private static Map<BlendResourceId, Transform> socketTransforms(ModelAsset asset, NodePalette canonicalPalette) {
        Map<BlendResourceId, Transform> transforms = new LinkedHashMap<>();
        for (BlendResourceId socketKey : asset.sockets().entries().keySet()) {
            Transform transform = SocketWorldTransform.query(asset, canonicalPalette, socketKey)
                    .orElseThrow(() -> new IllegalArgumentException("canonical socket is absent from the sampled palette"));
            transforms.put(socketKey, transform);
        }
        return transforms;
    }
}
