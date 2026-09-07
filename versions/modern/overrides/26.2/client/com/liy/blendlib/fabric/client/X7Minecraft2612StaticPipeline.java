package com.liy.blendlib.fabric.client;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Client-only static metadata for the T3a rigid direct-draw probe. No draw path is enabled here. */
final class X7Minecraft2612StaticPipeline {
    static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("blendlib", "pipeline/x7_static_rigid");
    static final Identifier VERTEX_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_rigid");
    static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_rigid");
    static final String VERTEX_SHADER_RESOURCE = "assets/blendlib/shaders/core/x7_static_rigid.vsh";
    static final String FRAGMENT_SHADER_RESOURCE = "assets/blendlib/shaders/core/x7_static_rigid.fsh";

    private static final X7Minecraft2612StaticPipeline PRODUCTION = new X7Minecraft2612StaticPipeline(
            new PipelineRegistry() {
                @Override
                public List<RenderPipeline> staticPipelines() {
                    return RenderPipelines.getStaticPipelines();
                }

                @Override
                public RenderPipeline register(RenderPipeline pipeline) {
                    return RenderPipelines.register(pipeline);
                }
            });

    enum RegistrationState {
        UNREGISTERED,
        REGISTERED,
        REJECTED
    }

    interface PipelineRegistry {
        List<RenderPipeline> staticPipelines();

        RenderPipeline register(RenderPipeline pipeline);
    }

    private final PipelineRegistry registry;
    private RegistrationState registrationState = RegistrationState.UNREGISTERED;
    private RenderPipeline pipeline;

    static boolean registerProductionOnce() {
        return PRODUCTION.registerOnce();
    }

    X7Minecraft2612StaticPipeline(PipelineRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    static RenderPipeline buildProbePipeline() {
        VertexFormat vertexFormat = VertexFormat.builder(0)
                .addAttribute("Position", 24, GpuFormat.RGB32_FLOAT)
                .addAttribute("UV0", GpuFormat.RG32_FLOAT)
                .build();
        return RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                        .withUniform("Projection", UniformType.UNIFORM_BUFFER).build())
                .withLocation(PIPELINE_ID)
                .withVertexShader(VERTEX_SHADER_ID)
                .withFragmentShader(FRAGMENT_SHADER_ID)
                .withVertexBinding(0, vertexFormat)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(true)
                .build();
    }

    synchronized boolean registerOnce() {
        if (registrationState != RegistrationState.UNREGISTERED) {
            registrationState = RegistrationState.REJECTED;
            return false;
        }
        try {
            if (registry.staticPipelines().stream().anyMatch(existing -> PIPELINE_ID.equals(existing.getLocation()))) {
                registrationState = RegistrationState.REJECTED;
                return false;
            }
            RenderPipeline candidate = buildProbePipeline();
            if (registry.register(candidate) != candidate) {
                registrationState = RegistrationState.REJECTED;
                return false;
            }
            pipeline = candidate;
            registrationState = RegistrationState.REGISTERED;
            return true;
        } catch (Throwable ignored) {
            registrationState = RegistrationState.REJECTED;
            return false;
        }
    }

    synchronized RegistrationState registrationState() {
        return registrationState;
    }

    synchronized RenderPipeline pipeline() {
        return registrationState == RegistrationState.REGISTERED ? pipeline : null;
    }
}
