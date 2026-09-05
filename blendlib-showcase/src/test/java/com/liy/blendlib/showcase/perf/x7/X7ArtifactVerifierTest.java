package com.liy.blendlib.showcase.perf.x7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class X7ArtifactVerifierTest {
    @Test
    void exactInventoryBindsTheCanonicalManifestAndRejectsUnlistedFiles(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        assertTrue(fixture.artifactVerification().structurallyValid());

        Files.writeString(temporaryDirectory.resolve("unlisted.txt"), "not declared", StandardCharsets.UTF_8);
        X7ArtifactVerifier.ArtifactVerification verification =
                X7ArtifactVerifier.verify(fixture.evidence(), temporaryDirectory);

        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
        assertTrue(verification.failures().stream().anyMatch(reason -> reason.contains("unlisted artifact file")));
    }

    @Test
    void symlinkEscapeFailsClosedWhenTheFilesystemPermitsPortableSymlinks(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        Path allocation = temporaryDirectory.resolve("evidence/allocation.jfr");
        Path outside = temporaryDirectory.resolveSibling("x7-outside-" + System.nanoTime() + ".jfr");
        try {
            Files.write(outside, Files.readAllBytes(allocation));
            Files.delete(allocation);
            try {
                Files.createSymbolicLink(allocation, outside);
            } catch (UnsupportedOperationException | IOException | SecurityException exception) {
                Assumptions.assumeTrue(false, "host does not permit test symlink creation: " + exception.getClass().getSimpleName());
            }

            X7ArtifactVerifier.ArtifactVerification verification =
                    X7ArtifactVerifier.verify(fixture.evidence(), temporaryDirectory);

            assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
            assertTrue(verification.failures().stream().anyMatch(reason -> reason.contains("symbolic link")
                    || reason.contains("outside the real artifact root")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void controlledWindowsReparseSeamFailsClosedWithoutHostJunctionPrivileges(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verifyWithForcedReparseForTest(
                fixture.evidence(), temporaryDirectory, temporaryDirectory.resolve("evidence/allocation.jfr"));

        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
        assertTrue(verification.failures().stream().anyMatch(reason -> reason.contains("reparse point")));
        assertFalse(verification.verified());
    }

    @Test
    void controlledAncestorSwapSeamFailsClosedBeforeItCanBecomeTrusted(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verifyWithForcedPathRaceForTest(
                fixture.evidence(), temporaryDirectory, temporaryDirectory.resolve("evidence"));

        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
        assertTrue(verification.failures().stream().anyMatch(reason -> reason.contains("ancestor replacement race")));
        assertFalse(verification.hasTrustedTraversal());
    }

    @Test
    void injectedInsecureProviderIsStructurallyUsefulButCanNeverClaimTrustedTraversal(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verifyWithForcedInsecureProviderForTest(
                fixture.evidence(), temporaryDirectory);

        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.STRUCTURALLY_VALID_WAITING, verification.state());
        assertTrue(verification.structurallyValid());
        assertFalse(verification.hasTrustedTraversal());
    }

    @Test
    void actualHardLinkBetweenArtifactsFailsClosedWhenTheFilesystemSupportsLinks(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        Path allocation = temporaryDirectory.resolve("evidence/allocation.jfr");
        Path alias = temporaryDirectory.resolve("evidence/capture.log");
        try {
            Files.createLink(alias, allocation);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "host does not permit hard-link creation: " + exception.getClass().getSimpleName());
        }

        X7BenchmarkEvidence evidence = withArtifacts(fixture.evidence(), List.of(
                fixture.evidence().artifactManifest().entries().getFirst(),
                new X7BenchmarkEvidence.Artifact("capture-log", "evidence/capture.log", X7ArtifactVerifier.sha256(alias),
                        Files.size(alias), X7BenchmarkEvidence.ArtifactKind.CAPTURE_LOG)));
        X7SyntheticTestFixtures.writeManifest(temporaryDirectory, evidence);

        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verify(evidence, temporaryDirectory);
        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
        assertTrue(verification.failures().stream().anyMatch(reason -> reason.contains("duplicate filesystem entity")));
    }

    @Test
    void hardLinkToTheSelfExcludedManifestIsStillAnIdentityDuplicate(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        Path manifest = temporaryDirectory.resolve("evidence/artifact-manifest.json");
        Path alias = temporaryDirectory.resolve("evidence/capture.log");
        X7BenchmarkEvidence evidence = withArtifacts(fixture.evidence(), List.of(
                fixture.evidence().artifactManifest().entries().getFirst(),
                new X7BenchmarkEvidence.Artifact("capture-log", "evidence/capture.log", "0".repeat(64), 1L,
                        X7BenchmarkEvidence.ArtifactKind.CAPTURE_LOG)));
        X7SyntheticTestFixtures.writeManifest(temporaryDirectory, evidence);
        try {
            Files.createLink(alias, manifest);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "host does not permit hard-link creation: " + exception.getClass().getSimpleName());
        }

        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verify(evidence, temporaryDirectory);
        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
        assertTrue(verification.failures().stream().anyMatch(reason -> reason.contains("duplicate filesystem entity")));
    }

    @Test
    void deterministicSameFileSeamCoversHostsWithoutHardLinkSupport(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7ArtifactVerifier.ArtifactVerification verification = X7ArtifactVerifier.verifyWithForcedSameFileForTest(
                fixture.evidence(), temporaryDirectory, temporaryDirectory.resolve("evidence/allocation.jfr"),
                temporaryDirectory.resolve("evidence/artifact-manifest.json"));

        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, verification.state());
        assertTrue(verification.failures().stream().anyMatch(reason -> reason.contains("duplicate filesystem entity")));
    }

    @Test
    void inventoryDepthAndEntryBudgetsFailClosedBeforeUnboundedWalking(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        Path deep = temporaryDirectory;
        for (int index = 0; index <= X7ArtifactVerifier.MAX_DIRECTORY_DEPTH; index++) {
            deep = deep.resolve("d" + index);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("too-deep.txt"), "x", StandardCharsets.UTF_8);
        X7ArtifactVerifier.ArtifactVerification tooDeep = X7ArtifactVerifier.verify(fixture.evidence(), temporaryDirectory);
        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, tooDeep.state());
        assertTrue(tooDeep.failures().stream().anyMatch(reason -> reason.contains("maximum directory depth")));

        Path entryRoot = temporaryDirectory.resolve("entry-limit");
        X7SyntheticTestFixtures.Fixture entryFixture = X7SyntheticTestFixtures.cpuCapture(entryRoot);
        for (int index = 0; index <= X7ArtifactVerifier.MAX_INVENTORY_ENTRIES; index++) {
            Files.writeString(entryRoot.resolve("unlisted-" + index + ".txt"), "x", StandardCharsets.UTF_8);
        }
        X7ArtifactVerifier.ArtifactVerification tooMany = X7ArtifactVerifier.verify(entryFixture.evidence(), entryRoot);
        assertEquals(X7ArtifactVerifier.ArtifactVerification.State.FAILED, tooMany.state());
        assertTrue(tooMany.failures().stream().anyMatch(reason -> reason.contains("entry-count limit")));
    }

    @Test
    void successTokensHaveNoPublicOrPackageConstructionAndCannotReplayAcrossRoots(@TempDir Path temporaryDirectory)
            throws Exception {
        assertTrue(Arrays.stream(X7ArtifactVerifier.ArtifactVerification.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(X7BenchmarkEvidenceValidator.ValidationResult.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));

        Path firstRoot = temporaryDirectory.resolve("first");
        Path secondRoot = temporaryDirectory.resolve("second");
        X7SyntheticTestFixtures.Fixture first = X7SyntheticTestFixtures.cpuCapture(firstRoot);
        X7SyntheticTestFixtures.Fixture second = X7SyntheticTestFixtures.cpuCapture(secondRoot);
        X7BenchmarkEvidenceValidator.ValidationResult firstResult = X7BenchmarkEvidenceValidator.validate(
                first.evidence(), firstRoot, first.artifactVerification());
        X7BenchmarkEvidenceValidator.ValidationResult replay = X7BenchmarkEvidenceValidator.validate(
                second.evidence(), secondRoot, first.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, firstResult.status());
        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, replay.status());
        assertTrue(replay.reasons().stream().anyMatch(reason -> reason.contains("does not bind")));
    }

    @Test
    void manifestSubstitutionInvalidatesAnAlreadyIssuedValidationToken(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult issued = X7BenchmarkEvidenceValidator.validate(
                fixture.evidence(), temporaryDirectory, fixture.artifactVerification());
        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, issued.status());

        Files.writeString(temporaryDirectory.resolve("evidence/artifact-manifest.json"), "{}", StandardCharsets.UTF_8);
        X7BenchmarkEvidenceValidator.ValidationResult afterSubstitution = X7BenchmarkEvidenceValidator.validate(
                fixture.evidence(), temporaryDirectory, fixture.artifactVerification());

        assertEquals(X7BenchmarkEvidenceValidator.Status.INVALID, afterSubstitution.status());
        assertTrue(afterSubstitution.reasons().stream().anyMatch(reason -> reason.contains("does not bind")));
    }

    private static X7BenchmarkEvidence withArtifacts(
            X7BenchmarkEvidence evidence, List<X7BenchmarkEvidence.Artifact> artifacts) {
        return new X7BenchmarkEvidence(
                evidence.schemaVersion(), evidence.captureClass(), evidence.gateStatus(), evidence.captureId(),
                evidence.sourceRevision(), evidence.scenario(), evidence.environment(), evidence.backend(), evidence.sampling(),
                evidence.metrics(), evidence.allocationEvidenceKey(),
                new X7BenchmarkEvidence.ArtifactManifest(evidence.artifactManifest().manifestPath(), artifacts),
                evidence.requiredExtensions(), evidence.optionalExtensions());
    }
}
