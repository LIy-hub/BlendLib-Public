package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Exhaustive regression coverage for the accepted 26.1.2 public material subset. */
class MaterialRenderMapperTest {
    private static final BlendResourceId TEXTURE = BlendResourceId.parse("material_test:textures/base.png");

    @Test
    void mapsTheAcceptedModeCullingAndEmissiveMatrixWithoutSemanticFallback() {
        for (boolean emissive : new boolean[] {false, true}) {
            assertSupported(MaterialDefinition.Mode.OPAQUE, emissive, false, null, RenderLayer.SOLID);
            assertRejected(
                    MaterialDefinition.Mode.OPAQUE,
                    emissive,
                    true,
                    null,
                    MaterialRejectionReason.OPAQUE_DOUBLE_SIDED_UNSUPPORTED);

            for (boolean doubleSided : new boolean[] {false, true}) {
                assertSupported(MaterialDefinition.Mode.CUTOUT, emissive, doubleSided, null, RenderLayer.CUTOUT);
                assertSupported(
                        MaterialDefinition.Mode.CUTOUT,
                        emissive,
                        doubleSided,
                        MaterialRenderMapper.PUBLIC_CUTOUT_THRESHOLD,
                        RenderLayer.CUTOUT);
            }

            assertRejected(
                    MaterialDefinition.Mode.TRANSLUCENT,
                    emissive,
                    false,
                    null,
                    MaterialRejectionReason.TRANSLUCENT_SINGLE_SIDED_UNSUPPORTED);
            assertSupported(MaterialDefinition.Mode.TRANSLUCENT, emissive, true, null, RenderLayer.TRANSLUCENT);

            for (boolean doubleSided : new boolean[] {false, true}) {
                assertRejected(
                        MaterialDefinition.Mode.ADDITIVE,
                        emissive,
                        doubleSided,
                        null,
                        MaterialRejectionReason.ADDITIVE_UNSUPPORTED_IN_P4);
            }
        }
    }

    @Test
    void mapsExactPointOneSingleSidedCutoutLitAndEmissiveVariantsToThePublicCullPath() {
        for (boolean emissive : new boolean[] {false, true}) {
            MaterialMapping mapping = MaterialRenderMapper.map(material(
                    MaterialDefinition.Mode.CUTOUT,
                    emissive,
                    false,
                    MaterialRenderMapper.PUBLIC_CUTOUT_THRESHOLD));
            assertTrue(mapping instanceof MaterialMapping.Supported);
            RenderMaterial mapped = ((MaterialMapping.Supported) mapping).material();
            assertEquals(RenderLayer.CUTOUT, mapped.layer());
            assertEquals(emissive, mapped.emissive());
            assertFalse(mapped.doubleSided());
            assertEquals(
                    Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_CUTOUT_CULL,
                    Minecraft2612StaticRigidRenderBackend.renderTypePathFor(mapped));
        }
    }

    @Test
    void rejectsEveryCutoutThresholdThatIsNotTheExactPublicFixedCutoff() {
        double notExactlyPublicCutoff = Math.nextUp(MaterialRenderMapper.PUBLIC_CUTOUT_THRESHOLD);
        assertFalse(Double.compare(notExactlyPublicCutoff, MaterialRenderMapper.PUBLIC_CUTOUT_THRESHOLD) == 0);

        for (boolean emissive : new boolean[] {false, true}) {
            for (boolean doubleSided : new boolean[] {false, true}) {
                assertRejected(
                        MaterialDefinition.Mode.CUTOUT,
                        emissive,
                        doubleSided,
                        notExactlyPublicCutoff,
                        MaterialRejectionReason.CUTOUT_THRESHOLD_UNSUPPORTED);
            }
        }
    }

