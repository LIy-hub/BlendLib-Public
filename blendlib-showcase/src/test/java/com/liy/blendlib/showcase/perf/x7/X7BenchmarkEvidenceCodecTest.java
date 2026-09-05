package com.liy.blendlib.showcase.perf.x7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class X7BenchmarkEvidenceCodecTest {
    @Test
    void canonicalJsonAndHashAreLocaleAndTimezoneIndependent(@TempDir Path temporaryDirectory) throws Exception {
        X7BenchmarkEvidence evidence = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        String expectedJson = X7BenchmarkEvidenceCodec.canonicalJson(evidence);
        String expectedHash = X7BenchmarkEvidenceCodec.canonicalSha256(evidence);
        Locale previousLocale = Locale.getDefault();
        TimeZone previousZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            assertEquals(expectedJson, X7BenchmarkEvidenceCodec.canonicalJson(evidence));
            assertEquals(expectedHash, X7BenchmarkEvidenceCodec.canonicalSha256(evidence));
        } finally {
            Locale.setDefault(previousLocale);
            TimeZone.setDefault(previousZone);
        }
        assertEquals(evidence, X7BenchmarkEvidenceCodec.parse(expectedJson));
        assertFalse(expectedJson.contains("NaN"));
        assertFalse(expectedJson.contains("Infinity"));
    }

    @Test
    void parserRejectsDuplicateKeysUnknownFieldsAndNonFiniteNumbers(@TempDir Path temporaryDirectory) throws Exception {
        X7BenchmarkEvidence evidence = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        String canonical = X7BenchmarkEvidenceCodec.canonicalJson(evidence);
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse("{\"schema_version\":1,\"schema_version\":1}"));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replace(
                        "\"optional_extensions\":[]",
                        "\"optional_extensions\":[],\"unknown\":true")));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replace("\"p50\":60", "\"p50\":1e999")));
    }

    @Test
    void decoderFailsClosedOnSemanticDriftCanonicalOrderingAndMalformedUnicode(@TempDir Path temporaryDirectory)
            throws Exception {
        X7BenchmarkEvidence base = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        String canonical = X7BenchmarkEvidenceCodec.canonicalJson(base);

        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replace("\"schema_version\":1", "\"schema_version\":2")));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replace("\"required_extensions\":[]",
                        "\"required_extensions\":[\"future.required.extension\"]")));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replace("\"p50\":60", "\"p50\":61")));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replace("\"capture_class\":\"CPU_CAPTURE\",",
                        "\"capture_id\":\"synthetic-test-only-capture\",\"capture_class\":\"CPU_CAPTURE\",")));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replaceFirst("UNKNOWN", "\\\\uD800")));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(canonical.replaceFirst("UNKNOWN", "\\\\uDC00")));

        X7BenchmarkEvidence emoji = copyWithEnvironment(base, new X7BenchmarkEvidence.RuntimeEnvironment(
                base.environment().javaVersion(), base.environment().osName(), base.environment().osVersion(),
                base.environment().minecraftVersion(), base.environment().fabricLoaderVersion(),
                base.environment().fabricApiVersion(), "GPU 😀", "NVIDIA", "555.55", "NONE", "NOT_PRESENT",
                "NOT_PRESENT", false));
        String emojiCanonical = X7BenchmarkEvidenceCodec.canonicalJson(emoji);
        assertEquals(emojiCanonical, X7BenchmarkEvidenceCodec.canonicalJson(X7BenchmarkEvidenceCodec.parse(emojiCanonical)));
    }

    @Test
    void parserLimitsPassAtSchemaBoundariesAndFailDeterministicallyOneOver(@TempDir Path temporaryDirectory)
            throws Exception {
        X7BenchmarkEvidence base = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        X7BenchmarkEvidence atStringLimit = copyWithEnvironment(base, new X7BenchmarkEvidence.RuntimeEnvironment(
                base.environment().javaVersion(), "x".repeat(X7BenchmarkEvidenceCodec.MAX_STRING_CHARS),
                base.environment().osVersion(), base.environment().minecraftVersion(),
                base.environment().fabricLoaderVersion(), base.environment().fabricApiVersion(),
                base.environment().gpuName(), base.environment().gpuVendor(), base.environment().gpuDriver(),
                base.environment().shaderpack(), base.environment().irisStatus(), base.environment().sodiumStatus(), false));
        assertEquals(atStringLimit, X7BenchmarkEvidenceCodec.parse(X7BenchmarkEvidenceCodec.canonicalJson(atStringLimit)));
        String tooLong = X7BenchmarkEvidenceCodec.canonicalJson(atStringLimit).replace(
                "\"os_name\":\"" + "x".repeat(X7BenchmarkEvidenceCodec.MAX_STRING_CHARS) + "\"",
                "\"os_name\":\"" + "x".repeat(X7BenchmarkEvidenceCodec.MAX_STRING_CHARS + 1) + "\"");
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(tooLong));

        List<String> extensions = new ArrayList<>();
        for (int index = 0; index < X7BenchmarkEvidenceCodec.MAX_ARRAY_ENTRIES; index++) {
            extensions.add(String.format(java.util.Locale.ROOT, "ext%04d", index));
        }
        X7BenchmarkEvidence atArrayLimit = new X7BenchmarkEvidence(
                base.schemaVersion(), base.captureClass(), base.gateStatus(), base.captureId(), base.sourceRevision(),
                base.scenario(), base.environment(), base.backend(), base.sampling(), base.metrics(), base.allocationEvidenceKey(),
                base.artifactManifest(), List.of(), extensions);
        assertEquals(atArrayLimit, X7BenchmarkEvidenceCodec.parse(X7BenchmarkEvidenceCodec.canonicalJson(atArrayLimit)));
        String tooMany = X7BenchmarkEvidenceCodec.canonicalJson(atArrayLimit).replace("\"optional_extensions\":[",
                "\"optional_extensions\":[\"one-over\",");
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse(tooMany));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.parse("[".repeat(X7BenchmarkEvidenceCodec.MAX_NESTING_DEPTH + 1)
                        + "]".repeat(X7BenchmarkEvidenceCodec.MAX_NESTING_DEPTH + 1)));
    }

    @Test
    void canonicalFiniteExtremeNumbersRoundTripWithinTheParserTokenLimit(@TempDir Path temporaryDirectory)
            throws Exception {
        X7BenchmarkEvidence base = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        X7BenchmarkEvidence extremes = copyWithMetrics(base, new X7BenchmarkEvidence.Metrics(
                new X7BenchmarkEvidence.Percentiles(Double.MIN_VALUE, Double.MAX_VALUE, Double.MAX_VALUE),
                base.metrics().frameTimeMillis(), base.metrics().cpuRenderMillis(), base.metrics().allocationBytes()));

        String canonical = X7BenchmarkEvidenceCodec.canonicalJson(extremes);
        assertTrue(X7BenchmarkEvidenceCodec.canonicalNumber(Double.MAX_VALUE).length()
                <= X7BenchmarkEvidenceCodec.MAX_NUMBER_CHARS);
        assertTrue(X7BenchmarkEvidenceCodec.canonicalNumber(Double.MIN_VALUE).length()
                <= X7BenchmarkEvidenceCodec.MAX_NUMBER_CHARS);
        assertEquals(extremes, X7BenchmarkEvidenceCodec.parse(canonical));

        X7BenchmarkEvidence negativeZero = copyWithMetrics(base, new X7BenchmarkEvidence.Metrics(
                new X7BenchmarkEvidence.Percentiles(-0.0d, 60.0d, 60.0d),
                base.metrics().frameTimeMillis(), base.metrics().cpuRenderMillis(), base.metrics().allocationBytes()));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.canonicalJson(negativeZero));
    }

    @Test
    void directConstructionCanonicalizesCollectionOrderBeforeEncodeSoRoundTripIsEqual(@TempDir Path temporaryDirectory)
            throws Exception {
        X7BenchmarkEvidence base = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        X7BenchmarkEvidence unsorted = new X7BenchmarkEvidence(
                base.schemaVersion(), base.captureClass(), base.gateStatus(), base.captureId(), base.sourceRevision(),
                base.scenario(), base.environment(), base.backend(), base.sampling(), base.metrics(),
                base.allocationEvidenceKey(), base.artifactManifest(), base.requiredExtensions(), List.of("z.extension", "a.extension"));

        assertEquals(List.of("a.extension", "z.extension"), unsorted.optionalExtensions());
        assertEquals(unsorted, X7BenchmarkEvidenceCodec.parse(X7BenchmarkEvidenceCodec.canonicalJson(unsorted)));
    }

    @Test
    void aggregateWriterLimitClosesThe4096By16KiBDirectConstructionCase(@TempDir Path temporaryDirectory)
            throws Exception {
        X7BenchmarkEvidence base = X7SyntheticTestFixtures.cpuCapture(temporaryDirectory).evidence();
        List<String> maximumStrings = java.util.Collections.nCopies(
                X7BenchmarkEvidenceCodec.MAX_ARRAY_ENTRIES, "x".repeat(X7BenchmarkEvidenceCodec.MAX_STRING_CHARS));
        X7BenchmarkEvidence aggregate = new X7BenchmarkEvidence(
                base.schemaVersion(), base.captureClass(), base.gateStatus(), base.captureId(), base.sourceRevision(),
                base.scenario(), base.environment(), base.backend(), base.sampling(), base.metrics(),
                base.allocationEvidenceKey(), base.artifactManifest(), base.requiredExtensions(), maximumStrings);

        String failure = X7BenchmarkEvidenceCodec.canonicalLimitFailure(aggregate);
        assertNotNull(failure);
        assertTrue(failure.contains("byte limit") || failure.contains("character limit"));
        assertThrows(X7BenchmarkEvidenceCodec.X7EvidenceFormatException.class,
                () -> X7BenchmarkEvidenceCodec.canonicalJson(aggregate));
    }

    @Test
    void syntheticFixtureIsExplicitlyMarkedAndCannotBeHardwareEvidence(@TempDir Path temporaryDirectory) throws Exception {
        X7SyntheticTestFixtures.Fixture fixture = X7SyntheticTestFixtures.syntheticCapture(temporaryDirectory);
        String canonical = X7BenchmarkEvidenceCodec.canonicalJson(fixture.evidence());
        assertTrue(canonical.contains("\"capture_class\":\"SYNTHETIC_TEST_ONLY\""));
        assertTrue(fixture.evidence().captureId().contains("synthetic-test-only"));
        X7BenchmarkEvidenceValidator.ValidationResult result =
                X7BenchmarkEvidenceValidator.validate(fixture.evidence(), temporaryDirectory, fixture.artifactVerification());
        assertEquals(X7BenchmarkEvidenceValidator.Status.STRUCTURALLY_VALID_WAITING, result.status());
    }

    private static X7BenchmarkEvidence copyWithEnvironment(
            X7BenchmarkEvidence evidence, X7BenchmarkEvidence.RuntimeEnvironment environment) {
        return new X7BenchmarkEvidence(
                evidence.schemaVersion(), evidence.captureClass(), evidence.gateStatus(), evidence.captureId(),
                evidence.sourceRevision(), evidence.scenario(), environment, evidence.backend(), evidence.sampling(),
                evidence.metrics(), evidence.allocationEvidenceKey(), evidence.artifactManifest(),
                evidence.requiredExtensions(), evidence.optionalExtensions());
    }

    private static X7BenchmarkEvidence copyWithMetrics(
            X7BenchmarkEvidence evidence, X7BenchmarkEvidence.Metrics metrics) {
        return new X7BenchmarkEvidence(
                evidence.schemaVersion(), evidence.captureClass(), evidence.gateStatus(), evidence.captureId(),
                evidence.sourceRevision(), evidence.scenario(), evidence.environment(), evidence.backend(), evidence.sampling(),
                metrics, evidence.allocationEvidenceKey(), evidence.artifactManifest(), evidence.requiredExtensions(),
                evidence.optionalExtensions());
    }
}
