package com.liy.blendlib.fabric.client.render.x7gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.core.animation.runtime.CpuSkinnedMesh;
import com.liy.blendlib.core.animation.runtime.CpuSkinner;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class X7CpuSkinnedUploadCandidateTest {
    @Test
    void acceptsVerifiedCpuSkinnerOutputButNamesItOnlyAsCpuSkinnedUploadCandidate() {
        CpuSkinnedMesh cpuOutput = CpuSkinner.skin(
                PreparedSkinnedGeometry.prepare(new MeshPrimitive(
                        "skin",
                        new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new int[] {0, 1, 2},
                        new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                        new float[] {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f})),
                identityPalette());

        X7CpuSkinnedUploadCandidate candidate = X7CpuSkinnedUploadCandidate.fromCpuSkinnerResult(
                cpuOutput, X7CpuSkinnedUploadCandidate.Limits.STRICT_V1);

        assertEquals(X7CpuSkinnedUploadCandidate.Kind.CPU_SKINNED_UPLOAD_CANDIDATE, candidate.kind());
        assertFalse(candidate.claimsGpuSkinning());
        X7CpuSkinnedUploadCandidate.PackedGeometry staging = candidate.transferPackedGeometryForFutureBridge();
        assertTrue(candidate.isClosed());
        ByteBuffer bytes = staging.vertexBytesForFutureBridge();
        assertEquals(0.0f, bytes.getFloat());
        assertEquals(0.0f, bytes.getFloat());
        assertEquals(0.0f, bytes.getFloat());
        staging.close();
        assertThrows(IllegalStateException.class, candidate::transferPackedGeometryForFutureBridge);
    }

    private static SkinPalette identityPalette() {
        Skin skin = new Skin("skin", 0, List.of(0), new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        });
        NodePalette nodes = NodePalette.from(
                new LocalPose(Map.of(0, Transform.IDENTITY)),
                List.of(new ModelNode(0, "joint", Transform.IDENTITY, List.of(), -1, -1, false)));
        return SkinPalette.from(skin, nodes);
    }
}
