package com.liy.blendlib.core.tooling;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.json.StrictJsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetValidatorServiceTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("blendlib_khronos_fixture:khronos/animated");
    private static final String RESOURCE_ROOT = "src/main/resources/assets";
    private static final String REPORT = "build/blendlib-authoring/blendlib_khronos_fixture/khronos/animated.asset-report.json";
    private static final String SIDECAR = "build/blendlib-authoring/blendlib_khronos_fixture/khronos/animated.blendlib-authoring.json";

    @TempDir
    Path temporary;

    @Test
    void validatesStrictLoaderRuntimeArtifactsAndAuthoringDocuments() throws Exception {
        Fixture fixture = createFixture();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertTrue(result.valid(), result.toText());
        assertEquals(1L, result.counts().get("textures"));
        assertTrue(result.toCanonicalJson().contains("blendlib-x5-cli-validation-v1"));
        assertFalse(result.toCanonicalJson().contains(temporary.toAbsolutePath().toString()));
    }

    @Test
    void rejectsReportHostPathAndMismatchedHashesWithoutLeakingProjectRoot() throws Exception {
        Fixture fixture = createFixture();
        String report = Files.readString(fixture.reportPath(), StandardCharsets.UTF_8)
                .replace("\"diagnostics\":[]", "\"diagnostics\":[{\"code\":\"BLENDLIB-X5-TEST-001\",\"location\":\"/host/path\",\"message\":\"bad\",\"remediation\":\"repair\",\"severity\":\"INFO\"}]")
                .replaceFirst("[0-9a-f]{64}", "0".repeat(64));
        Files.writeString(fixture.reportPath(), report, StandardCharsets.UTF_8);

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().equals("BLENDLIB-X5-CLI-003")));
        assertFalse(result.toCanonicalJson().contains(temporary.toAbsolutePath().toString()));
    }

    @Test
    void rejectsRuntimeExtensionFieldsInAuthoringSidecar() throws Exception {
        Fixture fixture = createFixture();
        String sidecar = Files.readString(fixture.sidecarPath(), StandardCharsets.UTF_8)
                .replace("\"mapping\":{\"action_animation_clips\"", "\"mapping\":{\"extensions\":{},\"action_animation_clips\"");
        Files.writeString(fixture.sidecarPath(), sidecar, StandardCharsets.UTF_8);
        fixture.rewriteReport();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().contains("extensions")));
    }

    @Test
    void rejectsStructuredMetadataAndIncompleteRuntimeBoundary() throws Exception {
        Fixture fixture = createFixture();
        String sidecar = Files.readString(fixture.sidecarPath(), StandardCharsets.UTF_8)
                .replace("\"object.rigidroot.small\":1e-7", "\"object.rigidroot.nested\":{}")
                .replace(",\"visual_events_are_presentation_only\":true", "");
        Files.writeString(fixture.sidecarPath(), sidecar, StandardCharsets.UTF_8);
        fixture.rewriteReport();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().contains("authoring_metadata")), result.toText());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("sidecar/runtime_boundary")), result.toText());
    }

    @Test
    void rejectsUnnormalizedSecretMetadataWithoutEchoingSecretInput() throws Exception {
        Fixture fixture = createFixture();
        String sidecar = Files.readString(fixture.sidecarPath(), StandardCharsets.UTF_8)
                .replace("\"object.rigidroot.small\":1e-7", "\"not-a-normalized-object-key\":\"password=TOPSECRET\"");
        Files.writeString(fixture.sidecarPath(), sidecar, StandardCharsets.UTF_8);
        fixture.rewriteReport();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());
        String rendered = result.toCanonicalJson();

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().startsWith("sidecar/authoring_metadata/entry:")),
                result.toText());
        assertFalse(rendered.contains("TOPSECRET"));
        assertFalse(rendered.contains("not-a-normalized-object-key"));
        assertFalse(rendered.contains("password="));
    }

    @Test
    void rejectsMoreThan64MetadataEntriesForOneObject() throws Exception {
        Fixture fixture = createFixture();
        StringBuilder metadata = new StringBuilder();
        for (int index = 0; index < 65; index++) {
            if (!metadata.isEmpty()) {
                metadata.append(',');
            }
            metadata.append("\"object.rigidroot.key").append(index).append("\":true");
        }
        String sidecar = Files.readString(fixture.sidecarPath(), StandardCharsets.UTF_8)
                .replace("\"object.rigidroot.small\":1e-7", metadata);
        Files.writeString(fixture.sidecarPath(), sidecar, StandardCharsets.UTF_8);
        fixture.rewriteReport();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.message().contains("64 entries")), result.toText());
    }

    @Test
    void rejectsSidecarClipMappingThatDoesNotMatchRuntimeGlb() throws Exception {
        Fixture fixture = createFixture();
        String sidecar = Files.readString(fixture.sidecarPath(), StandardCharsets.UTF_8)
                .replace("[{\"clip\":\"animation_AnimatedCube\",\"frame_end\":12,\"frame_start\":1,\"source_action\":\"animation_AnimatedCube\"}]", "[]");
        Files.writeString(fixture.sidecarPath(), sidecar, StandardCharsets.UTF_8);
        fixture.rewriteReport();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("sidecar/mapping/action_animation_clips")),
                result.toText());
    }

    @Test
    void rejectsReportCountAndPerformanceWarningMismatch() throws Exception {
        Fixture fixture = createFixture();
        String report = Files.readString(fixture.reportPath(), StandardCharsets.UTF_8)
                .replace("\"animation_clips\":1", "\"animation_clips\":0")
                .replace("\"performance_warnings\":[]", "\"performance_warnings\":[{\"code\":\"BLENDLIB-X5-TEST-001\",\"location\":\"mesh\",\"message\":\"warning\",\"remediation\":\"repair\",\"severity\":\"WARN\"}]");
        Files.writeString(fixture.reportPath(), report, StandardCharsets.UTF_8);

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("report/counts/animation_clips")), result.toText());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("report/performance_warnings")), result.toText());
    }

    @Test
    void rejectsSynchronizedButReverseSortedDiagnosticsAndWarnings() throws Exception {
        Fixture fixture = createFixture();
        String first = diagnostic("BLENDLIB-X5-TEST-900", "WARN");
        String second = diagnostic("BLENDLIB-X5-TEST-100", "WARN");
        String reversed = "[" + first + "," + second + "]";
        String report = Files.readString(fixture.reportPath(), StandardCharsets.UTF_8)
                .replace("\"diagnostics\":[]", "\"diagnostics\":" + reversed)
                .replace("\"performance_warnings\":[]", "\"performance_warnings\":" + reversed);
        Files.writeString(fixture.reportPath(), report, StandardCharsets.UTF_8);

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("report/diagnostics")
                && item.message().contains("canonical")), result.toText());
    }

    @Test
    void rejectsDuplicateDiagnosticsAndNonWarnPerformanceEntries() throws Exception {
        Fixture fixture = createFixture();
        String item = diagnostic("BLENDLIB-X5-TEST-100", "INFO");
        String report = Files.readString(fixture.reportPath(), StandardCharsets.UTF_8)
                .replace("\"diagnostics\":[]", "\"diagnostics\":[" + item + "," + item + "]")
                .replace("\"performance_warnings\":[]", "\"performance_warnings\":[" + item + "," + item + "]");
        Files.writeString(fixture.reportPath(), report, StandardCharsets.UTF_8);

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.message().contains("must not be duplicated")), result.toText());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.message().contains("must have WARN severity")), result.toText());
    }

    @Test
    void rejectsSparseOversizeAuthoringRuntimeAndTextureFilesBeforeAllocation() throws Exception {
        Fixture reportFixture = createFixture();
        setSparseLength(reportFixture.reportPath(), 512L * 1024L + 1L);
        AssetValidationResult reportResult = new AssetValidatorService().validate(reportFixture.request());
        assertFalse(reportResult.valid());
        assertTrue(reportResult.diagnostics().stream().anyMatch(item -> item.location().equals("report")), reportResult.toText());

        Path meshProject = temporary.resolve("mesh-project");
        Fixture meshFixture = createFixture(meshProject);
        setSparseLength(meshFixture.meshPath(), 64L * 1024L * 1024L + 1L);
        AssetValidationResult meshResult = new AssetValidatorService().validate(meshFixture.request());
        assertFalse(meshResult.valid());

        Path textureProject = temporary.resolve("texture-project");
        Fixture textureFixture = createFixture(textureProject);
        setSparseLength(textureFixture.texturePath(), 64L * 1024L * 1024L + 1L);
        AssetValidationResult textureResult = new AssetValidatorService().validate(textureFixture.request());
        assertFalse(textureResult.valid());
        assertTrue(textureResult.diagnostics().stream().anyMatch(item -> item.location().equals("texture")), textureResult.toText());
    }

    @Test
    void authoringSidecarCapMatchesPythonExact512KiBContract() throws Exception {
        Fixture exactFixture = createFixture(temporary.resolve("exact-sidecar-project"));
        String exactSidecar = sidecarAtAuthoringLimit();
        Files.writeString(exactFixture.sidecarPath(), exactSidecar, StandardCharsets.UTF_8);
        assertEquals(512L * 1024L, Files.size(exactFixture.sidecarPath()));
        exactFixture.rewriteReport();
        AssetValidationResult exactResult = new AssetValidatorService().validate(exactFixture.request());
        assertTrue(exactResult.valid(), exactResult.toText());

        Fixture overFixture = createFixture(temporary.resolve("over-sidecar-project"));
        Files.writeString(overFixture.sidecarPath(), exactSidecar + " ", StandardCharsets.UTF_8);
        assertEquals(512L * 1024L + 1L, Files.size(overFixture.sidecarPath()));
        AssetValidationResult overResult = new AssetValidatorService().validate(overFixture.request());
        assertFalse(overResult.valid(), overResult.toText());
        assertTrue(overResult.diagnostics().stream().anyMatch(item -> item.location().equals("sidecar")), overResult.toText());
    }

    @Test
    void rejectsAggregateMappingAbove4096EvenWhenEveryArrayIsIndividuallyBounded() throws Exception {
        Fixture fixture = createFixture(temporary.resolve("aggregate-mapping-project"));
        StringBuilder groups = new StringBuilder();
        for (int index = 0; index < 4_096; index++) {
            if (!groups.isEmpty()) {
                groups.append(',');
            }
            groups.append("{\"source_collection\":\"group_").append(index)
                    .append("\",\"variant_key\":\"group_").append(index).append("\"}");
        }
        String sidecar = Files.readString(fixture.sidecarPath(), StandardCharsets.UTF_8)
                .replace("\"collection_groups_variants\":[]", "\"collection_groups_variants\":[" + groups + "]");
        Files.writeString(fixture.sidecarPath(), sidecar, StandardCharsets.UTF_8);
        assertTrue(Files.size(fixture.sidecarPath()) <= 512L * 1024L);
        fixture.rewriteReport();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid(), result.toText());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("sidecar/mapping")
                && item.message().contains("4096")), result.toText());
    }

    @Test
    void boundedStreamUsesFixedBufferAndRejectsGrowthAndShrink() throws Exception {
        byte[] payload = new byte[20_000];
        TrackingInputStream exact = new TrackingInputStream(payload);
        assertArrayEquals(payload, AssetValidatorService.readBounded(
                exact, payload.length, 32_000, "BLENDLIB-X5-CLI-003", "test_input"));
        assertTrue(exact.maximumRequest <= 8 * 1024, "maximum request was " + exact.maximumRequest);

        TrackingInputStream growth = new TrackingInputStream(new byte[] {1, 2, 3, 4});
        RuntimeException failure = assertThrows(RuntimeException.class, () -> AssetValidatorService.readBounded(
                growth, 3, 8, "BLENDLIB-X5-CLI-003", "test_input"));

        assertTrue(failure.getMessage().contains("changed while being read"));
        assertTrue(growth.maximumRequest <= 8 * 1024);

        TrackingInputStream shrink = new TrackingInputStream(new byte[] {1, 2, 3});
        RuntimeException shrinkFailure = assertThrows(RuntimeException.class, () -> AssetValidatorService.readBounded(
                shrink, 4, 8, "BLENDLIB-X5-CLI-003", "test_input"));
        assertTrue(shrinkFailure.getMessage().contains("changed while being read"));
        assertTrue(shrink.maximumRequest <= 8 * 1024);
    }

    @Test
    void rejectsAuthoringSidecarInsideDefaultRuntimeTreeAndCliReturnsNonzero() throws Exception {
        Fixture fixture = createFixture();
        String runtimeSidecar = RESOURCE_ROOT + "/authoring/animated.blendlib-authoring.json";
        Path runtimeSidecarPath = fixture.projectRoot().resolve(runtimeSidecar);
        Files.createDirectories(runtimeSidecarPath.getParent());
        Files.copy(fixture.sidecarPath(), runtimeSidecarPath);
        String report = Files.readString(fixture.reportPath(), StandardCharsets.UTF_8)
                .replace('"' + SIDECAR + '"', '"' + runtimeSidecar + '"');
        Files.writeString(fixture.reportPath(), report, StandardCharsets.UTF_8);
        AssetValidationRequest request = new AssetValidationRequest(
                fixture.projectRoot(), RESOURCE_ROOT, MODEL_KEY, REPORT, runtimeSidecar);

        AssetValidationResult result = new AssetValidatorService().validate(request);
        assertFalse(result.valid(), result.toText());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().equals("BLENDLIB-X5-CLI-001")
                && item.location().equals("sidecar")), result.toText());

        int exit = AssetValidatorCli.run(new String[] {
                "--project-root", fixture.projectRoot().toString(),
                "--model-key", MODEL_KEY.value(),
                "--sidecar", runtimeSidecar,
                "--format", "json"
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(2, exit);
    }

    @Test
    void rejectsRuntimeTreeSidecarThroughResolvedSymlinkAlias() throws Exception {
        Fixture fixture = createFixture();
        Path runtimeSidecar = fixture.projectRoot().resolve(RESOURCE_ROOT).resolve("authoring/aliased.json");
        Files.createDirectories(runtimeSidecar.getParent());
        Files.copy(fixture.sidecarPath(), runtimeSidecar);
        Path alias = fixture.projectRoot().resolve("authoring-alias");
        try {
            Files.createSymbolicLink(alias, fixture.projectRoot().resolve("src/main/resources"));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Process junction = new ProcessBuilder(
                        "cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(),
                        fixture.projectRoot().resolve("src/main/resources").toString())
                        .redirectErrorStream(true)
                        .start();
                junction.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                Assumptions.assumeTrue(junction.waitFor() == 0, "symbolic links and junctions unavailable");
            } else {
                Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
            }
        }
        String aliasRelative = "authoring-alias/assets/authoring/aliased.json";
        String report = Files.readString(fixture.reportPath(), StandardCharsets.UTF_8)
                .replace('"' + SIDECAR + '"', '"' + aliasRelative + '"');
        Files.writeString(fixture.reportPath(), report, StandardCharsets.UTF_8);

        AssetValidationResult result = new AssetValidatorService().validate(new AssetValidationRequest(
                fixture.projectRoot(), RESOURCE_ROOT, MODEL_KEY, REPORT, aliasRelative));

        assertFalse(result.valid(), result.toText());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("sidecar")), result.toText());
    }

    @Test
    void explicitRuntimeRootsAreUnionedWithEveryDefaultRuntimeRoot() throws Exception {
        Fixture fixture = createFixture();
        AssetValidationRequest normal = new AssetValidationRequest(
                fixture.projectRoot(), RESOURCE_ROOT, MODEL_KEY, REPORT, SIDECAR, List.of("custom/runtime"));
        assertEquals(List.of(RESOURCE_ROOT, "src/main/resources", "build/resources/main", "custom/runtime"), normal.runtimeRoots());
        assertTrue(new AssetValidatorService().validate(normal).valid());

        assertThrows(IllegalArgumentException.class, () -> new AssetValidationRequest(
                fixture.projectRoot(), RESOURCE_ROOT, MODEL_KEY, REPORT, SIDECAR,
                java.util.stream.IntStream.range(0, 16).mapToObj(index -> "custom/runtime-" + index).toList()));

        String runtimeSidecar = "src/main/resources/authoring/explicit-roots-sidecar.json";
        Path runtimeSidecarPath = fixture.projectRoot().resolve(runtimeSidecar);
        Files.createDirectories(runtimeSidecarPath.getParent());
        Files.copy(fixture.sidecarPath(), runtimeSidecarPath);
        AssetValidationRequest escapedDefault = new AssetValidationRequest(
                fixture.projectRoot(), RESOURCE_ROOT, MODEL_KEY, REPORT, runtimeSidecar, List.of("custom/runtime"));

        AssetValidationResult result = new AssetValidatorService().validate(escapedDefault);

        assertFalse(result.valid(), result.toText());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.location().equals("sidecar")), result.toText());
    }

    @Test
    void preservesStrictCoreDiagnosticForBrokenGlb() throws Exception {
        Fixture fixture = createFixture();
        Files.write(fixture.meshPath(), new byte[] {0, 1, 2, 3});
        fixture.rewriteReport();

        AssetValidationResult result = new AssetValidatorService().validate(fixture.request());

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().startsWith("BLENDLIB-GLB-")), result.toText());
    }

    @Test
    void cliUsesDeterministicJsonAndUsageExitCodes() throws Exception {
        Fixture fixture = createFixture();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int validExit = AssetValidatorCli.run(new String[] {
                "--project-root", fixture.projectRoot().toString(),
                "--model-key", MODEL_KEY.value(),
                "--format", "json"
        }, new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(0, validExit);
        assertEquals("", error.toString(StandardCharsets.UTF_8));
        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("{\"counts\":"));
        assertEquals(64, AssetValidatorCli.run(new String[] {"--model-key", MODEL_KEY.value()}, System.out, System.err));
    }

    @Test
    void cliUsageFailureDoesNotEchoSecretInvalidPath() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String secretPath = "C:\\private\\SESSION_SECRET\u0000project";

        int exit = AssetValidatorCli.run(new String[] {
                "--project-root", secretPath,
                "--model-key", MODEL_KEY.value()
        }, new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(error, true, StandardCharsets.UTF_8));

        assertEquals(64, exit);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertTrue(error.toString(StandardCharsets.UTF_8).startsWith("BLENDLIB-X5-CLI-USAGE: invalid command-line arguments."));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("SESSION_SECRET"));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(secretPath));
    }

    @Test
    void canonicalJsonUsesPlainFiniteDecimalNumbers() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("negative_zero", -0.0d);
        values.put("small", 1e-7d);
        values.put("large", 1e20d);

        assertEquals("{\"large\":100000000000000000000,\"negative_zero\":0,\"small\":0.0000001}", ToolingJson.canonical(values));
        assertEquals("{\"large\":100000000000000000000,\"negative_zero\":0,\"small\":0.0000001}",
                ToolingJson.canonical(StrictJsonParser.parse("{\"negative_zero\":-0.0,\"small\":1e-7,\"large\":1e20}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> ToolingJson.canonical(Map.of("value", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> ToolingJson.canonical(Map.of("value", Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> ToolingJson.canonical(Map.of("value", Float.NEGATIVE_INFINITY)));
    }

    private Fixture createFixture() throws Exception {
        return createFixture(temporary.resolve("project"));
    }

    private Fixture createFixture(Path project) throws Exception {
        Path repository = Path.of(System.getProperty("blendlib.projectDir")).getParent();
        Path descriptor = project.resolve(RESOURCE_ROOT).resolve("blendlib_khronos_fixture/blend_models/khronos/animated.json");
        Path mesh = project.resolve(RESOURCE_ROOT).resolve("blendlib_khronos_fixture/models3d/khronos/animated-cube-derived.glb");
        Path texture = project.resolve(RESOURCE_ROOT).resolve("blendlib_khronos_fixture/textures/khronos/animated-cube-derived.png");
        Files.createDirectories(descriptor.getParent());
        Files.createDirectories(mesh.getParent());
        Files.createDirectories(texture.getParent());
        Files.copy(repository.resolve("blendlib-core/src/test/resources/p3/fixtures/khronos/animated-cube-derived.json"), descriptor);
        Files.copy(repository.resolve("blendlib-core/src/test/resources/p3/fixtures/khronos/animated-cube-derived.glb"), mesh);
        Files.copy(repository.resolve("test-assets/third_party/khronos/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/derived/textures/khronos/animated-cube-derived.png"), texture);
        Path sidecar = project.resolve(SIDECAR);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, sidecarText(), StandardCharsets.UTF_8);
        Path report = project.resolve(REPORT);
        Files.createDirectories(report.getParent());
        Fixture fixture = new Fixture(project, descriptor, mesh, texture, sidecar, report);
        fixture.rewriteReport();
        return fixture;
    }

    private static String sidecarText() {
        return """
                {"authoring_metadata":{"object.rigidroot.enabled":true,"object.rigidroot.label":"blue","object.rigidroot.small":1e-7},"format":"blendlib-x5-authoring-sidecar-v1","mapping":{"action_animation_clips":[{"clip":"animation_AnimatedCube","frame_end":12,"frame_start":1,"source_action":"animation_AnimatedCube"}],"collection_groups_variants":[],"collision_references":[],"empty_sockets":[],"lod_levels":[],"material_definitions":[{"authoring_material":"AnimatedCubeDerived","descriptor_mode":"opaque","source_material":"AnimatedCubeDerived"}],"timeline_visual_events":[]},"model":{"model_id":"khronos/animated","namespace":"blendlib_khronos_fixture","profile":"blendlib:rigid_v1"},"runtime_boundary":{"collision_references_are_authoring_only":true,"descriptor_extensions_are_not_used":true,"runtime_reads_blend_fbx_obj":false,"visual_events_are_presentation_only":true},"schema_version":"1.0.0"}
                """;
    }

    private static String diagnostic(String code, String severity) {
        return "{\"code\":\"" + code + "\",\"location\":\"mesh\",\"message\":\"message\",\"remediation\":\"repair\",\"severity\":\""
                + severity + "\"}";
    }

    private static String sidecarAtAuthoringLimit() {
        String template = sidecarText().strip();
        String marker = "\"authoring_metadata\":{";
        int contentStart = template.indexOf(marker) + marker.length();
        int contentEnd = template.indexOf("},\"format\"", contentStart);
        if (contentStart < marker.length() || contentEnd < contentStart) {
            throw new IllegalStateException("sidecar metadata boundary not found");
        }
        String prefix = template.substring(0, contentStart);
        String suffix = template.substring(contentEnd);
        int[] lengths = new int[64 * 64];
        String emptyMetadata = metadataText(lengths);
        int remaining = 512 * 1024 - (prefix + emptyMetadata + suffix).getBytes(StandardCharsets.UTF_8).length;
        for (int index = 0; index < lengths.length && remaining > 0; index++) {
            lengths[index] = Math.min(256, remaining);
            remaining -= lengths[index];
        }
        if (remaining != 0) {
            throw new IllegalStateException("metadata scalar capacity cannot reach the 512 KiB boundary");
        }
        String result = prefix + metadataText(lengths) + suffix;
        if (result.getBytes(StandardCharsets.UTF_8).length != 512 * 1024) {
            throw new IllegalStateException("sidecar boundary generator is not exact");
        }
        return result;
    }

    private static String metadataText(int[] lengths) {
        StringBuilder metadata = new StringBuilder();
        int index = 0;
        for (int object = 0; object < 64; object++) {
            for (int key = 0; key < 64; key++) {
                if (!metadata.isEmpty()) {
                    metadata.append(',');
                }
                metadata.append("\"object.object_").append(String.format("%02d", object))
                        .append(".key_").append(String.format("%02d", key)).append("\":\"")
                        .append("x".repeat(lengths[index++])).append('"');
            }
        }
        return metadata.toString();
    }

    private static void setSparseLength(Path path, long length) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(length);
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private int maximumRequest;

        private TrackingInputStream(byte[] payload) {
            super(payload);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            maximumRequest = Math.max(maximumRequest, length);
            return super.read(buffer, offset, length);
        }
    }

    private record Fixture(Path projectRoot, Path descriptorPath, Path meshPath, Path texturePath, Path sidecarPath, Path reportPath) {
        AssetValidationRequest request() {
            return new AssetValidationRequest(projectRoot, RESOURCE_ROOT, MODEL_KEY, REPORT, SIDECAR);
        }

        void rewriteReport() throws Exception {
            Map<String, String> artifacts = new LinkedHashMap<>();
            artifacts.put(RESOURCE_ROOT + "/blendlib_khronos_fixture/blend_models/khronos/animated.json", hash(descriptorPath));
            artifacts.put(RESOURCE_ROOT + "/blendlib_khronos_fixture/models3d/khronos/animated-cube-derived.glb", hash(meshPath));
            artifacts.put(RESOURCE_ROOT + "/blendlib_khronos_fixture/textures/khronos/animated-cube-derived.png", hash(texturePath));
            artifacts.put(SIDECAR, hash(sidecarPath));
            StringBuilder entries = new StringBuilder();
            for (Map.Entry<String, String> entry : artifacts.entrySet()) {
                if (!entries.isEmpty()) {
                    entries.append(',');
                }
                entries.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
            }
            String sidecarHash = hash(ToolingJson.canonical(StrictJsonParser.parse(Files.readAllBytes(sidecarPath))).getBytes(StandardCharsets.UTF_8));
            Files.writeString(reportPath, "{\"artifacts\":{" + entries + "},\"counts\":{\"animation_clips\":1,\"bones\":0,\"collision_references\":0,\"events\":0,\"lod_levels\":0,\"material_slots\":1,\"triangles\":12,\"vertex_weight_records\":0,\"vertices\":36},\"diagnostics\":[],\"format\":\"blendlib-x5-asset-report-v1\",\"model\":{\"model_id\":\"khronos/animated\",\"namespace\":\"blendlib_khronos_fixture\",\"profile\":\"blendlib:rigid_v1\"},\"performance_warnings\":[],\"schema_version\":\"1.0.0\",\"sidecar_sha256\":\"" + sidecarHash + "\"}", StandardCharsets.UTF_8);
        }

        private static String hash(Path path) throws Exception {
            return hash(Files.readAllBytes(path));
        }

        private static String hash(byte[] bytes) throws Exception {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        }
    }
}
