package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.render.MaterialRejectionReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reload-level regression tests for ADR-014 missing-model containment and diagnostics. */
class MaterialReloadDiagnosticTest {
    private static final BlendModelKey MODEL_KEY = BlendModelKey.parse("material_test:fixtures/invalid_material");
    private static final BlendResourceId TEXTURE = BlendResourceId.parse("material_test:textures/base.png");
    private static final String MATERIAL_SLOT = "Material~Slot/One";
    private static final long GENERATION = 41L;

    @Test
    void everyAcceptedAdr014RejectionBecomesOneMat004MissingModelAtTheRelevantField() {
        List<RejectionCase> cases = List.of(
                new RejectionCase(
                        material(MaterialDefinition.Mode.OPAQUE, false, true, null),
                        MaterialRejectionReason.OPAQUE_DOUBLE_SIDED_UNSUPPORTED),
                new RejectionCase(
                        material(MaterialDefinition.Mode.CUTOUT, false, true, 0.5D),
                        MaterialRejectionReason.CUTOUT_THRESHOLD_UNSUPPORTED),
                new RejectionCase(
                        material(MaterialDefinition.Mode.TRANSLUCENT, true, false, null),
                        MaterialRejectionReason.TRANSLUCENT_SINGLE_SIDED_UNSUPPORTED),
                new RejectionCase(
                        material(MaterialDefinition.Mode.ADDITIVE, true, true, null),
                        MaterialRejectionReason.ADDITIVE_UNSUPPORTED_IN_P4));

        for (RejectionCase rejection : cases) {
            ModelRegistryGeneration generation = ClientModelReloadListener.createPublishedGeneration(
                    new PreparedModelGeneration(GENERATION, Map.of(MODEL_KEY, asset(rejection.material())), Map.of(), List.of()));

            ModelHandle handle = generation.find(MODEL_KEY).orElseThrow();
            BlendDiagnostic diagnostic = generation.primaryDiagnostic(MODEL_KEY).orElseThrow();
            assertTrue(handle instanceof MissingModelHandle, rejection.reason()::name);
            assertTrue(handle.missing());
            assertTrue(handle.renderHandle().missingModel());
            assertEquals(BlendDiagnosticCodes.MAT_004, diagnostic.code());
            assertEquals(MODEL_KEY.resourceId(), diagnostic.modelKey());
            assertEquals(MODEL_KEY.descriptorResourceId(), diagnostic.resourceId());
            assertEquals(
                    "/materials/Material~0Slot~1One/" + rejection.reason().descriptorField(), diagnostic.location());
            assertTrue(diagnostic.message().contains(rejection.reason().name()));
            assertTrue(diagnostic.causeSummary().contains("UnsupportedRenderMaterialException"));
        }
    }

    private static MaterialDefinition material(
            MaterialDefinition.Mode mode, boolean emissive, boolean doubleSided, Double cutoutThreshold) {
        return new MaterialDefinition(TEXTURE, mode, emissive, doubleSided, cutoutThreshold);
    }

    private static ModelAsset asset(MaterialDefinition material) {
        MeshPrimitive primitive = new MeshPrimitive(
                MATERIAL_SLOT,
                new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                null,
                null);
        return new ModelAsset(
                MODEL_KEY.resourceId(),
                MODEL_KEY.descriptorResourceId(),
                GENERATION,
                ModelProfile.RIGID_V1,
                1.0D,
                Map.of(MATERIAL_SLOT, material),
                null,
                List.of(new ModelNode(0, "Root", Transform.IDENTITY, List.of(), 0, -1, false)),
                List.of(0),
                List.of(new ModelPrimitive(0, 0, 0, primitive)),
                null,
                List.of(),
                new SocketTable(Map.of()),
                new Bounds(Vec3.ZERO, new Vec3(1.0F, 1.0F, 0.0F)),
                List.of());
    }

    private record RejectionCase(MaterialDefinition material, MaterialRejectionReason reason) {
    }
}
