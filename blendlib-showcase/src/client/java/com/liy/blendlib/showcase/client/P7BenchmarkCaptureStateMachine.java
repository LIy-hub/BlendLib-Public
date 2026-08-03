package com.liy.blendlib.showcase.client;

import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.util.Objects;

/**
 * Deterministic, package-private phase accounting for the opt-in P7 capture controller.
 *
 * <p>This owns no Minecraft, JFR, file, or measurement-service state. The controller retains
 * those side effects and asks this state machine whether a completed render frame may advance the
 * frozen 600/1,800 capture contract.</p>
 */
final class P7BenchmarkCaptureStateMachine {
    enum State {
        DISABLED,
        WAITING,
        WARMUP,
        SAMPLING,
        COMPLETING,
        COMPLETE,
        INVALID
    }

    enum Transition {
        IGNORED,
        WAITING,
        START_WARMUP,
        READY_FOR_FRAME,
        WARMUP_FRAME,
        START_SAMPLING,
        SAMPLE_FRAME,
        COMPLETE_CAPTURE,
        INVALID_CAMERA,
        INVALID_CLIENT_CONDITIONS,
        INVALID_SCENE,
        INVALID_SUBMISSIONS
    }

    private State state;
    private int warmupFrames;
    private int sampleFrames;

    P7BenchmarkCaptureStateMachine(State initialState) {
        state = Objects.requireNonNull(initialState, "initialState");
    }

    State state() {
        return state;
    }

    int warmupFrames() {
        return warmupFrames;
    }

    int sampleFrames() {
        return sampleFrames;
    }

    /** Returns whether the controller should accept a client tick or completed render frame. */
    boolean acceptsEvents() {
        return state == State.WAITING || state == State.WARMUP || state == State.SAMPLING;
    }

    /** Begins capture only after the exact scene, frozen camera, and client conditions are ready. */
    Transition beginWhenReady(boolean sceneReady, boolean cameraReady, boolean clientConditionsReady) {
        if (state != State.WAITING) {
            return Transition.IGNORED;
        }
        if (!sceneReady || !cameraReady || !clientConditionsReady) {
            return Transition.WAITING;
        }
        warmupFrames = 0;
        sampleFrames = 0;
        state = State.WARMUP;
        return Transition.START_WARMUP;
    }

    /** Checks the camera, fixed client conditions, and host/model readiness before measuring a frame. */
    Transition validateActiveFrame(boolean sceneReady, boolean cameraReady, boolean clientConditionsReady) {
        if (state != State.WARMUP && state != State.SAMPLING) {
            return Transition.IGNORED;
        }
        if (!cameraReady) {
            return Transition.INVALID_CAMERA;
        }
        if (!clientConditionsReady) {
            return Transition.INVALID_CLIENT_CONDITIONS;
        }
        if (!sceneReady) {
            return Transition.INVALID_SCENE;
        }
        return Transition.READY_FOR_FRAME;
    }

    /**
     * Advances only a completed frame that submitted every frozen target host.
     *
     * <p>Invalid decisions deliberately do not mutate state: the controller must first release
     * its JFR and measurement-service resources through {@link #invalidate()}.</p>
     */
    Transition acceptExactSubmission(boolean exactTargetSubmission) {
        if (state != State.WARMUP && state != State.SAMPLING) {
            return Transition.IGNORED;
        }
        if (!exactTargetSubmission) {
            return Transition.INVALID_SUBMISSIONS;
        }
        if (state == State.WARMUP) {
            warmupFrames = Math.incrementExact(warmupFrames);
            if (warmupFrames == P7ReferenceScenario.WARMUP_FRAME_COUNT) {
                state = State.SAMPLING;
                return Transition.START_SAMPLING;
            }
            return Transition.WARMUP_FRAME;
        }

        sampleFrames = Math.incrementExact(sampleFrames);
        if (sampleFrames == P7ReferenceScenario.SAMPLE_FRAME_COUNT) {
            state = State.COMPLETING;
            return Transition.COMPLETE_CAPTURE;
        }
        return Transition.SAMPLE_FRAME;
    }

    /** Marks a successfully written runtime capture as terminal and rejects later completion. */
    boolean markComplete() {
        if (state != State.COMPLETING) {
            return false;
        }
        state = State.COMPLETE;
        return true;
    }

    /** Marks any active/in-progress capture invalid exactly once so its owner can release resources. */
    boolean invalidate() {
        if (state == State.DISABLED || state == State.COMPLETE || state == State.INVALID) {
            return false;
        }
        state = State.INVALID;
        return true;
    }
}
