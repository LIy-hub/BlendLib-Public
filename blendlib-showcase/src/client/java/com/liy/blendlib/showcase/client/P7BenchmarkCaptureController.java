package com.liy.blendlib.showcase.client;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.api.ClientAnimationRuntimeMetrics;
import com.liy.blendlib.fabric.client.api.ClientRenderMeasurementService;
import com.liy.blendlib.fabric.client.api.ClientRenderMeasurementSnapshot;
import com.liy.blendlib.showcase.perf.P7AllocationObservation;
import com.liy.blendlib.showcase.perf.P7BenchmarkReportWriter;
import com.liy.blendlib.showcase.perf.P7CacheObservation;
import com.liy.blendlib.showcase.perf.P7ClientMeasurementHarness;
import com.liy.blendlib.showcase.perf.P7FrameTiming;
import com.liy.blendlib.showcase.perf.P7MeasurementSummary;
import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordedEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Explicit opt-in real-client benchmark capture controller.
 *
 * <p>The controller is constructed only by the isolated benchmark Loom run configuration. It waits for
 * the exact 100/25 client host counts and both generated models, warms up for 600 completed render
 * frames, then records exactly 1,800 subsequent render frames together with JFR allocation
 * events. It writes a local benchmark report; visual quality and broad compatibility are outside
 * the scope of the automated capture.</p>
 */
public final class P7BenchmarkCaptureController {
    private static final String ENABLED_PROPERTY = "blendlib.showcase.p7.enabled";
    private static final String OUTPUT_PROPERTY = "blendlib.showcase.p7.output";
    private static final String JFR_TLAB_EVENT = "jdk.ObjectAllocationInNewTLAB";
    private static final String JFR_OUTSIDE_TLAB_EVENT = "jdk.ObjectAllocationOutsideTLAB";
    private static final System.Logger LOGGER = System.getLogger("BlendLib Showcase P7");

    private ClientRenderMeasurementService measurements;
    private final Path outputDirectory;
    private final List<PendingFrame> samples = new ArrayList<>(P7ReferenceScenario.SAMPLE_FRAME_COUNT);

    private final P7BenchmarkCaptureStateMachine captureState;
    private String lastObservation = "not observed";
    private P7BenchmarkClientBinding.SceneObservation sceneObservation = P7BenchmarkClientBinding.SceneObservation.unavailable();
    private boolean cameraReady;
    private String cameraObservation = "camera=not observed";
    private P7ReferenceScenario.ClientConditions clientConditions;
    private boolean clientConditionsReady;
    private String clientConditionsObservation = "clientConditions=not observed";
    private P7ReferenceScenario.ClientConditions clientConditionsAtStart;
    private long previousFrameEndNanos = -1L;
    private Instant previousFrameEndInstant;
    private Recording recording;
    private Path jfrFile;

    private P7BenchmarkCaptureController(
            ClientRenderMeasurementService measurements,
            Path outputDirectory,
            P7BenchmarkCaptureStateMachine.State initialState) {
        this.measurements = measurements;
        this.outputDirectory = outputDirectory;
        this.captureState = new P7BenchmarkCaptureStateMachine(initialState);
    }

