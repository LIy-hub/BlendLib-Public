package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Unregistered direct-static candidate using 26.2's local vertex attributes and bind groups. */
final class StaticDirectPipeline {
    static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("blendlib", "pipeline/x7_static_direct");
    static final Identifier VERTEX_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_direct");
    static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_direct");
    static final VertexFormatElement F32_NORMAL = new VertexFormatElement("Normal", 12, GpuFormat.RGB32_FLOAT);
    static final int POSITION_OFFSET_BYTES = 0;
    static final int NORMAL_OFFSET_BYTES = 3 * Float.BYTES;
    static final int UV0_OFFSET_BYTES = 6 * Float.BYTES;
    static final int VERTEX_STRIDE_BYTES = 8 * Float.BYTES;

    private StaticDirectPipeline() { }

    static RenderPipeline buildCandidate() {
        VertexFormat vertexFormat = VertexFormat.builder(0)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
                .addAttribute("UV0", GpuFormat.RG32_FLOAT)
                .build();
        if (vertexFormat.getVertexSize() != VERTEX_STRIDE_BYTES
                || vertexFormat.getElement("Position").offset() != POSITION_OFFSET_BYTES
                || vertexFormat.getElement("Normal").offset() != NORMAL_OFFSET_BYTES
                || vertexFormat.getElement("UV0").offset() != UV0_OFFSET_BYTES) {
            throw new IllegalStateException("The direct-static pipeline must decode the frozen D1 f32 vertex layout");
        }
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                .withLocation(PIPELINE_ID)
                .withVertexShader(VERTEX_SHADER_ID)
                .withFragmentShader(FRAGMENT_SHADER_ID)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("Sampler0").withSampler("Sampler2")
                        .withUniform("X7StaticInstances", UniformType.UNIFORM_BUFFER).build())
                .withShaderDefine("X7_STATIC_MAX_INSTANCES", StaticDirectBatch.MAX_INSTANCES)
                .withVertexBinding(0, vertexFormat)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(true)
                .build();
    }
}
