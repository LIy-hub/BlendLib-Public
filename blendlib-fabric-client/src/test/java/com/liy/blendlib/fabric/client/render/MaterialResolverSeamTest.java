package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Regression tests for the reload-only internal material resolver and layer-provider seam. */
class MaterialResolverSeamTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("material_seam:rigid/example");
    private static final BlendResourceId TEXTURE = BlendResourceId.parse("material_seam:textures/base.png");

    @Test
    void resolverSeamTypesArePackagePrivateImplementationDetails() {
        for (Class<?> internalType : new Class<?>[] {
            MaterialRenderMapper.MaterialResolver.class,
            MaterialRenderMapper.RenderLayerProvider.class,
            MaterialRenderMapper.RenderLayerResolution.class,
            MaterialRenderMapper.DefaultMaterialResolver.class,
            MaterialRenderMapper.DefaultRenderLayerProvider.class
        }) {
            assertFalse(Modifier.isPublic(internalType.getModifiers()), internalType.getName());
        }
    }

    @Test
    void defaultResolverRemainsEquivalentToTheEstablishedP4MappingMatrix() {
        AtomicInteger providerCalls = new AtomicInteger();
        MaterialRenderMapper.RenderLayerProvider provider = definition -> {
            providerCalls.incrementAndGet();
            return MaterialRenderMapper.DefaultRenderLayerProvider.INSTANCE.resolveLayer(definition);
        };
        MaterialRenderMapper.MaterialResolver resolver = new MaterialRenderMapper.DefaultMaterialResolver(provider);

        List<MaterialDefinition> cases = List.of(
                material(MaterialDefinition.Mode.OPAQUE, false, false, null),
                material(MaterialDefinition.Mode.OPAQUE, true, false, null),
                material(MaterialDefinition.Mode.OPAQUE, false, true, null),
                material(MaterialDefinition.Mode.CUTOUT, false, false, null),
                material(MaterialDefinition.Mode.CUTOUT, true, true, MaterialRenderMapper.PUBLIC_CUTOUT_THRESHOLD),
                material(MaterialDefinition.Mode.CUTOUT, false, false, Math.nextUp(MaterialRenderMapper.PUBLIC_CUTOUT_THRESHOLD)),
                material(MaterialDefinition.Mode.TRANSLUCENT, false, true, null),
                material(MaterialDefinition.Mode.TRANSLUCENT, true, false, null),
                material(MaterialDefinition.Mode.ADDITIVE, false, false, null),
                material(MaterialDefinition.Mode.ADDITIVE, true, true, null));

        for (MaterialDefinition definition : cases) {
            assertEquals(MaterialRenderMapper.map(definition), resolver.resolve(definition));
        }
        assertEquals(cases.size(), providerCalls.get());
    }

    @Test
    void providerKeepsEveryP4InvalidCombinationExplicitlyRejected() {
        MaterialRenderMapper.MaterialResolver resolver = MaterialRenderMapper.DefaultMaterialResolver.standard2612();
        for (boolean emissive : new boolean[] {false, true}) {
            assertRejected(
                    resolver,
                    material(MaterialDefinition.Mode.OPAQUE, emissive, true, null),
                    MaterialRejectionReason.OPAQUE_DOUBLE_SIDED_UNSUPPORTED);
            assertRejected(
                    resolver,
                    material(MaterialDefinition.Mode.TRANSLUCENT, emissive, false, null),
                    MaterialRejectionReason.TRANSLUCENT_SINGLE_SIDED_UNSUPPORTED);
            for (boolean doubleSided : new boolean[] {false, true}) {
                assertRejected(
                        resolver,
                        material(MaterialDefinition.Mode.ADDITIVE, emissive, doubleSided, null),
                        MaterialRejectionReason.ADDITIVE_UNSUPPORTED_IN_P4);
                assertRejected(
                        resolver,
                        material(
                                MaterialDefinition.Mode.CUTOUT,
                                emissive,
                                doubleSided,
                                Math.nextUp(MaterialRenderMapper.PUBLIC_CUTOUT_THRESHOLD)),
                        MaterialRejectionReason.CUTOUT_THRESHOLD_UNSUPPORTED);
            }
        }
    }

    @Test
    void resolverAndProviderRunWhileTheHandlePreparesAndAreAbsentFromSubmit() throws IOException {
        AtomicInteger providerCalls = new AtomicInteger();
        MaterialRenderMapper.RenderLayerProvider provider = definition -> {
            providerCalls.incrementAndGet();
            return MaterialRenderMapper.DefaultRenderLayerProvider.INSTANCE.resolveLayer(definition);
        };
        MaterialRenderMapper.MaterialResolver resolver = new MaterialRenderMapper.DefaultMaterialResolver(provider);

        StaticRigidRenderHandle handle = StaticRigidRenderHandle.prepare(KEY, rigidAsset(), resolver);
        assertEquals(1, providerCalls.get());
        assertEquals(RenderLayer.CUTOUT, handle.primitives().getFirst().material().layer());
        assertTrue(handle.primitives().getFirst().material().doubleSided());

        String staticHandle = Files.readString(renderSource("StaticRigidRenderHandle.java"));
        String skinnedHandle = Files.readString(renderSource("SkinnedRenderHandle.java"));
        String backend = Files.readString(renderSource("Minecraft2612StaticRigidRenderBackend.java"));
        assertTrue(staticHandle.contains("materialResolver.resolve(definition)"));
        assertTrue(skinnedHandle.contains("materialResolver.resolve(definition)"));
        assertFalse(backend.contains("MaterialResolver"));
        assertFalse(backend.contains("RenderLayerProvider"));
        assertFalse(backend.contains("MaterialRenderMapper.map("));
    }

    private static void assertRejected(
            MaterialRenderMapper.MaterialResolver resolver,
            MaterialDefinition definition,
            MaterialRejectionReason expectedReason) {
        MaterialMapping.Rejected rejected = assertInstanceOf(MaterialMapping.Rejected.class, resolver.resolve(definition));
        assertEquals(expectedReason, rejected.reason());
    }

    private static MaterialDefinition material(
            MaterialDefinition.Mode mode, boolean emissive, boolean doubleSided, Double cutoutThreshold) {
        return new MaterialDefinition(TEXTURE, mode, emissive, doubleSided, cutoutThreshold);
    }

    private static ModelAsset rigidAsset() {
        MeshPrimitive primitive = new MeshPrimitive(
                "Base",
                new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2},
                null,
                null);
        return new ModelAsset(
                KEY.resourceId(),
                KEY.descriptorResourceId(),
                1L,
                ModelProfile.RIGID_V1,
                1.0d,
                Map.of("Base", material(MaterialDefinition.Mode.CUTOUT, false, true, null)),
                null,
                List.of(new ModelNode(0, "Mesh", Transform.IDENTITY, List.of(), -1, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, primitive)),
                null,
                List.of(),
                new SocketTable(Map.of()),
                new Bounds(Vec3.ZERO, Vec3.ONE),
                List.of());
    }

    private static Path renderSource(String name) {
        return Path.of(
                System.getProperty("blendlib.projectDir"),
                "src",
                "client",
                "java",
                "com",
                "liy",
                "blendlib",
                "fabric",
                "client",
                "render",
                name);
    }
}