    /** Creates a disabled controller unless the dedicated isolated Loom run explicitly enables it. */
    public static P7BenchmarkCaptureController fromSystemProperties() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) {
            return new P7BenchmarkCaptureController(null, null, P7BenchmarkCaptureStateMachine.State.DISABLED);
        }
        String configuredOutput = System.getProperty(OUTPUT_PROPERTY);
        if (configuredOutput == null || configuredOutput.isBlank()) {
            LOGGER.log(System.Logger.Level.ERROR, "P7 benchmark enabled without an isolated output directory");
            return new P7BenchmarkCaptureController(null, null, P7BenchmarkCaptureStateMachine.State.INVALID);
        }
        Path output = Path.of(configuredOutput).toAbsolutePath().normalize();
        if (!isIsolatedBenchmarkDirectory(output)) {
            LOGGER.log(System.Logger.Level.ERROR, "Refusing P7 benchmark output outside run/p7-benchmark: {0}", output);
            return new P7BenchmarkCaptureController(null, null, P7BenchmarkCaptureStateMachine.State.INVALID);
        }
        // Fabric may initialize the consumer before the provider's client entrypoint. Bind lazily
        // at the first ready client tick rather than treating that valid ordering as a benchmark
        // failure.
        return new P7BenchmarkCaptureController(null, output, P7BenchmarkCaptureStateMachine.State.WAITING);
    }

    /** Updates scene and player-camera readiness through Fabric's public client-tick callback. */
    public void onClientTick(Minecraft client) {
        if (!captureState.acceptsEvents()) {
            return;
        }
        Minecraft checkedClient = Objects.requireNonNull(client, "client");
        ClientLevel level = checkedClient.level;
        sceneObservation = P7BenchmarkClientBinding.observe(level);
        cameraReady = checkedClient.player != null
                && P7ReferenceScenario.standard().isAtCaptureCamera(
                        checkedClient.player.getX(),
                        checkedClient.player.getY(),
                        checkedClient.player.getZ(),
                        checkedClient.player.getYRot(),
                        checkedClient.player.getXRot());
        cameraObservation = cameraReady
                ? "camera=ready"
                : "camera=waiting for " + P7ReferenceScenario.standard().camera();
        clientConditions = observeClientConditions(checkedClient);
        clientConditionsReady = clientConditions.meetsCaptureContract();
        clientConditionsObservation = "clientConditions=" + clientConditions.description()
                + ", ready=" + clientConditionsReady;
        lastObservation = readinessDescription(sceneObservation);
    }

    /** Invoked from Fabric's public level-render end event exactly once for each completed level frame. */
    public void onEndMainFrame() {
        if (!captureState.acceptsEvents()) {
            return;
        }
        P7BenchmarkClientBinding.SceneObservation observation = sceneObservation;
        lastObservation = readinessDescription(observation);
        if (captureState.state() == P7BenchmarkCaptureStateMachine.State.WAITING) {
            beginWhenReady(observation);
            return;
        }
        P7BenchmarkCaptureStateMachine.Transition readiness =
                captureState.validateActiveFrame(observation.readyForCapture(), cameraReady, clientConditionsReady);
        if (readiness == P7BenchmarkCaptureStateMachine.Transition.INVALID_CAMERA) {
            invalidate("P7 capture camera moved after capture began: " + lastObservation);
            return;
        }
        if (readiness == P7BenchmarkCaptureStateMachine.Transition.INVALID_CLIENT_CONDITIONS) {
            invalidate("P7 capture client conditions changed after capture began: " + lastObservation);
            return;
        }
        if (readiness == P7BenchmarkCaptureStateMachine.Transition.INVALID_SCENE) {
            invalidate("P7 scene changed after capture began: " + lastObservation);
            return;
        }
        captureFrame(observation);
    }

    /** Current local controller state for log/diagnostic inspection; it is never a Gate verdict. */
    public String stateName() {
        return captureState.state().name();
    }

    private void beginWhenReady(P7BenchmarkClientBinding.SceneObservation observation) {
        if (!observation.readyForCapture() || !cameraReady || !clientConditionsReady) {
            return;
        }
        P7ReferenceScenario.ClientConditions observedConditions = clientConditions;
        if (observedConditions == null) {
            invalidate("P7 benchmark has no observed client capture conditions");
            return;
        }
        if (outputDirectory == null) {
            invalidate("P7 benchmark has no isolated output directory");
            return;
        }
        if (measurements == null) {
            if (!BlendLibClientServices.isInitialized()) {
                return;
            }
            try {
                measurements = BlendLibClientServices.performanceMeasurements();
            } catch (IllegalStateException exception) {
                invalidate("P7 benchmark adapter measurements could not be bound: " + exception.getMessage());
                return;
            }
        }
        if (captureState.beginWhenReady(observation.readyForCapture(), cameraReady, clientConditionsReady)
                != P7BenchmarkCaptureStateMachine.Transition.START_WARMUP) {
            return;
        }
        clientConditionsAtStart = observedConditions;
        measurements.beginCapture();
        previousFrameEndNanos = System.nanoTime();
        previousFrameEndInstant = Instant.now();
        LOGGER.log(System.Logger.Level.INFO,
                "P7 benchmark scene ready; starting {0}-frame warm-up. This is not a Gate result.",
                P7ReferenceScenario.WARMUP_FRAME_COUNT);
    }

    private void captureFrame(P7BenchmarkClientBinding.SceneObservation observation) {
        Optional<ClientRenderMeasurementSnapshot> measurement = measurements.completeFrame();
        if (measurement.isEmpty()) {
            invalidate("P7 benchmark measurement capture stopped before a render frame completed");
            return;
        }
        long frameEndNanos = System.nanoTime();
        Instant frameEndInstant = Instant.now();
        if (previousFrameEndNanos < 0L || previousFrameEndInstant == null || frameEndNanos < previousFrameEndNanos) {
            invalidate("P7 benchmark frame clock was not monotonic");
            return;
        }
        long frameNanos = frameEndNanos - previousFrameEndNanos;
        ClientRenderMeasurementSnapshot adapter = measurement.get();
        int rigidSubmits = adapter.submittedModelCounts().getOrDefault(P7BenchmarkClientBinding.RIGID_MODEL, 0);
        int skinnedSubmits = adapter.submittedModelCounts().getOrDefault(P7BenchmarkClientBinding.SKINNED_MODEL, 0);
        P7BenchmarkCaptureStateMachine.Transition transition =
                captureState.acceptExactSubmission(P7ReferenceScenario.hasExactTargetSubmissions(rigidSubmits, skinnedSubmits));
        if (transition == P7BenchmarkCaptureStateMachine.Transition.INVALID_SUBMISSIONS) {
            String phase = captureState.state() == P7BenchmarkCaptureStateMachine.State.WARMUP ? "warm-up" : "sample";
            invalidate("P7 " + phase + " frame did not submit every target host: rigid=" + rigidSubmits
                    + ", skinned=" + skinnedSubmits + ", expected=100/25");
            return;
        }
        if (transition == P7BenchmarkCaptureStateMachine.Transition.WARMUP_FRAME
                || transition == P7BenchmarkCaptureStateMachine.Transition.START_SAMPLING) {
            previousFrameEndNanos = frameEndNanos;
            previousFrameEndInstant = frameEndInstant;
            if (transition == P7BenchmarkCaptureStateMachine.Transition.START_SAMPLING) {
                beginJfrSampling();
            }
            return;
        }
        if (transition != P7BenchmarkCaptureStateMachine.Transition.SAMPLE_FRAME
                && transition != P7BenchmarkCaptureStateMachine.Transition.COMPLETE_CAPTURE) {
            invalidate("P7 benchmark received a frame in unexpected state " + captureState.state());
            return;
        }
        ClientAnimationRuntimeMetrics runtime = adapter.animationRuntime();
        if (!runtime.available()) {
            invalidate("P7 animation runtime metrics are unavailable during an active capture");
            return;
        }
        if (adapter.animationPreparationNanos() > frameNanos || adapter.submitCpuNanos() > frameNanos) {
            invalidate("P7 CPU timing exceeded the observed frame interval; capture cannot safely summarize overlapping work");
            return;
        }
        try {
            P7FrameTiming timing = new P7FrameTiming(
                    frameNanos, adapter.animationPreparationNanos(), adapter.submitCpuNanos());
            P7CacheObservation cache = new P7CacheObservation(
                    runtime.poseCacheEntries(),
                    runtime.poseCacheCapacity(),
                    runtime.poseCacheHits(),
                    runtime.poseCacheMisses(),
                    runtime.poseCacheEvictions(),
                    runtime.trackedAnimationInstances(),
                    runtime.preparedAnimationAssets());
            samples.add(new PendingFrame(previousFrameEndInstant, frameEndInstant, timing, cache, observation));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            invalidate("P7 frame observation was invalid: " + exception.getMessage());
            return;
        }
        previousFrameEndNanos = frameEndNanos;
        previousFrameEndInstant = frameEndInstant;
        if (transition == P7BenchmarkCaptureStateMachine.Transition.COMPLETE_CAPTURE) {
            completeCapture(rigidSubmits, skinnedSubmits);
        }
    }

    private void beginJfrSampling() {
        try {
            Recording nextRecording = new Recording();
            nextRecording.enable(JFR_TLAB_EVENT).withThreshold(Duration.ZERO);
            nextRecording.enable(JFR_OUTSIDE_TLAB_EVENT).withThreshold(Duration.ZERO);
            nextRecording.start();
            recording = nextRecording;
            jfrFile = outputDirectory.resolve("p7-runtime-" + System.currentTimeMillis() + ".jfr");
            LOGGER.log(System.Logger.Level.INFO,
                    "P7 warm-up complete; recording {0} measured render frames with JFR allocation events.",
                    P7ReferenceScenario.SAMPLE_FRAME_COUNT);
        } catch (RuntimeException exception) {
            invalidate("P7 could not start Java Flight Recorder: " + exception.getMessage());
        }
    }

    private void completeCapture(int rigidSubmits, int skinnedSubmits) {
        try {
            Path completedJfr = stopAndDumpJfr();
            long[] allocations = readAllocations(completedJfr, samples);
            P7ClientMeasurementHarness harness = P7ClientMeasurementHarness.standard();
            for (int index = 0; index < samples.size(); index++) {
                PendingFrame frame = samples.get(index);
                harness.capture(
                        frame.timing(),
                        new P7AllocationObservation(allocations[index], P7AllocationObservation.Source.JFR),
                        frame.cache());
            }
            if (!harness.complete()) {
                invalidate("P7 runtime capture did not reach its required bounded sample count");
                return;
            }
            P7MeasurementSummary summary = harness.summarize();
            P7ReferenceScenario.ClientConditions completedConditions = clientConditionsAtStart;
            if (completedConditions == null) {
                throw new IllegalStateException("P7 runtime capture has no retained client-condition observation");
            }
            Completion completion = new Completion(
                    outputDirectory,
                    completedJfr,
                    summary,
                    rigidSubmits,
                    skinnedSubmits,
                    completedConditions);
            Path report = P7BenchmarkReportWriter.writeCompleted(completion);
            measurements.endCapture();
            if (!captureState.markComplete()) {
                invalidate("P7 runtime capture completion lost its deterministic terminal state");
                return;
            }
            LOGGER.log(System.Logger.Level.INFO,
                    "Benchmark capture completed; report: {0}.", report);
        } catch (IOException | RuntimeException exception) {
            invalidate("P7 JFR/report completion failed: " + exception.getMessage());
        }
    }

    private Path stopAndDumpJfr() throws IOException {
        if (recording == null || jfrFile == null) {
            throw new IllegalStateException("P7 sampling has no active JFR recording");
        }
        try {
            recording.stop();
            recording.dump(jfrFile);
            return jfrFile;
        } finally {
            recording.close();
            recording = null;
        }
    }

    private static long[] readAllocations(Path source, List<PendingFrame> frames) throws IOException {
        long[] allocations = new long[frames.size()];
        int currentFrame = 0;
        try (RecordingFile recordingFile = new RecordingFile(source)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                String eventName = event.getEventType().getName();
                if (!JFR_TLAB_EVENT.equals(eventName) && !JFR_OUTSIDE_TLAB_EVENT.equals(eventName)) {
                    continue;
                }
                Instant time = event.getStartTime();
                while (currentFrame < frames.size() && !time.isBefore(frames.get(currentFrame).endInstant())) {
                    currentFrame++;
                }
                if (currentFrame >= frames.size() || time.isBefore(frames.get(currentFrame).startInstant())) {
                    continue;
                }
                allocations[currentFrame] = Math.addExact(allocations[currentFrame], event.getLong("allocationSize"));
            }
        }
        return allocations;
    }

    private void invalidate(String reason) {
        if (!captureState.invalidate()) {
            return;
        }
        stopRecordingQuietly();
        if (measurements != null) {
            measurements.endCapture();
        }
        if (outputDirectory != null) {
            try {
                Path report = P7BenchmarkReportWriter.writeInvalid(outputDirectory, reason, lastObservation, clientConditions);
                LOGGER.log(System.Logger.Level.ERROR, "P7 runtime capture is invalid: {0}; evidence: {1}", reason, report);
                return;
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.ERROR, "P7 runtime capture is invalid and report writing failed: {0}", exception.getMessage());
            }
        }
        LOGGER.log(System.Logger.Level.ERROR, "P7 runtime capture is invalid: {0}", reason);
    }

    private void stopRecordingQuietly() {
        if (recording == null) {
            return;
        }
        try {
            recording.stop();
        } catch (RuntimeException ignored) {
            // The invalid report already records that this incomplete capture cannot be evidence.
        } finally {
            recording.close();
            recording = null;
        }
    }

    private String readinessDescription(P7BenchmarkClientBinding.SceneObservation observation) {
        return observation.readinessDescription() + ", " + cameraObservation + ", " + clientConditionsObservation;
    }

    /** Reads public 26.1.2 window/options values into the Minecraft-free scenario contract. */
    private static P7ReferenceScenario.ClientConditions observeClientConditions(Minecraft client) {
        return new P7ReferenceScenario.ClientConditions(
                client.getWindow().getWidth(),
                client.getWindow().getHeight(),
                client.getMainRenderTarget().width,
                client.getMainRenderTarget().height,
                client.options.fov().get(),
                client.options.fovEffectScale().get(),
                client.options.renderDistance().get(),
                client.options.getEffectiveRenderDistance());
    }

    private static boolean isIsolatedBenchmarkDirectory(Path output) {
        String normalized = output.toString().replace((char) 92, '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/run/p7-benchmark/");
    }

    /** Immutable result written only after all required runtime samples and JFR allocation data exist. */
    public record Completion(
            Path outputDirectory,
            Path jfrFile,
            P7MeasurementSummary summary,
            int rigidSubmitsPerSample,
            int skinnedSubmitsPerSample,
            P7ReferenceScenario.ClientConditions clientConditionsAtStart) {
        public Completion {
            outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
            jfrFile = Objects.requireNonNull(jfrFile, "jfrFile");
            summary = Objects.requireNonNull(summary, "summary");
            clientConditionsAtStart = Objects.requireNonNull(clientConditionsAtStart, "clientConditionsAtStart");
            if (rigidSubmitsPerSample != P7ReferenceScenario.RIGID_INSTANCE_COUNT
                    || skinnedSubmitsPerSample != P7ReferenceScenario.SKINNED_INSTANCE_COUNT) {
                throw new IllegalArgumentException("P7 completion must retain exact submitted instance counts");
            }
            if (!clientConditionsAtStart.meetsCaptureContract()) {
                throw new IllegalArgumentException("P7 completion must retain compliant client capture conditions");
            }
        }
    }

    private record PendingFrame(
            Instant startInstant,
            Instant endInstant,
            P7FrameTiming timing,
            P7CacheObservation cache,
            P7BenchmarkClientBinding.SceneObservation observation) {
        private PendingFrame {
            startInstant = Objects.requireNonNull(startInstant, "startInstant");
            endInstant = Objects.requireNonNull(endInstant, "endInstant");
            if (!endInstant.isAfter(startInstant)) {
                throw new IllegalArgumentException("P7 frame wall-clock window must be positive");
            }
            timing = Objects.requireNonNull(timing, "timing");
            cache = Objects.requireNonNull(cache, "cache");
            observation = Objects.requireNonNull(observation, "observation");
        }
    }

}
