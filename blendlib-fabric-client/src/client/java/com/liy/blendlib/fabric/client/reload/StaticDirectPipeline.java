package com.liy.blendlib.fabric.client.reload;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Static metadata for the unregistered direct-static pipeline candidate. */
final class StaticDirectPipeline {
    static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("blendlib", "pipeline/x7_static_direct");
    static final Identifier VERTEX_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_direct");
    static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_direct");
    /**
     * A private, non-registered element id for D1's f32 normal triplet. The public constructor is intentionally
     * used without process-global registration, so this otherwise local candidate cannot affect unrelated pipelines.
     */
    static final VertexFormatElement F32_NORMAL = new VertexFormatElement(
            7, 0, VertexFormatElement.Type.FLOAT, false, 3);
    static final int POSITION_OFFSET_BYTES = 0;
    static final int NORMAL_OFFSET_BYTES = 3 * Float.BYTES;
    static final int UV0_OFFSET_BYTES = 6 * Float.BYTES;
    static final int VERTEX_STRIDE_BYTES = 8 * Float.BYTES;

    private StaticDirectPipeline() {
    }

    /**
     * Builds but deliberately does not register the candidate. T3c owns registration and host reachability.
     */
    static RenderPipeline buildCandidate() {
        VertexFormat vertexFormat = VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .add("Normal", F32_NORMAL)
                .add("UV0", VertexFormatElement.UV0)
                .build();
        // 26.1.2's public builder preserves a local custom element in getElements(), but its offset table is seeded
        // only from process-global registered ids. Fill the public per-format table for this one unregistered id;
        // no global element registration or private access is involved.
        int[] offsets = vertexFormat.getOffsetsByElement();
        if (offsets.length <= F32_NORMAL.id() || offsets[F32_NORMAL.id()] != -1) {
            throw new IllegalStateException("The direct-static custom normal element must start as an unregistered local id");
        }
        offsets[F32_NORMAL.id()] = NORMAL_OFFSET_BYTES;
        if (vertexFormat.getVertexSize() != VERTEX_STRIDE_BYTES
                || vertexFormat.getOffset(VertexFormatElement.POSITION) != POSITION_OFFSET_BYTES
                || vertexFormat.getOffset(F32_NORMAL) != NORMAL_OFFSET_BYTES
                || vertexFormat.getOffset(VertexFormatElement.UV0) != UV0_OFFSET_BYTES) {
            throw new IllegalStateException("The direct-static pipeline must decode the frozen D1 f32 vertex layout");
        }
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                .withLocation(PIPELINE_ID)
                .withVertexShader(VERTEX_SHADER_ID)
                .withFragmentShader(FRAGMENT_SHADER_ID)
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withShaderDefine("X7_STATIC_MAX_INSTANCES", StaticDirectBatch.MAX_INSTANCES)
                .withUniform("X7StaticInstances", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(vertexFormat, VertexFormat.Mode.TRIANGLES)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(true)
                .build();
    }
}
