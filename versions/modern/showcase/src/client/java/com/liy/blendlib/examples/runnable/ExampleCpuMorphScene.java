package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.render.*;
import java.util.List;
import java.util.Map;

/** Shared live/headless consumer choices; every attachment comes from the final captured bone socket. */
public final class ExampleCpuMorphScene {
    public static final BlendModelKey MARKER = BlendModelKey.parse("blendlib_runnable_examples:marker");
    public static final BlendEntityCullingEnvelope ENVELOPE = new BlendEntityCullingEnvelope(-4, -4, -4, 4, 4, 4);
    private static final BlendEntitySocketPose MARKER_OFFSET = new BlendEntitySocketPose(
            0, .55, 0, BlendEntityRotation.IDENTITY, .12F);
    private ExampleCpuMorphScene() { }

    public static Map<String, MaterialSlotAppearance> appearance(String name) {
        return "morph_amber".equals(name) ? Map.of("MorphSurface", new MaterialSlotAppearance(0xFFB640, true))
                : "morph_hide_details".equals(name) ? Map.of("FaceDetails", new MaterialSlotAppearance(0xFFFFFF, false))
                : Map.of();
    }

    public static List<BlendEntityAttachment> attachments(ClientModelLookup models,
            BlendEntitySnapshotRequest request, BlendEntitySockets sockets, int overlay) {
        var face = sockets.socket(ExampleCpuMorphControls.FACE);
        var marker = models.resolve(MARKER);
        if (face.isEmpty() || marker.missing() || marker.generationId() != sockets.generation()) return List.of();
        var handle = marker.renderHandle();
        var snapshot = new ModelRenderSnapshot(handle, Transform.IDENTITY, request.packedLight(), overlay,
                0xFFFFC040, RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
        return List.of(BlendEntityAttachment.at(face.orElseThrow(), MARKER_OFFSET, snapshot));
    }
}
