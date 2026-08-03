package com.liy.blendlib.fabric.client.animation.extract;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.ClientAnimationInstanceRegistry;
import com.liy.blendlib.fabric.client.animation.ClientAnimationPoseSnapshot;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.StaticRigidRenderHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Extraction-only bridge from a current rigid-v1 controller pose to an immutable render frame.
 *
 * <p>Lifecycle and pose-generation validation deliberately stay here, outside {@code render}.
 * The rendering package receives only the checked rigid handle and precomputed canonical world
 * transforms needed to freeze the palette handoff.</p>
 */
final class ClientRigidExtractionBridge {
    private ClientRigidExtractionBridge() {
    }

    /** Captures one rigid-node palette handoff for the exact loaded model generation. */
    static ClientSkinnedExtractionFrame extract(
            ClientAnimationInstanceRegistry instances,
            LoadedModelHandle loaded,
            ClientAnimationPoseSnapshot poseSnapshot,
            SkinnedExtractionRequest request) {
        ClientAnimationInstanceRegistry checkedInstances = Objects.requireNonNull(instances, "instances");
        LoadedModelHandle checkedLoaded = Objects.requireNonNull(loaded, "loaded");
        ClientAnimationPoseSnapshot checkedPose = Objects.requireNonNull(poseSnapshot, "poseSnapshot");
        SkinnedExtractionRequest checkedRequest = Objects.requireNonNull(request, "request");

        checkedInstances.requireCurrentPoseSnapshot(checkedPose);
        if (!checkedLoaded.key().equals(checkedPose.modelKey())
                || checkedLoaded.generationId() != checkedPose.generation()) {
            throw new IllegalArgumentException("pose snapshot does not match the loaded model generation");
        }
        if (!(checkedLoaded.renderHandle() instanceof StaticRigidRenderHandle rigidHandle)) {
            throw new IllegalArgumentException("loaded model does not carry a P5 rigid render handle");
        }

        ModelAsset asset = checkedLoaded.asset();
        if (asset.profile() != ModelProfile.RIGID_V1 || asset.skeleton() != null) {
            throw new IllegalArgumentException("rigid palette extraction requires a strict rigid-v1 asset without skeleton data");
        }
        NodePalette canonicalPalette = NodePalette.fromCanonicalScene(
                checkedPose.localPose(), asset.nodes(), asset.defaultSceneRoots());
        ModelRenderSnapshot renderSnapshot = ModelRenderSnapshot.rigid(
                rigidHandle,
                checkedRequest.rootTransform(),
                checkedRequest.packedLight(),
                checkedRequest.packedOverlay(),
                checkedRequest.tintArgb(),
                checkedRequest.visibility(),
                checkedRequest.culling(),
                canonicalPalette.worldTransforms());
        return new ClientSkinnedExtractionFrame(renderSnapshot, socketTransforms(asset, canonicalPalette));
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
