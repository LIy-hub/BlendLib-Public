package com.liy.blendlib.fabric.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Metadata-only 26.2 candidate. The production renderer retains the existing CPU path. */
final class X7Minecraft2612SkinnedPipeline {
    static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("blendlib", "pipeline/x7_skinned");
    static final Identifier VERTEX_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_skinned");
    static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_skinned");
    static final String VERTEX_SHADER_RESOURCE = "assets/blendlib/shaders/core/x7_skinned.vsh";
    static final String FRAGMENT_SHADER_RESOURCE = "assets/blendlib/shaders/core/x7_skinned.fsh";
    static final int VERTEX_STRIDE_BYTES = 8 * Float.BYTES;
    static final int POSITION_OFFSET_BYTES = 0;
    static final int UV0_OFFSET_BYTES = 6 * Float.BYTES;

    private X7Minecraft2612SkinnedPipeline() { }

    static RenderPipeline buildCandidate() {
        VertexFormat vertexFormat = VertexFormat.builder(0)
                .addAttribute("Position", 24, GpuFormat.RGB32_FLOAT)
                .addAttribute("UV0", GpuFormat.RG32_FLOAT)
                .build();
        if (vertexFormat.getVertexSize() != VERTEX_STRIDE_BYTES
                || vertexFormat.getElement("Position").offset() != POSITION_OFFSET_BYTES
                || vertexFormat.getElement("UV0").offset() != UV0_OFFSET_BYTES) {
            throw new IllegalStateException("The skinned candidate must consume the frozen 32-byte shared geometry layout");
        }
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                .withLocation(PIPELINE_ID)
                .withVertexShader(VERTEX_SHADER_ID)
                .withFragmentShader(FRAGMENT_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("Sampler0").withSampler("Sampler2")
                        .withUniform("SkinSourceBytes", UniformType.TEXEL_BUFFER, GpuFormat.R8_SINT)
                        .withUniform("BonePalette", UniformType.UNIFORM_BUFFER)
                        .withUniform("X7SkinnedInstance", UniformType.UNIFORM_BUFFER).build())
                .withShaderDefine("X7_SKINNED_MAX_BONES", 64)
                .withVertexBinding(0, vertexFormat)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(true)
                .build();
    }
}
