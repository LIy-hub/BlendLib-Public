package com.liy.blendlib.showcase.perf.x7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class X7HardwareEligibilityTest {
    @Test
    void relabelledSyntheticCompleteShapeAndPlainTextJfrCanNeverBecomeHardwareEligible(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.relabeledSyntheticHardwareCapture(temporaryDirectory);
        assertTrue(fixture.artifactVerification().structurallyValid());

        X7BenchmarkEvidenceValidator.ValidationResult result = X7BenchmarkEvidenceValidator.validate(
                fixture.evidence(), temporaryDirectory, fixture.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, result.status());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("SYNTHETIC_TEST_ONLY")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("JFR FLR header")));
    }

    @Test
    void completeHashBoundRawP7FixtureRemainsWaitingWithoutSecureTraversalAndOwnerReceipt(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.wellFormedButUntrustedHardwareCapture(temporaryDirectory);
        assertEquals(8, fixture.evidence().artifactManifest().entries().size());
        assertEquals("artifact-manifest.json", fixture.evidence().artifactManifest().manifestPath());
        assertTrue(fixture.evidence().artifactManifest().entries().stream()
                .noneMatch(artifact -> artifact.path().equals(fixture.evidence().artifactManifest().manifestPath())));
        X7BenchmarkEvidenceValidator.ValidationResult result = X7BenchmarkEvidenceValidator.validate(
                fixture.evidence(), temporaryDirectory, fixture.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, result.status(),
                result.reasons()::toString);
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("secure relative traversal")
                || reason.contains("trusted capture owner/capability")));
    }

    @Test
    void envelopeMetricsMustBeRecomputedFromTheBoundedCanonicalRawP7Frames(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.wellFormedButUntrustedHardwareCapture(temporaryDirectory);
        Path rawPath = temporaryDirectory.resolve("raw-p7-samples.txt");
        String raw = Files.readString(rawPath, StandardCharsets.UTF_8)
                .replace("60,16,4,1024,100,25", "61,16,4,1024,100,25");
        Files.writeString(rawPath, raw, StandardCharsets.UTF_8);
        X7BenchmarkEvidence evidence = withUpdatedRawArtifact(fixture.evidence(), rawPath);
        evidence = withReboundOwnerReceipt(temporaryDirectory, fixture.evidence(), evidence);
        X7SyntheticTestFixtures.writeManifest(temporaryDirectory, evidence);
        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verify(evidence, temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult result = X7BenchmarkEvidenceValidator.validate(
                evidence, temporaryDirectory, verification);

        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, result.status());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("recompute the envelope")),
                result.reasons()::toString);
    }

    @Test
    void whitespacePaddedUnknownIsCanonicalizedToUnknownAndIsNeverEligible(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.relabeledSyntheticHardwareCapture(temporaryDirectory);
        X7BenchmarkEvidence.RuntimeEnvironment original = fixture.evidence().environment();
        X7BenchmarkEvidence changed = new X7BenchmarkEvidence(
                fixture.evidence().schemaVersion(), fixture.evidence().captureClass(), fixture.evidence().gateStatus(),
                fixture.evidence().captureId(), fixture.evidence().sourceRevision(), fixture.evidence().scenario(),
                new X7BenchmarkEvidence.RuntimeEnvironment(
                        original.javaVersion(), original.osName(), original.osVersion(), original.minecraftVersion(),
                        original.fabricLoaderVersion(), original.fabricApiVersion(), "  UNKNOWN  ", original.gpuVendor(),
                        original.gpuDriver(), original.shaderpack(), original.irisStatus(), original.sodiumStatus(), true),
                fixture.evidence().backend(), fixture.evidence().sampling(), fixture.evidence().metrics(),
                fixture.evidence().allocationEvidenceKey(), fixture.evidence().artifactManifest(),
                fixture.evidence().requiredExtensions(), fixture.evidence().optionalExtensions());

        assertEquals(X7BenchmarkEvidence.UNKNOWN, changed.environment().gpuName());
        assertFalse(changed.environment().hasVerifiedHardwareIdentity());
        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID,
                X7BenchmarkEvidenceValidator.validate(changed, temporaryDirectory, fixture.artifactVerification()).status());
    }

    @Test
    void everyHardwarePackageWithoutOneOfTheFrozenP7ArtifactRolesIsInvalid(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.unverifiedHardwareCapture(temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult result = X7BenchmarkEvidenceValidator.validate(
                fixture.evidence(), temporaryDirectory, fixture.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, result.status());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("P7_CAPTURE_REPORT")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("ENVIRONMENT_REPORT")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("CAPABILITY_REPORT")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("SCREENSHOT")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("OWNER_RECEIPT")));
    }

    @Test
    void ordinaryWindowsWaitingTokenCannotBePromotedThroughReflection(@TempDir Path temporaryDirectory)
            throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                "requires ordinary Windows path traversal");
        X7SyntheticTestFixtures.Fixture fixture =
                X7SyntheticTestFixtures.wellFormedButUntrustedHardwareCapture(temporaryDirectory);
        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.STRUCTURALLY_VALID_WAITING,
                fixture.artifactVerification().state());
        X7BenchmarkEvidenceValidator.ValidationResult issued = X7BenchmarkEvidenceValidator.validate(
                fixture.evidence(), temporaryDirectory, fixture.artifactVerification());
        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, issued.status());

        assertEquals(List.of("INVALID", "STRUCTURALLY_VALID_WAITING"),
                Arrays.stream(X7BenchmarkEvidenceValidator.Status.values()).map(Enum::name).toList());
        assertEquals(List.of("NOT_COMPARABLE", "WAITING_FOR_TRUSTED_OWNER"),
                Arrays.stream(X7BenchmarkComparator.Status.values()).map(Enum::name).toList());
        assertTrue(Arrays.stream(X7BenchmarkEvidenceValidator.class.getDeclaredClasses())
                .noneMatch(type -> type.getSimpleName().contains("Eligibility")));
        assertTrue(Arrays.stream(X7BenchmarkEvidenceValidator.ValidationResult.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("hardwareComparisonEligibility")
                        || method.getName().equals("isComparisonEligibleHardwareCapture")));
        assertTrue(Arrays.stream(X7BenchmarkComparator.ComparisonResult.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("deltas")));

        Constructor<X7BenchmarkEvidenceValidator.ValidationResult> validationConstructor =
                X7BenchmarkEvidenceValidator.ValidationResult.class.getDeclaredConstructor(
                        X7BenchmarkEvidenceValidator.Status.class, List.class, String.class, Path.class,
                        X7ArtifactVerifier.ArtifactVerification.class);
        validationConstructor.setAccessible(true);
        X7BenchmarkEvidenceValidator.ValidationResult reflected = validationConstructor.newInstance(
                X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING,
                List.of("reflection cannot provide a trusted owner"),
                X7BenchmarkEvidenceCodec.canonicalSha256(fixture.evidence()),
                temporaryDirectory.toAbsolutePath().normalize(),
                fixture.artifactVerification());
        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, reflected.status());
        assertTrue(reflected.attests(fixture.evidence(), temporaryDirectory));

        X7BenchmarkComparator.ComparisonResult comparison = X7BenchmarkComparator.compare(
                fixture.evidence(), temporaryDirectory, reflected,
                fixture.evidence(), temporaryDirectory, reflected);
        assertEquals(X7BenchmarkComparator.Status.WAITING_FOR_TRUSTED_OWNER, comparison.status());
        assertEquals(X7BenchmarkComparator.Conclusion.NO_AUTOMATIC_GPU_SPEED_CLAIM, comparison.conclusion());
        assertTrue(comparison.reasons().stream().anyMatch(reason -> reason.contains("trusted capture owner")));

        Constructor<X7BenchmarkComparator.ComparisonResult> comparisonConstructor =
                X7BenchmarkComparator.ComparisonResult.class.getDeclaredConstructor(
                        X7BenchmarkComparator.Status.class, List.class, X7BenchmarkComparator.Conclusion.class);
        comparisonConstructor.setAccessible(true);
        X7BenchmarkComparator.ComparisonResult reflectedComparison = comparisonConstructor.newInstance(
                X7BenchmarkComparator.Status.WAITING_FOR_TRUSTED_OWNER,
                List.of("reflection still has no hardware-comparable status"),
                X7BenchmarkComparator.Conclusion.NO_AUTOMATIC_GPU_SPEED_CLAIM);
        assertEquals(X7BenchmarkComparator.Status.WAITING_FOR_TRUSTED_OWNER, reflectedComparison.status());
    }

    @Test
    void hostileCapabilityAndReceiptGrammarsRejectUnknownAndDuplicateFieldsAfterHashesAreRebound(
            @TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.wellFormedButUntrustedHardwareCapture(temporaryDirectory);
        Path capabilityPath = temporaryDirectory.resolve("capability.txt");
        Files.writeString(capabilityPath, Files.readString(capabilityPath, StandardCharsets.UTF_8) + "unknown_counter=1\n",
                StandardCharsets.UTF_8);
        X7BenchmarkEvidence mutatedCapability = withUpdatedArtifact(
                fixture.evidence(), X7BenchmarkEvidence.ArtifactKind.CAPABILITY_REPORT, capabilityPath);
        mutatedCapability = withReboundOwnerReceipt(temporaryDirectory, fixture.evidence(), mutatedCapability);
        X7SyntheticTestFixtures.writeManifest(temporaryDirectory, mutatedCapability);
        X7ArtifactVerifier.ArtifactVerification capabilityVerification = X7ArtifactVerifier.verify(
                mutatedCapability, temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult capabilityResult = X7BenchmarkEvidenceValidator.validate(
                mutatedCapability, temporaryDirectory, capabilityVerification);
        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, capabilityResult.status());
        assertTrue(capabilityResult.reasons().stream().anyMatch(reason -> reason.contains("capability report")),
                capabilityResult.reasons()::toString);

        X7SyntheticTestFixtures.Fixture receiptFixture = X7SyntheticTestFixtures.wellFormedButUntrustedHardwareCapture(temporaryDirectory);
        Path receiptPath = temporaryDirectory.resolve("owner-receipt.json");
        Files.writeString(receiptPath, Files.readString(receiptPath, StandardCharsets.UTF_8)
                .replace("\"terminal\":\"SEALED_WAITING\"", "\"terminal\":\"SEALED_WAITING\",\"terminal\":\"SEALED_WAITING\""),
                StandardCharsets.UTF_8);
        X7BenchmarkEvidence mutatedReceipt = withUpdatedArtifact(
                receiptFixture.evidence(), X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT, receiptPath);
        X7SyntheticTestFixtures.writeManifest(temporaryDirectory, mutatedReceipt);
        X7ArtifactVerifier.ArtifactVerification receiptVerification = X7ArtifactVerifier.verify(mutatedReceipt, temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult receiptResult = X7BenchmarkEvidenceValidator.validate(
                mutatedReceipt, temporaryDirectory, receiptVerification);
        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, receiptResult.status());
        assertTrue(receiptResult.reasons().stream().anyMatch(reason -> reason.contains("owner receipt")),
                receiptResult.reasons()::toString);
    }

    @Test
    void capabilityReportRejectsEveryIndependentFrozenFallbackAndT5ActionMutation(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.wellFormedButUntrustedHardwareCapture(temporaryDirectory);
        Path capabilityPath = temporaryDirectory.resolve("capability.txt");
        Path receiptPath = temporaryDirectory.resolve("owner-receipt.json");
        String canonicalCapability = Files.readString(capabilityPath, StandardCharsets.UTF_8);
        String canonicalReceipt = Files.readString(receiptPath, StandardCharsets.UTF_8);
        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING,
                X7BenchmarkEvidenceValidator.validate(
                        fixture.evidence(), temporaryDirectory, fixture.artifactVerification()).status());

        assertCapabilityMutationInvalid(temporaryDirectory, fixture, capabilityPath, receiptPath,
                canonicalCapability, canonicalReceipt,
                report -> report.replace(
                        "completed_active_fallback_reason_start=NONE\ncompleted_active_fallback_reason_end=NONE\n",
                        "completed_active_fallback_reason_start=CAPABILITY_UNAVAILABLE\n"
                                + "completed_active_fallback_reason_end=CAPABILITY_UNAVAILABLE\n"),
                "fallback reason");
        assertCapabilityMutationInvalid(temporaryDirectory, fixture, capabilityPath, receiptPath,
                canonicalCapability, canonicalReceipt,
                report -> withCounterDelta(report, "t5_bone_culls", 1_801L), "t5_bone_culls");
        assertCapabilityMutationInvalid(temporaryDirectory, fixture, capabilityPath, receiptPath,
                canonicalCapability, canonicalReceipt,
                report -> withCounterDelta(report, "t5_submitted_primitive_count", 225_001L),
                "t5_submitted_primitive_count");
        assertCapabilityMutationInvalid(temporaryDirectory, fixture, capabilityPath, receiptPath,
                canonicalCapability, canonicalReceipt,
                report -> withCounterDelta(report, "t5_submitted_batch_count", 225_001L), "t5_submitted_batch_count");
        for (String counter : List.of(
                "t5_gpu_candidate_selections", "t5_cpu_selections", "t5_capability_unavailable_fallbacks",
                "t5_prepare_failure_fallbacks", "t5_upload_failure_fallbacks")) {
            assertCapabilityMutationInvalid(temporaryDirectory, fixture, capabilityPath, receiptPath,
                    canonicalCapability, canonicalReceipt,
                    report -> withCounterDelta(report, counter, 1L), counter);
        }
    }

    private static void assertCapabilityMutationInvalid(
            Path root,
            X7SyntheticTestFixtures.Fixture fixture,
            Path capabilityPath,
            Path receiptPath,
            String canonicalCapability,
            String canonicalReceipt,
            UnaryOperator<String> mutation,
            String expectedReason) throws Exception {
        Files.writeString(capabilityPath, mutation.apply(canonicalCapability), StandardCharsets.UTF_8);
        Files.writeString(receiptPath, canonicalReceipt, StandardCharsets.UTF_8);
        X7BenchmarkEvidence mutated = withUpdatedArtifact(
                fixture.evidence(), X7BenchmarkEvidence.ArtifactKind.CAPABILITY_REPORT, capabilityPath);
        mutated = withReboundOwnerReceipt(root, fixture.evidence(), mutated);
        X7SyntheticTestFixtures.writeManifest(root, mutated);
        X7BenchmarkEvidenceValidator.ValidationResult result = X7BenchmarkEvidenceValidator.validate(
                mutated, root, X7ArtifactVerifier.verify(mutated, root));

        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, result.status(), result.reasons()::toString);
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains(expectedReason)), result.reasons()::toString);
    }

    private static String withCounterDelta(String report, String counter, long delta) {
        String[] lines = report.split("\\n", -1);
        String startPrefix = counter + "_start=";
        String endPrefix = counter + "_end=";
        String deltaPrefix = counter + "_delta=";
        long start = -1L;
        for (String line : lines) {
            if (line.startsWith(startPrefix)) {
                start = Long.parseLong(line.substring(startPrefix.length()));
                break;
            }
        }
        if (start < 0L) {
            throw new IllegalArgumentException("missing fixture capability counter: " + counter);
        }
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].startsWith(endPrefix)) {
                lines[index] = endPrefix + Math.addExact(start, delta);
            } else if (lines[index].startsWith(deltaPrefix)) {
                lines[index] = deltaPrefix + delta;
            }
        }
        return String.join("\n", lines);
    }

    private static X7BenchmarkEvidence withUpdatedRawArtifact(X7BenchmarkEvidence evidence, Path rawPath) throws Exception {
        return withUpdatedArtifact(evidence, X7BenchmarkEvidence.ArtifactKind.P7_RAW_SAMPLES, rawPath);
    }

    private static X7BenchmarkEvidence withUpdatedArtifact(
            X7BenchmarkEvidence evidence, X7BenchmarkEvidence.ArtifactKind changedKind, Path changedPath) throws Exception {
        String hash = X7ArtifactVerifier.sha256(changedPath);
        long bytes = Files.size(changedPath);
        List<X7BenchmarkEvidence.Artifact> artifacts = evidence.artifactManifest().entries().stream()
                .map(artifact -> artifact.kind() == changedKind
                        ? new X7BenchmarkEvidence.Artifact(
                                artifact.key(), artifact.path(), hash, bytes, artifact.kind())
                        : artifact)
                .toList();
        return new X7BenchmarkEvidence(
                evidence.schemaVersion(), evidence.captureClass(), evidence.gateStatus(), evidence.captureId(),
                evidence.sourceRevision(), evidence.scenario(), evidence.environment(), evidence.backend(), evidence.sampling(),
                evidence.metrics(), evidence.allocationEvidenceKey(),
                new X7BenchmarkEvidence.ArtifactManifest(evidence.artifactManifest().manifestPath(), artifacts),
                evidence.requiredExtensions(), evidence.optionalExtensions());
    }

    /** Keeps the fixture structurally coherent so this test isolates raw/report recomputation. */
    private static X7BenchmarkEvidence withReboundOwnerReceipt(
            Path root,
            X7BenchmarkEvidence beforePayloadMutation,
            X7BenchmarkEvidence afterPayloadMutation) throws Exception {
        X7BenchmarkEvidence.Artifact receipt = artifact(afterPayloadMutation,
                X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT);
        Path receiptPath = root.resolve(receipt.path());
        String receiptText = Files.readString(receiptPath, StandardCharsets.UTF_8);
        for (X7BenchmarkEvidence.Artifact original : beforePayloadMutation.artifactManifest().entries()) {
            if (original.kind() == X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT) {
                continue;
            }
            X7BenchmarkEvidence.Artifact updated = artifact(afterPayloadMutation, original.kind());
            String originalBinding = receiptBinding(original);
            String updatedBinding = receiptBinding(updated);
            if (!receiptText.contains(originalBinding)) {
                throw new IllegalStateException("fixture owner receipt did not bind the original " + original.kind());
            }
            receiptText = receiptText.replace(originalBinding, updatedBinding);
        }
        Files.writeString(receiptPath, receiptText, StandardCharsets.UTF_8);
        String receiptHash = X7ArtifactVerifier.sha256(receiptPath);
        long receiptBytes = Files.size(receiptPath);
        List<X7BenchmarkEvidence.Artifact> artifacts = afterPayloadMutation.artifactManifest().entries().stream()
                .map(artifact -> artifact.kind() == X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT
                        ? new X7BenchmarkEvidence.Artifact(
                                artifact.key(), artifact.path(), receiptHash, receiptBytes, artifact.kind())
                        : artifact)
                .toList();
        return new X7BenchmarkEvidence(
                afterPayloadMutation.schemaVersion(), afterPayloadMutation.captureClass(), afterPayloadMutation.gateStatus(),
                afterPayloadMutation.captureId(), afterPayloadMutation.sourceRevision(), afterPayloadMutation.scenario(),
                afterPayloadMutation.environment(), afterPayloadMutation.backend(), afterPayloadMutation.sampling(),
                afterPayloadMutation.metrics(), afterPayloadMutation.allocationEvidenceKey(),
                new X7BenchmarkEvidence.ArtifactManifest(
                        afterPayloadMutation.artifactManifest().manifestPath(), artifacts),
                afterPayloadMutation.requiredExtensions(), afterPayloadMutation.optionalExtensions());
    }

    private static X7BenchmarkEvidence.Artifact artifact(
            X7BenchmarkEvidence evidence, X7BenchmarkEvidence.ArtifactKind kind) {
        return evidence.artifactManifest().entries().stream()
                .filter(artifact -> artifact.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing fixture artifact: " + kind));
    }

    private static String receiptBinding(X7BenchmarkEvidence.Artifact artifact) {
        return "\"key\":\"" + artifact.key() + "\",\"path\":\"" + artifact.path()
                + "\",\"kind\":\"" + artifact.kind() + "\",\"sha256\":\"" + artifact.sha256()
                + "\",\"byte_count\":" + artifact.byteCount();
    }
}