    @Test
    void backendRouteUsesOnlyTheExactVerifiedPublicPaths() throws IOException {
        assertEquals(
                Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_SOLID,
                Minecraft2612StaticRigidRenderBackend.renderTypePathFor(renderMaterial(RenderLayer.SOLID, false)));
        assertEquals(
                Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_CUTOUT_CULL,
                Minecraft2612StaticRigidRenderBackend.renderTypePathFor(renderMaterial(RenderLayer.CUTOUT, false)));
        assertEquals(
                Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_CUTOUT,
                Minecraft2612StaticRigidRenderBackend.renderTypePathFor(renderMaterial(RenderLayer.CUTOUT, true)));
        assertEquals(
                Minecraft2612StaticRigidRenderBackend.RenderTypePath.ENTITY_TRANSLUCENT,
                Minecraft2612StaticRigidRenderBackend.renderTypePathFor(renderMaterial(RenderLayer.TRANSLUCENT, true)));

        assertThrows(
                IllegalArgumentException.class,
                () -> Minecraft2612StaticRigidRenderBackend.renderTypePathFor(renderMaterial(RenderLayer.SOLID, true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Minecraft2612StaticRigidRenderBackend.renderTypePathFor(renderMaterial(RenderLayer.TRANSLUCENT, false)));

        String source = Files.readString(Path.of(
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
                "Minecraft2612StaticRigidRenderBackend.java"));
        assertTrue(source.contains("RenderTypes.entitySolid(texture)"));
        assertTrue(source.contains("RenderTypes.entityCutoutCull(texture)"));
        assertTrue(source.contains("RenderTypes.entityCutout(texture, false)"));
        assertTrue(source.contains("RenderTypes.entityTranslucent(texture, false)"));
        assertFalse(source.contains("entityTranslucentCullItemTarget"));
    }

    @Test
    void emissiveRemainsAFullbrightVertexLightChoiceIndependentOfTheSelectedCullingPath() {
        for (RenderMaterial material : new RenderMaterial[] {
            new RenderMaterial(TEXTURE, RenderLayer.SOLID, true, false, 0xFFFFFFFF, false),
            new RenderMaterial(TEXTURE, RenderLayer.CUTOUT, true, true, 0xFFFFFFFF, false),
            new RenderMaterial(TEXTURE, RenderLayer.TRANSLUCENT, true, true, 0xFFFFFFFF, false)
        }) {
            assertEquals(
                    Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                    Minecraft2612StaticRigidRenderBackend.packedLightFor(material, 0x000A000B));
        }
        assertEquals(
                0x000A000B,
                Minecraft2612StaticRigidRenderBackend.packedLightFor(
                        new RenderMaterial(TEXTURE, RenderLayer.CUTOUT, false, false, 0xFFFFFFFF, false),
                        0x000A000B));
    }

    private static void assertSupported(
            MaterialDefinition.Mode mode,
            boolean emissive,
            boolean doubleSided,
            Double cutoutThreshold,
            RenderLayer expectedLayer) {
        MaterialMapping mapping = MaterialRenderMapper.map(material(mode, emissive, doubleSided, cutoutThreshold));
        assertTrue(mapping instanceof MaterialMapping.Supported, () -> "expected supported " + describe(mode, emissive, doubleSided, cutoutThreshold));
        RenderMaterial mapped = ((MaterialMapping.Supported) mapping).material();
        assertEquals(expectedLayer, mapped.layer());
        assertEquals(emissive, mapped.emissive());
        assertEquals(doubleSided, mapped.doubleSided());
    }

    private static void assertRejected(
            MaterialDefinition.Mode mode,
            boolean emissive,
            boolean doubleSided,
            Double cutoutThreshold,
            MaterialRejectionReason expectedReason) {
        MaterialMapping mapping = MaterialRenderMapper.map(material(mode, emissive, doubleSided, cutoutThreshold));
        assertTrue(mapping instanceof MaterialMapping.Rejected, () -> "expected rejected " + describe(mode, emissive, doubleSided, cutoutThreshold));
        MaterialMapping.Rejected rejected = (MaterialMapping.Rejected) mapping;
        assertEquals(expectedReason, rejected.reason());
        assertEquals(expectedReason.descriptorField(), rejected.reason().descriptorField());
    }

    private static MaterialDefinition material(
            MaterialDefinition.Mode mode, boolean emissive, boolean doubleSided, Double cutoutThreshold) {
        return new MaterialDefinition(TEXTURE, mode, emissive, doubleSided, cutoutThreshold);
    }

    private static RenderMaterial renderMaterial(RenderLayer layer, boolean doubleSided) {
        return new RenderMaterial(TEXTURE, layer, false, doubleSided, 0xFFFFFFFF, false);
    }

    private static String describe(
            MaterialDefinition.Mode mode, boolean emissive, boolean doubleSided, Double cutoutThreshold) {
        return "mode=" + mode + ", emissive=" + emissive + ", doubleSided=" + doubleSided
                + ", cutoutThreshold=" + cutoutThreshold;
    }
}
