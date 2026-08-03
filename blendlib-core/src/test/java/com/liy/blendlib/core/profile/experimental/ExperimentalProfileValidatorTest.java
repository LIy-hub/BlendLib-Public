package com.liy.blendlib.core.profile.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.descriptor.DescriptorDecoder;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.testsupport.P3FixtureCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Contract tests for the isolated X9 validation candidate, not a v1 runtime extension. */
class ExperimentalProfileValidatorTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("x9:fixtures/morph_candidate");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("x9:blend_models/morph_candidate.json");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("x9:models3d/morph_candidate.glb");
    private static final BlendResourceId SKINNED_MESH_ID = BlendResourceId.parse("x9:models3d/skinned_candidate.glb");

    private final ExperimentalProfileValidator validator = new ExperimentalProfileValidator();

    @Test
    void reviewerRegressionRejectsShapeOnlySkinWithoutAStructuralSkinGraph() {
        byte[] incomplete = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"skins\":[{\"name\":\"CandidateSkin\",\"inverseBindMatrices\":11,\"skeleton\":1,\"joints\":[1]}],",
                        ""));
        assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), incomplete));
    }

    @Test
    void positiveMorphFixtureProducesTheGoldenDeterministicSummary() throws IOException {
        String descriptor = positiveDescriptor();
        ExperimentalProfileValidationResult first = validate(descriptor, candidateGlb("", 6, 1, false));
        ExperimentalProfileValidationResult second = validate(descriptor, candidateGlb("", 6, 1, false));

        assertEquals(ExperimentalProfile.MORPH_V1, first.descriptor().profile());
        assertEquals(1, first.primitiveCount());
        assertEquals(1, first.morphTargetCount());
        assertEquals(1, first.cubicSplineSamplerCount());
        assertEquals(1, first.vertexColorPrimitiveCount());
        assertEquals(1, first.secondaryUvPrimitiveCount());
        assertEquals(List.of(
                BlendResourceId.parse("blendlib:cubic-spline"),
                BlendResourceId.parse("blendlib:morph-targets"),
                BlendResourceId.parse("blendlib:multiple-uv"),
                BlendResourceId.parse("blendlib:richer-material-metadata"),
                BlendResourceId.parse("blendlib:vertex-color")), first.negotiatedCapabilities());
        assertEquals(1, first.diagnostics().size());
        ExperimentalProfileDiagnostic diagnostic = first.diagnostics().getFirst();
        assertEquals(ExperimentalProfileDiagnostic.Severity.WARN, diagnostic.severity());
        assertEquals("BLENDLIB-X9-EXT-002", diagnostic.code());
        assertEquals("metadata_ignore", diagnostic.fallback());
        assertEquals(first.negotiatedCapabilities(), second.negotiatedCapabilities());
        assertEquals(first.diagnostics(), second.diagnostics());

        String golden = resourceText("x9/golden/validation-summary.json").strip();
        assertNotNull(StrictJsonParser.parse(golden.getBytes(StandardCharsets.UTF_8)));
        String externalGolden = Files.readString(repositoryRoot().resolve("test-assets/x9/golden/validation-summary.json")).strip();
        assertEquals(golden, first.canonicalJson());
        assertEquals(golden, externalGolden);
        assertEquals(first.canonicalJson(), second.canonicalJson());
    }

    @Test
    void positiveSkinnedFixtureHasARealSkinGraphAndTrsCubicSplineAnimation() throws IOException {
        ExperimentalProfileValidationResult result = validator.validate(
                MODEL_KEY,
                descriptor(resourceText("x9/descriptors/positive-skinned-v2.json")),
                new AssetBytes(SKINNED_MESH_ID, candidateSkinnedGlb()));
        assertEquals(ExperimentalProfile.SKINNED_V2, result.descriptor().profile());
        assertEquals(1, result.primitiveCount());
        assertEquals(0, result.morphTargetCount());
        assertEquals(1, result.cubicSplineSamplerCount());
        assertEquals(1, result.vertexColorPrimitiveCount());
        assertEquals(1, result.secondaryUvPrimitiveCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void schemaAndFixturesStayStrictlyParseableAndV1RemainsIsolated() throws IOException {
        String schema = Files.readString(repositoryRoot().resolve("schemas/experimental/blendlib-model-x9.schema.json"));
        assertNotNull(StrictJsonParser.parse(schema.getBytes(StandardCharsets.UTF_8)));
        assertTrue(schema.contains("\"const\": 2"));
        assertTrue(schema.contains("\"blendlib:skinned_v2\""));
        assertTrue(schema.contains("\"blendlib:morph_v1\""));
        String v1Schema = Files.readString(repositoryRoot().resolve("schemas/blendlib-model-v1.schema.json"));
        assertFalse(v1Schema.contains("blendlib:skinned_v2"));
        assertFalse(v1Schema.contains("blendlib:morph_v1"));
        assertNotNull(StrictJsonParser.parse(resourceText("x9/descriptors/positive-morph-v1.json")
                .getBytes(StandardCharsets.UTF_8)));
        assertNotNull(StrictJsonParser.parse(resourceText("x9/descriptors/positive-skinned-v2.json")
                .getBytes(StandardCharsets.UTF_8)));
        assertNotNull(StrictJsonParser.parse(resourceText("x9/descriptors/invalid-unknown-required-capability.json")
                .getBytes(StandardCharsets.UTF_8)));
        assertNotNull(StrictJsonParser.parse(resourceText("x9/descriptors/invalid-unsafe-texture-path.json")
                .getBytes(StandardCharsets.UTF_8)));
        assertNotNull(StrictJsonParser.parse(Files.readAllBytes(repositoryRoot().resolve("test-assets/x9/descriptor-matrix.json"))));
        assertNotNull(StrictJsonParser.parse(Files.readAllBytes(repositoryRoot().resolve("test-assets/x9/disabled-codecs.json"))));

        ExperimentalProfileValidationException v1ForX9 = assertThrows(ExperimentalProfileValidationException.class,
                () -> new ExperimentalDescriptorDecoder().decode(MODEL_KEY, descriptor(v1Descriptor("blendlib:rigid_v1"))));
        assertEquals("BLENDLIB-X9-DESC-001", v1ForX9.diagnostic().code());

        BlendAssetLoadException x9ForV1 = assertThrows(BlendAssetLoadException.class,
                () -> new DescriptorDecoder().decode(MODEL_KEY,
                        descriptor(v1Descriptor("blendlib:morph_v1").replace("\"format_version\":1", "\"format_version\":2"))));
        assertEquals(BlendDiagnosticCodes.DESC_001, x9ForV1.diagnostic().code());

        BlendAssetLoadException morphForV1 = assertThrows(BlendAssetLoadException.class,
                () -> new DescriptorDecoder().decode(MODEL_KEY, descriptor(v1Descriptor("blendlib:morph_v1"))));
        assertEquals(BlendDiagnosticCodes.DESC_002, morphForV1.diagnostic().code());

        AssetBytes oldDescriptor = descriptor(v1Descriptor("blendlib:rigid_v1"));
        AssetBytes oldGlb = new AssetBytes(MESH_ID, P3FixtureCatalog.glb(P3FixtureCatalog.GlbFixture.VALID_TRIANGLE));
        ModelAsset oldAsset = new ModelAssetLoader().load(MODEL_KEY, oldDescriptor, ignored -> oldGlb);
        assertEquals("blendlib:rigid_v1", oldAsset.profile().serializedName());
        assertEquals(3, oldAsset.primitives().getFirst().geometry().vertexCount());
        assertEquals("blendlib:skinned_v1", new DescriptorDecoder().decode(MODEL_KEY,
                descriptor(v1Descriptor("blendlib:skinned_v1"))).profile().serializedName());
    }

    @Test
    void schemaBoundaryCorpusMatchesTheStrictDescriptorDecoder() throws IOException {
        Path corpus = repositoryRoot().resolve("test-assets/x9/schema-corpus");
        ExperimentalDescriptorDecoder decoder = new ExperimentalDescriptorDecoder();
        for (String file : List.of("valid-standard.json", "valid-hidden-model.json")) {
            byte[] bytes = Files.readAllBytes(corpus.resolve(file));
            ExperimentalDescriptor decoded = decoder.decode(MODEL_KEY, new AssetBytes(
                    BlendResourceId.parse("x9:schema-corpus/" + file), bytes));
            assertNotNull(decoded, file);
        }
        for (String file : List.of(
                "invalid-blank-material.json",
                "invalid-empty-capability-segment.json",
                "invalid-extra-property.json",
                "invalid-duplicate-capability.json",
                "invalid-too-many-capabilities.json",
                "invalid-control-character-path.json",
                "invalid-unicode-material.json",
                "invalid-number-type.json",
                "invalid-semver-component-bound.json",
                "invalid-oversize-material-key.json")) {
            byte[] bytes = Files.readAllBytes(corpus.resolve(file));
            assertThrows(ExperimentalProfileValidationException.class,
                    () -> decoder.decode(MODEL_KEY, new AssetBytes(
                            BlendResourceId.parse("x9:schema-corpus/" + file), bytes)), file);
        }
    }

    @Test
    void formatProfileAndVersionRangesFailClosedAtTheirBoundaries() {
        ExperimentalProfileValidationException wrongFormat = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor().replace("\"format_version\": 2", "\"format_version\": 1"),
                        candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-DESC-001", wrongFormat.diagnostic().code());

        ExperimentalProfileValidationException wrongProfile = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor().replace("blendlib:morph_v1", "blendlib:rigid_v1"),
                        candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-DESC-002", wrongProfile.diagnostic().code());

        ExperimentalCapabilityRequirement halfOpen = new ExperimentalCapabilityRequirement(
                BlendResourceId.parse("blendlib:cubic-spline"), ExperimentalSemVer.parse("1.0.0"),
                ExperimentalSemVer.parse("2.0.0"), true, OptionalCapabilityFallback.FAIL_CLOSED);
        assertTrue(halfOpen.includes(ExperimentalSemVer.parse("1.0.0")));
        assertTrue(halfOpen.includes(ExperimentalSemVer.parse("1.999.999")));
        assertFalse(halfOpen.includes(ExperimentalSemVer.parse("2.0.0")));

        ExperimentalProfileValidationResult atMinimum = validate(replaceCapabilityRange(
                positiveDescriptor(), "blendlib:cubic-spline", "1.0.0", "1.0.1"), candidateGlb("", 6, 1, false));
        assertEquals(ExperimentalProfile.MORPH_V1, atMinimum.descriptor().profile());
        assertExtensionFailure(replaceCapabilityRange(positiveDescriptor(), "blendlib:cubic-spline", "1.0.1", "2.0.0"));
        assertExtensionFailure(replaceCapabilityRange(positiveDescriptor(), "blendlib:cubic-spline", "0.0.0", "1.0.0"));

        ExperimentalProfileValidationException emptyRange = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(replaceCapabilityRange(positiveDescriptor(), "blendlib:cubic-spline", "1.0.0", "1.0.0"),
                        candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-DESC-002", emptyRange.diagnostic().code());
    }

    @Test
    void unknownOptionalIsWarningOnlyForMetadataIgnoreAndAlwaysAppearsInTheSummary() {
        ExperimentalProfileValidationResult metadataOnly = validate(positiveDescriptor(), candidateGlb("", 6, 1, false));
        assertEquals(List.of("BLENDLIB-X9-EXT-002"), metadataOnly.diagnostics().stream()
                .map(ExperimentalProfileDiagnostic::code).toList());

        assertOptionalFailure(positiveDescriptor().replace("example:metadata/editor-labels", "example:visual-layer"));
        assertOptionalFailure(positiveDescriptor().replace("\"fallback\": \"metadata_ignore\"", "\"fallback\": \"missing_model\""));
        assertOptionalFailure(positiveDescriptor().replace("example:metadata/editor-labels", "example:metadata/editor-labels-v2")
                .replace("\"fallback\": \"metadata_ignore\"", "\"fallback\": \"missing_model\""));
    }

    @Test
    void disabledCodecsRejectDescriptorAndGlbPaths() {
        for (String codec : List.of("blendlib:draco", "blendlib:meshopt", "blendlib:ktx2")) {
            ExperimentalProfileValidationException exception = assertThrows(ExperimentalProfileValidationException.class,
                    () -> validate(positiveDescriptor().replace("blendlib:morph-targets", codec), candidateGlb("", 6, 1, false)));
            assertEquals(BlendDiagnosticCodes.EXT_001, exception.diagnostic().code(), codec);
        }
        for (String extension : List.of("KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_texture_basisu")) {
            ExperimentalProfileValidationException used = assertThrows(ExperimentalProfileValidationException.class,
                    () -> validate(positiveDescriptor(), candidateGlb("\"extensionsUsed\":[\"" + extension + "\"]", 6, 1, false)));
            assertEquals(BlendDiagnosticCodes.EXT_001, used.diagnostic().code(), extension + " used");
            ExperimentalProfileValidationException required = assertThrows(ExperimentalProfileValidationException.class,
                    () -> validate(positiveDescriptor(), candidateGlb("\"extensionsRequired\":[\"" + extension + "\"]", 6, 1, false)));
            assertEquals(BlendDiagnosticCodes.EXT_001, required.diagnostic().code(), extension + " required");
        }
    }

    @Test
    void strictStructureRejectsNestedExtensionsLimitsReferencesTransformsAndAccessorViolations() {
        for (String extension : List.of(
                "KHR_draco_mesh_compression", "EXT_meshopt_compression", "KHR_texture_basisu")) {
            byte[] payload = rewriteGlbJson(candidateGlb("", 6, 1, false),
                    json -> json.replace(
                            "\"material\":0,\"targets\":",
                            "\"material\":0,\"extensions\":{\"" + extension + "\":{}},\"targets\":"));
            assertGlbFailure(BlendDiagnosticCodes.EXT_001, payload);
        }
        byte[] unknownPayload = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"material\":0,\"targets\":",
                        "\"material\":0,\"extensions\":{\"example_visual\":{}},\"targets\":"));
        assertGlbFailure("BLENDLIB-X9-EXT-003", unknownPayload);

        byte[] tooManyNodes = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"nodes\":[{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0},{\"name\":\"RootJoint\",\"children\":[0]}]",
                        "\"nodes\":" + nodeArray(4_097)));
        assertGlbFailure("BLENDLIB-X9-LIMIT-001", tooManyNodes);

        byte[] cycle = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0}",
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0,\"children\":[1]}"));
        assertGlbFailure("BLENDLIB-X9-SCENE-004", cycle);

        byte[] nonFiniteTransform = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0}",
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0,\"translation\":[1e400,0,0]}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", nonFiniteTransform);

        byte[] invalidMaterial = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("\"material\":0", "\"material\":1"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", invalidMaterial);

        byte[] normalizedFloatPosition = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                                + "\"min\":[0,0,0],\"max\":[1,1,0]}",
                        "{\"bufferView\":0,\"componentType\":5126,\"normalized\":true,\"count\":3,"
                                + "\"type\":\"VEC3\",\"min\":[0,0,0],\"max\":[1,1,0]}"));
        // The audited v1 accessor reader now rejects this shared accessor invariant
        // before the X9-only profile pass. Preserve the stronger canonical diagnostic.
        assertGlbFailure(BlendDiagnosticCodes.GLB_015, normalizedFloatPosition);

        byte[] futureMinimum = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"asset\":{\"version\":\"2.0\"}",
                        "\"asset\":{\"version\":\"2.0\",\"minVersion\":\"3.0\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-002", futureMinimum);

        byte[] unrelatedSkeleton = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("\"skeleton\":1,\"joints\":[1]", "\"skeleton\":0,\"joints\":[1]"));
        assertGlbFailure("BLENDLIB-X9-SKIN-001", unrelatedSkeleton);
    }

    @Test
    void reviewerProbeRejectsDuplicateEffectiveJointInfluences() {
        byte[] duplicateInfluence = rewriteGlbBinary(candidateGlb("", 6, 1, false), binary -> {
            ByteBuffer values = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
            values.putFloat(144, 0.5f);
            values.putFloat(148, 0.5f);
            return binary;
        });
        assertGlbFailure("BLENDLIB-X9-SKIN-001", duplicateInfluence);
    }

    @Test
    void reviewerProbeRejectsSingularInverseBindMatrices() {
        byte[] singularInverseBind = rewriteGlbBinary(candidateGlb("", 6, 1, false), binary -> {
            Arrays.fill(binary, 316, 380, (byte) 0);
            return binary;
        });
        assertGlbFailure("BLENDLIB-X9-SKIN-001", singularInverseBind);
    }

    @Test
    void reviewerProbeRequiresPositionAndAnimationInputBounds() {
        byte[] withoutPositionBounds = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(",\"min\":[0,0,0],\"max\":[1,1,0]", ""));
        assertGlbFailure("BLENDLIB-X9-GLB-002", withoutPositionBounds);
        byte[] withoutAnimationBounds = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(",\"min\":[0],\"max\":[1]", ""));
        assertGlbFailure("BLENDLIB-X9-GLB-002", withoutAnimationBounds);
    }

    @Test
    void reviewerProbeRejectsFalseAccessorBounds() {
        byte[] falseBounds = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"min\":[0,0,0],\"max\":[1,1,0]",
                        "\"min\":[-10,-10,-10],\"max\":[10,10,10]"));
        assertGlbFailure(BlendDiagnosticCodes.GLB_015, falseBounds);

        byte[] halfDeclaredBounds = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"min\":[0,0,0],\"max\":[1,1,0]",
                        "\"min\":[0,0,0]"));
        // A half-declared pair is completed by the X9 structural contract.
        assertGlbFailure("BLENDLIB-X9-GLB-015", halfDeclaredBounds);

        byte[] reversedBounds = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"min\":[0,0,0],\"max\":[1,1,0]",
                        "\"min\":[2,0,0],\"max\":[1,1,0]"));
        assertGlbFailure(BlendDiagnosticCodes.GLB_015, reversedBounds);
    }

    @Test
    void reviewerProbeValidatesMorphNormalTangentAndRejectsSparseAccessors() {
        byte[] fullMorphTarget = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"targets\":[{\"POSITION\":8}]",
                        "\"targets\":[{\"POSITION\":8,\"NORMAL\":8,\"TANGENT\":8}]"));
        assertEquals(1, validate(positiveDescriptor(), fullMorphTarget).morphTargetCount());

        byte[] wrongTangentType = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"targets\":[{\"POSITION\":8}]",
                        "\"targets\":[{\"POSITION\":8,\"TANGENT\":2}]"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", wrongTangentType);

        byte[] sparseAccessor = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"bufferView\":8,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}",
                        "{\"bufferView\":8,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                                + "\"sparse\":{\"count\":1}}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", sparseAccessor);
    }

    @Test
    void reviewerProbeAvoidsAnExtraWholeBinaryCopyInTheX9StructurePass() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "blendlib-core/src/main/java/com/liy/blendlib/core/profile/experimental/ExperimentalGlbStructureValidator.java"));
        assertFalse(source.contains("document.binaryCopy()"));
    }

    @Test
    void reviewerProbeRejectsNonUnitStaticRotation() {
        byte[] staticRotation = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0}",
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0,\"rotation\":[2,0,0,0]}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", staticRotation);
    }

    @Test
    void reviewerProbeRejectsNonUnitAnimatedRotation() {
        byte[] animatedRotation = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("\"buffer\":0,\"byteOffset\":244,\"byteLength\":72",
                                "\"buffer\":0,\"byteOffset\":244,\"byteLength\":96")
                        .replace("{\"bufferView\":10,\"componentType\":5126,\"count\":6,\"type\":\"SCALAR\"}",
                                "{\"bufferView\":10,\"componentType\":5126,\"count\":6,\"type\":\"VEC4\"}")
                        .replace("\"target\":{\"node\":0,\"path\":\"weights\"}",
                                "\"target\":{\"node\":0,\"path\":\"rotation\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", animatedRotation);
    }

    @Test
    void reviewerProbeRejectsNormalizedIndices() {
        byte[] normalizedIndices = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"bufferView\":7,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}",
                        "{\"bufferView\":7,\"componentType\":5123,\"normalized\":true,"
                                + "\"count\":3,\"type\":\"SCALAR\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", normalizedIndices);
    }

    @Test
    void reviewerProbeRejectsInvalidOptionalNameType() {
        byte[] invalidBufferName = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("{\"byteLength\":380}", "{\"byteLength\":380,\"name\":{}}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", invalidBufferName);
    }

    @Test
    void reviewerProbeRejectsInvalidBufferViewTargetType() {
        byte[] invalidBufferViewTarget = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36}",
                        "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36,\"target\":\"ARRAY_BUFFER\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", invalidBufferViewTarget);
    }

    @Test
    void reviewerProbeBoundsLocalCollectionsBeforeCopyingThem() throws IOException {
        String source = experimentalStructureValidatorSource();
        assertTrue(source.contains("boundedIntegerList("));
        assertTrue(source.contains("boundedFiniteList("));
    }

    @Test
    void reviewerProbeIndexesHierarchyOnceInsteadOfAllocatingASubtreePerSkin() throws IOException {
        String source = experimentalStructureValidatorSource();
        assertTrue(source.contains("HierarchyOrder"));
        assertFalse(source.contains("descendantsIncludingSelf"));
    }

    @Test
    void declaredAdvancedCapabilitiesRequireMatchingGlbFeaturesAndExactMorphAnimationSemantics() {
        byte[] withoutColor = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(",\"COLOR_0\":4", ""));
        assertGlbFailure("BLENDLIB-X9-GLB-015", withoutColor);

        byte[] withoutUv1 = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(",\"TEXCOORD_1\":3", ""));
        assertGlbFailure("BLENDLIB-X9-GLB-015", withoutUv1);

        byte[] withoutCubicSpline = rewriteGlbJson(candidateGlb("", 2, 1, false),
                json -> json.replace("\"interpolation\":\"CUBICSPLINE\"", "\"interpolation\":\"LINEAR\""));
        assertGlbFailure("BLENDLIB-X9-GLB-015", withoutCubicSpline);

        ExperimentalProfileValidationException material = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(descriptorWithDefaultOnlyMaterial(), candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-DESC-003", material.diagnostic().code());

        byte[] wrongWeightTarget = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("\"target\":{\"node\":0,\"path\":\"weights\"}",
                        "\"target\":{\"node\":1,\"path\":\"weights\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", wrongWeightTarget);

        byte[] vec3Weights = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"bufferView\":10,\"componentType\":5126,\"count\":6,\"type\":\"SCALAR\"}",
                        "{\"bufferView\":10,\"componentType\":5126,\"count\":6,\"type\":\"VEC3\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", vec3Weights);

        assertGlbFailure("BLENDLIB-X9-GLB-015",
                candidateGlb("", 6, new int[] {1, 2}, false));
    }

    @Test
    void strictNegativeInputsCheckCardinalityBoundsNonFiniteValuesAndSafePaths() throws IOException {
        ExperimentalProfileValidationException unknownRequired = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(resourceText("x9/descriptors/invalid-unknown-required-capability.json")
                        .replace("models3d/skinned_candidate.glb", "models3d/morph_candidate.glb"), candidateGlb("", 6, 1, false)));
        assertEquals(BlendDiagnosticCodes.EXT_001, unknownRequired.diagnostic().code());

        ExperimentalProfileValidationException unsafePath = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(resourceText("x9/descriptors/invalid-unsafe-texture-path.json"), candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-DESC-002", unsafePath.diagnostic().code());

        ExperimentalProfileValidationException cubicCardinality = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), candidateGlb("", 2, 1, false)));
        assertEquals("BLENDLIB-X9-GLB-015", cubicCardinality.diagnostic().code());

        ExperimentalProfileValidationException morphBound = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), candidateGlb("", 6, 65, false)));
        assertEquals("BLENDLIB-X9-LIMIT-001", morphBound.diagnostic().code());

        ExperimentalProfileValidationException nonFinite = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), candidateGlb("", 6, 1, true)));
        assertEquals(BlendDiagnosticCodes.GLB_015, nonFinite.diagnostic().code());

        byte[] oversized = new byte[ExperimentalProfileLimits.DEFAULT.maxDescriptorBytes() + 1];
        ExperimentalProfileValidationException descriptorBound = assertThrows(ExperimentalProfileValidationException.class,
                () -> new ExperimentalDescriptorDecoder().decode(MODEL_KEY, new AssetBytes(DESCRIPTOR_ID, oversized)));
        assertEquals("BLENDLIB-X9-LIMIT-001", descriptorBound.diagnostic().code());
    }

    private void assertExtensionFailure(String descriptor) {
        ExperimentalProfileValidationException exception = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(descriptor, candidateGlb("", 6, 1, false)));
        assertEquals(BlendDiagnosticCodes.EXT_001, exception.diagnostic().code());
    }

    private void assertOptionalFailure(String descriptor) {
        ExperimentalProfileValidationException exception = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(descriptor, candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-EXT-003", exception.diagnostic().code());
        assertEquals("missing_model", exception.diagnostic().fallback());
    }

    private void assertGlbFailure(String expectedCode, byte[] glb) {
        ExperimentalProfileValidationException exception = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), glb));
        assertEquals(expectedCode, exception.diagnostic().code());
    }

    private ExperimentalProfileValidationResult validate(String descriptor, byte[] glb) {
        return validator.validate(MODEL_KEY, descriptor(descriptor), new AssetBytes(MESH_ID, glb));
    }

    private static AssetBytes descriptor(String json) {
        return new AssetBytes(DESCRIPTOR_ID, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String positiveDescriptor() {
        try {
            return resourceText("x9/descriptors/positive-morph-v1.json");
        } catch (IOException exception) {
            throw new IllegalStateException("Missing X9 positive descriptor fixture", exception);
        }
    }

    private static String replaceCapabilityRange(String descriptor, String capability, String min, String max) {
        String original = "\"" + capability
                + "\": { \"requirement\": \"required\", \"min_version\": \"1.0.0\", \"max_version\": \"2.0.0\" }";
        String replacement = "\"" + capability
                + "\": { \"requirement\": \"required\", \"min_version\": \"" + min
                + "\", \"max_version\": \"" + max + "\" }";
        assertTrue(descriptor.contains(original), "fixture must contain the capability range under test");
        return descriptor.replace(original, replacement);
    }

    private static String v1Descriptor(String profile) {
        return """
                {"format_version":1,"profile":"%s","mesh":"x9:models3d/morph_candidate.glb",
                 "materials":{"FixtureMaterial":{"base_color":"x9:textures/x9/candidate.png"}}}
                """.formatted(profile);
    }

    private static String descriptorWithDefaultOnlyMaterial() {
        String descriptor = positiveDescriptor();
        int start = descriptor.indexOf("\"materials\":");
        int end = descriptor.indexOf(",\n  \"capabilities\":", start);
        assertTrue(start >= 0 && end > start, "positive fixture material section must be replaceable");
        return descriptor.substring(0, start)
                + "\"materials\":{\"CandidateSurface\":{\"base_color\":\"x9:textures/x9/candidate.png\"}}"
                + descriptor.substring(end);
    }

    private static String resourceText(String resourcePath) throws IOException {
        try (InputStream stream = ExperimentalProfileValidatorTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Missing test resource: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent();
    }

    private static String experimentalStructureValidatorSource() throws IOException {
        return Files.readString(repositoryRoot().resolve(
                "blendlib-core/src/main/java/com/liy/blendlib/core/profile/experimental/ExperimentalGlbStructureValidator.java"));
    }

    private static byte[] candidateGlb(String rootMember, int cubicOutputCount, int morphTargetCount, boolean nonFinitePosition) {
        return candidateGlb(rootMember, cubicOutputCount, new int[] {morphTargetCount}, nonFinitePosition);
    }

    private static byte[] candidateSkinnedGlb() {
        return rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("\"weights\":[0],", "")
                        .replace(",\"targets\":[{\"POSITION\":8}]", "")
                        .replace("{\"bufferView\":10,\"componentType\":5126,\"count\":6,\"type\":\"SCALAR\"}",
                                "{\"bufferView\":10,\"componentType\":5126,\"count\":6,\"type\":\"VEC3\"}")
                        .replace("\"target\":{\"node\":0,\"path\":\"weights\"}",
                                "\"target\":{\"node\":0,\"path\":\"translation\"}"));
    }

    private static byte[] candidateGlb(
            String rootMember, int cubicOutputCount, int[] primitiveTargetCounts, boolean nonFinitePosition) {
        ByteBuffer binary = ByteBuffer.allocate(380).order(ByteOrder.LITTLE_ENDIAN);
        putFloats(binary, 0, nonFinitePosition ? Float.NaN : 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        putFloats(binary, 36, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f);
        putFloats(binary, 72, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f);
        putFloats(binary, 96, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f);
        for (int index = 0; index < 12; index++) {
            binary.put(120 + index, (byte) 0xff);
            binary.put(132 + index, (byte) 0);
        }
        for (int vertex = 0; vertex < 3; vertex++) {
            binary.putFloat(144 + vertex * 16, 1.0f);
            binary.putFloat(148 + vertex * 16, 0.0f);
            binary.putFloat(152 + vertex * 16, 0.0f);
            binary.putFloat(156 + vertex * 16, 0.0f);
        }
        binary.putShort(192, (short) 0);
        binary.putShort(194, (short) 1);
        binary.putShort(196, (short) 2);
        putFloats(binary, 200, 0.0f, 0.1f, 0.0f, 0.0f, 0.1f, 0.0f, 0.0f, 0.1f, 0.0f);
        putFloats(binary, 236, 0.0f, 1.0f);
        for (int index = 0; index < 18; index++) {
            binary.putFloat(244 + index * Float.BYTES, index * 0.01f);
        }
        for (int row = 0; row < 4; row++) {
            binary.putFloat(316 + (row * 4 + row) * Float.BYTES, 1.0f);
        }

        int meshTargetCount = primitiveTargetCounts[0];
        String primitives = primitiveArray(primitiveTargetCounts);
        String weights = zeroWeights(meshTargetCount);
        String extension = rootMember.isEmpty() ? "" : "," + rootMember;
        String json = """
                {"asset":{"version":"2.0"}%s,"buffers":[{"byteLength":380}],
                "bufferViews":[
                {"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},
                {"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":24},
                {"buffer":0,"byteOffset":120,"byteLength":12},{"buffer":0,"byteOffset":132,"byteLength":12},
                {"buffer":0,"byteOffset":144,"byteLength":48},{"buffer":0,"byteOffset":192,"byteLength":6},
                {"buffer":0,"byteOffset":200,"byteLength":36},{"buffer":0,"byteOffset":236,"byteLength":8},
                {"buffer":0,"byteOffset":244,"byteLength":72},{"buffer":0,"byteOffset":316,"byteLength":64}],
                "accessors":[
                {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":3,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":4,"componentType":5121,"normalized":true,"count":3,"type":"VEC4"},
                {"bufferView":5,"componentType":5121,"count":3,"type":"VEC4"},
                {"bufferView":6,"componentType":5126,"count":3,"type":"VEC4"},
                {"bufferView":7,"componentType":5123,"count":3,"type":"SCALAR"},
                {"bufferView":8,"componentType":5126,"count":3,"type":"VEC3"},
                {"bufferView":9,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
                {"bufferView":10,"componentType":5126,"count":%d,"type":"SCALAR"},
                {"bufferView":11,"componentType":5126,"count":1,"type":"MAT4"}],
                "materials":[{"name":"CandidateSurface"}],
                "meshes":[{"weights":[%s],"primitives":%s}],
                "nodes":[{"name":"MorphMesh","mesh":0,"skin":0},{"name":"RootJoint","children":[0]}],
                "skins":[{"name":"CandidateSkin","inverseBindMatrices":11,"skeleton":1,"joints":[1]}],
                "scenes":[{"nodes":[1]}],"scene":0,
                "animations":[{"name":"MorphPulse","samplers":[{"input":9,"output":10,"interpolation":"CUBICSPLINE"}],
                "channels":[{"sampler":0,"target":{"node":0,"path":"weights"}}]}]}
                """.formatted(extension, cubicOutputCount, weights, primitives).replaceAll("\\s+", "");
        return glb(json, binary.array());
    }

    private static void putFloats(ByteBuffer buffer, int offset, float... values) {
        for (int index = 0; index < values.length; index++) {
            buffer.putFloat(offset + index * Float.BYTES, values[index]);
        }
    }

    private static String targetArray(int count) {
        StringBuilder targets = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                targets.append(',');
            }
            targets.append("{\"POSITION\":8}");
        }
        return targets.append(']').toString();
    }

    private static String primitiveArray(int[] targetCounts) {
        StringBuilder primitives = new StringBuilder("[");
        for (int index = 0; index < targetCounts.length; index++) {
            if (index > 0) {
                primitives.append(',');
            }
            primitives.append("{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,")
                    .append("\"TEXCOORD_1\":3,\"COLOR_0\":4,\"JOINTS_0\":5,\"WEIGHTS_0\":6},")
                    .append("\"indices\":7,\"material\":0,\"targets\":")
                    .append(targetArray(targetCounts[index]))
                    .append('}');
        }
        return primitives.append(']').toString();
    }

    private static String zeroWeights(int count) {
        StringBuilder weights = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                weights.append(',');
            }
            weights.append('0');
        }
        return weights.toString();
    }

    private static String nodeArray(int count) {
        StringBuilder nodes = new StringBuilder(count * 3);
        nodes.append('[');
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                nodes.append(',');
            }
            nodes.append("{}");
        }
        return nodes.append(']').toString();
    }

    private static byte[] rewriteGlbJson(byte[] source, UnaryOperator<String> rewrite) {
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = input.getInt(binaryHeader);
        String json = new String(source, 20, jsonLength, StandardCharsets.UTF_8).stripTrailing();
        byte[] binary = Arrays.copyOfRange(source, binaryHeader + 8, binaryHeader + 8 + binaryLength);
        return glb(rewrite.apply(json), binary);
    }

    private static byte[] rewriteGlbBinary(byte[] source, UnaryOperator<byte[]> rewrite) {
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = input.getInt(binaryHeader);
        String json = new String(source, 20, jsonLength, StandardCharsets.UTF_8).stripTrailing();
        byte[] binary = Arrays.copyOfRange(source, binaryHeader + 8, binaryHeader + 8 + binaryLength);
        return glb(json, rewrite.apply(binary));
    }

    private static byte[] glb(String json, byte[] binary) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = (jsonBytes.length + 3) & ~3;
        int paddedBinaryLength = (binary.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJsonLength + 8 + paddedBinaryLength;
        ByteBuffer result = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x46546c67).putInt(2).putInt(totalLength);
        result.putInt(paddedJsonLength).putInt(0x4e4f534a).put(jsonBytes);
        while (result.position() < 20 + paddedJsonLength) {
            result.put((byte) 0x20);
        }
        result.putInt(paddedBinaryLength).putInt(0x004e4942).put(binary);
        while (result.hasRemaining()) {
            result.put((byte) 0);
        }
        return result.array();
    }
}
