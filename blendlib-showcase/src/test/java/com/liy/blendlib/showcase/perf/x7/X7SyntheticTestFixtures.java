package com.liy.blendlib.showcase.perf.x7;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/** Test-only fixture factory. No fixture can receive an owner-sealed hardware receipt. */
final class X7SyntheticTestFixtures {
    static final String MARKER = "SYNTHETIC_TEST_ONLY";
    static final String COMMIT = "991d5477d67114c0a3b9676a0411771c781ca140";
    static final String TREE = "c6fb2397a39108962eca6b02b150a696e32c03a9";

    private X7SyntheticTestFixtures() {
    }

    static Fixture cpuCapture(Path root) throws IOException {
        return capture(root, X7BenchmarkEvidence.CaptureClass.CPU_CAPTURE, environment(false, X7BenchmarkEvidence.UNKNOWN),
                new X7BenchmarkEvidence.BackendSelection(
                        X7BenchmarkEvidence.BackendClass.CPU,
                        "cpu-submit",
                        "CPU_BASELINE",
                        X7BenchmarkEvidence.FallbackState.NONE,
                        true));
    }

    static Fixture syntheticCapture(Path root) throws IOException {
        return capture(root, X7BenchmarkEvidence.CaptureClass.SYNTHETIC_TEST_ONLY,
                environment(false, X7BenchmarkEvidence.UNKNOWN),
                new X7BenchmarkEvidence.BackendSelection(
                        X7BenchmarkEvidence.BackendClass.CPU,
                        "cpu-submit",
                        "CPU_BASELINE",
                        X7BenchmarkEvidence.FallbackState.NONE,
                        false));
    }

    static Fixture unverifiedHardwareCapture(Path root) throws IOException {
        return capture(root, X7BenchmarkEvidence.CaptureClass.HARDWARE_CAPTURE,
                environment(false, X7BenchmarkEvidence.UNKNOWN),
                new X7BenchmarkEvidence.BackendSelection(
                        X7BenchmarkEvidence.BackendClass.GPU,
                        "gpu-skinning",
                        "GPU_SKINNING_V1",
                        X7BenchmarkEvidence.FallbackState.NONE,
                        true));
    }

    /** A complete-shaped package whose retained report/JFR/log still explicitly prove synthetic origin. */
    static Fixture relabeledSyntheticHardwareCapture(Path root) throws IOException {
        X7BenchmarkEvidence.RuntimeEnvironment environment = new X7BenchmarkEvidence.RuntimeEnvironment(
                "25.0.2", "Windows " + MARKER, "11", "26.1.2", "0.19.3", "0.154.2+26.1.2",
                "RTX synthetic", "NVIDIA", "555.55", "NONE", "NOT_PRESENT", "NOT_PRESENT", true);
        return hardwarePackage(root, environment, gpuBackend(), true);
    }

    /** A fully structured, hash-bound local fixture that still remains WAITING without trusted traversal/receipt. */
    static Fixture wellFormedButUntrustedHardwareCapture(Path root) throws IOException {
        X7BenchmarkEvidence.RuntimeEnvironment environment = new X7BenchmarkEvidence.RuntimeEnvironment(
                "25.0.2", "Windows", "11", "26.1.2", "0.19.3", "0.154.2+26.1.2",
                "RTX test fixture", "NVIDIA", "555.55", "NONE", "NOT_PRESENT", "NOT_PRESENT", true);
        return hardwarePackage(root, environment, gpuBackend(), false);
    }

