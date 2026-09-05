package com.liy.blendlib.showcase.perf.x7;

import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Versioned, offline-only evidence envelope for the X7 benchmark tooling.
 *
 * <p>This is deliberately a description of a capture and its retained artifacts. It has no
 * renderer registration, no Minecraft type, no file I/O, and no field through which a capture can
 * promote itself to a performance or visual Gate pass. The frozen workload is projected from
 * {@link P7ReferenceScenario} so X7 cannot quietly create a smaller second benchmark target.</p>
 */
public record X7BenchmarkEvidence(
        int schemaVersion,
        CaptureClass captureClass,
        GateStatus gateStatus,
        String captureId,
        SourceRevision sourceRevision,
        FrozenScenario scenario,
        RuntimeEnvironment environment,
        BackendSelection backend,
        Sampling sampling,
        Metrics metrics,
        String allocationEvidenceKey,
        ArtifactManifest artifactManifest,
        List<String> requiredExtensions,
        List<String> optionalExtensions) {
    /** The only schema version accepted by this initial fail-closed tool. */
    public static final int SCHEMA_VERSION = 1;
    /** Sentinel used when an observed identity is genuinely unavailable. */
    public static final String UNKNOWN = "UNKNOWN";

    public X7BenchmarkEvidence {
        captureClass = Objects.requireNonNull(captureClass, "captureClass");
        gateStatus = Objects.requireNonNull(gateStatus, "gateStatus");
        captureId = requireText(captureId, "captureId");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        scenario = Objects.requireNonNull(scenario, "scenario");
        environment = Objects.requireNonNull(environment, "environment");
        backend = Objects.requireNonNull(backend, "backend");
        sampling = Objects.requireNonNull(sampling, "sampling");
        metrics = Objects.requireNonNull(metrics, "metrics");
        allocationEvidenceKey = requireText(allocationEvidenceKey, "allocationEvidenceKey");
        ArtifactManifest suppliedManifest = Objects.requireNonNull(artifactManifest, "artifactManifest");
        artifactManifest = new ArtifactManifest(suppliedManifest.manifestPath(), suppliedManifest.canonicalEntries());
        requiredExtensions = canonicalStrings(requiredExtensions, "requiredExtensions");
        optionalExtensions = canonicalStrings(optionalExtensions, "optionalExtensions");
    }

    /** Returns the exact immutable P7 scenario identity that every X7 capture must preserve. */
    public static FrozenScenario frozenScenario() {
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();
        P7ReferenceScenario.Asset rigid = scenario.assets().stream()
                .filter(asset -> asset.kind() == P7ReferenceScenario.Kind.RIGID)
                .findFirst()
                .orElseThrow();
        P7ReferenceScenario.Asset skinned = scenario.assets().stream()
                .filter(asset -> asset.kind() == P7ReferenceScenario.Kind.SKINNED)
                .findFirst()
                .orElseThrow();
        return new FrozenScenario(
                P7ReferenceScenario.FORMAT,
                rigid.modelKey(),
                skinned.modelKey(),
                P7ReferenceScenario.RIGID_INSTANCE_COUNT,
                P7ReferenceScenario.RIGID_TRIANGLES_PER_INSTANCE,
                Math.multiplyExact(P7ReferenceScenario.RIGID_TRIANGLES_PER_INSTANCE, 3),
                P7ReferenceScenario.SKINNED_INSTANCE_COUNT,
                P7ReferenceScenario.SKINNED_TRIANGLES_PER_INSTANCE,
                Math.multiplyExact(P7ReferenceScenario.SKINNED_TRIANGLES_PER_INSTANCE, 3),
                P7ReferenceScenario.SKINNED_JOINTS_PER_INSTANCE,
                scenario.totalTriangleCount());
    }

    /** Capture origin classes; only a verified real hardware client capture can be compared as hardware evidence. */
    public enum CaptureClass {
        HARDWARE_CAPTURE,
        CPU_CAPTURE,
        SYNTHETIC_TEST_ONLY,
        UNIT_TEST,
        SOURCE_REPLAY,
        NO_SHADER_SMOKE
    }

    /** Captures stay waiting for the larger X7 Gate; this schema has no self-issued PASS value. */
    public enum GateStatus {
        WAITING
    }

    /** Backend family used for compatibility checks. */
    public enum BackendClass {
        CPU,
        GPU,
        CPU_FALLBACK
    }

    /** Explicit fallback state instead of inferring it from a backend name. */
    public enum FallbackState {
        NONE,
        CPU_FALLBACK_ACTIVE,
        UNKNOWN
    }

    /** Artifact kinds accepted by the initial schema. */
    public enum ArtifactKind {
        JFR_ALLOCATION,
        P7_CAPTURE_REPORT,
        /** Canonical per-frame P7 samples from which every X7 percentile is recomputed. */
        P7_RAW_SAMPLES,
        CAPTURE_LOG,
        SCREENSHOT,
        ENVIRONMENT_REPORT,
        /** Completed-frame backend/pass/resource observations; never a requested-policy label. */
        CAPABILITY_REPORT,
        /** Live owner/session/source binding retained after all seven payload hashes are final. */
        OWNER_RECEIPT
    }

    public record SourceRevision(String commit, String tree) {
        public SourceRevision {
            commit = requireText(commit, "commit");
            tree = requireText(tree, "tree");
        }
    }

    /** Exact P7 workload identity, including the generated vertex counts rather than a reduced fixture. */
    public record FrozenScenario(
            String scenarioFormat,
            String rigidModelKey,
            String skinnedModelKey,
            int rigidInstances,
            int rigidTrianglesPerInstance,
            int rigidVerticesPerInstance,
            int skinnedInstances,
            int skinnedTrianglesPerInstance,
            int skinnedVerticesPerInstance,
            int skinnedJointsPerInstance,
            int totalTriangles) {
        public FrozenScenario {
            scenarioFormat = requireText(scenarioFormat, "scenarioFormat");
            rigidModelKey = requireText(rigidModelKey, "rigidModelKey");
            skinnedModelKey = requireText(skinnedModelKey, "skinnedModelKey");
        }
    }

    /** Required runtime identity. UNKNOWN is allowed only for an explicitly unverified environment. */
    public record RuntimeEnvironment(
            String javaVersion,
            String osName,
            String osVersion,
            String minecraftVersion,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String gpuName,
            String gpuVendor,
            String gpuDriver,
            String shaderpack,
            String irisStatus,
            String sodiumStatus,
            boolean environmentVerified) {
        public RuntimeEnvironment {
            javaVersion = canonicalText(javaVersion, "javaVersion");
            osName = canonicalText(osName, "osName");
            osVersion = canonicalText(osVersion, "osVersion");
            minecraftVersion = canonicalText(minecraftVersion, "minecraftVersion");
            fabricLoaderVersion = canonicalText(fabricLoaderVersion, "fabricLoaderVersion");
            fabricApiVersion = canonicalText(fabricApiVersion, "fabricApiVersion");
            gpuName = canonicalText(gpuName, "gpuName");
            gpuVendor = canonicalText(gpuVendor, "gpuVendor");
            gpuDriver = canonicalText(gpuDriver, "gpuDriver");
            shaderpack = canonicalText(shaderpack, "shaderpack");
            irisStatus = canonicalText(irisStatus, "irisStatus");
            sodiumStatus = canonicalText(sodiumStatus, "sodiumStatus");
        }

        /** True only when all identity fields required for a hardware comparison are known and attested. */
        public boolean hasVerifiedHardwareIdentity() {
            return environmentVerified
                    && isKnown(gpuName)
                    && isKnown(gpuVendor)
                    && isKnown(gpuDriver)
                    && isKnown(javaVersion)
                    && isKnown(osName)
                    && isKnown(osVersion)
                    && isKnown(minecraftVersion)
                    && isKnown(fabricLoaderVersion)
                    && isKnown(fabricApiVersion)
                    && isKnown(shaderpack)
                    && isKnown(irisStatus)
                    && isKnown(sodiumStatus);
        }
    }

    public record BackendSelection(
            BackendClass backendClass,
            String backendName,
            String capability,
            FallbackState fallbackState,
            boolean backendVerified) {
        public BackendSelection {
            backendClass = Objects.requireNonNull(backendClass, "backendClass");
            backendName = canonicalText(backendName, "backendName");
            capability = canonicalText(capability, "capability");
            fallbackState = Objects.requireNonNull(fallbackState, "fallbackState");
        }
    }

    /** Counts recorded alongside a capture; the validator requires the frozen P7 600/1800 window. */
    public record Sampling(int warmupFrames, int sampleFrames, long reloadGeneration) {
    }

    /** Ordered p50/p95/p99 values in one explicit unit. */
    public record Percentiles(double p50, double p95, double p99) {
        public boolean isFiniteNonNegativeAndOrdered() {
            return Double.isFinite(p50)
                    && Double.isFinite(p95)
                    && Double.isFinite(p99)
                    && p50 >= 0.0d
                    && p95 >= p50
                    && p99 >= p95;
        }

        /**
         * Canonical evidence has one zero representation. Negative zero would serialize as zero
         * and therefore cannot round-trip as an equal record value.
         */
        public boolean hasNegativeZero() {
            return isNegativeZero(p50) || isNegativeZero(p95) || isNegativeZero(p99);
        }

        private static boolean isNegativeZero(double value) {
            return Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0d);
        }
    }

    /** FPS, frame-time milliseconds, CPU render milliseconds, and allocation bytes. */
    public record Metrics(
            Percentiles fps,
            Percentiles frameTimeMillis,
            Percentiles cpuRenderMillis,
            Percentiles allocationBytes) {
        public Metrics {
            fps = Objects.requireNonNull(fps, "fps");
            frameTimeMillis = Objects.requireNonNull(frameTimeMillis, "frameTimeMillis");
            cpuRenderMillis = Objects.requireNonNull(cpuRenderMillis, "cpuRenderMillis");
            allocationBytes = Objects.requireNonNull(allocationBytes, "allocationBytes");
        }
    }

    /** Path/hash/byte-count triplet retained in the self-excluding artifact manifest. */
    public record Artifact(String key, String path, String sha256, long byteCount, ArtifactKind kind) {
        public Artifact {
            key = requireText(key, "key");
            path = requireText(path, "path");
            sha256 = requireText(sha256, "sha256");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    /** The manifest must never include its own manifestPath as one of its artifacts. */
    public record ArtifactManifest(String manifestPath, List<Artifact> entries) {
        public ArtifactManifest {
            manifestPath = requireText(manifestPath, "manifestPath");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }

        /** Stable entry order used by canonical JSON and manifest hashes. */
        public List<Artifact> canonicalEntries() {
            return entries.stream().sorted(Comparator.comparing(Artifact::key)).toList();
        }
    }

    static boolean isKnown(String value) {
        return value != null && !value.trim().isEmpty() && !UNKNOWN.equals(value.trim().toUpperCase(Locale.ROOT));
    }

    static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        requireWellFormedUnicode(checked, name);
        return checked;
    }

    private static String canonicalText(String value, String name) {
        String checked = requireText(value, name).trim();
        return UNKNOWN.equalsIgnoreCase(checked) ? UNKNOWN : checked;
    }

    static void requireWellFormedUnicode(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains an unpaired high surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " contains an unpaired low surrogate");
            }
        }
    }

    private static List<String> canonicalStrings(List<String> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name)).stream().sorted().toList();
    }
}
