package com.liy.blendlib.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class X7Minecraft2612TargetPipelineContractTest {
    @Test
    void localPublicTargetAndPipelineSurfaceMatchesThePinnedT3aContract() throws Exception {
        assertEquals(
                com.mojang.blaze3d.pipeline.RenderTarget.class,
                Minecraft.class.getMethod("getMainRenderTarget").getReturnType());
        assertEquals(
                com.mojang.blaze3d.textures.GpuTextureView.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class
                        .getMethod("getColorTextureView")
                        .getReturnType());
        assertEquals(
                com.mojang.blaze3d.textures.GpuTextureView.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class
                        .getMethod("getDepthTextureView")
                        .getReturnType());
        assertEquals(
                RenderPipeline.class,
                RenderPipelines.class.getMethod("register", RenderPipeline.class).getReturnType());
        assertEquals(
                List.class,
                RenderPipelines.class.getMethod("getStaticPipelines").getReturnType());
    }

    @Test
    void probePipelinePinsTheRigidVertexAndUniformAbi() {
        RenderPipeline pipeline = X7Minecraft2612StaticPipeline.buildProbePipeline();

        assertEquals(Identifier.fromNamespaceAndPath("blendlib", "pipeline/x7_static_rigid"), pipeline.getLocation());
        assertEquals(Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_rigid"), pipeline.getVertexShader());
        assertEquals(Identifier.fromNamespaceAndPath("blendlib", "core/x7_static_rigid"), pipeline.getFragmentShader());
        assertEquals(VertexFormat.Mode.TRIANGLES, pipeline.getVertexFormatMode());
        assertSame(DepthStencilState.DEFAULT, pipeline.getDepthStencilState());
        assertTrue(pipeline.isCull());
        assertTrue(pipeline.getSamplers().isEmpty());

        VertexFormat format = pipeline.getVertexFormat();
        assertEquals(32, format.getVertexSize());
        assertEquals(0, format.getOffset(VertexFormatElement.POSITION));
        assertEquals(24, format.getOffset(VertexFormatElement.UV0));
        assertFalse(format.contains(VertexFormatElement.NORMAL));
        assertEquals(List.of(VertexFormatElement.POSITION, VertexFormatElement.UV0), format.getElements());
        assertEquals(List.of("Position", "UV0"), format.getElementAttributeNames());
        assertEquals(List.of(
                new RenderPipeline.UniformDescription("DynamicTransforms", UniformType.UNIFORM_BUFFER),
                new RenderPipeline.UniformDescription("Projection", UniformType.UNIFORM_BUFFER)), pipeline.getUniforms());
    }

    @Test
    void registrationAllowsOneExactPipelineAndFailsClosedForDuplicateOrCollision() {
        FakePipelineRegistry registry = new FakePipelineRegistry();
        X7Minecraft2612StaticPipeline pipeline = new X7Minecraft2612StaticPipeline(registry);

        assertTrue(pipeline.registerOnce());
        assertEquals(X7Minecraft2612StaticPipeline.RegistrationState.REGISTERED, pipeline.registrationState());
        assertEquals(1, registry.registrations);
        assertTrue(pipeline.pipeline() != null);

        assertFalse(pipeline.registerOnce());
        assertEquals(X7Minecraft2612StaticPipeline.RegistrationState.REJECTED, pipeline.registrationState());
        assertEquals(1, registry.registrations);
        assertNull(pipeline.pipeline());

        FakePipelineRegistry collisionRegistry = new FakePipelineRegistry();
        collisionRegistry.pipelines.add(X7Minecraft2612StaticPipeline.buildProbePipeline());
        X7Minecraft2612StaticPipeline collision = new X7Minecraft2612StaticPipeline(collisionRegistry);
        assertFalse(collision.registerOnce());
        assertEquals(X7Minecraft2612StaticPipeline.RegistrationState.REJECTED, collision.registrationState());
        assertEquals(0, collisionRegistry.registrations);
    }

    @Test
    void resourcesAndBootstrapOrderingStayExactAndNoPublicPipelineApiLeaks() throws IOException {
        Path projectDir = Path.of(System.getProperty("blendlib.projectDir"));
        Path resourcesDir = projectDir.resolve("src/client/resources");
        String vertex = Files.readString(resourcesDir.resolve(X7Minecraft2612StaticPipeline.VERTEX_SHADER_RESOURCE));
        String fragment = Files.readString(resourcesDir.resolve(X7Minecraft2612StaticPipeline.FRAGMENT_SHADER_RESOURCE));
        String pipelineSource = Files.readString(projectDir.resolve(
                "src/client/java/com/liy/blendlib/fabric/client/X7Minecraft2612StaticPipeline.java"));
        String hostSource = Files.readString(projectDir.resolve(
                "src/client/java/com/liy/blendlib/fabric/client/X7Minecraft2612PassOwnerHost.java"));

        assertTrue(vertex.contains("#moj_import <minecraft:dynamictransforms.glsl>"));
        assertTrue(vertex.contains("#moj_import <minecraft:projection.glsl>"));
        assertTrue(vertex.contains("in vec3 Position;"));
        assertTrue(fragment.contains("#moj_import <minecraft:dynamictransforms.glsl>"));
        assertTrue(fragment.contains("fragColor = ColorModulator;"));
        assertFalse(vertex.toLowerCase().contains("sampler"));
        assertFalse(fragment.toLowerCase().contains("sampler"));
        assertFalse(vertex.toLowerCase().contains("normal"));
        assertFalse(fragment.toLowerCase().contains("normal"));
        assertFalse(vertex.toLowerCase().contains("fog"));
        assertFalse(fragment.toLowerCase().contains("fog"));
        assertFalse(pipelineSource.contains("VertexFormatElement.NORMAL"));
        assertFalse(pipelineSource.contains("VertexFormatElement.register"));
        assertTrue(pipelineSource.contains("RenderPipelines.MATRICES_PROJECTION_SNIPPET"));
        assertTrue(hostSource.indexOf("X7Minecraft2612StaticPipeline.registerProductionOnce()")
                < hostSource.indexOf("return new X7Minecraft2612PassOwnerHost"));
        assertFalse(Modifier.isPublic(X7Minecraft2612StaticPipeline.class.getModifiers()));
        assertTrue(java.util.Arrays.stream(X7Minecraft2612StaticPipeline.class.getDeclaredMethods())
                .noneMatch(method -> Modifier.isPublic(method.getModifiers())));
    }

    private static final class FakePipelineRegistry implements X7Minecraft2612StaticPipeline.PipelineRegistry {
        private final List<RenderPipeline> pipelines = new ArrayList<>();
        private int registrations;

        @Override
        public List<RenderPipeline> staticPipelines() {
            return pipelines;
        }

        @Override
        public RenderPipeline register(RenderPipeline pipeline) {
            registrations++;
            pipelines.add(pipeline);
            return pipeline;
        }
    }
}
