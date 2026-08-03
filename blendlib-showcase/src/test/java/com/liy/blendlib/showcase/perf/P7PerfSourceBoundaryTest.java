package com.liy.blendlib.showcase.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class P7PerfSourceBoundaryTest {
    private static final Pattern BLENDLIB_IMPORT = Pattern.compile("import\\s+(com\\.liy\\.blendlib\\.[\\w.]+);");

    @Test
    void p7ClientHarnessUsesOnlyThePublicClientFacade() throws IOException {
        String source = Files.readString(clientSource("P7ClientMeasurementHarness.java"));
        for (String forbidden : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.common.",
                "com.liy.blendlib.fabric.client.reload.",
                "com.liy.blendlib.fabric.client.animation.",
                "com.liy.blendlib.fabric.client.render.",
                "com.liy.blendlib.fabric.client.network.",
                "Minecraft.getInstance",
                "org.lwjgl")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        Matcher matcher = BLENDLIB_IMPORT.matcher(source);
        while (matcher.find()) {
            String importedType = matcher.group(1);
            assertTrue(
                    importedType.startsWith("com.liy.blendlib.showcase.perf.")
                            || importedType.equals("com.liy.blendlib.fabric.client.api.BlendLibClientServices")
                            || importedType.equals("com.liy.blendlib.fabric.client.api.ClientRegistryView"),
                    () -> "P7 client harness escaped the public client facade: " + importedType);
        }
        assertTrue(source.contains("BlendLibClientServices.models().snapshot()"));
    }

    @Test
    void p7ScenarioAndGeneratorDoNotUseRenderOrLoaderImplementations() throws IOException {
        Path sourceRoot = projectRoot().resolve("src/main/java/com/liy/blendlib/showcase/perf");
        String combined;
        try (var paths = Files.walk(sourceRoot)) {
            combined = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(this::readUnchecked)
                    .reduce("", String::concat);
        }
        for (String forbidden : List.of(
                "com.liy.blendlib.core.",
                "com.liy.blendlib.fabric.",
                "org.lwjgl",
                "glBind",
                "glDraw")) {
            assertFalse(combined.contains(forbidden), forbidden);
        }
        assertTrue(combined.contains("not wired into"));
        assertTrue(combined.contains("JFR or profiler"));
    }

    @Test
    void captureControllerRejectsPartialTargetSubmissionBeforeWarmupCanAdvance() throws IOException {
        String controller = Files.readString(projectRoot().resolve(
                "src/client/java/com/liy/blendlib/showcase/client/P7BenchmarkCaptureController.java"));
        String stateMachine = Files.readString(projectRoot().resolve(
                "src/client/java/com/liy/blendlib/showcase/client/P7BenchmarkCaptureStateMachine.java"));
        int exactSubmissionGuard = controller.indexOf("P7ReferenceScenario.hasExactTargetSubmissions");
        int rejectionHandling = controller.indexOf(
                "if (transition == P7BenchmarkCaptureStateMachine.Transition.INVALID_SUBMISSIONS)");
        int stateMachineGuard = stateMachine.indexOf("if (!exactTargetSubmission)");
        int warmupAdvance = stateMachine.indexOf("warmupFrames = Math.incrementExact(warmupFrames)");

        assertTrue(exactSubmissionGuard >= 0, "P7 capture must verify the complete frozen target population");
        assertTrue(rejectionHandling >= 0, "P7 capture must invalidate incomplete target submissions");
        assertTrue(stateMachineGuard >= 0, "P7 state machine must reject incomplete target submissions");
        assertTrue(warmupAdvance >= 0, "P7 state machine must retain its explicit warm-up counter");
        assertTrue(exactSubmissionGuard < rejectionHandling,
                "P7 must test exact target submission before handling the rejection transition");
        assertTrue(stateMachineGuard < warmupAdvance,
                "P7 must reject partial submissions before counting a warm-up frame");
        assertTrue(controller.contains("P7 \" + phase + \" frame did not submit every target host"));
        assertTrue(stateMachine.contains("return Transition.INVALID_SUBMISSIONS"));
    }

    @Test
    void captureControllerWaitsForThePublicTickCameraBeforeBeginningWarmup() throws IOException {
        String controller = Files.readString(projectRoot().resolve(
                "src/client/java/com/liy/blendlib/showcase/client/P7BenchmarkCaptureController.java"));
        String stateMachine = Files.readString(projectRoot().resolve(
                "src/client/java/com/liy/blendlib/showcase/client/P7BenchmarkCaptureStateMachine.java"));
        String entrypoint = Files.readString(projectRoot().resolve(
                "src/client/java/com/liy/blendlib/showcase/client/BlendLibShowcaseClientEntrypoint.java"));
        int cameraReadyGuard = controller.indexOf(
                "if (!observation.readyForCapture() || !cameraReady || !clientConditionsReady)");
        int beginCapture = controller.indexOf("measurements.beginCapture()");

        assertTrue(cameraReadyGuard >= 0, "P7 capture must wait for the frozen player camera and client conditions");
        assertTrue(beginCapture >= 0, "P7 capture must retain its explicit warm-up start");
        assertTrue(cameraReadyGuard < beginCapture,
                "P7 must verify the player camera and client conditions before beginning warm-up capture");
        assertTrue(controller.contains("public void onClientTick(Minecraft client)"));
        assertTrue(controller.contains("P7ReferenceScenario.standard().isAtCaptureCamera"));
        assertTrue(controller.contains("client.getWindow().getWidth()"));
        assertTrue(controller.contains("client.getWindow().getHeight()"));
        assertTrue(controller.contains("client.getMainRenderTarget().width"));
        assertTrue(controller.contains("client.getMainRenderTarget().height"));
        assertTrue(controller.contains("client.options.fov().get()"));
        assertTrue(controller.contains("client.options.fovEffectScale().get()"));
        assertTrue(controller.contains("client.options.getEffectiveRenderDistance()"));
        assertTrue(controller.contains("P7ReferenceScenario.ClientConditions"));
        assertTrue(controller.contains("Transition.INVALID_CLIENT_CONDITIONS"));
        assertTrue(stateMachine.contains("INVALID_CLIENT_CONDITIONS"));
        assertTrue(stateMachine.contains("boolean clientConditionsReady"));
        assertFalse(controller.contains("Minecraft.getInstance"));
        assertTrue(entrypoint.contains("ClientTickEvents.END_CLIENT_TICK.register(client -> p7BenchmarkCapture.onClientTick(client))"));
    }

    @Test
    void reportContractPersistsActualFramebufferAndFovConditionsWithoutWeakeningSubmissionGuard() throws IOException {
        String controller = Files.readString(projectRoot().resolve(
                "src/client/java/com/liy/blendlib/showcase/client/P7BenchmarkCaptureController.java"));
        String reportWriter = Files.readString(clientSource("P7BenchmarkReportWriter.java"));

        assertTrue(controller.contains("P7ReferenceScenario.hasExactTargetSubmissions"));
        assertTrue(controller.contains("clientConditionsAtStart"));
        assertTrue(controller.contains("clientConditionsAtStart.meetsCaptureContract()"));
        assertTrue(controller.contains("P7BenchmarkReportWriter.writeInvalid(outputDirectory, reason, lastObservation, clientConditions)"));
        assertTrue(reportWriter.contains("client_conditions_at_capture_start"));
        assertTrue(reportWriter.contains("client_conditions_at_invalidation"));
        for (String requiredField : List.of(
                "framebuffer_width",
                "framebuffer_height",
                "render_target_width",
                "render_target_height",
                "fov_degrees",
                "fov_effect_scale",
                "dynamic_fov_disabled",
                "effective_render_distance_chunks",
                "contract_satisfied")) {
            assertTrue(reportWriter.contains(requiredField), requiredField);
        }
        assertFalse(reportWriter.contains("org.lwjgl"));
        assertFalse(reportWriter.contains("glBind"));
        assertFalse(reportWriter.contains("glDraw"));
    }

    private static Path clientSource(String fileName) {
        return projectRoot().resolve("src/client/java/com/liy/blendlib/showcase/perf").resolve(fileName);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("blendlib.projectDir"));
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new AssertionError("Unable to read " + path, exception);
        }
    }
}
