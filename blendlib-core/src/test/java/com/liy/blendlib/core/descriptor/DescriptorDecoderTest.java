package com.liy.blendlib.core.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.limits.BlendAssetLimits;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.testsupport.P3FixtureCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DescriptorDecoderTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("fixture:model");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("fixture:blend_models/model.json");
    private final DescriptorDecoder decoder = new DescriptorDecoder();

    @Test
    void appliesSchemaMaterialDefaultsAndSupportsCutoutAndAdditive() {
        ModelDescriptor defaults = decode("""
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"fixture:models3d/model.glb",
                 "materials":{"Base":{"base_color":"fixture:textures/base.png"}}}
                """);
        MaterialDefinition base = defaults.materials().get("Base");
        assertEquals(ModelProfile.RIGID_V1, defaults.profile());
        assertEquals(MaterialDefinition.Mode.OPAQUE, base.mode());
        assertFalse(base.emissive());
        assertFalse(base.doubleSided());
        assertNull(base.cutoutThreshold());

        ModelDescriptor special = decode("""
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"fixture:models3d/model.glb",
                 "materials":{
                   "Cut":{"base_color":"fixture:textures/cut.png","mode":"cutout","cutout_threshold":0.25},
                   "Add":{"base_color":"fixture:textures/add.png","mode":"additive","emissive":true,"double_sided":true}
                 }}
                """);
        assertEquals(MaterialDefinition.Mode.CUTOUT, special.materials().get("Cut").mode());
        assertEquals(0.25, special.materials().get("Cut").cutoutThreshold());
        assertEquals(MaterialDefinition.Mode.ADDITIVE, special.materials().get("Add").mode());
    }

    @Test
    void rejectsInvalidCutoutShapeWithStableDescriptorCode() {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, () -> decode("""
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"fixture:models3d/model.glb",
                 "materials":{"Base":{"base_color":"fixture:textures/base.png","cutout_threshold":0.5}}}
                """));
        assertEquals(BlendDiagnosticCodes.DESC_002, exception.diagnostic().code());
    }

    @Test
    void rejectsMeshOutsideTheFrozenModels3dResourceDirectory() {
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, () -> decode("""
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"fixture:not-models3d/model.glb",
                 "materials":{"Base":{"base_color":"fixture:textures/base.png"}}}
                """));
        assertEquals(BlendDiagnosticCodes.DESC_002, exception.diagnostic().code());
    }

    @Test
    void enforcesTheUnifiedDescriptorSpeedBoundaryWithAnExactPointer() {
        ModelDescriptor atLimit = decode(animationDescriptor(
                Double.toString(BlendAssetLimits.MAX_ANIMATION_SPEED), 0));
        assertEquals(BlendAssetLimits.MAX_ANIMATION_SPEED,
                atLimit.animation().states().get(BlendResourceId.parse("fixture:idle")).speed());

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                () -> decode(animationDescriptor(Double.toString(Math.nextUp(BlendAssetLimits.MAX_ANIMATION_SPEED)), 0)));
        assertEquals(BlendDiagnosticCodes.LIMIT_001, exception.diagnostic().code());
        assertEquals("/animation/states/fixture:idle/speed", exception.diagnostic().location());

        for (String invalid : new String[] {"0", "-1"}) {
            BlendAssetLoadException invalidException = assertThrows(BlendAssetLoadException.class,
                    () -> decode(animationDescriptor(invalid, 0)));
            assertEquals(BlendDiagnosticCodes.DESC_002, invalidException.diagnostic().code());
            assertEquals("/animation/states/fixture:idle/speed", invalidException.diagnostic().location());
        }
    }

    @Test
    void acceptsThePerStateEventBoundaryAndRejectsTheNextDeclaration() {
        ModelDescriptor atLimit = decode(animationDescriptor(
                "1", BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE));
        assertEquals(BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE,
                atLimit.animation().states().get(BlendResourceId.parse("fixture:idle")).events().size());

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, () -> decode(animationDescriptor(
                "1", BlendAssetLimits.MAX_VISUAL_EVENTS_PER_STATE + 1)));
        assertEquals(BlendDiagnosticCodes.LIMIT_001, exception.diagnostic().code());
        assertEquals("/animation/states/fixture:idle/events", exception.diagnostic().location());
    }

    @Test
    void descriptorCannotBypassTheDefaultJsonArrayLimit() {
        String extensions = "\"fixture:extension\",".repeat(16_385);
        String json = """
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"fixture:models3d/model.glb",
                 "materials":{"Base":{"base_color":"fixture:textures/base.png"}},
                 "extensions_used":[%s]}
                """.formatted(extensions.substring(0, extensions.length() - 1));

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, () -> decode(json));

        assertEquals(BlendDiagnosticCodes.DESC_002, exception.diagnostic().code());
        assertEquals("", exception.diagnostic().location());
        assertTrue(exception.getCause().getMessage().startsWith(
                "JSON array entry count exceeds configured limit at JSON character "));
    }

    @Test
    void selfAuthoredDescriptorFixturesMapToFrozenCodes() throws IOException {
        for (P3FixtureCatalog.DescriptorFixture fixture : P3FixtureCatalog.DescriptorFixture.values()) {
            byte[] bytes = readResource(fixture.resourcePath());
            if (fixture.metadata().valid()) {
                decoder.decode(MODEL_KEY, new AssetBytes(DESCRIPTOR_ID, bytes));
                continue;
            }
            BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                    () -> decoder.decode(MODEL_KEY, new AssetBytes(DESCRIPTOR_ID, bytes)), fixture.name());
            String expected = fixture.metadata().expectedDiagnosticFamily();
            if (expected.endsWith("-001")) {
                assertEquals(expected, exception.diagnostic().code(), fixture.name());
            } else {
                assertEquals(BlendDiagnosticCodes.DESC_002, exception.diagnostic().code(), fixture.name());
            }
        }
    }

    private ModelDescriptor decode(String json) {
        return decoder.decode(MODEL_KEY, new AssetBytes(DESCRIPTOR_ID, json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String animationDescriptor(String speed, int eventCount) {
        StringBuilder events = new StringBuilder(eventCount * 55);
        for (int index = 0; index < eventCount; index++) {
            if (index > 0) {
                events.append(',');
            }
            events.append("{\"time_seconds\":0,\"event\":\"fixture:event\"}");
        }
        return """
                {"format_version":1,"profile":"blendlib:rigid_v1","mesh":"fixture:models3d/model.glb",
                 "materials":{"Base":{"base_color":"fixture:textures/base.png"}},
                 "animation":{"initial_state":"fixture:idle","states":{"fixture:idle":{
                   "clip":"idle","loop":true,"speed":%s,"events":[%s]}}}}
                """.formatted(speed, events);
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream stream = DescriptorDecoderTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return stream.readAllBytes();
        }
    }
}
