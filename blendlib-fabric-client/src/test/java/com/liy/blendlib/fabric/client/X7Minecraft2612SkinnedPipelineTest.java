package com.liy.blendlib.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Static/public-contract proof for the isolated unregistered T4 shader pipeline. */
class X7Minecraft2612SkinnedPipelineTest {
    @Test
    void candidateUsesShared32ByteGeometryAndOnlyPublicBoundedSkinInputs() {
        RenderPipeline pipeline = X7Minecraft2612SkinnedPipeline.buildCandidate();
        VertexFormat format = pipeline.getVertexFormat();

        assertEquals(X7Minecraft2612SkinnedPipeline.VERTEX_STRIDE_BYTES, format.getVertexSize());
        assertEquals(X7Minecraft2612SkinnedPipeline.POSITION_OFFSET_BYTES, format.getOffset(VertexFormatElement.POSITION));
        assertEquals(X7Minecraft2612SkinnedPipeline.UV0_OFFSET_BYTES, format.getOffset(VertexFormatElement.UV0));
        assertFalse(format.contains(VertexFormatElement.NORMAL));
        assertTrue(pipeline.getSamplers().containsAll(java.util.List.of("Sampler0", "Sampler2")));
        assertTrue(pipeline.getUniforms().stream().anyMatch(uniform -> uniform.name().equals("SkinSourceBytes")));
        assertTrue(pipeline.getUniforms().stream().anyMatch(uniform -> uniform.name().equals("BonePalette")));
        assertTrue(pipeline.getUniforms().stream().anyMatch(uniform -> uniform.name().equals("X7SkinnedInstance")));
    }

    @Test
    void shadersPinRed8iDecodePositionNormalLightOverlayAndFinalViewFogContracts() throws IOException {
        Path projectDir = Path.of(System.getProperty("blendlib.projectDir"));
        Path shaderRoot = projectDir.resolve("src/client/resources/assets/blendlib/shaders/core");
        String vertex = Files.readString(shaderRoot.resolve("x7_skinned.vsh"));
        String fragment = Files.readString(shaderRoot.resolve("x7_skinned.fsh"));
        String pipeline = Files.readString(projectDir.resolve(
                "src/client/java/com/liy/blendlib/fabric/client/X7Minecraft2612SkinnedPipeline.java"));

        assertTrue(vertex.contains("uniform isamplerBuffer SkinSourceBytes"));
        assertTrue(vertex.contains("texelFetch(SkinSourceBytes"));
        assertTrue(vertex.contains("uintBitsToFloat"));
        assertTrue(vertex.contains("int sourceBase = gl_VertexID * 36"));
        assertTrue(vertex.contains("mat4 BonePosition[X7_SKINNED_MAX_BONES]"));
        assertTrue(vertex.contains("mat4 BoneNormal[X7_SKINNED_MAX_BONES]"));
        assertTrue(vertex.contains("skinnedNormalLength > 1.0e-8"));
        assertTrue(vertex.contains("sample_lightmap(Sampler2, ivec2(PackedLight.xy))"));
        assertTrue(vertex.contains("vec4 viewPosition = ModelView * vec4(skinnedPosition, 1.0)"));
        assertTrue(vertex.contains("fog_spherical_distance(viewPosition.xyz)"));
        assertTrue(vertex.contains("fog_cylindrical_distance(viewPosition.xyz)"));
        assertTrue(fragment.contains("apply_fog"));
        assertTrue(pipeline.contains("UniformType.TEXEL_BUFFER, TextureFormat.RED8I"));
        assertTrue(pipeline.contains("withShaderDefine(\"X7_SKINNED_MAX_BONES\", 64)"));
        assertFalse(pipeline.contains(".register("));
    }
}