    private static Fixture hardwarePackage(
            Path root,
            X7BenchmarkEvidence.RuntimeEnvironment environment,
            X7BenchmarkEvidence.BackendSelection backend,
            boolean syntheticMarker) throws IOException {
        Files.createDirectories(root);
        Path report = root.resolve("p7-report.json");
        Path raw = root.resolve("raw-p7-samples.txt");
        Path jfr = root.resolve("allocation.jfr");
        Path log = root.resolve("capture.log");
        Path environmentFile = root.resolve("environment.txt");
        Path capability = root.resolve("capability.txt");
        Path screenshot = root.resolve("scene.png");
        String reportText = syntheticMarker
                ? completeP7Report().replace("Offline fixture remains untrusted.", MARKER)
                : completeP7Report();
        Files.writeString(report, reportText, StandardCharsets.UTF_8);
        Files.write(raw, canonicalRawSamples());
        if (syntheticMarker) {
            Files.write(jfr, (MARKER + " plain text fake JFR").getBytes(StandardCharsets.UTF_8));
        } else {
            writeRealJfr(jfr);
        }
        Files.writeString(log, syntheticMarker ? MARKER + " capture log\n" : "completed local capture fixture\n", StandardCharsets.UTF_8);
        Files.writeString(environmentFile,
                environmentReport(environment, backend, X7BenchmarkEvidence.CaptureClass.HARDWARE_CAPTURE), StandardCharsets.UTF_8);
        Files.writeString(capability, capabilityReport(), StandardCharsets.UTF_8);
        if (syntheticMarker) {
            Files.write(screenshot, pngWithMarker());
        } else {
            writeCompletePng(screenshot);
        }
        List<X7BenchmarkEvidence.Artifact> payloadArtifacts = List.of(
                artifact("p7-capture", "p7-report.json", report, X7BenchmarkEvidence.ArtifactKind.P7_CAPTURE_REPORT),
                artifact("raw-p7-samples", "raw-p7-samples.txt", raw, X7BenchmarkEvidence.ArtifactKind.P7_RAW_SAMPLES),
                artifact("jfr-allocation", "allocation.jfr", jfr, X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION),
                artifact("capture-log", "capture.log", log, X7BenchmarkEvidence.ArtifactKind.CAPTURE_LOG),
                artifact("environment", "environment.txt", environmentFile,
                        X7BenchmarkEvidence.ArtifactKind.ENVIRONMENT_REPORT),
                artifact("capability", "capability.txt", capability, X7BenchmarkEvidence.ArtifactKind.CAPABILITY_REPORT),
                artifact("screenshot", "scene.png", screenshot, X7BenchmarkEvidence.ArtifactKind.SCREENSHOT));
        Path receipt = root.resolve("owner-receipt.json");
        Files.writeString(receipt, ownerReceipt(payloadArtifacts), StandardCharsets.UTF_8);
        List<X7BenchmarkEvidence.Artifact> artifacts = new ArrayList<>(payloadArtifacts);
        artifacts.add(artifact("owner-receipt", "owner-receipt.json", receipt,
                X7BenchmarkEvidence.ArtifactKind.OWNER_RECEIPT));
        X7BenchmarkEvidence evidence = hardwareEvidence(environment, backend, artifacts);
        writeManifest(root, evidence);
        return new Fixture(evidence, X7ArtifactVerifier.verify(evidence, root));
    }

    static Fixture capture(
            Path root,
            X7BenchmarkEvidence.CaptureClass captureClass,
            X7BenchmarkEvidence.RuntimeEnvironment environment,
            X7BenchmarkEvidence.BackendSelection backend) throws IOException {
        Path evidenceDirectory = root.resolve("evidence");
        Files.createDirectories(evidenceDirectory);
        Path jfr = evidenceDirectory.resolve("allocation.jfr");
        Files.writeString(jfr, MARKER + " fixture only; no hardware measurement", StandardCharsets.UTF_8);
        X7BenchmarkEvidence.Artifact allocation = new X7BenchmarkEvidence.Artifact(
                "jfr-allocation",
                "evidence/allocation.jfr",
                X7ArtifactVerifier.sha256(jfr),
                Files.size(jfr),
                X7BenchmarkEvidence.ArtifactKind.JFR_ALLOCATION);
        X7BenchmarkEvidence evidence = new X7BenchmarkEvidence(
                X7BenchmarkEvidence.SCHEMA_VERSION,
                captureClass,
                X7BenchmarkEvidence.GateStatus.WAITING,
                "synthetic-test-only-capture",
                new X7BenchmarkEvidence.SourceRevision(COMMIT, TREE),
                X7BenchmarkEvidence.frozenScenario(),
                environment,
                backend,
                new X7BenchmarkEvidence.Sampling(600, 1_800, 7L),
                standardMetrics(),
                "jfr-allocation",
                new X7BenchmarkEvidence.ArtifactManifest("evidence/artifact-manifest.json", List.of(allocation)),
                List.of(),
                List.of());
        writeManifest(root, evidence);
        return new Fixture(evidence, X7ArtifactVerifier.verify(evidence, root));
    }

