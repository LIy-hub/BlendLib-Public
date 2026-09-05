package com.liy.blendlib.showcase.perf.x7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class X7BenchmarkComparatorTest {
    @Test
    void refusesSyntheticAndIncompatibleEnvironmentComparison(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture left = X7SyntheticTestFixtures.syntheticCapture(temporaryDirectory.resolve("left"));
        X7SyntheticTestFixtures.Fixture right = X7SyntheticTestFixtures.syntheticCapture(temporaryDirectory.resolve("right"));
        X7BenchmarkEvidence incompatible = withFabricApi(right.evidence(), "0.999.0+26.1.2");
        X7BenchmarkEvidenceValidator.ValidationResult leftValidation =
                X7BenchmarkEvidenceValidator.validate(left.evidence(), temporaryDirectory.resolve("left"), left.artifactVerification());
        X7BenchmarkEvidenceValidator.ValidationResult rightValidation =
                X7BenchmarkEvidenceValidator.validate(incompatible, temporaryDirectory.resolve("right"),
                        right.artifactVerification());

        X7BenchmarkComparator.ComparisonResult result =
                X7BenchmarkComparator.compare(left.evidence(), temporaryDirectory.resolve("left"), leftValidation,
                        incompatible, temporaryDirectory.resolve("right"), rightValidation);

        assertEquals(X7BenchmarkComparator.Status.NOT_COMPARABLE, result.status());
        assertEquals(X7BenchmarkComparator.Conclusion.NO_AUTOMATIC_GPU_SPEED_CLAIM, result.conclusion());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("not bound")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("Fabric API version differs")));
    }

    @Test
    void refusesCpuCaptureEvenWhenItsStructureAndArtifactsAreValid(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult validation =
                X7BenchmarkEvidenceValidator.validate(fixture.evidence(), temporaryDirectory, fixture.artifactVerification());

        X7BenchmarkComparator.ComparisonResult result =
                X7BenchmarkComparator.compare(fixture.evidence(), temporaryDirectory, validation,
                        fixture.evidence(), temporaryDirectory, validation);

        assertEquals(X7BenchmarkComparator.Status.WAITING_FOR_TRUSTED_OWNER, result.status());
        assertEquals(X7BenchmarkComparator.Conclusion.NO_AUTOMATIC_GPU_SPEED_CLAIM, result.conclusion());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("trusted capture owner")));
    }

    @Test
    void gpuNameVendorAndDriverEachIndependentlyMakeComparisonIncompatible(@TempDir Path temporaryDirectory)
            throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory);
        X7BenchmarkEvidenceValidator.ValidationResult validation = X7BenchmarkEvidenceValidator.validate(
                fixture.evidence(), temporaryDirectory, fixture.artifactVerification());
        X7BenchmarkEvidence.RuntimeEnvironment environment = fixture.evidence().environment();
        for (String field : java.util.List.of("GPU name", "GPU vendor", "GPU driver")) {
            X7BenchmarkEvidence changed = withGpuField(fixture.evidence(), field, environment);
            X7BenchmarkComparator.ComparisonResult result = X7BenchmarkComparator.compare(
                    fixture.evidence(), temporaryDirectory, validation,
                    changed, temporaryDirectory, validation);
            assertEquals(X7BenchmarkComparator.Status.NOT_COMPARABLE, result.status());
            assertTrue(result.reasons().stream().anyMatch(reason -> reason.equals(field + " differs")), result::toString);
            assertEquals(X7BenchmarkComparator.Conclusion.NO_AUTOMATIC_GPU_SPEED_CLAIM, result.conclusion());
        }
    }

    @Test
    void publicComparisonShapeHasNoComparableStateOrNumericOutput() {
        assertEquals(java.util.List.of("NOT_COMPARABLE", "WAITING_FOR_TRUSTED_OWNER"),
                Arrays.stream(X7BenchmarkComparator.Status.values()).map(Enum::name).toList());
        assertTrue(Arrays.stream(X7BenchmarkComparator.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("metricDeltas")));
        assertTrue(Arrays.stream(X7BenchmarkComparator.ComparisonResult.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("deltas")));
    }

    @Test
    void comparisonDiagnosticHasNoPublicOrPackageFactoryConstructor() {
        assertEquals(0, X7BenchmarkComparator.ComparisonResult.class.getConstructors().length);
        assertTrue(Arrays.stream(X7BenchmarkComparator.ComparisonResult.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    private static X7BenchmarkEvidence withFabricApi(X7BenchmarkEvidence evidence, String fabricApiVersion) {
        X7BenchmarkEvidence.RuntimeEnvironment environment = evidence.environment();
        return new X7BenchmarkEvidence(
                evidence.schemaVersion(),
                evidence.captureClass(),
                evidence.gateStatus(),
                evidence.captureId(),
                evidence.sourceRevision(),
                evidence.scenario(),
                new X7BenchmarkEvidence.RuntimeEnvironment(
                        environment.javaVersion(),
                        environment.osName(),
                        environment.osVersion(),
                        environment.minecraftVersion(),
                        environment.fabricLoaderVersion(),
                        fabricApiVersion,
                        environment.gpuName(),
                        environment.gpuVendor(),
                        environment.gpuDriver(),
                        environment.shaderpack(),
                        environment.irisStatus(),
                        environment.sodiumStatus(),
                        environment.environmentVerified()),
                evidence.backend(),
                evidence.sampling(),
                evidence.metrics(),
                evidence.allocationEvidenceKey(),
                evidence.artifactManifest(),
                evidence.requiredExtensions(),
                evidence.optionalExtensions());
    }

    private static X7BenchmarkEvidence withGpuField(
            X7BenchmarkEvidence evidence,
            String field,
            X7BenchmarkEvidence.RuntimeEnvironment environment) {
        String gpuName = field.equals("GPU name") ? "other-gpu" : environment.gpuName();
        String gpuVendor = field.equals("GPU vendor") ? "other-vendor" : environment.gpuVendor();
        String gpuDriver = field.equals("GPU driver") ? "other-driver" : environment.gpuDriver();
        return new X7BenchmarkEvidence(
                evidence.schemaVersion(), evidence.captureClass(), evidence.gateStatus(), evidence.captureId(),
                evidence.sourceRevision(), evidence.scenario(), new X7BenchmarkEvidence.RuntimeEnvironment(
                        environment.javaVersion(), environment.osName(), environment.osVersion(), environment.minecraftVersion(),
                        environment.fabricLoaderVersion(), environment.fabricApiVersion(), gpuName, gpuVendor, gpuDriver,
                        environment.shaderpack(), environment.irisStatus(), environment.sodiumStatus(),
                        environment.environmentVerified()),
                evidence.backend(), evidence.sampling(), evidence.metrics(), evidence.allocationEvidenceKey(),
                evidence.artifactManifest(), evidence.requiredExtensions(), evidence.optionalExtensions());
    }
}
