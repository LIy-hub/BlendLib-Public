package com.liy.blendlib.core.profile.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.descriptor.DescriptorDecoder;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.glb.GlbDocument;
import com.liy.blendlib.core.glb.GlbReader;
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
    void r3RejectsNonFiniteValuesInUnusedFloatAccessors() {
        for (float nonFinite : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            byte[] payload = rewriteGlbBinary(candidateGlb("", 6, 1, false), binary -> {
                ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putFloat(380, nonFinite);
                return binary;
            });
            assertThrows(ExperimentalProfileValidationException.class,
                    () -> validate(positiveDescriptor(), payload), Float.toString(nonFinite));
        }
    }

    @Test
    void r3RejectsFalseBoundsForEveryDeclaredAccessor() {
        byte[] falseNormalBounds = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}",
                "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                        + "\"min\":[-1,-1,-1],\"max\":[1,1,1]}"));
        assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), falseNormalBounds));
    }

    @Test
    void r3RejectsPrimitiveRestartReservedIndex() {
        byte[] payload = rewriteGlbBinary(candidateGlb("", 6, 1, false), binary -> {
            ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putShort(192, (short) 0xffff);
            return binary;
        });
        ExperimentalProfileValidationException exception = assertThrows(
                ExperimentalProfileValidationException.class, () -> validate(positiveDescriptor(), payload));
        assertTrue(exception.diagnostic().message().contains("primitive restart"));
    }

    @Test
    void r3AllowsOneAsTheConfiguredUvSetLimit() {
        ExperimentalProfileLimits defaults = ExperimentalProfileLimits.DEFAULT;
        assertDoesNotThrow(() -> copyLimits(defaults.maxDescriptorBytes(), defaults.maxMaterials(),
                defaults.maxCapabilities(), defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(),
                1, defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                defaults.glbLimits()));
    }

    @Test
    void r3AuditsReferencedAndUnusedFloatAccessorsForAllNonFiniteEncodings() {
        for (int offset : new int[] {36, 380}) {
            for (float nonFinite : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                byte[] payload = rewriteGlbBinary(candidateGlb("", 6, 1, false), binary -> {
                    ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, nonFinite);
                    return binary;
                });
                ExperimentalProfileValidationException exception = assertThrows(
                        ExperimentalProfileValidationException.class,
                        () -> validate(positiveDescriptor(), payload), offset + ":" + nonFinite);
                assertEquals(BlendDiagnosticCodes.GLB_015, exception.diagnostic().code());
            }
        }
        assertDoesNotThrow(() -> validate(positiveDescriptor(), candidateGlb("", 6, 1, false)));
    }

    @Test
    void r3ValidatesFloatIntegerNormalizedStrideAndOffsetBoundsAgainstRawData() {
        byte[] exactFloat = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}",
                "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                        + "\"min\":[0,0,1],\"max\":[0,0,1]}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), exactFloat));

        byte[] normalizedRawBounds = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                "{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\","
                        + "\"min\":[255,255,255,255],\"max\":[255,255,255,255]}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), normalizedRawBounds));

        byte[] falseNormalizedBounds = rewriteGlbJson(normalizedRawBounds,
                json -> json.replace("\"max\":[255,255,255,255]", "\"max\":[254,255,255,255]"));
        assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), falseNormalizedBounds));

        byte[] fractionalJoints = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"bufferView\":5,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\"}",
                "{\"bufferView\":5,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\","
                        + "\"min\":[0.5,0,0,0],\"max\":[0.5,0,0,0]}"));
        assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), fractionalJoints));

        byte[] stridedAndOffset = appendAccessor(rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"buffer\":0,\"byteOffset\":380,\"byteLength\":48}",
                        "{\"buffer\":0,\"byteOffset\":380,\"byteLength\":48,\"byteStride\":16}")),
                "{\"bufferView\":12,\"byteOffset\":4,\"componentType\":5126,\"count\":3,"
                        + "\"type\":\"VEC2\",\"min\":[0,0],\"max\":[0,0]}");
        assertDoesNotThrow(() -> validate(positiveDescriptor(), stridedAndOffset));

        byte[] falseStridedBounds = rewriteGlbJson(stridedAndOffset,
                json -> json.replace("\"type\":\"VEC2\",\"min\":[0,0],\"max\":[0,0]}",
                        "\"type\":\"VEC2\",\"min\":[0,0],\"max\":[1,0]}"));
        assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), falseStridedBounds));

        assertDoesNotThrow(() -> validate(positiveDescriptor(), candidateGlb("", 6, 1, false)),
                "Accessors without optional bounds remain legal outside required semantics");
    }

    @Test
    void r3RejectsOnlyTheReservedRestartMaximumForEveryIndexComponentType() {
        long[] componentMaximums = {0xffL, 0xffffL, 0xffff_ffffL};
        int[] componentTypes = {5121, 5123, 5125};
        for (int index = 0; index < componentTypes.length; index++) {
            int componentType = componentTypes[index];
            long maximum = componentMaximums[index];
            byte[] legal = scalarIndexCandidate(componentType, maximum - 1L);
            ExperimentalGlbStructureValidator.Result legalStructure = structure(legal);
            assertEquals(1, legalStructure.validatePrimitiveIndices(7, maximum, "/probe/indices"),
                    "componentType=" + componentType);

            byte[] reserved = scalarIndexCandidate(componentType, maximum);
            ExperimentalProfileValidationException exception = assertThrows(
                    ExperimentalProfileValidationException.class,
                    () -> structure(reserved).validatePrimitiveIndices(7, maximum + 1L, "/probe/indices"),
                    "componentType=" + componentType);
            assertTrue(exception.diagnostic().message().contains("primitive restart"));
        }

        ExperimentalProfileValidationException range = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> structure(scalarIndexCandidate(5125, 0xffff_fffeL))
                        .validatePrimitiveIndices(7, 3L, "/probe/indices"));
        assertTrue(range.diagnostic().message().contains("missing vertex"),
                "U32 max-1 must pass restart validation and reach the independent vertex-range check");

        byte[] u8Triangle = u8TriangleCandidate();
        assertEquals(1, validate(positiveDescriptor(), u8Triangle).primitiveCount());
        byte[] u8OutOfRange = rewriteGlbBinary(u8Triangle, binary -> {
            binary[194] = 3;
            return binary;
        });
        ExperimentalProfileValidationException vertexBoundary = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), u8OutOfRange));
        assertTrue(vertexBoundary.diagnostic().message().contains("missing vertex"));
    }

    @Test
    void r3ConsumesUvLimitAcrossSingleAndMultiplePrimitivesAndRequiresCanonicalConsecutiveSemantics() {
        ExperimentalProfileLimits defaults = ExperimentalProfileLimits.DEFAULT;
        ExperimentalProfileLimits oneUvLimit = copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), 1,
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                defaults.glbLimits());
        ExperimentalProfileValidator oneUvValidator = new ExperimentalProfileValidator(oneUvLimit);
        String oneUvDescriptor = withoutMultipleUvCapability(positiveDescriptor());
        byte[] oneUv = withoutSecondaryUv(candidateGlb("", 6, 1, false));
        assertEquals(1, validate(oneUvValidator, oneUvDescriptor, oneUv).primitiveCount());

        ExperimentalProfileValidationException secondaryRejected = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> validate(oneUvValidator, oneUvDescriptor, candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-LIMIT-001", secondaryRejected.diagnostic().code());

        byte[] twoOneUvPrimitives = withoutSecondaryUv(candidateGlb("", 6, new int[] {1, 1}, false));
        assertEquals(2, validate(oneUvValidator, oneUvDescriptor, twoOneUvPrimitives).primitiveCount());

        byte[] missingUvZero = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("\"TEXCOORD_0\":2,", ""));
        assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), missingUvZero));

        byte[] nonCanonical = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace("\"TEXCOORD_1\":3", "\"TEXCOORD_01\":3"));
        assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), nonCanonical));

        assertEquals(2, ExperimentalProfileLimits.DEFAULT.maxUvSets());
    }

    @Test
    void r4MakesMultipleUvSchemaOptionalAndBindsTheCapabilityToActualUv1BothWays() throws IOException {
        String schema = Files.readString(repositoryRoot().resolve("schemas/experimental/blendlib-model-x9.schema.json"));
        assertFalse(schema.contains("\"blendlib:multiple-uv\""),
                "The X9 schema must not require the optional multiple-UV capability for either profile");

        String noUvCapability = withoutMultipleUvCapability(positiveDescriptor());
        byte[] noSecondaryUv = withoutSecondaryUv(candidateGlb("", 6, 1, false));
        ExperimentalProfileValidationResult noUvResult = validate(noUvCapability, noSecondaryUv);
        assertEquals(0, noUvResult.secondaryUvPrimitiveCount());

        ExperimentalProfileValidationException uvWithoutCapability = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> validate(noUvCapability, candidateGlb("", 6, 1, false)));
        assertEquals("BLENDLIB-X9-GLB-015", uvWithoutCapability.diagnostic().code());

        ExperimentalProfileValidationException capabilityWithoutUv = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), noSecondaryUv));
        assertEquals("BLENDLIB-X9-GLB-015", capabilityWithoutUv.diagnostic().code());

        ExperimentalProfileValidationResult uvWithCapability = validate(positiveDescriptor(), candidateGlb("", 6, 1, false));
        assertEquals(1, uvWithCapability.secondaryUvPrimitiveCount());
    }

    @Test
    void r4RejectsEveryNonCanonicalTexcoordSuffixBeforeItCanBeIgnoredOrCounted() {
        for (String suffix : List.of(
                "", "+1", "-1", "01", "1 ", "1\\t", "2147483648",
                "999999999999999999999999", "not-a-number")) {
            byte[] malformed = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                    "\"TEXCOORD_1\":3", "\"TEXCOORD_" + suffix + "\":3"));
            ExperimentalProfileValidationException exception = assertThrows(
                    ExperimentalProfileValidationException.class,
                    () -> validate(positiveDescriptor(), malformed), suffix);
            assertEquals("BLENDLIB-X9-GLB-015", exception.diagnostic().code(), suffix);
        }

        byte[] beyondHardCeiling = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "\"TEXCOORD_1\":3", "\"TEXCOORD_2\":3"));
        ExperimentalProfileValidationException exception = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), beyondHardCeiling));
        assertEquals("BLENDLIB-X9-LIMIT-001", exception.diagnostic().code());
    }

    @Test
    void r3BoundsTheAggregateAccessorAuditWithTheX9SpecificGlbLimits() {
        ExperimentalProfileLimits defaults = ExperimentalProfileLimits.DEFAULT;
        ExperimentalGlbLimits glb = defaults.glbLimits();
        ExperimentalGlbLimits scanLimitedGlb = copyGlbLimits(
                glb.maxGlbBytes(), 1, 1, glb.maxNodes(), 1,
                glb.maxHierarchyDepth(), 1);
        ExperimentalProfileLimits scanLimited = copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                scanLimitedGlb);
        ExperimentalProfileValidationException scanBound = assertThrows(
                ExperimentalProfileValidationException.class,
                () -> structure(candidateGlb("", 6, 1, false), scanLimited));
        assertEquals("BLENDLIB-X9-LIMIT-001", scanBound.diagnostic().code());
    }

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
        assertNotNull(StrictJsonParser.parse(Files.readAllBytes(repositoryRoot().resolve(
                "test-assets/x9/schema-corpus/valid-morph-without-multiple-uv.json"))));
        assertNotNull(StrictJsonParser.parse(Files.readAllBytes(repositoryRoot().resolve(
                "test-assets/x9/schema-corpus/valid-skinned-without-multiple-uv.json"))));

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
        for (String file : List.of(
                "valid-standard.json", "valid-hidden-model.json", "valid-maximum-capabilities.json",
                "valid-skinned-without-multiple-uv.json", "valid-morph-without-multiple-uv.json")) {
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
                        "\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2",
                        "\"POSITION\":0,\"NORMAL\":1,\"TANGENT\":12,\"TEXCOORD_0\":2")
                        .replace(
                        "\"targets\":[{\"POSITION\":8}]",
                        "\"targets\":[{\"POSITION\":8,\"NORMAL\":8,\"TANGENT\":8}]"));
        assertEquals(1, validate(positiveDescriptor(), fullMorphTarget).morphTargetCount());

        byte[] wrongTangentType = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2",
                        "\"POSITION\":0,\"NORMAL\":1,\"TANGENT\":12,\"TEXCOORD_0\":2")
                        .replace(
                        "\"targets\":[{\"POSITION\":8}]",
                        "\"targets\":[{\"POSITION\":8,\"TANGENT\":2}]"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", wrongTangentType);

        byte[] sparseAccessor = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"bufferView\":8,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                                + "\"min\":[0,0.1,0],\"max\":[0,0.1,0]}",
                        "{\"bufferView\":8,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                                + "\"min\":[0,0.1,0],\"max\":[0,0.1,0],\"sparse\":{\"count\":1}}"));
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
                json -> json.replace("{\"byteLength\":428}", "{\"byteLength\":428,\"name\":{}}"));
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
    void r5BindsDeclaredBufferViewTargetsToObservedAccessorRoles() {
        byte[] candidate = candidateGlb("", 6, 1, false);
        assertDoesNotThrow(() -> validate(positiveDescriptor(), candidate));

        byte[] correctVertexTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36}",
                "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36,\"target\":34962}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), correctVertexTarget));

        byte[] wrongVertexTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36}",
                "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36,\"target\":34963}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", wrongVertexTarget);

        byte[] correctIndexTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6}",
                "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6,\"target\":34963}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), correctIndexTarget));

        byte[] wrongIndexTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6}",
                "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6,\"target\":34962}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", wrongIndexTarget);

        byte[] correctMorphTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":200,\"byteLength\":36}",
                "{\"buffer\":0,\"byteOffset\":200,\"byteLength\":36,\"target\":34962}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), correctMorphTarget));

        byte[] wrongMorphTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":200,\"byteLength\":36}",
                "{\"buffer\":0,\"byteOffset\":200,\"byteLength\":36,\"target\":34963}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", wrongMorphTarget);

        byte[] animationTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":236,\"byteLength\":8}",
                "{\"buffer\":0,\"byteOffset\":236,\"byteLength\":8,\"target\":34962}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", animationTarget);

        byte[] inverseBindMatrixTarget = rewriteGlbJson(candidate, json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":316,\"byteLength\":64}",
                "{\"buffer\":0,\"byteOffset\":316,\"byteLength\":64,\"target\":34963}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", inverseBindMatrixTarget);
    }

    @Test
    void r5RejectsBufferViewsSharedAcrossIncompatibleObservedRoles() {
        byte[] source = appendAccessor(candidateGlb("", 6, 1, false),
                "{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}");
        byte[] sharedVertexAndIndexView = rewriteGlbJson(source, json -> json
                .replace("{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6}",
                        "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":48}")
                .replace("{\"bufferView\":7,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}",
                        "{\"bufferView\":0,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}")
                .replace("{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":7,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}"));
        ExperimentalProfileValidationException exception = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), sharedVertexAndIndexView));
        assertEquals("BLENDLIB-X9-GLB-015", exception.diagnostic().code());
        assertEquals("/bufferViews/0", exception.diagnostic().location());
        assertTrue(exception.diagnostic().message().contains("must not mix"));
    }

    @Test
    void r5RejectsBufferViewsSharedByAnimationAndInverseBindMatrix() {
        byte[] source = appendAccessor(candidateGlb("", 6, 1, false),
                "{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}");
        byte[] sharedLayout = rewriteGlbJson(source, json -> json
                .replace("{\"buffer\":0,\"byteOffset\":236,\"byteLength\":8}",
                        "{\"buffer\":0,\"byteOffset\":236,\"byteLength\":48}")
                .replace("{\"bufferView\":9,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\",\"min\":[0],\"max\":[1]}",
                        "{\"bufferView\":11,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\",\"min\":[0],\"max\":[1]}")
                .replace("{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":9,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}"));
        byte[] sharedAnimationAndInverseBind = rewriteGlbBinary(sharedLayout, binary -> {
            ByteBuffer values = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
            putFloats(values, 316,
                    0.0f, 1.0f, 0.0f, 0.0f,
                    -1.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 1.0f);
            return binary;
        });
        ExperimentalProfileValidationException exception = assertThrows(ExperimentalProfileValidationException.class,
                () -> validate(positiveDescriptor(), sharedAnimationAndInverseBind));
        assertEquals("BLENDLIB-X9-GLB-015", exception.diagnostic().code());
        assertEquals("/bufferViews/11", exception.diagnostic().location());
        assertTrue(exception.diagnostic().message().contains("must not mix"));
    }

    @Test
    void r5EnforcesRoleAwareByteStrideRules() {
        byte[] legalVertexStride = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12,\"byteStride\":4}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), legalVertexStride));

        byte[] strideFive = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":14,\"byteStride\":5}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", strideFive);

        byte[] indexStride = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6}",
                "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":10,\"byteStride\":4}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", indexStride);

        byte[] animationInputStride = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":236,\"byteLength\":8}",
                "{\"buffer\":0,\"byteOffset\":236,\"byteLength\":8,\"byteStride\":4}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", animationInputStride);

        byte[] animationOutputStride = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":244,\"byteLength\":72}",
                "{\"buffer\":0,\"byteOffset\":244,\"byteLength\":72,\"byteStride\":4}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", animationOutputStride);

        byte[] inverseBindMatrixStride = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":316,\"byteLength\":64}",
                "{\"buffer\":0,\"byteOffset\":316,\"byteLength\":64,\"byteStride\":64}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", inverseBindMatrixStride);
    }

    @Test
    void r5EnforcesEffectiveStrideForTightlyPackedVertexAttributes() {
        byte[] u8UvWithTightStrideTwo = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":72,\"byteLength\":24}",
                        "{\"buffer\":0,\"byteOffset\":72,\"byteLength\":6}")
                .replace("{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"}",
                        "{\"bufferView\":2,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC2\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", u8UvWithTightStrideTwo);

        byte[] u8ColorWithTightStrideThree = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":9}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC3\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", u8ColorWithTightStrideThree);

        byte[] u16ColorWithTightStrideSix = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":18}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"componentType\":5123,\"normalized\":true,\"count\":3,\"type\":\"VEC3\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", u16ColorWithTightStrideSix);

        byte[] paddedU8Color = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":11,\"byteStride\":4}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC3\"}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), paddedU8Color));

        byte[] paddedU16Color = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":22,\"byteStride\":8}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"componentType\":5123,\"normalized\":true,\"count\":3,\"type\":\"VEC3\"}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), paddedU16Color));
    }

    @Test
    void r5AllowsConformantInterleavedVertexAttributes() {
        byte[] source = appendAccessor(candidateGlb("", 6, 1, false),
                "{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}");
        byte[] interleavedLayout = rewriteGlbJson(source, json -> json
                .replace("{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36}",
                        "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":72,\"byteStride\":24}")
                .replace("{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36}",
                        "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":48}")
                .replace("{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}",
                        "{\"bufferView\":0,\"byteOffset\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}")
                .replace("{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}"));
        byte[] interleaved = rewriteGlbBinary(interleavedLayout, binary -> {
            ByteBuffer values = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
            putFloats(values, 0, 0.0f, 0.0f, 0.0f);
            putFloats(values, 12, 0.0f, 0.0f, 1.0f);
            putFloats(values, 24, 1.0f, 0.0f, 0.0f);
            putFloats(values, 36, 0.0f, 0.0f, 1.0f);
            putFloats(values, 48, 0.0f, 1.0f, 0.0f);
            putFloats(values, 60, 0.0f, 0.0f, 1.0f);
            return binary;
        });
        assertDoesNotThrow(() -> validate(positiveDescriptor(), interleaved));
    }

    @Test
    void r5EnforcesVertexAccessorOffsetsWithoutOverApplyingItToIndices() {
        byte[] u8ViewOffsetWithoutAccessorOffset = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json.replace(
                "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                "{\"buffer\":0,\"byteOffset\":121,\"byteLength\":12}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), u8ViewOffsetWithoutAccessorOffset));

        byte[] u8ViewOffsetWithFourByteAccessorOffset = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":121,\"byteLength\":16}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"byteOffset\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), u8ViewOffsetWithFourByteAccessorOffset));

        byte[] unalignedU8AccessorOffset = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":13}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"byteOffset\":1,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", unalignedU8AccessorOffset);

        byte[] unalignedU16AccessorOffset = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":26}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"byteOffset\":2,\"componentType\":5123,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", unalignedU16AccessorOffset);

        byte[] alignedU16AccessorOffset = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":120,\"byteLength\":12}",
                        "{\"buffer\":0,\"byteOffset\":120,\"byteLength\":28}")
                .replace("{\"bufferView\":4,\"componentType\":5121,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}",
                        "{\"bufferView\":4,\"byteOffset\":4,\"componentType\":5123,\"normalized\":true,\"count\":3,\"type\":\"VEC4\"}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), alignedU16AccessorOffset));

        byte[] indexOffsetTwo = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6}",
                        "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":8}")
                .replace("{\"bufferView\":7,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}",
                        "{\"bufferView\":7,\"byteOffset\":2,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}"));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), indexOffsetTwo));
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
    void r2RejectsMorphPositionWithoutMinMax() {
        byte[] withoutBounds = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"bufferView\":8,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                                + "\"min\":[0,0.1,0],\"max\":[0,0.1,0]}",
                        "{\"bufferView\":8,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}"));
        assertGlbFailure("BLENDLIB-X9-GLB-002", withoutBounds);
    }

    @Test
    void r2RejectsMorphTangentWithoutBaseAttribute() {
        byte[] tangentWithoutBase = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"targets\":[{\"POSITION\":8}]",
                        "\"targets\":[{\"POSITION\":8,\"TANGENT\":8}]"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", tangentWithoutBase);
    }

    @Test
    void r2RejectsZeroCountAccessorEvenWhenUnused() {
        byte[] zeroCount = appendAccessor(candidateGlb("", 6, 1, false),
                "{\"bufferView\":8,\"componentType\":5126,\"count\":0,\"type\":\"VEC3\"}");
        assertGlbFailure(BlendDiagnosticCodes.GLB_015, zeroCount);
    }

    @Test
    void r2RejectsUnusedNormalizedUnsignedIntAccessor() {
        byte[] normalizedUnsignedInt = appendAccessor(candidateGlb("", 6, 1, false),
                "{\"bufferView\":7,\"componentType\":5125,\"normalized\":true,"
                        + "\"count\":1,\"type\":\"SCALAR\"}");
        assertGlbFailure(BlendDiagnosticCodes.GLB_015, normalizedUnsignedInt);
    }

    @Test
    void r2RejectsUnsignedIntAccessorOutsidePrimitiveIndices() {
        byte[] unusedUnsignedInt = appendAccessor(candidateGlb("", 6, 1, false),
                "{\"bufferView\":7,\"componentType\":5125,\"count\":1,\"type\":\"SCALAR\"}");
        assertGlbFailure("BLENDLIB-X9-GLB-015", unusedUnsignedInt);
    }

    @Test
    void r2RejectsNonUnitBaseNormal() {
        byte[] nonUnitNormal = rewriteGlbBinary(candidateGlb("", 6, 1, false), binary -> {
            ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putFloat(44, 2.0f);
            return binary;
        });
        assertGlbFailure("BLENDLIB-X9-GLB-015", nonUnitNormal);
    }

    @Test
    void r2ValidatesBaseTangentUnitLengthAndExactHandednessButNotMorphDeltaLength() {
        byte[] withBaseTangent = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2",
                        "\"POSITION\":0,\"NORMAL\":1,\"TANGENT\":12,\"TEXCOORD_0\":2")
                        .replace(
                                "\"targets\":[{\"POSITION\":8}]",
                                "\"targets\":[{\"POSITION\":8,\"NORMAL\":8,\"TANGENT\":8}]"));
        assertEquals(1, validate(positiveDescriptor(), withBaseTangent).morphTargetCount());

        byte[] withinTolerance = rewriteGlbBinary(withBaseTangent, binary -> {
            ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putFloat(380, 1.00004f);
            return binary;
        });
        assertEquals(1, validate(positiveDescriptor(), withinTolerance).morphTargetCount());

        byte[] nonUnitTangent = rewriteGlbBinary(withBaseTangent, binary -> {
            ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putFloat(380, 1.01f);
            return binary;
        });
        assertGlbFailure("BLENDLIB-X9-GLB-015", nonUnitTangent);

        byte[] invalidHandedness = rewriteGlbBinary(withBaseTangent, binary -> {
            ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putFloat(392, 0.0f);
            return binary;
        });
        assertGlbFailure("BLENDLIB-X9-GLB-015", invalidHandedness);
    }

    @Test
    void r2AcceptsUnsignedIntPrimitiveIndices() {
        byte[] unsignedIntIndices = appendAccessor(rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"bufferView\":7,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}",
                        "{\"bufferView\":12,\"componentType\":5125,\"count\":3,\"type\":\"SCALAR\"}")),
                "{\"bufferView\":7,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}");
        unsignedIntIndices = rewriteGlbBinary(unsignedIntIndices, binary -> {
            ByteBuffer values = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
            values.putInt(380, 0);
            values.putInt(384, 1);
            values.putInt(388, 2);
            return binary;
        });
        assertEquals(1, validate(positiveDescriptor(), unsignedIntIndices).primitiveCount());
    }

    @Test
    void r2RejectsExplicitEmptyNodeWeights() {
        byte[] emptyNodeWeights = rewriteGlbJson(candidateGlb("", 6, 1, false),
                json -> json.replace(
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0}",
                        "{\"name\":\"MorphMesh\",\"mesh\":0,\"skin\":0,\"weights\":[]}"));
        assertGlbFailure("BLENDLIB-X9-GLB-015", emptyNodeWeights);
    }

    @Test
    void r4PublicX9LimitTypesAcceptMinimumAndDefaultValuesAndRejectEveryHardExpansion() {
        ExperimentalProfileLimits defaults = ExperimentalProfileLimits.DEFAULT;
        ExperimentalGlbLimits glb = defaults.glbLimits();

        assertDoesNotThrow(() -> new ExperimentalGlbLimits(1, 1, 1, 1, 1, 1, 1));
        assertDoesNotThrow(() -> new ExperimentalProfileLimits(
                1, 1, 1, 1, 1, 1, 1, 1, 1.0, new ExperimentalGlbLimits(1, 1, 1, 1, 1, 1, 1)));
        assertDoesNotThrow(() -> validate(positiveDescriptor(), candidateGlb("", 6, 1, false)));

        assertThrows(NoSuchMethodException.class,
                () -> ExperimentalProfileLimits.class.getMethod("baseGlbLimits"));
        assertThrows(NoSuchMethodException.class,
                () -> ExperimentalGlbLimits.class.getMethod("maxRigidNodes"));
        assertThrows(NoSuchMethodException.class,
                () -> ExperimentalGlbLimits.class.getMethod("maxSockets"));

        assertThrows(IllegalArgumentException.class, () -> new ExperimentalProfileLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities() + 1,
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                glb));

        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes() + 1, defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                glb));
        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials() + 1, defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                glb));
        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive() + 1, defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                glb));
        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh() + 1, defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                glb));
        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets() + 1,
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                glb));
        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers() + 1, defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                glb));
        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations() + 1, defaults.maxClipDurationSeconds(),
                glb));
        assertThrows(IllegalArgumentException.class, () -> copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds() + 0.1,
                glb));

        assertThrows(IllegalArgumentException.class, () -> copyGlbLimits(
                glb.maxGlbBytes() + 1, glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()));
        assertThrows(IllegalArgumentException.class, () -> copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices() + 1, glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()));
        assertThrows(IllegalArgumentException.class, () -> copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices() + 1, glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()));
        assertThrows(IllegalArgumentException.class, () -> copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), glb.maxNodes() + 1, glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()));
        assertThrows(IllegalArgumentException.class, () -> copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints() + 1,
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()));
        assertThrows(IllegalArgumentException.class, () -> copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth() + 1, glb.maxKeyframeSamples()));
        assertThrows(IllegalArgumentException.class, () -> copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples() + 1));
    }

    @Test
    void r4EveryPublicX9LimitAxisActuallyRejectsAnInputAboveItsConfiguredValue() {
        ExperimentalProfileLimits defaults = ExperimentalProfileLimits.DEFAULT;
        ExperimentalGlbLimits glb = defaults.glbLimits();
        byte[] candidate = candidateGlb("", 6, 1, false);

        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                1, defaults.maxMaterials(), defaults.maxCapabilities(), defaults.maxMorphTargetsPerPrimitive(),
                defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(), defaults.maxAnimationSamplers(),
                defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glb)), positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), 1, defaults.maxCapabilities(), defaults.maxMorphTargetsPerPrimitive(),
                defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(), defaults.maxAnimationSamplers(),
                defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glb)), positiveDescriptor(),
                withAdditionalMaterial(candidate));
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), 1, defaults.maxMorphTargetsPerPrimitive(),
                defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(), defaults.maxAnimationSamplers(),
                defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glb)), positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(), 1,
                defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(), defaults.maxAnimationSamplers(),
                defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glb)), positiveDescriptor(),
                candidateGlb("", 6, 2, false));
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), 1, defaults.maxUvSets(), defaults.maxAnimationSamplers(),
                defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glb)), positiveDescriptor(),
                candidateGlb("", 6, new int[] {1, 1}, false));
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), 1,
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glb)),
                positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(), 1,
                defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glb)), positiveDescriptor(),
                withTwoAnimationSamplers(candidate));
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), 1, defaults.maxClipDurationSeconds(), glb)), positiveDescriptor(),
                withTwoAnimations(candidate));
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(copyLimits(
                defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), 1.0, glb)), positiveDescriptor(),
                withAnimationEndTime(candidate, 1.1f));

        assertConfiguredLimitRejects(new ExperimentalProfileValidator(withGlbLimits(copyGlbLimits(
                1, glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()))), positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(withGlbLimits(copyGlbLimits(
                glb.maxGlbBytes(), 1, glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()))), positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(withGlbLimits(copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), 1, glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()))), positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(withGlbLimits(copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), 1, glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()))), positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(withGlbLimits(copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), 1,
                glb.maxHierarchyDepth(), glb.maxKeyframeSamples()))), positiveDescriptor(),
                withTwoSkinJointEntries(candidate));
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(withGlbLimits(copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(), 1,
                glb.maxKeyframeSamples()))), positiveDescriptor(), candidate);
        assertConfiguredLimitRejects(new ExperimentalProfileValidator(withGlbLimits(copyGlbLimits(
                glb.maxGlbBytes(), glb.maxVertices(), glb.maxIndices(), glb.maxNodes(), glb.maxSkinJoints(),
                glb.maxHierarchyDepth(), 1))), positiveDescriptor(), candidate);
    }

    @Test
    void r2CapabilityCountAccepts32Rejects33AndHonorsASmallerCustomLimit() {
        String atHardMaximum = descriptorWithCapabilityCount(32);
        ExperimentalDescriptor hardMaximum = new ExperimentalDescriptorDecoder()
                .decode(MODEL_KEY, descriptor(atHardMaximum));
        assertEquals(32, hardMaximum.requiredCapabilities().size() + hardMaximum.optionalCapabilities().size());
        assertThrows(ExperimentalProfileValidationException.class,
                () -> new ExperimentalDescriptorDecoder().decode(MODEL_KEY, descriptor(descriptorWithCapabilityCount(33))));

        ExperimentalProfileLimits smaller = limitsWithMaxCapabilities(31);
        ExperimentalDescriptor customMaximum = new ExperimentalDescriptorDecoder(smaller)
                .decode(MODEL_KEY, descriptor(descriptorWithCapabilityCount(31)));
        assertEquals(31, customMaximum.requiredCapabilities().size() + customMaximum.optionalCapabilities().size());
        assertThrows(ExperimentalProfileValidationException.class,
                () -> new ExperimentalDescriptorDecoder(smaller).decode(MODEL_KEY, descriptor(atHardMaximum)));
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

    private static ExperimentalProfileValidationResult validate(
            ExperimentalProfileValidator target, String descriptor, byte[] glb) {
        return target.validate(MODEL_KEY, descriptor(descriptor), new AssetBytes(MESH_ID, glb));
    }

    private static ExperimentalGlbStructureValidator.Result structure(byte[] glb) {
        return structure(glb, ExperimentalProfileLimits.DEFAULT);
    }

    private static ExperimentalGlbStructureValidator.Result structure(
            byte[] glb, ExperimentalProfileLimits limits) {
        GlbDocument document = new GlbReader(limits.glbLimits().asInternalBlendAssetLimits()).read(
                MODEL_KEY, new AssetBytes(MESH_ID, glb));
        return new ExperimentalGlbStructureValidator(MODEL_KEY, MESH_ID, document, limits).validate();
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

    private static String withoutMultipleUvCapability(String descriptor) {
        String result = descriptor.replaceAll(
                "(?m)^\\s*\"blendlib:multiple-uv\"\\s*:\\s*\\{[^\\r\\n]*}\\s*,\\s*\\R", "");
        assertFalse(result.contains("blendlib:multiple-uv"), "multiple-uv capability line must be removable");
        return result;
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

    private static String descriptorWithCapabilityCount(int count) {
        String descriptor = positiveDescriptor();
        int existingCount = 6;
        if (count < existingCount) {
            throw new IllegalArgumentException("Capability count must preserve the six positive-fixture capabilities");
        }
        StringBuilder additions = new StringBuilder();
        for (int index = existingCount; index < count; index++) {
            additions.append(",\n    \"example:metadata/r2-cap-")
                    .append(index)
                    .append("\": { \"requirement\": \"optional\", \"min_version\": \"1.0.0\", ")
                    .append("\"max_version\": \"2.0.0\", \"fallback\": \"metadata_ignore\" }");
        }
        String anchor = "\n  }\n}";
        int insertion = descriptor.lastIndexOf(anchor);
        assertTrue(insertion >= 0, "positive descriptor capability object must be replaceable");
        return descriptor.substring(0, insertion) + additions + descriptor.substring(insertion);
    }

    private static ExperimentalProfileLimits limitsWithMaxCapabilities(int maxCapabilities) {
        ExperimentalProfileLimits defaults = ExperimentalProfileLimits.DEFAULT;
        return copyLimits(defaults.maxDescriptorBytes(), defaults.maxMaterials(), maxCapabilities,
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(),
                defaults.glbLimits());
    }

    private static ExperimentalProfileLimits copyLimits(
            int maxDescriptorBytes,
            int maxMaterials,
            int maxCapabilities,
            int maxMorphTargetsPerPrimitive,
            int maxMorphTargetsPerMesh,
            int maxUvSets,
            int maxAnimationSamplers,
            int maxAnimations,
            double maxClipDurationSeconds,
            ExperimentalGlbLimits glbLimits) {
        return new ExperimentalProfileLimits(maxDescriptorBytes, maxMaterials, maxCapabilities,
                maxMorphTargetsPerPrimitive, maxMorphTargetsPerMesh, maxUvSets, maxAnimationSamplers, maxAnimations,
                maxClipDurationSeconds, glbLimits);
    }

    private static ExperimentalProfileLimits withGlbLimits(ExperimentalGlbLimits glbLimits) {
        ExperimentalProfileLimits defaults = ExperimentalProfileLimits.DEFAULT;
        return copyLimits(defaults.maxDescriptorBytes(), defaults.maxMaterials(), defaults.maxCapabilities(),
                defaults.maxMorphTargetsPerPrimitive(), defaults.maxMorphTargetsPerMesh(), defaults.maxUvSets(),
                defaults.maxAnimationSamplers(), defaults.maxAnimations(), defaults.maxClipDurationSeconds(), glbLimits);
    }

    private static ExperimentalGlbLimits copyGlbLimits(
            int maxGlbBytes,
            int maxVertices,
            int maxIndices,
            int maxNodes,
            int maxSkinJoints,
            int maxHierarchyDepth,
            int maxKeyframeSamples) {
        return new ExperimentalGlbLimits(
                maxGlbBytes,
                maxVertices,
                maxIndices,
                maxNodes,
                maxSkinJoints,
                maxHierarchyDepth,
                maxKeyframeSamples);
    }

    private static void assertConfiguredLimitRejects(
            ExperimentalProfileValidator target, String descriptor, byte[] glb) {
        assertThrows(ExperimentalProfileValidationException.class, () -> validate(target, descriptor, glb));
    }

    private static byte[] withAdditionalMaterial(byte[] source) {
        return rewriteGlbJson(source, json -> json.replace(
                "\"materials\":[{\"name\":\"CandidateSurface\"}]",
                "\"materials\":[{\"name\":\"CandidateSurface\"},{\"name\":\"SecondSurface\"}]"));
    }

    private static byte[] withTwoSkinJointEntries(byte[] source) {
        return rewriteGlbJson(source, json -> json.replace("\"joints\":[1]", "\"joints\":[1,1]"));
    }

    private static byte[] withTwoAnimationSamplers(byte[] source) {
        String sampler = "{\"input\":9,\"output\":10,\"interpolation\":\"CUBICSPLINE\"}";
        return rewriteGlbJson(source, json -> json.replace(
                "\"samplers\":[" + sampler + "]", "\"samplers\":[" + sampler + ',' + sampler + ']'));
    }

    private static byte[] withTwoAnimations(byte[] source) {
        return rewriteGlbJson(source, json -> {
            String marker = "\"animations\":[";
            int arrayStart = json.indexOf(marker);
            if (arrayStart < 0 || !json.endsWith("]}")) {
                throw new IllegalArgumentException("Candidate GLB must contain one terminal animations array");
            }
            arrayStart += marker.length();
            String animation = json.substring(arrayStart, json.length() - 2);
            return json.substring(0, arrayStart) + animation + ',' + animation + "]}";
        });
    }

    private static byte[] withAnimationEndTime(byte[] source, float endTime) {
        byte[] withMatchingBounds = rewriteGlbJson(source, json -> json.replace(
                "\"min\":[0],\"max\":[1]",
                "\"min\":[0],\"max\":[" + Float.toString(endTime) + ']'));
        return rewriteGlbBinary(withMatchingBounds, binary -> {
            ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putFloat(240, endTime);
            return binary;
        });
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

    private static byte[] withoutSecondaryUv(byte[] source) {
        return rewriteGlbJson(source, json -> json.replace("\"TEXCOORD_1\":3,", ""));
    }

    private static byte[] scalarIndexCandidate(int componentType, long value) {
        int byteLength = switch (componentType) {
            case 5121 -> Byte.BYTES;
            case 5123 -> Short.BYTES;
            case 5125 -> Integer.BYTES;
            default -> throw new IllegalArgumentException("Unsupported test component type: " + componentType);
        };
        byte[] rewritten = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6}",
                        "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":" + byteLength + "}")
                .replace("{\"bufferView\":7,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}",
                        "{\"bufferView\":7,\"componentType\":" + componentType
                                + ",\"count\":1,\"type\":\"SCALAR\"}"));
        return rewriteGlbBinary(rewritten, binary -> {
            ByteBuffer values = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
            switch (componentType) {
                case 5121 -> values.put(192, (byte) value);
                case 5123 -> values.putShort(192, (short) value);
                case 5125 -> values.putInt(192, (int) value);
                default -> throw new AssertionError("validated test component type");
            }
            return binary;
        });
    }

    private static byte[] u8TriangleCandidate() {
        byte[] rewritten = rewriteGlbJson(candidateGlb("", 6, 1, false), json -> json
                .replace("{\"buffer\":0,\"byteOffset\":192,\"byteLength\":6}",
                        "{\"buffer\":0,\"byteOffset\":192,\"byteLength\":3}")
                .replace("{\"bufferView\":7,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}",
                        "{\"bufferView\":7,\"componentType\":5121,\"count\":3,\"type\":\"SCALAR\"}"));
        return rewriteGlbBinary(rewritten, binary -> {
            binary[192] = 0;
            binary[193] = 1;
            binary[194] = 2;
            return binary;
        });
    }

    private static byte[] candidateGlb(
            String rootMember, int cubicOutputCount, int[] primitiveTargetCounts, boolean nonFinitePosition) {
        ByteBuffer binary = ByteBuffer.allocate(428).order(ByteOrder.LITTLE_ENDIAN);
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
        for (int vertex = 0; vertex < 3; vertex++) {
            putFloats(binary, 380 + vertex * 16, 1.0f, 0.0f, 0.0f, 1.0f);
        }

        int meshTargetCount = primitiveTargetCounts[0];
        String primitives = primitiveArray(primitiveTargetCounts);
        String weights = zeroWeights(meshTargetCount);
        String extension = rootMember.isEmpty() ? "" : "," + rootMember;
        String json = """
                {"asset":{"version":"2.0"}%s,"buffers":[{"byteLength":428}],
                "bufferViews":[
                {"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},
                {"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":24},
                {"buffer":0,"byteOffset":120,"byteLength":12},{"buffer":0,"byteOffset":132,"byteLength":12},
                {"buffer":0,"byteOffset":144,"byteLength":48},{"buffer":0,"byteOffset":192,"byteLength":6},
                {"buffer":0,"byteOffset":200,"byteLength":36},{"buffer":0,"byteOffset":236,"byteLength":8},
                {"buffer":0,"byteOffset":244,"byteLength":72},{"buffer":0,"byteOffset":316,"byteLength":64},
                {"buffer":0,"byteOffset":380,"byteLength":48}],
                "accessors":[
                {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":3,"componentType":5126,"count":3,"type":"VEC2"},
                {"bufferView":4,"componentType":5121,"normalized":true,"count":3,"type":"VEC4"},
                {"bufferView":5,"componentType":5121,"count":3,"type":"VEC4"},
                {"bufferView":6,"componentType":5126,"count":3,"type":"VEC4"},
                {"bufferView":7,"componentType":5123,"count":3,"type":"SCALAR"},
                {"bufferView":8,"componentType":5126,"count":3,"type":"VEC3","min":[0,0.1,0],"max":[0,0.1,0]},
                {"bufferView":9,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
                {"bufferView":10,"componentType":5126,"count":%d,"type":"SCALAR"},
                {"bufferView":11,"componentType":5126,"count":1,"type":"MAT4"},
                {"bufferView":12,"componentType":5126,"count":3,"type":"VEC4"}],
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

    private static byte[] appendAccessor(byte[] source, String accessorJson) {
        return rewriteGlbJson(source, json -> json.replace(
                "{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"}]",
                "{\"bufferView\":12,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"},"
                        + accessorJson + "]"));
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
