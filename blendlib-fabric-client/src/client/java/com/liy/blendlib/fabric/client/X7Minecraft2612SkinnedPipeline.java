package com.liy.blendlib.fabric.client;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Metadata-only public-Blaze3D pipeline candidate for the isolated T4 skinned path.
 *
 * <p>It is intentionally not registered here. T3c is the sole owner of a source-order proof, host installation, and
 * any later registration decision; until then the normal CPU renderer remains the only live route.</p>
 */
final class X7Minecraft2612SkinnedPipeline {
    static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("blendlib", "pipeline/x7_skinned");
    static final Identifier VERTEX_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_skinned");
    static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_skinned");
    static final String VERTEX_SHADER_RESOURCE = "assets/blendlib/shaders/core/x7_skinned.vsh";
    static final String FRAGMENT_SHADER_RESOURCE = "assets/blendlib/shaders/core/x7_skinned.fsh";
    static final int VERTEX_STRIDE_BYTES = 8 * Float.BYTES;
    static final int POSITION_OFFSET_BYTES = 0;
    static final int UV0_OFFSET_BYTES = 6 * Float.BYTES;

    private X7Minecraft2612SkinnedPipeline() {
    }

    /** Builds the candidate only; callers must never treat construction as shader-compile or registration proof. */
    static RenderPipeline buildCandidate() {
        // The immutable shared geometry remains the existing 32-byte strict-v1 Position/Normal/UV island. The T4
        // shader reads its source normal and influences from the separate RED8I texel-buffer, so no global custom
        // vertex-element registration is necessary or permitted.
        VertexFormat vertexFormat = VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .padding(3 * Float.BYTES)
                .add("UV0", VertexFormatElement.UV0)
                .build();
        if (vertexFormat.getVertexSize() != VERTEX_STRIDE_BYTES
                || vertexFormat.getOffset(VertexFormatElement.POSITION) != POSITION_OFFSET_BYTES
                || vertexFormat.getOffset(VertexFormatElement.UV0) != UV0_OFFSET_BYTES) {
            throw new IllegalStateException("The skinned candidate must consume the frozen 32-byte shared geometry layout");
        }
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                .withLocation(PIPELINE_ID)
                .withVertexShader(VERTEX_SHADER_ID)
                .withFragmentShader(FRAGMENT_SHADER_ID)
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withShaderDefine("X7_SKINNED_MAX_BONES", 64)
                .withUniform("SkinSourceBytes", UniformType.TEXEL_BUFFER, TextureFormat.RED8I)
                .withUniform("BonePalette", UniformType.UNIFORM_BUFFER)
                .withUniform("X7SkinnedInstance", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(vertexFormat, VertexFormat.Mode.TRIANGLES)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(true)
                .build();
    }
}
