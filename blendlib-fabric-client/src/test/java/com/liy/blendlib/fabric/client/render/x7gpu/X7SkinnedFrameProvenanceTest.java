package com.liy.blendlib.fabric.client.render.x7gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderMaterial;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Focused R4 proof that a same-topology CPU mesh cannot be sealed under a different current palette. */
class X7SkinnedFrameProvenanceTest {
    @Test
    void cpuResultFromPaletteACannotBeSealedWithPaletteBBeforeMaterializationOrNativeAllocation() {
        PreparedSkinnedGeometry geometry = geometry();
        PreparedSkinnedRenderPrimitive primitive = new PreparedSkinnedRenderPrimitive(
                0, 0, geometry, RenderMaterial.missing(0xFFFFFFFF));
        SkinPalette paletteA = palette(0.0F);
        SkinPalette paletteB = palette(3.0F);
        CpuSkinnedMesh cpuA = CpuSkinner.skin(geometry, paletteA);

        assertFalse(Arrays.equals(cpuA.positions(), CpuSkinner.skin(geometry, paletteB).positions()));
        int[] materializationCalls = {0};

        assertThrows(IllegalArgumentException.class, () -> {
            X7SkinnedFrameProvenance.validateAndSeal(primitive, paletteB, cpuA);
            materializationCalls[0]++;
        });

        assertEquals(0, materializationCalls[0],
                "the mismatched pair must fail before it can materialize or reach a native allocator");
    }

    private static PreparedSkinnedGeometry geometry() {
        return PreparedSkinnedGeometry.prepare(new MeshPrimitive(
                "skinned",
                new float[] {1.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F}));
    }

    private static SkinPalette palette(float translationX) {
        Skin skin = new Skin("skin", 0, List.of(0), new float[] {
                1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, 0.0F,
                translationX, 0.0F, 0.0F, 1.0F
        });
        NodePalette nodes = NodePalette.from(
                new LocalPose(Map.of(0, Transform.IDENTITY)),
                List.of(new ModelNode(0, "joint", Transform.IDENTITY, List.of(), -1, -1, false)));
        return SkinPalette.from(skin, nodes);
    }
}
