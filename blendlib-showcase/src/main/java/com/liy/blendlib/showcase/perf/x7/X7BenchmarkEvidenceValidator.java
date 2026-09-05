package com.liy.blendlib.showcase.perf.x7;

import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Fail-closed structural and offline-evidence validator for {@link X7BenchmarkEvidence}.
 *
 * <p>This offline validator can validate package structure only. Client-runtime live authority is
 * deliberately not deserializable through this type, so every offline result remains structural
 * WAITING rather than a hardware-eligible, trusted, or Gate result.</p>
 */
public final class X7BenchmarkEvidenceValidator {
    private static final Pattern OBJECT_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,127}");
    private static final Pattern SHA_1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RELATIVE_PATH = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._/-]*");
    private static final Set<String> SUPPORTED_REQUIRED_EXTENSIONS = Set.of();
    private static final Set<String> ENVIRONMENT_REPORT_KEYS = Set.of(
            "format", "capture_class", "java_version", "os_name", "os_version", "minecraft_version",
            "fabric_loader_version", "fabric_api_version", "gpu_name", "gpu_vendor", "gpu_driver",
            "shaderpack", "iris_status", "sodium_status", "backend_class", "backend_name",
            "backend_capability", "fallback_state", "source_commit", "source_tree", "source_clean");
    private X7BenchmarkEvidenceValidator() {
    }

    /**
     * Validates an evidence envelope only with the opaque verification token generated for the
     * same current root. A token from another evidence object, root, or stale manifest is invalid.
     */
    public static ValidationResult validate(
            X7BenchmarkEvidence evidence,
            Path artifactRoot,
            X7ArtifactVerifier.ArtifactVerification artifactVerification) {
        X7BenchmarkEvidence checked = Objects.requireNonNull(evidence, "evidence");
        Path checkedRoot = Objects.requireNonNull(artifactRoot, "artifactRoot");
        X7ArtifactVerifier.ArtifactVerification verification = Objects.requireNonNull(artifactVerification,
                "artifactVerification");
        List<String> structural = structuralErrors(checked);
        if (!structural.isEmpty()) {
            return ValidationResult.invalid(structural);
        }
        if (!verification.structurallyValid()) {
            return ValidationResult.invalid(verification.failures());
        }
        if (!verification.attests(checked, checkedRoot)) {
            return ValidationResult.invalid(List.of(
                    "artifact verification token does not bind the current evidence, real root, manifest, and inventory"));
        }

        if (checked.captureClass() != X7BenchmarkEvidence.CaptureClass.HARDWARE_CAPTURE) {
            if (checked.captureClass() == X7BenchmarkEvidence.CaptureClass.CPU_CAPTURE) {
                return ValidationResult.structurallyValidWaiting(
                        List.of("CPU_CAPTURE is a structurally valid offline example, not hardware evidence"),
                        checked,
                        checkedRoot,
                        verification);
            }
            return ValidationResult.structurallyValidWaiting(
                    List.of("capture_class " + checked.captureClass() + " is never hardware evidence"),
                    checked,
                    checkedRoot,
                    verification);
        }

        HardwareArtifactValidation hardware = validateHardwareArtifacts(checked, checkedRoot);
        if (!hardware.errors().isEmpty()) {
            return ValidationResult.invalid(hardware.errors());
        }
        if (!metricsEqual(checked.metrics(), hardware.rawSamples().metrics())) {
            return ValidationResult.invalid(List.of(
                    "canonical raw P7 samples do not recompute the envelope p50/p95/p99 metrics"));
        }
        if (!checked.environment().hasVerifiedHardwareIdentity()
                || !checked.backend().backendVerified()
                || !X7BenchmarkEvidence.isKnown(checked.backend().backendName())
                || !X7BenchmarkEvidence.isKnown(checked.backend().capability())
                || checked.backend().backendClass() != X7BenchmarkEvidence.BackendClass.GPU
                || checked.backend().fallbackState() != X7BenchmarkEvidence.FallbackState.NONE) {
            return ValidationResult.structurallyValidWaiting(
                    List.of("hardware identity, GPU backend, or fallback state is missing, UNKNOWN, or unverified",
                            "no trusted capture owner/capability is implemented; hardware comparison remains WAITING"),
                    checked,
                    checkedRoot,
                    verification);
        }
        if (!verification.hasTrustedTraversal()) {
            return ValidationResult.structurallyValidWaiting(
                    List.of("ordinary Windows/path traversal is structurally valid only; secure relative traversal is required",
                            "no trusted capture owner/capability is implemented; hardware comparison remains WAITING"),
                    checked,
                    checkedRoot,
                    verification);
        }
        return ValidationResult.structurallyValidWaiting(
                List.of("no trusted capture owner/capability is implemented; hardware comparison remains WAITING"),
                checked,
                checkedRoot,
                verification);
    }

    /** Returns all schema violations without touching a filesystem. Codec parsing calls this before returning. */
    public static List<String> structuralErrors(X7BenchmarkEvidence evidence) {
        X7BenchmarkEvidence checked = Objects.requireNonNull(evidence, "evidence");
        List<String> errors = new ArrayList<>();
        if (checked.schemaVersion() != X7BenchmarkEvidence.SCHEMA_VERSION) {
            errors.add("unsupported schema_version");
        }
        if (checked.gateStatus() != X7BenchmarkEvidence.GateStatus.WAITING) {
            errors.add("capture evidence may not self-issue a Gate result");
        }
        if (!OBJECT_ID.matcher(checked.captureId()).matches()) {
            errors.add("capture_id must be a stable lowercase identifier");
        }
        validateRevision(checked.sourceRevision(), errors);
        if (!X7BenchmarkEvidence.frozenScenario().equals(checked.scenario())) {
            errors.add("scenario does not exactly match the frozen P7 reference target");
        }
        validateEnvironment(checked.environment(), errors);
        validateBackend(checked.backend(), errors);
        validateSampling(checked.sampling(), errors);
        validateMetrics(checked.metrics(), errors);
        validateExtensions(checked.requiredExtensions(), checked.optionalExtensions(), errors);
        validateArtifactManifest(checked, errors);
        validateStringAndCollectionLimits(checked, errors);
        if (errors.isEmpty()) {
            String canonicalLimitFailure = X7BenchmarkEvidenceCodec.canonicalLimitFailure(checked);
            if (canonicalLimitFailure != null) {
                errors.add("canonical evidence exceeds parser/writer aggregate limits: " + canonicalLimitFailure);
            } else {
                long declaredBytes = declaredArtifactBytes(checked);
                int canonicalManifestBytes = X7BenchmarkEvidenceCodec.canonicalUtf8Length(checked);
                if (declaredBytes > X7ArtifactVerifier.MAX_TOTAL_ARTIFACT_BYTES - canonicalManifestBytes) {
                    errors.add("artifact byte_count aggregate plus canonical manifest exceeds the total-byte limit");
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void validateRevision(X7BenchmarkEvidence.SourceRevision revision, List<String> errors) {
        if (!SHA_1.matcher(revision.commit()).matches()) {
            errors.add("source_revision.commit must be a lowercase full Git SHA-1");
        }
        if (!SHA_1.matcher(revision.tree()).matches()) {
            errors.add("source_revision.tree must be a lowercase full Git tree SHA-1");
        }
    }

    private static void validateEnvironment(X7BenchmarkEvidence.RuntimeEnvironment environment, List<String> errors) {
        for (String value : List.of(
                environment.javaVersion(), environment.osName(), environment.osVersion(), environment.minecraftVersion(),
                environment.fabricLoaderVersion(), environment.fabricApiVersion(), environment.gpuName(), environment.gpuVendor(),
                environment.gpuDriver(), environment.shaderpack(), environment.irisStatus(), environment.sodiumStatus())) {
            if (value.isBlank()) {
                errors.add("environment fields must be non-blank; use UNKNOWN when identity is unavailable");
                return;
            }
        }
        if (!"26.1.2".equals(environment.minecraftVersion())) {
            errors.add("minecraft_version must be the frozen 26.1.2 adapter target");
        }
        if (!"0.19.3".equals(environment.fabricLoaderVersion())) {
            errors.add("fabric_loader_version must be the frozen 0.19.3 target");
        }
        if (!"0.154.2+26.1.2".equals(environment.fabricApiVersion())) {
            errors.add("fabric_api_version must be the frozen 0.154.2+26.1.2 target");
        }
        if (!environment.javaVersion().startsWith("25")) {
            errors.add("java_version must be Java 25 for the frozen P7 runtime");
        }
    }

    private static void validateBackend(X7BenchmarkEvidence.BackendSelection backend, List<String> errors) {
        if (backend.backendName().isBlank() || backend.capability().isBlank()) {
            errors.add("backend name and capability must be recorded or explicitly UNKNOWN");
        }
        if (backend.backendClass() == X7BenchmarkEvidence.BackendClass.CPU_FALLBACK
                && backend.fallbackState() != X7BenchmarkEvidence.FallbackState.CPU_FALLBACK_ACTIVE) {
            errors.add("CPU_FALLBACK backend_class requires CPU_FALLBACK_ACTIVE fallback_state");
        }
        if (backend.backendClass() != X7BenchmarkEvidence.BackendClass.CPU_FALLBACK
                && backend.fallbackState() == X7BenchmarkEvidence.FallbackState.CPU_FALLBACK_ACTIVE) {
            errors.add("CPU_FALLBACK_ACTIVE must use the CPU_FALLBACK backend_class");
        }
    }

    private static void validateSampling(X7BenchmarkEvidence.Sampling sampling, List<String> errors) {
        if (sampling.warmupFrames() != P7ReferenceScenario.WARMUP_FRAME_COUNT) {
            errors.add("warmup_frames must exactly equal the frozen P7 count");
        }
        if (sampling.sampleFrames() != P7ReferenceScenario.SAMPLE_FRAME_COUNT) {
            errors.add("sample_frames must exactly equal the frozen P7 count");
        }
        if (sampling.reloadGeneration() < 0L) {
            errors.add("reload_generation must be non-negative");
        }
    }

    private static void validateMetrics(X7BenchmarkEvidence.Metrics metrics, List<String> errors) {
        validatePercentiles("fps", metrics.fps(), errors);
        validatePercentiles("frame_time_ms", metrics.frameTimeMillis(), errors);
        validatePercentiles("cpu_render_ms", metrics.cpuRenderMillis(), errors);
        validatePercentiles("allocation_bytes", metrics.allocationBytes(), errors);
    }

    private static void validatePercentiles(String name, X7BenchmarkEvidence.Percentiles percentiles, List<String> errors) {
        if (!percentiles.isFiniteNonNegativeAndOrdered() || percentiles.hasNegativeZero()) {
            errors.add(name + " percentiles must be finite, non-negative canonical values, and p50 <= p95 <= p99");
        }
    }

    private static void validateExtensions(List<String> required, List<String> optional, List<String> errors) {
        validateExtensionList("required_extensions", required, errors);
        validateExtensionList("optional_extensions", optional, errors);
        for (String extension : required) {
            if (!SUPPORTED_REQUIRED_EXTENSIONS.contains(extension)) {
                errors.add("unknown required extension: " + extension);
            }
        }
    }

    private static void validateExtensionList(String name, List<String> extensions, List<String> errors) {
        if (extensions.size() > X7BenchmarkEvidenceCodec.MAX_ARRAY_ENTRIES) {
            errors.add(name + " exceeds the parser collection limit");
        }
        Set<String> seen = new HashSet<>();
        for (String extension : extensions) {
            if (extension == null || extension.isBlank()) {
                errors.add(name + " cannot contain blank values");
            } else if (!seen.add(extension)) {
                errors.add(name + " cannot contain duplicate values");
            }
        }
    }

    private static void validateArtifactManifest(X7BenchmarkEvidence evidence, List<String> errors) {
        X7BenchmarkEvidence.ArtifactManifest manifest = evidence.artifactManifest();
        if (!isSafeRelativePath(manifest.manifestPath())) {
            errors.add("artifact_manifest.manifest_path must be a canonical relative path");
        }
        Set<String> keys = new HashSet<>();
        Set<String> paths = new HashSet<>();
        Set<String> foldedPaths = new HashSet<>();
        Set<X7BenchmarkEvidence.ArtifactKind> kinds = new HashSet<>();
        if (manifest.entries().size() > X7BenchmarkEvidenceCodec.MAX_ARRAY_ENTRIES) {
            errors.add("artifact_manifest.entries exceeds the parser collection limit");
        }
        long totalDeclaredBytes = 0L;
        boolean foundAllocation = false;
        for (X7BenchmarkEvidence.Artifact artifact : manifest.entries()) {
            if (!OBJECT_ID.matcher(artifact.key()).matches()) {
                errors.add("artifact key must be a stable lowercase identifier");
            }
            if (!keys.add(artifact.key())) {
                errors.add("artifact keys must be unique");
            }
            if (!isSafeRelativePath(artifact.path())) {
                errors.add("artifact path must be a canonical relative path");
            }
            if (!paths.add(artifact.path()) || !foldedPaths.add(artifact.path().toLowerCase(Locale.ROOT))) {
                errors.add("artifact paths must be unique without case aliases");
            }
            if (manifest.manifestPath().equals(artifact.path())) {
                errors.add("artifact manifest must exclude itself from its entries");
            }
            if (!SHA_256.matcher(artifact.sha256()).matches()) {
                errors.add("artifact SHA-256 must be lowercase hexadecimal");
            }
            if (artifact.byteCount() <= 0L) {
                errors.add("artifact byte_count must be positive");
            } else if (artifact.byteCount() > X7ArtifactVerifier.maxArtifactBytes(artifact.kind())) {
                errors.add("artifact byte_count exceeds the " + artifact.kind() + " per-file limit");
            }
            try {
                totalDeclaredBytes = Math.addExact(totalDeclaredBytes, Math.max(0L, artifact.byteCount()));
            } catch (ArithmeticException exception) {
                errors.add("artifact byte_count aggregate overflows the total-byte limit");
            }
            if (!kinds.add(artifact.kind())) {
                errors.add("artifact kinds must be unique");
            }
            if (artifact.key().equals(evidence.allocationEvidenceKey())) {
                if (artifact.kind() != X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION) {
                    errors.add("allocation_evidence_key must name a JFR_ALLOCATION artifact");
                }
                foundAllocation = true;
            }
        }
        if (!keys.contains(evidence.allocationEvidenceKey())) {
            errors.add("allocation_evidence_key is missing from artifact_manifest");
        } else if (!foundAllocation) {
            errors.add("allocation evidence must be an explicit JFR artifact");
        }
        if (totalDeclaredBytes > X7ArtifactVerifier.MAX_TOTAL_ARTIFACT_BYTES) {
            errors.add("artifact byte_count aggregate exceeds the total-byte limit");
        }
    }

    private static long declaredArtifactBytes(X7BenchmarkEvidence evidence) {
        long total = 0L;
        for (X7BenchmarkEvidence.Artifact artifact : evidence.artifactManifest().entries()) {
            total = Math.addExact(total, artifact.byteCount());
        }
        return total;
    }

    /** Applies the exact parser string/count ceilings to direct construction as well as decode. */
    private static void validateStringAndCollectionLimits(X7BenchmarkEvidence evidence, List<String> errors) {
        validateStringLimit("capture_id", evidence.captureId(), errors);
        validateStringLimit("source_revision.commit", evidence.sourceRevision().commit(), errors);
        validateStringLimit("source_revision.tree", evidence.sourceRevision().tree(), errors);
        X7BenchmarkEvidence.FrozenScenario scenario = evidence.scenario();
        validateStringLimit("scenario.scenario_format", scenario.scenarioFormat(), errors);
        validateStringLimit("scenario.rigid_model_key", scenario.rigidModelKey(), errors);
        validateStringLimit("scenario.skinned_model_key", scenario.skinnedModelKey(), errors);
        X7BenchmarkEvidence.RuntimeEnvironment environment = evidence.environment();
        for (Map.Entry<String, String> entry : Map.ofEntries(
                Map.entry("environment.java_version", environment.javaVersion()),
                Map.entry("environment.os_name", environment.osName()),
                Map.entry("environment.os_version", environment.osVersion()),
                Map.entry("environment.minecraft_version", environment.minecraftVersion()),
                Map.entry("environment.fabric_loader_version", environment.fabricLoaderVersion()),
                Map.entry("environment.fabric_api_version", environment.fabricApiVersion()),
                Map.entry("environment.gpu_name", environment.gpuName()),
                Map.entry("environment.gpu_vendor", environment.gpuVendor()),
                Map.entry("environment.gpu_driver", environment.gpuDriver()),
                Map.entry("environment.shaderpack", environment.shaderpack()),
                Map.entry("environment.iris_status", environment.irisStatus()),
                Map.entry("environment.sodium_status", environment.sodiumStatus())).entrySet()) {
            validateStringLimit(entry.getKey(), entry.getValue(), errors);
        }
        validateStringLimit("backend.backend_name", evidence.backend().backendName(), errors);
        validateStringLimit("backend.capability", evidence.backend().capability(), errors);
        validateStringLimit("allocation_evidence_key", evidence.allocationEvidenceKey(), errors);
        validateStringLimit("artifact_manifest.manifest_path", evidence.artifactManifest().manifestPath(), errors);
        for (X7BenchmarkEvidence.Artifact artifact : evidence.artifactManifest().entries()) {
            validateStringLimit("artifact.key", artifact.key(), errors);
            validateStringLimit("artifact.path", artifact.path(), errors);
            validateStringLimit("artifact.sha256", artifact.sha256(), errors);
        }
        validateStringListLimit("required_extensions", evidence.requiredExtensions(), errors);
        validateStringListLimit("optional_extensions", evidence.optionalExtensions(), errors);
    }

    private static void validateStringListLimit(String name, List<String> values, List<String> errors) {
        if (values.size() > X7BenchmarkEvidenceCodec.MAX_ARRAY_ENTRIES) {
            errors.add(name + " exceeds the parser collection limit");
        }
        for (String value : values) {
            validateStringLimit(name + " entry", value, errors);
        }
    }

    private static void validateStringLimit(String name, String value, List<String> errors) {
        if (value == null) {
            errors.add(name + " cannot be null");
            return;
        }
        if (value.length() > X7BenchmarkEvidenceCodec.MAX_STRING_CHARS) {
            errors.add(name + " exceeds the parser string limit");
        }
        try {
            X7BenchmarkEvidence.requireWellFormedUnicode(value, name);
        } catch (IllegalArgumentException exception) {
            errors.add(name + " has malformed Unicode");
        }
    }

    static boolean isSafeRelativePath(String value) {
        if (value == null
                || value.length() > X7ArtifactVerifier.MAX_RELATIVE_PATH_CHARS
                || !RELATIVE_PATH.matcher(value).matches()
                || value.contains("//")
                || value.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : value.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static HardwareArtifactValidation validateHardwareArtifacts(X7BenchmarkEvidence evidence, Path root) {
        Map<X7BenchmarkEvidence.ArtifactKind, X7BenchmarkEvidence.Artifact> byKind =
                new EnumMap<>(X7BenchmarkEvidence.ArtifactKind.class);
        for (X7BenchmarkEvidence.Artifact artifact : evidence.artifactManifest().entries()) {
            byKind.put(artifact.kind(), artifact);
        }
        List<String> errors = new ArrayList<>();
        for (X7BenchmarkEvidence.ArtifactKind required : List.of(
                X7BenchmarkEvidence.ArtifactKind.P7_CAPTURE_REPORT,
                X7BenchmarkEvidence.ArtifactKind.P7_RAW_SAMPLES,
                X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION,
                X7BenchmarkEvidence.ArtifactKind.CAPTURE_LOG,
                X7BenchmarkEvidence.ArtifactKind.ENVIRONMENT_REPORT,
                X7BenchmarkEvidence.ArtifactKind.CAPABILITY_REPORT,
                X7BenchmarkEvidence.ArtifactKind.SCREENSHOT,
                X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT)) {
            if (!byKind.containsKey(required)) {
                errors.add("hardware capture is missing required " + required + " artifact");
            }
        }
        if (byKind.size() != 8) {
            errors.add("hardware capture must retain exactly eight self-excluded artifact roles");
        }
        Map<X7BenchmarkEvidence.ArtifactKind, String> exactPaths = Map.of(
                X7BenchmarkEvidence.ArtifactKind.P7_CAPTURE_REPORT, "p7-report.json",
                X7BenchmarkEvidence.ArtifactKind.P7_RAW_SAMPLES, "raw-p7-samples.txt",
                X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION, "allocation.jfr",
                X7BenchmarkEvidence.ArtifactKind.CAPTURE_LOG, "capture.log",
                X7BenchmarkEvidence.ArtifactKind.ENVIRONMENT_REPORT, "environment.txt",
                X7BenchmarkEvidence.ArtifactKind.CAPABILITY_REPORT, "capability.txt",
                X7BenchmarkEvidence.ArtifactKind.SCREENSHOT, "scene.png",
                X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT, "owner-receipt.json");
        for (Map.Entry<X7BenchmarkEvidence.ArtifactKind, String> expected : exactPaths.entrySet()) {
            X7BenchmarkEvidence.Artifact artifact = byKind.get(expected.getKey());
            if (artifact != null && !expected.getValue().equals(artifact.path())) {
                errors.add("hardware capture role " + expected.getKey() + " must use " + expected.getValue());
            }
        }
        if (!"artifact-manifest.json".equals(evidence.artifactManifest().manifestPath())) {
            errors.add("hardware capture manifest must use artifact-manifest.json");
        }
        if (!errors.isEmpty()) {
            return new HardwareArtifactValidation(List.copyOf(errors), null);
        }
        X7RawP7Samples.RawSamples rawSamples = null;
        try {
            X7BenchmarkEvidence.Artifact p7ReportArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.P7_CAPTURE_REPORT);
            X7BenchmarkEvidence.Artifact rawArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.P7_RAW_SAMPLES);
            X7BenchmarkEvidence.Artifact jfrArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION);
            X7BenchmarkEvidence.Artifact logArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.CAPTURE_LOG);
            X7BenchmarkEvidence.Artifact environmentArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.ENVIRONMENT_REPORT);
            X7BenchmarkEvidence.Artifact capabilityArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.CAPABILITY_REPORT);
            X7BenchmarkEvidence.Artifact screenshotArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.SCREENSHOT);
            X7BenchmarkEvidence.Artifact receiptArtifact = byKind.get(X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT);
            byte[] p7Report = X7ArtifactVerifier.readRetainedArtifact(root,
                    p7ReportArtifact.path(),
                    X7BenchmarkEvidence.ArtifactKind.P7_CAPTURE_REPORT);
            byte[] raw = X7ArtifactVerifier.readRetainedArtifact(root,
                    rawArtifact.path(),
                    X7BenchmarkEvidence.ArtifactKind.P7_RAW_SAMPLES);
            byte[] jfr = X7ArtifactVerifier.readRetainedArtifact(root,
                    jfrArtifact.path(), X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION);
            byte[] log = X7ArtifactVerifier.readRetainedArtifact(root,
                    logArtifact.path(),
                    X7BenchmarkEvidence.ArtifactKind.CAPTURE_LOG);
            byte[] environment = X7ArtifactVerifier.readRetainedArtifact(root,
                    environmentArtifact.path(),
                    X7BenchmarkEvidence.ArtifactKind.ENVIRONMENT_REPORT);
            byte[] capability = X7ArtifactVerifier.readRetainedArtifact(root,
                    capabilityArtifact.path(),
                    X7BenchmarkEvidence.ArtifactKind.CAPABILITY_REPORT);
            byte[] ownerReceipt = X7ArtifactVerifier.readRetainedArtifact(root,
                    receiptArtifact.path(),
                    X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT);
            byte[] screenshot = X7ArtifactVerifier.readRetainedArtifact(root,
                    screenshotArtifact.path(), X7BenchmarkEvidence.ArtifactKind.SCREENSHOT);
            rejectSyntheticMarker("P7 capture report", p7Report, errors);
            rejectSyntheticMarker("canonical raw P7 samples", raw, errors);
            rejectSyntheticMarker("capture log", log, errors);
            rejectSyntheticMarker("environment report", environment, errors);
            rejectSyntheticMarker("capability report", capability, errors);
            rejectSyntheticMarker("owner receipt", ownerReceipt, errors);
            validateJfr(jfr, root.resolve(jfrArtifact.path()), jfrArtifact.byteCount(), errors);
            validatePng(screenshot, screenshotArtifact.byteCount(), errors);
            X7P7CaptureReport.Report report = X7P7CaptureReport.parse(p7Report);
            validateP7Report(report, jfrArtifact, errors);
            rawSamples = X7RawP7Samples.parseCanonical(raw);
            validateP7SummaryBinding(report, rawSamples.metrics(), errors);
            if (log.length == 0) {
                errors.add("capture log is empty");
            }
            validateEnvironmentReport(environment, evidence, errors);
            validateCapabilityReport(capability, evidence, errors);
            validateOwnerReceipt(ownerReceipt, evidence, receiptArtifact, errors);
        } catch (IOException | X7BenchmarkEvidenceCodec.X7EvidenceFormatException exception) {
            errors.add("hardware artifact content could not be read or parsed safely: " + exception.getMessage());
        }
        return new HardwareArtifactValidation(List.copyOf(errors), rawSamples);
    }

    private static void validateP7Report(
            X7P7CaptureReport.Report report,
            X7BenchmarkEvidence.Artifact jfrArtifact,
            List<String> errors) {
        if (!"blendlib-showcase-p7-runtime-capture-v1".equals(report.format())) {
            errors.add("P7 capture report has the wrong format");
        }
        if (!"RUNTIME_CAPTURE_COMPLETE".equals(report.status()) || !"WAITING".equals(report.gate())) {
            errors.add("P7 capture report must retain completed WAITING status");
        }
        if (report.gateReason().isBlank()) {
            errors.add("P7 capture report is missing its gate reason");
        }
        if (report.warmupFrames() != P7ReferenceScenario.WARMUP_FRAME_COUNT
                || report.sampleFrames() != P7ReferenceScenario.SAMPLE_FRAME_COUNT
                || report.rigidSubmitsPerSample() != P7ReferenceScenario.RIGID_INSTANCE_COUNT
                || report.skinnedSubmitsPerSample() != P7ReferenceScenario.SKINNED_INSTANCE_COUNT) {
            errors.add("P7 capture report does not bind the frozen 600/1800/100/25 workload");
        }
        String expectedJfrName = Path.of(jfrArtifact.path()).getFileName().toString();
        try {
            if (!expectedJfrName.equals(Path.of(report.jfrFile()).getFileName().toString())) {
                errors.add("P7 capture report does not bind the retained JFR role");
            }
        } catch (RuntimeException exception) {
            errors.add("P7 capture report has an unreadable JFR path");
        }
        X7P7CaptureReport.ClientConditions conditions = report.clientConditions();
        if (conditions.framebufferWidth() != P7ReferenceScenario.CAPTURE_FRAMEBUFFER_WIDTH
                || conditions.framebufferHeight() != P7ReferenceScenario.CAPTURE_FRAMEBUFFER_HEIGHT
                || conditions.renderTargetWidth() != P7ReferenceScenario.CAPTURE_FRAMEBUFFER_WIDTH
                || conditions.renderTargetHeight() != P7ReferenceScenario.CAPTURE_FRAMEBUFFER_HEIGHT
                || !"16:9".equals(conditions.requiredAspectRatio())
                || conditions.fovDegrees() != P7ReferenceScenario.CAPTURE_FOV_DEGREES
                || Double.compare(conditions.fovEffectScale(), P7ReferenceScenario.DISABLED_DYNAMIC_FOV_EFFECT_SCALE) != 0
                || !conditions.dynamicFovDisabled()
                || conditions.configuredRenderDistanceChunks() < P7ReferenceScenario.MIN_CAPTURE_RENDER_DISTANCE_CHUNKS
                || conditions.effectiveRenderDistanceChunks() < P7ReferenceScenario.MIN_CAPTURE_RENDER_DISTANCE_CHUNKS
                || conditions.requiredMinimumRenderDistanceChunks() != P7ReferenceScenario.MIN_CAPTURE_RENDER_DISTANCE_CHUNKS
                || !conditions.contractSatisfied()) {
            errors.add("P7 capture report does not bind frozen camera/layout/framebuffer/FOV/client conditions");
        }
    }

    private static void validateP7SummaryBinding(
            X7P7CaptureReport.Report report, X7BenchmarkEvidence.Metrics metrics, List<String> errors) {
        validateNanos("P7 frame_time_nanos.p50", report.frameTimeNanos().p50(), metrics.frameTimeMillis().p50(), errors);
        validateNanos("P7 frame_time_nanos.p95", report.frameTimeNanos().p95(), metrics.frameTimeMillis().p95(), errors);
        validateNanos("P7 submit_cpu_nanos.p50", report.submitCpuNanos().p50(), metrics.cpuRenderMillis().p50(), errors);
        validateNanos("P7 submit_cpu_nanos.p95", report.submitCpuNanos().p95(), metrics.cpuRenderMillis().p95(), errors);
        validateIntegral("P7 jfr_allocation_bytes.p50", report.allocationBytes().p50(), metrics.allocationBytes().p50(), errors);
        validateIntegral("P7 jfr_allocation_bytes.p95", report.allocationBytes().p95(), metrics.allocationBytes().p95(), errors);
    }

    private static void validateNanos(String field, long reported, double milliseconds, List<String> errors) {
        double nanos = milliseconds * 1_000_000.0d;
        if (!Double.isFinite(nanos) || nanos < 0.0d || nanos > Long.MAX_VALUE || nanos != Math.rint(nanos)
                || reported != (long) nanos) {
            errors.add(field + " does not match canonical raw P7 samples");
        }
    }

    private static void validateIntegral(String field, long reported, double value, List<String> errors) {
        if (!Double.isFinite(value) || value < 0.0d || value > Long.MAX_VALUE || value != Math.rint(value)
                || reported != (long) value) {
            errors.add(field + " does not match canonical raw P7 samples");
        }
    }

    private static void validateJfr(byte[] bytes, Path path, long artifactBytes, List<String> errors) throws IOException {
        if (artifactBytes < 64L || bytes.length < 4 || bytes[0] != 'F' || bytes[1] != 'L' || bytes[2] != 'R' || bytes[3] != 0) {
            errors.add("JFR allocation artifact does not have the JFR FLR header");
            return;
        }
        boolean allocationEvent = false;
        try (RecordingFile recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String name = event.getEventType().getName();
                if ("jdk.ObjectAllocationInNewTLAB".equals(name)
                        || "jdk.ObjectAllocationOutsideTLAB".equals(name)) {
                    long allocation = event.getLong("allocationSize");
                    if (allocation <= 0L) {
                        errors.add("JFR allocation event has a non-positive allocationSize");
                        return;
                    }
                    allocationEvent = true;
                }
            }
        } catch (RuntimeException exception) {
            errors.add("JFR allocation artifact cannot be parsed as a bounded recording");
            return;
        }
        if (!allocationEvent) {
            errors.add("JFR allocation artifact has no allocation event");
        }
    }

    private static void validatePng(byte[] bytes, long artifactBytes, List<String> errors) throws IOException {
        byte[] magic = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        if (artifactBytes < 33L || bytes.length < 33) {
            errors.add("screenshot artifact is too short to be PNG");
            return;
        }
        for (int index = 0; index < magic.length; index++) {
            if (bytes[index] != magic[index]) {
                errors.add("screenshot artifact does not have the PNG header");
                return;
            }
        }
        if (bytes[8] != 0 || bytes[9] != 0 || bytes[10] != 0 || bytes[11] != 13
                || bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            errors.add("screenshot artifact does not have a PNG IHDR chunk");
            return;
        }
        int width = ((bytes[16] & 0xff) << 24) | ((bytes[17] & 0xff) << 16) | ((bytes[18] & 0xff) << 8) | (bytes[19] & 0xff);
        int height = ((bytes[20] & 0xff) << 24) | ((bytes[21] & 0xff) << 16) | ((bytes[22] & 0xff) << 8) | (bytes[23] & 0xff);
        if (width != P7ReferenceScenario.CAPTURE_FRAMEBUFFER_WIDTH
                || height != P7ReferenceScenario.CAPTURE_FRAMEBUFFER_HEIGHT) {
            errors.add("screenshot PNG dimensions do not bind the frozen 1920x1080 framebuffer");
            return;
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
            errors.add("screenshot PNG cannot be fully decoded at the retained dimensions");
        }
    }

    private static void rejectSyntheticMarker(String name, byte[] bytes, List<String> errors) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (text.contains(X7BenchmarkEvidence.CaptureClass.SYNTHETIC_TEST_ONLY.name())) {
            errors.add(name + " contains the SYNTHETIC_TEST_ONLY marker");
        }
    }

    private static void validateEnvironmentReport(byte[] bytes, X7BenchmarkEvidence evidence, List<String> errors) {
        String report = decodeUtf8(bytes, "environment report", errors);
        if (report == null) {
            return;
        }
        Map<String, String> fields = new HashMap<>();
        String[] lines = report.split("\\n", -1);
        int limit = lines.length;
        if (limit > 0 && lines[limit - 1].isEmpty()) {
            limit--;
        }
        for (int index = 0; index < limit; index++) {
            String line = lines[index];
            if (line.indexOf('\r') >= 0 || line.isEmpty()) {
                errors.add("environment report has a non-canonical line");
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0 || equals != line.lastIndexOf('=')) {
                errors.add("environment report has an invalid field line");
                continue;
            }
            String prior = fields.putIfAbsent(line.substring(0, equals), line.substring(equals + 1));
            if (prior != null) {
                errors.add("environment report has a duplicate field");
            }
        }
        if (!fields.keySet().equals(ENVIRONMENT_REPORT_KEYS)) {
            errors.add("environment report has missing or unknown fields");
            return;
        }
        Map<String, String> expected = expectedEnvironmentFields(evidence);
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!Objects.equals(entry.getValue(), fields.get(entry.getKey()))) {
                errors.add("environment report does not bind " + entry.getKey());
            }
        }
        if (!"true".equals(fields.get("source_clean")) && !"false".equals(fields.get("source_clean"))) {
            errors.add("environment report source_clean must be an explicit boolean");
        }
    }

    /** Structural-only completed-work grammar. It has no route to a live authority. */
    private static void validateCapabilityReport(byte[] bytes, X7BenchmarkEvidence evidence, List<String> errors) {
        String report = decodeUtf8(bytes, "capability report", errors);
        if (report == null || report.isBlank() || report.indexOf('\r') >= 0 || !report.endsWith("\n")) {
            errors.add("capability report must be non-empty canonical LF text");
            return;
        }
        List<String> expected = capabilityFieldOrder();
        String[] lines = report.split("\\n", -1);
        if (lines.length != expected.size() + 1 || !lines[lines.length - 1].isEmpty()) {
            errors.add("capability report has an unexpected bounded field count");
            return;
        }
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (int index = 0; index < expected.size(); index++) {
            String line = lines[index];
            int equals = line.indexOf('=');
            if (equals <= 0 || equals != line.lastIndexOf('=') || !expected.get(index).equals(line.substring(0, equals))
                    || fields.putIfAbsent(expected.get(index), line.substring(equals + 1)) != null) {
                errors.add("capability report has an unknown, duplicate, or out-of-order field");
                return;
            }
        }
        if (!"x7_capability_report_v3".equals(fields.get("format"))
                || !evidence.captureId().equals(fields.get("capture_id"))
                || !equalsLong(fields, "generation_id", evidence.sampling().reloadGeneration(), errors)
                || !evidence.backend().backendClass().name().equals(fields.get("backend_class"))
                || !evidence.backend().backendName().equals(fields.get("backend_name"))
                || !evidence.backend().capability().equals(fields.get("backend_capability"))
                || !evidence.backend().fallbackState().name().equals(fields.get("fallback_state"))) {
            errors.add("capability report does not exactly bind capture generation and backend identity");
            return;
        }
        String producerFallbackReason = validateCompletedBackendIdentity(fields, evidence.backend(), errors);
        if (!errors.isEmpty()) {
            return;
        }
        long first = exactNonNegativeLong(fields, "first_completed_frame_id", errors);
        long last = exactNonNegativeLong(fields, "last_completed_frame_id", errors);
        if (!errors.isEmpty()) {
            return;
        }
        if (last < first || spanInclusive(first, last, "capability report", errors) != P7ReferenceScenario.SAMPLE_FRAME_COUNT) {
            errors.add("capability report completed frame span does not bind exactly 1,800 samples");
            return;
        }
        validateCounterTriples(fields, errors);
        if (!errors.isEmpty()) {
            return;
        }
        requireDelta(fields, "t3_completed_passes", P7ReferenceScenario.SAMPLE_FRAME_COUNT, errors);
        requireDelta(fields, "t3_completed_rigid_submissions",
                (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT * P7ReferenceScenario.RIGID_INSTANCE_COUNT, errors);
        requireDelta(fields, "t3_completed_skinned_submissions",
                (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT * P7ReferenceScenario.SKINNED_INSTANCE_COUNT, errors);
        requireDelta(fields, "t3_completed_draw_work",
                (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT
                        * (P7ReferenceScenario.RIGID_INSTANCE_COUNT + P7ReferenceScenario.SKINNED_INSTANCE_COUNT), errors);
        requireDelta(fields, "t5_submitted_batch_instance_count",
                (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT
                        * (P7ReferenceScenario.RIGID_INSTANCE_COUNT + P7ReferenceScenario.SKINNED_INSTANCE_COUNT), errors);
        requireGenerationUsageStable(fields, errors);
        requireCompletedFrameAnimatedAdmission(fields, errors);
        requireCompletedFrameWorkBounds(fields, errors);
        if (!errors.isEmpty()) {
            return;
        }
        requireT5ActionReconciliation(fields, evidence.backend().backendClass(), producerFallbackReason, errors);
        requireDelta(fields, "backend_completed_passes", P7ReferenceScenario.SAMPLE_FRAME_COUNT, errors);
        long backendFrames = sumDeltas(fields, errors,
                "backend_actual_cpu_frames", "backend_actual_gpu_frames", "backend_actual_fallback_frames");
        if (!errors.isEmpty()) {
            return;
        }
        if (backendFrames != P7ReferenceScenario.SAMPLE_FRAME_COUNT) {
            errors.add("capability report backend frame deltas do not reconcile to every sample");
        }
        requireCompletedBackendRoute(fields, evidence.backend().backendClass(), errors);
    }

    private static List<String> capabilityFieldOrder() {
        List<String> fields = new ArrayList<>();
        fields.addAll(List.of("format", "capture_id", "generation_id", "first_completed_frame_id", "last_completed_frame_id",
                "backend_class", "backend_name", "backend_capability", "fallback_state",
                "completed_backend_route_start", "completed_backend_route_end",
                "completed_active_fallback_reason_start", "completed_active_fallback_reason_end",
                "completed_backend_selection_verified_start", "completed_backend_selection_verified_end"));
        for (String counter : policyCounterNames()) {
            fields.add(counter + "_start");
            fields.add(counter + "_end");
            fields.add(counter + "_delta");
        }
        return List.copyOf(fields);
    }

    private static List<String> policyCounterNames() {
        List<String> counters = new ArrayList<>(80);
        counters.addAll(List.of(
                "t3_completed_passes", "t3_completed_rigid_submissions", "t3_completed_skinned_submissions",
                "t3_completed_draw_work", "t4_completed_gpu_skin_proofs", "t4_completed_fallback_parity_proofs",
                "t4_completed_gpu_skin_work", "t4_completed_cpu_fallback_work", "t5_gpu_candidate_selections",
                "t5_cpu_selections", "t5_capability_unavailable_fallbacks", "t5_prepare_failure_fallbacks",
                "t5_upload_failure_fallbacks", "t5_budget_accepted", "t5_budget_degraded", "t5_budget_cpu_fallback",
                "t5_budget_rejected"));
        for (int level = 0; level < 8; level++) {
            counters.add("t5_actual_lod_selections_level_" + level);
        }
        counters.addAll(List.of(
                "t5_actual_lod_handle_switches", "t5_lod_rejections", "t5_instance_draws", "t5_instance_culls",
                "t5_primitive_draws", "t5_primitive_culls", "t5_bone_draws", "t5_bone_culls",
                "t5_submitted_batch_count", "t5_submitted_batch_instance_count", "t5_submitted_primitive_count",
                "t5_skipped_primitive_count", "t5_skipped_bone_layer_count", "t5_animation_full_executed",
                "t5_animation_reduced_executed", "t5_animation_reuse_pose_executed", "t5_animation_paused_executed",
                "t5_reduced_cadence_advance_skips", "t5_pose_reuse_hits", "t5_captured_skin_reuse_hits",
                "t5_visible_missing_fallbacks", "t5_projection_failures", "t5_generation_models", "t5_generation_primitives",
                "t5_generation_bones", "t5_generation_vertices", "t5_generation_gpu_resource_count",
                "t5_generation_gpu_vertex_bytes", "t5_generation_gpu_index_bytes",
                "t5_completed_frame_requested_animated_instances", "t5_completed_frame_admitted_animated_instances",
                "t5_completed_frame_requested_bone_work", "t5_completed_frame_admitted_bone_work",
                "t5_completed_frame_requested_vertex_work", "t5_completed_frame_admitted_vertex_work",
                "backend_actual_cpu_frames", "backend_actual_gpu_frames", "backend_actual_fallback_frames",
                "backend_completed_passes"));
        return List.copyOf(counters);
    }

    /** The report retains the producer-completed identity at both frozen capture boundaries. */
    private static String validateCompletedBackendIdentity(
            Map<String, String> fields,
            X7BenchmarkEvidence.BackendSelection backend,
            List<String> errors) {
        String route = backend.backendClass().name();
        String verified = Boolean.toString(backend.backendVerified());
        String startReason = fields.get("completed_active_fallback_reason_start");
        String endReason = fields.get("completed_active_fallback_reason_end");
        if (!route.equals(fields.get("completed_backend_route_start"))
                || !route.equals(fields.get("completed_backend_route_end"))
                || !verified.equals(fields.get("completed_backend_selection_verified_start"))
                || !verified.equals(fields.get("completed_backend_selection_verified_end"))) {
            errors.add("capability report completed backend identity drifted from the frozen capture identity");
            return "";
        }
        if (!Set.of("NONE", "CAPABILITY_UNAVAILABLE", "PREPARE_FAILED", "UPLOAD_FAILED").contains(startReason)
                || !startReason.equals(endReason)) {
            errors.add("capability report completed producer fallback reason is not a frozen exact enum value");
            return "";
        }
        boolean nonFallbackRoute = backend.backendClass() != X7BenchmarkEvidence.BackendClass.CPU_FALLBACK;
        if ((nonFallbackRoute && (backend.fallbackState() != X7BenchmarkEvidence.FallbackState.NONE
                || !"NONE".equals(startReason)))
                || (!nonFallbackRoute && (backend.fallbackState()
                        != X7BenchmarkEvidence.FallbackState.CPU_FALLBACK_ACTIVE || "NONE".equals(startReason)))) {
            errors.add("capability report exact producer fallback reason contradicts the frozen backend route");
            return "";
        }
        return startReason;
    }

    /**
     * Reconciles the full frozen T5 action inventory without fabricating geometry totals or a
     * fallback cause unavailable from the upstream producer. These relations mirror the per-frame
     * checks made before the writer accepts a payload.
     */
    private static void requireT5ActionReconciliation(
            Map<String, String> fields,
            X7BenchmarkEvidence.BackendClass backendClass,
            String producerFallbackReason,
            List<String> errors) {
        long submissions = (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT
                * (P7ReferenceScenario.RIGID_INSTANCE_COUNT + P7ReferenceScenario.SKINNED_INSTANCE_COUNT);
        long skinned = (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT * P7ReferenceScenario.SKINNED_INSTANCE_COUNT;
        for (String counter : List.of(
                "t5_budget_accepted", "t5_budget_degraded", "t5_budget_cpu_fallback", "t5_budget_rejected")) {
            requireAtMostDelta(fields, counter, submissions, errors);
        }
        long budgetActions = sumDeltas(fields, errors,
                "t5_budget_accepted", "t5_budget_degraded", "t5_budget_cpu_fallback", "t5_budget_rejected");
        if (!errors.isEmpty()) {
            return;
        }
        if (budgetActions != submissions) {
            errors.add("capability report T5 budget actions do not reconcile to exact P7 submissions");
        }
        String[] lodNames = new String[8];
        for (int level = 0; level < lodNames.length; level++) {
            lodNames[level] = "t5_actual_lod_selections_level_" + level;
            requireAtMostDelta(fields, lodNames[level], submissions, errors);
        }
        long lodSelections = sumDeltas(fields, errors, lodNames);
        if (!errors.isEmpty()) {
            return;
        }
        if (lodSelections != submissions) {
            errors.add("capability report T5 LOD actions do not reconcile to exact P7 submissions");
        }
        long lodChanges = sumDeltas(fields, errors, "t5_actual_lod_handle_switches", "t5_lod_rejections");
        if (!errors.isEmpty()) {
            return;
        }
        if (lodChanges > lodSelections) {
            errors.add("capability report T5 LOD changes exceed the completed selections");
        }
        requireAtMostDelta(fields, "t5_actual_lod_handle_switches", lodSelections, errors);
        requireAtMostDelta(fields, "t5_lod_rejections", lodSelections, errors);
        long instanceActions = sumDeltas(fields, errors, "t5_instance_draws", "t5_instance_culls");
        if (!errors.isEmpty()) {
            return;
        }
        if (instanceActions != submissions) {
            errors.add("capability report T5 instance actions do not reconcile to exact P7 submissions");
        }
        requireAtMostDelta(fields, "t5_instance_draws", submissions, errors);
        requireAtMostDelta(fields, "t5_instance_culls", submissions, errors);
        if (delta(fields, "t5_submitted_batch_count") < P7ReferenceScenario.SAMPLE_FRAME_COUNT) {
            errors.add("capability report t5_submitted_batch_count omits a completed-frame batch action");
        }
        requireAtMostDelta(fields, "t5_submitted_batch_count", submissions, errors);
        requireEqualDelta(fields, "t5_submitted_primitive_count", "t5_primitive_draws", errors);
        requireEqualDelta(fields, "t5_skipped_primitive_count", "t5_primitive_culls", errors);
        requireEqualDelta(fields, "t5_skipped_bone_layer_count", "t5_bone_culls", errors);
        long animationActions = sumDeltas(fields, errors,
                "t5_animation_full_executed", "t5_animation_reduced_executed",
                "t5_animation_reuse_pose_executed", "t5_animation_paused_executed");
        if (!errors.isEmpty()) {
            return;
        }
        if (animationActions != skinned) {
            errors.add("capability report T5 animation actions do not reconcile to exact P7 skinned submissions");
        }
        for (String counter : List.of(
                "t5_animation_full_executed", "t5_animation_reduced_executed",
                "t5_animation_reuse_pose_executed", "t5_animation_paused_executed")) {
            requireAtMostDelta(fields, counter, skinned, errors);
        }
        long reduced = delta(fields, "t5_animation_reduced_executed");
        long reuse = delta(fields, "t5_animation_reuse_pose_executed");
        if (delta(fields, "t5_reduced_cadence_advance_skips") > reduced
                || delta(fields, "t5_pose_reuse_hits") > reuse
                || delta(fields, "t5_captured_skin_reuse_hits") > safeSum(reduced, reuse, errors)) {
            errors.add("capability report T5 animation reuse actions exceed their execution modes");
        }
        if (!errors.isEmpty()) {
            return;
        }
        long visibleMissing = delta(fields, "t5_visible_missing_fallbacks");
        long projectionFailures = delta(fields, "t5_projection_failures");
        long rejected = delta(fields, "t5_budget_rejected");
        if (visibleMissing > submissions || projectionFailures > visibleMissing || rejected > visibleMissing) {
            errors.add("capability report T5 missing-model actions do not reconcile to rejection/failure work");
        }
        requireAtMostDelta(fields, "t5_visible_missing_fallbacks", submissions, errors);
        requireAtMostDelta(fields, "t5_projection_failures", submissions, errors);
        requireFrozenGenerationBackendActions(fields, backendClass, producerFallbackReason, errors);
        if (!errors.isEmpty()) {
            return;
        }
        if (backendClass != X7BenchmarkEvidence.BackendClass.CPU_FALLBACK
                && delta(fields, "t5_budget_cpu_fallback") != 0L) {
            errors.add("capability report T5 fallback actions contradict the frozen non-fallback backend route");
        }
    }

    /**
     * Backend/fallback actions are cumulative before the completed-frame window. They must remain
     * frozen for this generation and retain the collector's CPU-to-concrete-cause relation; a
     * per-frame report must not manufacture either repeated selections or a GPU action for a CPU
     * preparation/upload failure.
     */
    private static void requireFrozenGenerationBackendActions(
            Map<String, String> fields,
            X7BenchmarkEvidence.BackendClass backendClass,
            String producerFallbackReason,
            List<String> errors) {
        List<String> actionCounters = List.of(
                "t5_gpu_candidate_selections", "t5_cpu_selections", "t5_capability_unavailable_fallbacks",
                "t5_prepare_failure_fallbacks", "t5_upload_failure_fallbacks");
        for (String counter : actionCounters) {
            requireDelta(fields, counter, 0L, errors);
        }
        if (!errors.isEmpty()) {
            return;
        }
        long gpuSelections = exactNonNegativeLong(fields, "t5_gpu_candidate_selections_start", errors);
        long cpuSelections = exactNonNegativeLong(fields, "t5_cpu_selections_start", errors);
        long capabilityUnavailable = exactNonNegativeLong(
                fields, "t5_capability_unavailable_fallbacks_start", errors);
        long prepareFailure = exactNonNegativeLong(fields, "t5_prepare_failure_fallbacks_start", errors);
        long uploadFailure = exactNonNegativeLong(fields, "t5_upload_failure_fallbacks_start", errors);
        long knownCpuFallbacks = safeSum(safeSum(capabilityUnavailable, prepareFailure, errors), uploadFailure, errors);
        if (!errors.isEmpty()) {
            return;
        }
        if (cpuSelections != knownCpuFallbacks) {
            errors.add("capability report t5_cpu_selections does not reconcile to concrete producer fallback actions");
            return;
        }
        switch (backendClass) {
            case GPU -> requirePositiveStart(fields, "t5_gpu_candidate_selections", gpuSelections, errors);
            case CPU -> {
                // CPU with NONE is the legacy route, not a synthetic T5 fallback action.
            }
            case CPU_FALLBACK -> {
                requirePositiveStart(fields, "t5_cpu_selections", cpuSelections, errors);
                long matchingFallback = switch (producerFallbackReason) {
                    case "CAPABILITY_UNAVAILABLE" -> capabilityUnavailable;
                    case "PREPARE_FAILED" -> prepareFailure;
                    case "UPLOAD_FAILED" -> uploadFailure;
                    case "NONE" -> {
                        errors.add("capability report CPU fallback lacks an exact producer fallback reason");
                        yield 0L;
                    }
                    default -> {
                        errors.add("capability report has an unknown producer fallback reason");
                        yield 0L;
                    }
                };
                requirePositiveStart(fields, "t5 active producer fallback", matchingFallback, errors);
            }
        }
    }

    /** All T3/T4/backend observations must prove the route selected by the completed producer. */
    private static void requireCompletedBackendRoute(
            Map<String, String> fields,
            X7BenchmarkEvidence.BackendClass backendClass,
            List<String> errors) {
        long gpuProofs = 0L;
        long fallbackProofs = 0L;
        long gpuWork = 0L;
        long cpuFallbackWork = 0L;
        long cpuFrames = 0L;
        long gpuFrames = 0L;
        long fallbackFrames = 0L;
        switch (backendClass) {
            case GPU -> {
                gpuProofs = P7ReferenceScenario.SAMPLE_FRAME_COUNT;
                fallbackProofs = P7ReferenceScenario.SAMPLE_FRAME_COUNT;
                gpuWork = (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT * P7ReferenceScenario.SKINNED_INSTANCE_COUNT;
                gpuFrames = P7ReferenceScenario.SAMPLE_FRAME_COUNT;
            }
            case CPU -> cpuFrames = P7ReferenceScenario.SAMPLE_FRAME_COUNT;
            case CPU_FALLBACK -> {
                fallbackProofs = P7ReferenceScenario.SAMPLE_FRAME_COUNT;
                cpuFallbackWork = (long) P7ReferenceScenario.SAMPLE_FRAME_COUNT * P7ReferenceScenario.SKINNED_INSTANCE_COUNT;
                fallbackFrames = P7ReferenceScenario.SAMPLE_FRAME_COUNT;
            }
        }
        requireDelta(fields, "t4_completed_gpu_skin_proofs", gpuProofs, errors);
        requireDelta(fields, "t4_completed_fallback_parity_proofs", fallbackProofs, errors);
        requireDelta(fields, "t4_completed_gpu_skin_work", gpuWork, errors);
        requireDelta(fields, "t4_completed_cpu_fallback_work", cpuFallbackWork, errors);
        requireDelta(fields, "backend_actual_cpu_frames", cpuFrames, errors);
        requireDelta(fields, "backend_actual_gpu_frames", gpuFrames, errors);
        requireDelta(fields, "backend_actual_fallback_frames", fallbackFrames, errors);
    }

    private static void requireGenerationUsageStable(Map<String, String> fields, List<String> errors) {
        for (String name : List.of(
                "t5_generation_models", "t5_generation_primitives", "t5_generation_bones", "t5_generation_vertices",
                "t5_generation_gpu_resource_count", "t5_generation_gpu_vertex_bytes", "t5_generation_gpu_index_bytes")) {
            requireDelta(fields, name, 0L, errors);
        }
    }

    private static void requireCompletedFrameAnimatedAdmission(Map<String, String> fields, List<String> errors) {
        long expected = P7ReferenceScenario.SKINNED_INSTANCE_COUNT;
        for (String name : List.of(
                "t5_completed_frame_requested_animated_instances",
                "t5_completed_frame_admitted_animated_instances")) {
            long start = exactNonNegativeLong(fields, name + "_start", errors);
            long end = exactNonNegativeLong(fields, name + "_end", errors);
            if (start != expected || end != expected || delta(fields, name) != 0L) {
                errors.add("capability report " + name + " is not bound to exact completed P7 skinned admission");
            }
        }
    }

    /** Completed-frame work is frame-local: validate each retained endpoint independently. */
    private static void requireCompletedFrameWorkBounds(Map<String, String> fields, List<String> errors) {
        requireCompletedFrameWorkEndpoint(fields, "start", errors);
        requireCompletedFrameWorkEndpoint(fields, "end", errors);
    }

    private static void requireCompletedFrameWorkEndpoint(
            Map<String, String> fields,
            String endpoint,
            List<String> errors) {
        long requestedBone = exactNonNegativeLong(
                fields, "t5_completed_frame_requested_bone_work_" + endpoint, errors);
        long admittedBone = exactNonNegativeLong(
                fields, "t5_completed_frame_admitted_bone_work_" + endpoint, errors);
        long requestedVertex = exactNonNegativeLong(
                fields, "t5_completed_frame_requested_vertex_work_" + endpoint, errors);
        long admittedVertex = exactNonNegativeLong(
                fields, "t5_completed_frame_admitted_vertex_work_" + endpoint, errors);
        if (admittedBone > requestedBone || admittedVertex > requestedVertex) {
            errors.add("capability report " + endpoint + " completed-frame work over-admits producer requested work");
        }
    }

    private static void requirePositiveStart(
            Map<String, String> fields,
            String counter,
            long value,
            List<String> errors) {
        if (value <= 0L) {
            errors.add("capability report " + counter + " lacks the frozen producer action");
        }
    }

    private static void requireEqualDelta(
            Map<String, String> fields,
            String counter,
            String action,
            List<String> errors) {
        if (delta(fields, counter) != delta(fields, action)) {
            errors.add("capability report " + counter + " does not reconcile to " + action);
        }
    }

    private static void requireAtMostDelta(
            Map<String, String> fields,
            String counter,
            long maximum,
            List<String> errors) {
        if (delta(fields, counter) > maximum) {
            errors.add("capability report " + counter + " exceeds its exact completed-action bound");
        }
    }

    private static long sumDeltas(Map<String, String> fields, List<String> errors, String... counters) {
        long total = 0L;
        try {
            for (String counter : counters) {
                total = Math.addExact(total, delta(fields, counter));
            }
            return total;
        } catch (ArithmeticException exception) {
            errors.add("capability report counter aggregate overflowed");
            return -1L;
        }
    }

    private static long safeSum(long first, long second, List<String> errors) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            errors.add("capability report counter aggregate overflowed");
            return -1L;
        }
    }

    private static void validateCounterTriples(Map<String, String> fields, List<String> errors) {
        for (String counter : policyCounterNames()) {
            long start = exactNonNegativeLong(fields, counter + "_start", errors);
            long end = exactNonNegativeLong(fields, counter + "_end", errors);
            long declared = exactNonNegativeLong(fields, counter + "_delta", errors);
            if (!errors.isEmpty()) {
                return;
            }
            try {
                if (Math.subtractExact(end, start) != declared) {
                    errors.add("capability report " + counter + " start/end/delta is inconsistent");
                    return;
                }
            } catch (ArithmeticException exception) {
                errors.add("capability report " + counter + " delta overflowed");
                return;
            }
        }
    }

    private static void requireDelta(Map<String, String> fields, String counter, long expected, List<String> errors) {
        if (delta(fields, counter) != expected) {
            errors.add("capability report " + counter + " does not reconcile to the frozen capture window");
        }
    }

    private static long delta(Map<String, String> fields, String counter) {
        return Long.parseLong(fields.get(counter + "_delta"));
    }

    /** Owner receipt is hostile parsed only; it cannot construct a live authority or token. */
    private static void validateOwnerReceipt(
            byte[] bytes,
            X7BenchmarkEvidence evidence,
            X7BenchmarkEvidence.Artifact receiptArtifact,
            List<String> errors) {
        final Object parsed;
        try {
            parsed = X7BenchmarkEvidenceCodec.parseStrictJsonUtf8(bytes, 24, 8);
        } catch (X7BenchmarkEvidenceCodec.X7EvidenceFormatException exception) {
            errors.add("owner receipt is not bounded strict JSON: " + exception.getMessage());
            return;
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            errors.add("owner receipt must be a JSON object");
            return;
        }
        Map<String, Object> receipt = stringObject(raw, "owner receipt", errors);
        if (receipt == null) {
            return;
        }
        List<String> expected = List.of(
                "format", "capture_id", "owner_epoch", "measurement_session_epoch", "owner_render_thread_id",
                "owner_render_thread_name", "source_commit", "source_tree", "source_clean", "warmup_frames",
                "sample_frames", "rigid_submits_per_sample", "skinned_submits_per_sample", "generation_id",
                "first_completed_frame_id", "last_completed_frame_id", "started_at_utc", "ended_at_utc", "jfr_file",
                "artifacts", "terminal");
        if (!new ArrayList<>(receipt.keySet()).equals(expected)) {
            errors.add("owner receipt has unknown, missing, duplicate, or out-of-order fields");
            return;
        }
        if (!"x7-p7-live-owner-receipt-v1".equals(receipt.get("format"))
                || !evidence.captureId().equals(receipt.get("capture_id"))
                || !evidence.sourceRevision().commit().equals(receipt.get("source_commit"))
                || !evidence.sourceRevision().tree().equals(receipt.get("source_tree"))
                || !Boolean.TRUE.equals(receipt.get("source_clean"))
                || !"allocation.jfr".equals(receipt.get("jfr_file"))
                || !"SEALED_WAITING".equals(receipt.get("terminal"))) {
            errors.add("owner receipt has an invalid fixed source/capture binding");
            return;
        }
        requireJsonLong(receipt, "owner_epoch", 1L, Long.MAX_VALUE, errors);
        requireJsonLong(receipt, "measurement_session_epoch", 1L, Long.MAX_VALUE, errors);
        requireJsonLong(receipt, "owner_render_thread_id", 1L, Long.MAX_VALUE, errors);
        requireJsonLong(receipt, "warmup_frames", P7ReferenceScenario.WARMUP_FRAME_COUNT,
                P7ReferenceScenario.WARMUP_FRAME_COUNT, errors);
        requireJsonLong(receipt, "sample_frames", P7ReferenceScenario.SAMPLE_FRAME_COUNT,
                P7ReferenceScenario.SAMPLE_FRAME_COUNT, errors);
        requireJsonLong(receipt, "rigid_submits_per_sample", P7ReferenceScenario.RIGID_INSTANCE_COUNT,
                P7ReferenceScenario.RIGID_INSTANCE_COUNT, errors);
        requireJsonLong(receipt, "skinned_submits_per_sample", P7ReferenceScenario.SKINNED_INSTANCE_COUNT,
                P7ReferenceScenario.SKINNED_INSTANCE_COUNT, errors);
        requireJsonLong(receipt, "generation_id", evidence.sampling().reloadGeneration(), evidence.sampling().reloadGeneration(), errors);
        long first = jsonLong(receipt.get("first_completed_frame_id"), "first_completed_frame_id", errors);
        long last = jsonLong(receipt.get("last_completed_frame_id"), "last_completed_frame_id", errors);
        if (!errors.isEmpty()) {
            return;
        }
        if (first < 0L || last < first
                || spanInclusive(first, last, "owner receipt", errors) != P7ReferenceScenario.SAMPLE_FRAME_COUNT) {
            errors.add("owner receipt frame range does not bind the frozen 1,800 samples");
            return;
        }
        if (!(receipt.get("owner_render_thread_name") instanceof String name) || name.isBlank()
                || !(receipt.get("started_at_utc") instanceof String started)
                || !(receipt.get("ended_at_utc") instanceof String ended)) {
            errors.add("owner receipt has invalid owner/timestamp field types");
            return;
        }
        try {
            if (!java.time.Instant.parse(ended).isAfter(java.time.Instant.parse(started))) {
                errors.add("owner receipt timestamps are not strictly ordered");
                return;
            }
        } catch (RuntimeException exception) {
            errors.add("owner receipt timestamps are not canonical ISO instants");
            return;
        }
        if (!(receipt.get("artifacts") instanceof List<?> artifacts) || artifacts.size() != 7) {
            errors.add("owner receipt must retain exactly seven pre-receipt artifact bindings");
            return;
        }
        List<X7BenchmarkEvidence.Artifact> payloadArtifacts = evidence.artifactManifest().entries().stream()
                .filter(artifact -> artifact.kind() != X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT)
                .sorted(java.util.Comparator.comparingInt(artifact -> receiptPayloadOrder(artifact.kind())))
                .toList();
        if (payloadArtifacts.size() != 7 || receiptArtifact.path().contains("artifact-manifest")) {
            errors.add("owner receipt attempted an invalid self-binding");
            return;
        }
        for (int index = 0; index < artifacts.size(); index++) {
            if (!(artifacts.get(index) instanceof Map<?, ?> rawArtifact)) {
                errors.add("owner receipt artifact binding is not an object");
                return;
            }
            Map<String, Object> artifact = stringObject(rawArtifact, "owner receipt artifact", errors);
            if (artifact == null || !new ArrayList<>(artifact.keySet()).equals(List.of("key", "path", "kind", "sha256", "byte_count"))) {
                errors.add("owner receipt artifact binding has unknown or missing fields");
                return;
            }
            X7BenchmarkEvidence.Artifact expectedArtifact = payloadArtifacts.get(index);
            if (!expectedArtifact.key().equals(artifact.get("key")) || !expectedArtifact.path().equals(artifact.get("path"))
                    || !expectedArtifact.kind().name().equals(artifact.get("kind")) || !expectedArtifact.sha256().equals(artifact.get("sha256"))
                    || jsonLong(artifact.get("byte_count"), "owner receipt artifact byte_count", errors) != expectedArtifact.byteCount()) {
                errors.add("owner receipt artifact hashes/counts do not exactly bind the manifest");
                return;
            }
        }
    }

    private static int receiptPayloadOrder(X7BenchmarkEvidence.ArtifactKind kind) {
        return switch (kind) {
            case P7_CAPTURE_REPORT -> 0;
            case P7_RAW_SAMPLES -> 1;
            case JFR_ALLOCATION -> 2;
            case CAPTURE_LOG -> 3;
            case ENVIRONMENT_REPORT -> 4;
            case CAPABILITY_REPORT -> 5;
            case SCREENSHOT -> 6;
            case OWNER_RECEIPT -> Integer.MAX_VALUE;
        };
    }

    private static Map<String, Object> stringObject(Map<?, ?> raw, String name, List<String> errors) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || result.putIfAbsent(key, entry.getValue()) != null) {
                errors.add(name + " has a non-string or duplicate key");
                return null;
            }
        }
        return result;
    }

    private static void requireJsonLong(Map<String, Object> values, String key, long minimum, long maximum, List<String> errors) {
        long value = jsonLong(values.get(key), key, errors);
        if (!errors.isEmpty() || value < minimum || value > maximum) {
            if (errors.isEmpty()) {
                errors.add("owner receipt " + key + " is outside its exact range");
            }
        }
    }

    private static long jsonLong(Object value, String name, List<String> errors) {
        if (!(value instanceof BigDecimal number)) {
            errors.add("owner receipt " + name + " must be an exact integer");
            return -1L;
        }
        try {
            long result = number.longValueExact();
            if (!Long.toString(result).equals(number.toPlainString())) {
                errors.add("owner receipt " + name + " must use a canonical integer token");
                return -1L;
            }
            return result;
        } catch (ArithmeticException exception) {
            errors.add("owner receipt " + name + " is outside the signed-long range");
            return -1L;
        }
    }

    private static boolean equalsLong(Map<String, String> fields, String key, long expected, List<String> errors) {
        return exactNonNegativeLong(fields, key, errors) == expected && errors.isEmpty();
    }

    private static long exactNonNegativeLong(Map<String, String> fields, String key, List<String> errors) {
        String text = fields.get(key);
        if (text == null) {
            errors.add("capability report is missing " + key);
            return -1L;
        }
        try {
            long value = Long.parseLong(text);
            if (value < 0L || !Long.toString(value).equals(text)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            errors.add("capability report " + key + " must be a canonical non-negative long");
            return -1L;
        }
    }

    private static long spanInclusive(long first, long last, String name, List<String> errors) {
        try {
            return Math.addExact(Math.subtractExact(last, first), 1L);
        } catch (ArithmeticException exception) {
            errors.add(name + " completed frame span overflowed");
            return -1L;
        }
    }

    private static Map<String, String> expectedEnvironmentFields(X7BenchmarkEvidence evidence) {
        X7BenchmarkEvidence.RuntimeEnvironment environment = evidence.environment();
        X7BenchmarkEvidence.BackendSelection backend = evidence.backend();
        Map<String, String> expected = new HashMap<>();
        expected.put("format", "x7_environment_report_v1");
        expected.put("capture_class", evidence.captureClass().name());
        expected.put("java_version", environment.javaVersion());
        expected.put("os_name", environment.osName());
        expected.put("os_version", environment.osVersion());
        expected.put("minecraft_version", environment.minecraftVersion());
        expected.put("fabric_loader_version", environment.fabricLoaderVersion());
        expected.put("fabric_api_version", environment.fabricApiVersion());
        expected.put("gpu_name", environment.gpuName());
        expected.put("gpu_vendor", environment.gpuVendor());
        expected.put("gpu_driver", environment.gpuDriver());
        expected.put("shaderpack", environment.shaderpack());
        expected.put("iris_status", environment.irisStatus());
        expected.put("sodium_status", environment.sodiumStatus());
        expected.put("backend_class", backend.backendClass().name());
        expected.put("backend_name", backend.backendName());
        expected.put("backend_capability", backend.capability());
        expected.put("fallback_state", backend.fallbackState().name());
        expected.put("source_commit", evidence.sourceRevision().commit());
        expected.put("source_tree", evidence.sourceRevision().tree());
        return expected;
    }

    private static String decodeUtf8(byte[] bytes, String name, List<String> errors) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            errors.add(name + " is not well-formed UTF-8");
            return null;
        }
    }

    private static boolean metricsEqual(X7BenchmarkEvidence.Metrics left, X7BenchmarkEvidence.Metrics right) {
        return percentilesEqual(left.fps(), right.fps())
                && percentilesEqual(left.frameTimeMillis(), right.frameTimeMillis())
                && percentilesEqual(left.cpuRenderMillis(), right.cpuRenderMillis())
                && percentilesEqual(left.allocationBytes(), right.allocationBytes());
    }

    private static boolean percentilesEqual(
            X7BenchmarkEvidence.Percentiles left, X7BenchmarkEvidence.Percentiles right) {
        return Double.doubleToLongBits(left.p50()) == Double.doubleToLongBits(right.p50())
                && Double.doubleToLongBits(left.p95()) == Double.doubleToLongBits(right.p95())
                && Double.doubleToLongBits(left.p99()) == Double.doubleToLongBits(right.p99());
    }

    private record HardwareArtifactValidation(List<String> errors, X7RawP7Samples.RawSamples rawSamples) {
    }

    public enum Status {
        INVALID,
        STRUCTURALLY_VALID_WAITING
    }

    /** Opaque, root/evidence-bound structural result with no hardware-eligibility state. */
    public static final class ValidationResult {
        private final Status status;
        private final List<String> reasons;
        private final String evidenceDigest;
        private final Path root;
        private final X7ArtifactVerifier.ArtifactVerification verification;

        private ValidationResult(
                Status status,
                List<String> reasons,
                String evidenceDigest,
                Path root,
                X7ArtifactVerifier.ArtifactVerification verification) {
            this.status = Objects.requireNonNull(status, "status");
            this.reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            this.evidenceDigest = evidenceDigest;
            this.root = root;
            this.verification = verification;
            if (status == Status.STRUCTURALLY_VALID_WAITING
                    && (evidenceDigest == null || root == null || verification == null)) {
                throw new IllegalArgumentException("a structural waiting result requires an evidence binding");
            }
            if (status == Status.INVALID && (evidenceDigest != null || root != null || verification != null)) {
                throw new IllegalArgumentException("an invalid result cannot retain a structural evidence binding");
            }
        }

        private static ValidationResult invalid(List<String> reasons) {
            return new ValidationResult(Status.INVALID, reasons, null, null, null);
        }

        private static ValidationResult structurallyValidWaiting(
                List<String> reasons,
                X7BenchmarkEvidence evidence,
                Path root,
                X7ArtifactVerifier.ArtifactVerification verification) {
            return bound(Status.STRUCTURALLY_VALID_WAITING, reasons, evidence, root, verification);
        }

        private static ValidationResult bound(
                Status status,
                List<String> reasons,
                X7BenchmarkEvidence evidence,
                Path root,
                X7ArtifactVerifier.ArtifactVerification verification) {
            return new ValidationResult(status, reasons,
                    X7BenchmarkEvidenceCodec.canonicalSha256(evidence), root.toAbsolutePath().normalize(), verification);
        }

        public Status status() {
            return status;
        }

        public List<String> reasons() {
            return reasons;
        }

        /** Re-checks a structural waiting token against the current root before diagnostics. */
        boolean attests(X7BenchmarkEvidence evidence, Path artifactRoot) {
            if (status != Status.STRUCTURALLY_VALID_WAITING
                    || evidenceDigest == null || root == null || verification == null) {
                return false;
            }
            try {
                return root.equals(artifactRoot.toAbsolutePath().normalize())
                        && evidenceDigest.equals(X7BenchmarkEvidenceCodec.canonicalSha256(evidence))
                        && verification.attests(evidence, artifactRoot);
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

}
