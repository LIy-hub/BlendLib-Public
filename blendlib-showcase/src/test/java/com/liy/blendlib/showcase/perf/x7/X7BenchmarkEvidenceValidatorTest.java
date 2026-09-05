package com.liy.blendlib.showcase.perf.x7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class X7BenchmarkEvidenceValidatorTest {
    @Test
    void validCpuCaptureIsOnlyAStructuralExample(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);

        X7BenchmarkEvidenceValidator.ValidationResult result =
                X7BenchmarkEvidenceValidator.validate(fixture.evidence(), temporaryDirectory, fixture.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, result.status());
        assertTrue(result.reasons().getFirst().contains("not hardware evidence")
                || result.reasons().getFirst().contains("ordinary path traversal"));
    }

    @Test
    void incompleteHardwarePackageFailsBeforeUnknownGpuCanBecomeEligible(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.unverifiedHardwareCapture(temporaryDirectory);

        X7BenchmarkEvidenceValidator.ValidationResult result =
                X7BenchmarkEvidenceValidator.validate(fixture.evidence(), temporaryDirectory, fixture.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, result.status());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("missing required")));
    }

    @Test
    void wrongArtifactHashOrBytesFailsClosed(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7BenchmarkEvidence.Artifact original = fixture.evidence().artifactManifest().entries().getFirst();
        X7BenchmarkEvidence.Artifact wrong = new X7BenchmarkEvidence.Artifact(
                original.key(),
                original.path(),
                "0".repeat(64),
                original.byteCount() + 1L,
                original.kind());
        X7BenchmarkEvidence evidence = withManifest(
                fixture.evidence(),
                new X7BenchmarkEvidence.ArtifactManifest(
                        fixture.evidence().artifactManifest().manifestPath(),
                        List.of(wrong)));

        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verify(evidence, temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult result =
                X7BenchmarkEvidenceValidator.validate(evidence, temporaryDirectory, verification);

        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, result.status());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("byte count mismatch")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("SHA-256 mismatch")));
    }

    @Test
    void rejectsScenarioDriftWrongSampleCountAndUnorderedOrNonFinitePercentiles(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7BenchmarkEvidence base = fixture.evidence();
        X7BenchmarkEvidence.FrozenScenario scenario = base.scenario();
        X7BenchmarkEvidence drift = copy(
                base,
                new X7BenchmarkEvidence.FrozenScenario(
                        scenario.scenarioFormat(),
                        scenario.rigidModelKey(),
                        scenario.skinnedModelKey(),
                        99,
                        scenario.rigidTrianglesPerInstance(),
                        scenario.rigidVerticesPerInstance(),
                        scenario.skinnedInstances(),
                        scenario.skinnedTrianglesPerInstance(),
                        scenario.skinnedVerticesPerInstance(),
                        scenario.skinnedJointsPerInstance(),
                        scenario.totalTriangles()),
                new X7BenchmarkEvidence.Sampling(600, 1_799, 7L),
                new X7BenchmarkEvidence.Metrics(
                        new X7BenchmarkEvidence.Percentiles(Double.NaN, 60.0d, 62.0d),
                        new X7BenchmarkEvidence.Percentiles(20.0d, 18.0d, 22.0d),
                        base.metrics().cpuRenderMillis(),
                        base.metrics().allocationBytes()),
                base.artifactManifest(),
                base.requiredExtensions());

        X7BenchmarkEvidenceValidator.ValidationResult result =
                X7BenchmarkEvidenceValidator.validate(drift, temporaryDirectory, fixture.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, result.status());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("scenario")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("sample_frames")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("fps percentiles")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("frame_time_ms percentiles")));

        X7BenchmarkEvidence infinite = copy(
                base,
                base.scenario(),
                base.sampling(),
                new X7BenchmarkEvidence.Metrics(
                        base.metrics().fps(),
                        base.metrics().frameTimeMillis(),
                        new X7BenchmarkEvidence.Percentiles(1.0d, 2.0d, Double.POSITIVE_INFINITY),
                        base.metrics().allocationBytes()),
                base.artifactManifest(),
                base.requiredExtensions());
        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(infinite), "cpu_render_ms percentiles");
    }

    @Test
    void rejectsDuplicateArtifactKeysSelfListedManifestAndUnknownRequiredExtension(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7BenchmarkEvidence base = fixture.evidence();
        X7BenchmarkEvidence.Artifact original = base.artifactManifest().entries().getFirst();
        X7BenchmarkEvidence duplicateKey = withManifest(
                base,
                new X7BenchmarkEvidence.ArtifactManifest(
                        base.artifactManifest().manifestPath(),
                        List.of(original, original)));
        X7BenchmarkEvidence selfListed = withManifest(
                base,
                new X7BenchmarkEvidence.ArtifactManifest(
                        base.artifactManifest().manifestPath(),
                        List.of(new X7BenchmarkEvidence.Artifact(
                                original.key(),
                                base.artifactManifest().manifestPath(),
                                original.sha256(),
                                original.byteCount(),
                                original.kind()))));
        X7BenchmarkEvidence unknownExtension = copy(
                base,
                base.scenario(),
                base.sampling(),
                base.metrics(),
                base.artifactManifest(),
                List.of("future.required.extension"));

        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(duplicateKey), "artifact keys must be unique");
        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(selfListed),
                "artifact manifest must exclude itself");
        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(unknownExtension), "unknown required extension");
    }

    @Test
    void directConstructionSharesCanonicalPathPerFileAndAggregateLimits(@TempDir Path temporaryDirectory) throws Exception {
        X7BenchmarkEvidence base = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        X7BenchmarkEvidence negativeZero = copy(
                base,
                base.scenario(),
                base.sampling(),
                new X7BenchmarkEvidence.Metrics(
                        new X7BenchmarkEvidence.Percentiles(-0.0d, 60.0d, 60.0d),
                        base.metrics().frameTimeMillis(), base.metrics().cpuRenderMillis(), base.metrics().allocationBytes()),
                base.artifactManifest(),
                base.requiredExtensions());
        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(negativeZero), "fps percentiles");

        X7BenchmarkEvidence.Artifact original = base.artifactManifest().entries().getFirst();
        X7BenchmarkEvidence tooLongPath = withManifest(base, new X7BenchmarkEvidence.ArtifactManifest(
                base.artifactManifest().manifestPath(), List.of(new X7BenchmarkEvidence.Artifact(
                        original.key(), "a".repeat(X7ArtifactVerifier.MAX_RELATIVE_PATH_CHARS + 1), original.sha256(),
                        original.byteCount(), original.kind()))));
        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(tooLongPath), "canonical relative path");

        X7BenchmarkEvidence tooLargeFile = withManifest(base, new X7BenchmarkEvidence.ArtifactManifest(
                base.artifactManifest().manifestPath(), List.of(new X7BenchmarkEvidence.Artifact(
                        original.key(), original.path(), original.sha256(), X7ArtifactVerifier.MAX_JFR_BYTES + 1L,
                        original.kind()))));
        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(tooLargeFile), "per-file limit");

        List<X7BenchmarkEvidence.Artifact> overTotal = List.of(
                boundedJfr("one", "evidence/one.jfr"),
                boundedJfr("two", "evidence/two.jfr"),
                boundedJfr("three", "evidence/three.jfr"));
        X7BenchmarkEvidence total = withManifest(base, new X7BenchmarkEvidence.ArtifactManifest(
                base.artifactManifest().manifestPath(), overTotal));
        assertContains(X7BenchmarkEvidenceValidator.structuralErrors(total), "aggregate exceeds the total-byte limit");
    }

    @Test
    void sourceReplayUnitTestAndNoShaderSmokeNeverBecomeHardwareEvidence(@TempDir Path temporaryDirectory) throws Exception {
        X7BenchmarkEvidence.RuntimeEnvironment environment =
                X7SyntheticTestFixtures.environment(false, X7BenchmarkEvidence.UNKNOWN);
        X7BenchmarkEvidence.BackendSelection backend = new X7BenchmarkEvidence.BackendSelection(
                X7BenchmarkEvidence.BackendClass.CPU,
                "cpu-submit",
                "CPU_BASELINE",
                X7BenchmarkEvidence.FallbackState.NONE,
                false);
        for (X7BenchmarkEvidence.CaptureClass captureClass : List.of(
                X7BenchmarkEvidence.CaptureClass.SOURCE_REPLAY,
                X7BenchmarkEvidence.CaptureClass.UNIT_TEST,
                X7BenchmarkEvidence.CaptureClass.NO_SHADER_SMOKE)) {
            X7SyntheticTestFixtures.Fixture fixture =
                    X7SyntheticTestFixtures.capture(temporaryDirectory.resolve(captureClass.name()), captureClass, environment, backend);
            X7BenchmarkEvidenceValidator.ValidationResult result =
                    X7BenchmarkEvidenceValidator.validate(fixture.evidence(), temporaryDirectory.resolve(captureClass.name()),
                            fixture.artifactVerification());
            assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, result.status());
        }
    }

    private static void assertContains(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(error -> error.contains(fragment)), () -> "Missing " + fragment + " in " + errors);
    }

    private static X7BenchmarkEvidence withManifest(
            X7BenchmarkEvidence evidence,
            X7BenchmarkEvidence.ArtifactManifest manifest) {
        return copy(
                evidence,
                evidence.scenario(),
                evidence.sampling(),
                evidence.metrics(),
                manifest,
                evidence.requiredExtensions());
    }

    private static X7BenchmarkEvidence copy(
            X7BenchmarkEvidence evidence,
            X7BenchmarkEvidence.FrozenScenario scenario,
            X7BenchmarkEvidence.Sampling sampling,
            X7BenchmarkEvidence.Metrics metrics,
            X7BenchmarkEvidence.ArtifactManifest manifest,
            List<String> requiredExtensions) {
        return new X7BenchmarkEvidence(
                evidence.schemaVersion(),
                evidence.captureClass(),
                evidence.gateStatus(),
                evidence.captureId(),
                evidence.sourceRevision(),
                scenario,
                evidence.environment(),
                evidence.backend(),
                sampling,
                metrics,
                evidence.allocationEvidenceKey(),
                manifest,
                requiredExtensions,
                evidence.optionalExtensions());
    }

    private static X7BenchmarkEvidence.Artifact boundedJfr(String key, String path) {
        return new X7BenchmarkEvidence.Artifact(
                key, path, "0".repeat(64), X7ArtifactVerifier.MAX_JFR_BYTES,
                X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION);
    }
}