    static void writeManifest(Path root, X7BenchmarkEvidence evidence) throws IOException {
        Path manifest = root.resolve(evidence.artifactManifest().manifestPath());
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, X7BenchmarkEvidenceCodec.canonicalJson(evidence), StandardCharsets.UTF_8);
    }

    private static X7BenchmarkEvidence hardwareEvidence(
            X7BenchmarkEvidence.RuntimeEnvironment environment,
            X7BenchmarkEvidence.BackendSelection backend,
            List<X7BenchmarkEvidence.Artifact> artifacts) {
        return new X7BenchmarkEvidence(
                X7BenchmarkEvidence.SCHEMA_VERSION,
                X7BenchmarkEvidence.CaptureClass.HARDWARE_CAPTURE,
                X7BenchmarkEvidence.GateStatus.WAITING,
                "synthetic-test-only-capture",
                new X7BenchmarkEvidence.SourceRevision(COMMIT, TREE),
                X7BenchmarkEvidence.frozenScenario(),
                environment,
                backend,
                new X7BenchmarkEvidence.Sampling(600, 1_800, 7L),
                standardMetrics(),
                "jfr-allocation",
                new X7BenchmarkEvidence.ArtifactManifest("artifact-manifest.json", artifacts),
                List.of(),
                List.of());
    }

    private static X7BenchmarkEvidence.BackendSelection gpuBackend() {
        return new X7BenchmarkEvidence.BackendSelection(
                X7BenchmarkEvidence.BackendClass.GPU, "gpu-skinning", "GPU_SKINNING_V1",
                X7BenchmarkEvidence.FallbackState.NONE, true);
    }

    private static X7BenchmarkEvidence.Metrics standardMetrics() {
        return new X7BenchmarkEvidence.Metrics(
                new X7BenchmarkEvidence.Percentiles(60.0d, 60.0d, 60.0d),
                new X7BenchmarkEvidence.Percentiles(16.0d, 16.0d, 16.0d),
                new X7BenchmarkEvidence.Percentiles(4.0d, 4.0d, 4.0d),
                new X7BenchmarkEvidence.Percentiles(1_024.0d, 1_024.0d, 1_024.0d));
    }

    private static byte[] canonicalRawSamples() {
        List<X7RawP7Samples.Sample> samples = new ArrayList<>(1_800);
        for (int index = 0; index < 1_800; index++) {
            samples.add(new X7RawP7Samples.Sample(60.0d, 16.0d, 4.0d, 1_024.0d, 100, 25));
        }
        return X7RawP7Samples.canonicalUtf8(samples);
    }

    private static X7BenchmarkEvidence.Artifact artifact(
            String key, String path, Path file, X7BenchmarkEvidence.ArtifactKind kind) throws IOException {
        return new X7BenchmarkEvidence.Artifact(key, path, X7ArtifactVerifier.sha256(file), Files.size(file), kind);
    }

    private static String environmentReport(
            X7BenchmarkEvidence.RuntimeEnvironment environment,
            X7BenchmarkEvidence.BackendSelection backend,
            X7BenchmarkEvidence.CaptureClass captureClass) {
        return String.join("\n",
                "format=x7_environment_report_v1",
                "capture_class=" + captureClass,
                "java_version=" + environment.javaVersion(),
                "os_name=" + environment.osName(),
                "os_version=" + environment.osVersion(),
                "minecraft_version=" + environment.minecraftVersion(),
                "fabric_loader_version=" + environment.fabricLoaderVersion(),
                "fabric_api_version=" + environment.fabricApiVersion(),
                "gpu_name=" + environment.gpuName(),
                "gpu_vendor=" + environment.gpuVendor(),
                "gpu_driver=" + environment.gpuDriver(),
                "shaderpack=" + environment.shaderpack(),
                "iris_status=" + environment.irisStatus(),
                "sodium_status=" + environment.sodiumStatus(),
                "backend_class=" + backend.backendClass(),
                "backend_name=" + backend.backendName(),
                "backend_capability=" + backend.capability(),
                "fallback_state=" + backend.fallbackState(),
                "source_commit=" + COMMIT,
                "source_tree=" + TREE,
                "source_clean=true",
                "");
    }

    private static String capabilityReport() {
        StringBuilder report = new StringBuilder();
        report.append("format=x7_capability_report_v3\n")
                .append("capture_id=synthetic-test-only-capture\n")
                .append("generation_id=7\n")
                .append("first_completed_frame_id=601\n")
                .append("last_completed_frame_id=2400\n")
                .append("backend_class=GPU\n")
                .append("backend_name=gpu-skinning\n")
                .append("backend_capability=GPU_SKINNING_V1\n")
                .append("fallback_state=NONE\n")
                .append("completed_backend_route_start=GPU\n")
                .append("completed_backend_route_end=GPU\n")
                .append("completed_active_fallback_reason_start=NONE\n")
                .append("completed_active_fallback_reason_end=NONE\n")
                .append("completed_backend_selection_verified_start=true\n")
                .append("completed_backend_selection_verified_end=true\n");
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
        for (String name : counters) {
            long delta = switch (name) {
                case "t3_completed_rigid_submissions" -> 180_000L;
                case "t3_completed_skinned_submissions", "t4_completed_gpu_skin_work", "t5_bone_draws",
                        "t5_animation_full_executed" -> 45_000L;
                case "t3_completed_draw_work", "t5_budget_accepted", "t5_actual_lod_selections_level_0",
                        "t5_instance_draws", "t5_primitive_draws", "t5_submitted_batch_instance_count",
                        "t5_submitted_primitive_count" -> 225_000L;
                case "t3_completed_passes", "t4_completed_gpu_skin_proofs", "t4_completed_fallback_parity_proofs",
                        "t5_submitted_batch_count", "backend_actual_gpu_frames",
                        "backend_completed_passes" -> 1_800L;
                case "t5_primitive_culls", "t5_bone_culls", "t5_skipped_primitive_count",
                        "t5_skipped_bone_layer_count" -> 1_800L;
                case "t4_completed_cpu_fallback_work", "backend_actual_cpu_frames", "backend_actual_fallback_frames",
                        "t5_gpu_candidate_selections", "t5_cpu_selections", "t5_capability_unavailable_fallbacks",
                        "t5_prepare_failure_fallbacks", "t5_upload_failure_fallbacks", "t5_budget_degraded", "t5_budget_cpu_fallback",
                        "t5_budget_rejected", "t5_actual_lod_selections_level_1",
                        "t5_actual_lod_selections_level_2", "t5_actual_lod_selections_level_3",
                        "t5_actual_lod_selections_level_4", "t5_actual_lod_selections_level_5",
                        "t5_actual_lod_selections_level_6", "t5_actual_lod_selections_level_7",
                        "t5_actual_lod_handle_switches", "t5_lod_rejections", "t5_instance_culls",
                        "t5_animation_reduced_executed",
                        "t5_animation_reuse_pose_executed", "t5_animation_paused_executed",
                        "t5_reduced_cadence_advance_skips", "t5_pose_reuse_hits", "t5_captured_skin_reuse_hits",
                        "t5_visible_missing_fallbacks", "t5_projection_failures", "t5_generation_models",
                        "t5_generation_primitives", "t5_generation_bones", "t5_generation_vertices",
                        "t5_generation_gpu_resource_count", "t5_generation_gpu_vertex_bytes",
                        "t5_generation_gpu_index_bytes", "t5_completed_frame_requested_animated_instances",
                        "t5_completed_frame_admitted_animated_instances", "t5_completed_frame_requested_bone_work",
                        "t5_completed_frame_admitted_bone_work", "t5_completed_frame_requested_vertex_work",
                        "t5_completed_frame_admitted_vertex_work" -> 0L;
                default -> throw new IllegalArgumentException("unrecognized frozen capability counter: " + name);
            };
            long startValue = switch (name) {
                case "t5_completed_frame_requested_animated_instances",
                        "t5_completed_frame_admitted_animated_instances",
                        "t5_completed_frame_requested_bone_work",
                        "t5_completed_frame_admitted_bone_work" -> 25L;
                case "t5_completed_frame_requested_vertex_work",
                        "t5_completed_frame_admitted_vertex_work" -> 125L;
                case "t5_gpu_candidate_selections" -> 1L;
                default -> 0L;
            };
            long endValue = switch (name) {
                case "t5_completed_frame_requested_bone_work" -> 50L;
                case "t5_completed_frame_admitted_bone_work" -> 40L;
                case "t5_completed_frame_requested_vertex_work" -> 250L;
                case "t5_completed_frame_admitted_vertex_work" -> 220L;
                default -> Math.addExact(startValue, delta);
            };
            report.append(name).append("_start=").append(startValue).append('\n')
                    .append(name).append("_end=").append(endValue).append('\n')
                    .append(name).append("_delta=").append(Math.subtractExact(endValue, startValue)).append('\n');
        }
        return report.toString();
    }

    private static String ownerReceipt(List<X7BenchmarkEvidence.Artifact> payloadArtifacts) {
        StringBuilder receipt = new StringBuilder("{");
        receipt.append("\"format\":\"x7-p7-live-owner-receipt-v1\",");
        receipt.append("\"capture_id\":\"synthetic-test-only-capture\",");
        receipt.append("\"owner_epoch\":1,\"measurement_session_epoch\":1,");
        receipt.append("\"owner_render_thread_id\":1,\"owner_render_thread_name\":\"fixture\",");
        receipt.append("\"source_commit\":\"").append(COMMIT).append("\",");
        receipt.append("\"source_tree\":\"").append(TREE).append("\",\"source_clean\":true,");
        receipt.append("\"warmup_frames\":600,\"sample_frames\":1800,");
        receipt.append("\"rigid_submits_per_sample\":100,\"skinned_submits_per_sample\":25,");
        receipt.append("\"generation_id\":7,\"first_completed_frame_id\":601,\"last_completed_frame_id\":2400,");
        receipt.append("\"started_at_utc\":\"2026-09-04T00:00:00Z\",");
        receipt.append("\"ended_at_utc\":\"2026-09-04T00:01:00Z\",\"jfr_file\":\"allocation.jfr\",");
        receipt.append("\"artifacts\":[");
        for (int index = 0; index < payloadArtifacts.size(); index++) {
            if (index > 0) {
                receipt.append(',');
            }
            X7BenchmarkEvidence.Artifact artifact = payloadArtifacts.get(index);
            receipt.append("{\"key\":\"").append(artifact.key())
                    .append("\",\"path\":\"").append(artifact.path())
                    .append("\",\"kind\":\"").append(artifact.kind())
                    .append("\",\"sha256\":\"").append(artifact.sha256())
                    .append("\",\"byte_count\":").append(artifact.byteCount()).append('}');
        }
        return receipt.append("],\"terminal\":\"SEALED_WAITING\"}\n").toString();
    }

    private static String completeP7Report() {
        return """
                {
                  "format": "blendlib-showcase-p7-runtime-capture-v1",
                  "status": "RUNTIME_CAPTURE_COMPLETE",
                  "gate": "WAITING",
                  "gate_reason": "Offline fixture remains untrusted.",
                  "jfr_file": "/capture/allocation.jfr",
                  "warmup_frames": 600,
                  "sample_frames": 1800,
                  "rigid_submits_per_sample": 100,
                  "skinned_submits_per_sample": 25,
                  "client_conditions_at_capture_start": {
                    "framebuffer_width": 1920,
                    "framebuffer_height": 1080,
                    "render_target_width": 1920,
                    "render_target_height": 1080,
                    "required_aspect_ratio": "16:9",
                    "fov_degrees": 90,
                    "fov_effect_scale": 0.0,
                    "dynamic_fov_disabled": true,
                    "configured_render_distance_chunks": 8,
                    "effective_render_distance_chunks": 8,
                    "required_minimum_render_distance_chunks": 8,
                    "contract_satisfied": true
                  },
                  "frame_time_nanos": {"p50": 16000000, "p95": 16000000},
                  "animation_preparation_nanos": {"p50": 1, "p95": 1},
                  "submit_cpu_nanos": {"p50": 4000000, "p95": 4000000},
                  "jfr_allocation_bytes": {"p50": 1024, "p95": 1024},
                  "cache_peaks": {"pose_entries": 1, "pose_capacity": 1, "tracked_animation_instances": 1, "prepared_animation_assets": 1},
                  "model_handles": {"peak_total": 1, "peak_missing": 0}
                }
                """;
    }

    private static void writeRealJfr(Path destination) throws IOException {
        Recording recording = new Recording();
        recording.enable("jdk.ObjectAllocationInNewTLAB").withThreshold(java.time.Duration.ZERO);
        recording.enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(java.time.Duration.ZERO);
        recording.start();
        List<byte[]> retained = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            retained.add(new byte[1_000_000]);
        }
        if (retained.size() != 16) {
            throw new IOException("fixture allocation retention failed");
        }
        recording.stop();
        try {
            recording.dump(destination);
        } finally {
            recording.close();
        }
    }

    private static void writeCompletePng(Path destination) throws IOException {
        BufferedImage image = new BufferedImage(1_920, 1_080, BufferedImage.TYPE_INT_ARGB);
        if (!ImageIO.write(image, "png", destination.toFile())) {
            throw new IOException("PNG writer is unavailable for the fixture");
        }
    }

    private static byte[] pngWithMarker() {
        byte[] header = pngHeader();
        byte[] marker = MARKER.getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = java.util.Arrays.copyOf(header, header.length + marker.length);
        System.arraycopy(marker, 0, bytes, header.length, marker.length);
        return bytes;
    }

    private static byte[] pngHeader() {
        byte[] bytes = new byte[33];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        bytes[4] = '\r';
        bytes[5] = '\n';
        bytes[6] = 0x1a;
        bytes[7] = '\n';
        bytes[11] = 13;
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        bytes[18] = 7;
        bytes[19] = (byte) 0x80;
        bytes[22] = 4;
        bytes[23] = 56;
        bytes[24] = 8;
        bytes[25] = 6;
        return bytes;
    }

    static X7BenchmarkEvidence.RuntimeEnvironment environment(boolean verified, String gpuName) {
        return new X7BenchmarkEvidence.RuntimeEnvironment(
                "25.0.2",
                "Windows",
                "11",
                "26.1.2",
                "0.19.3",
                "0.154.2+26.1.2",
                gpuName,
                verified ? "NVIDIA" : X7BenchmarkEvidence.UNKNOWN,
                verified ? "555.55" : X7BenchmarkEvidence.UNKNOWN,
                "NONE",
                "NOT_PRESENT",
                "NOT_PRESENT",
                verified);
    }

    record Fixture(
            X7BenchmarkEvidence evidence,
            X7ArtifactVerifier.ArtifactVerification artifactVerification) {
    }
}
