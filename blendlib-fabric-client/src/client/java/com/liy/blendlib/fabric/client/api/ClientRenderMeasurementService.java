package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.fabric.client.perf.ClientRenderMeasurementCollector;
import java.lang.StackWalker.StackFrame;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Explicit opt-in adapter service for a client benchmark or diagnostics harness.
 *
 * <p>The public session is generic telemetry ownership. Its P7 admission object is deliberately
 * not a public type: only the exact P7 controller call site can reserve one and only the internal
 * authority call site can transfer or inspect it. The service ledger, rather than caller-shaped
 * frame/source data, is the source of that capability.</p>
 */
public final class ClientRenderMeasurementService {
    private static final Object EXCLUSIVE_LOCK = new Object();
    private static final AtomicLong NEXT_EXCLUSIVE_EPOCH = new AtomicLong();
    private static final AtomicReference<ExclusiveCapture> ACTIVE_EXCLUSIVE = new AtomicReference<>();
    private static final StackWalker CALLERS = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String P7_CONTROLLER = "com.liy.blendlib.showcase.client.P7BenchmarkCaptureController";
    private static final String P7_AUTHORITY = "com.liy.blendlib.showcase.perf.x7.live.X7LiveCaptureAuthority";

    private final Supplier<ClientAnimationRuntimeMetrics> animationMetrics;

    public ClientRenderMeasurementService(Supplier<ClientAnimationRuntimeMetrics> animationMetrics) {
        this.animationMetrics = Objects.requireNonNull(animationMetrics, "animationMetrics");
    }

    /** Attempts to acquire the sole thread-bound exclusive measurement session in this process. */
    public Optional<ClientRenderMeasurementSession> tryBeginExclusiveCapture() {
        synchronized (EXCLUSIVE_LOCK) {
            if (ACTIVE_EXCLUSIVE.get() != null || ClientRenderMeasurementCollector.capturing()) {
                return Optional.empty();
            }
            long epoch = NEXT_EXCLUSIVE_EPOCH.updateAndGet(previous -> {
                if (previous == Long.MAX_VALUE) {
                    throw new IllegalStateException("client render measurement exclusive epoch overflowed");
                }
                return previous + 1L;
            });
            ExclusiveCapture capture = new ExclusiveCapture(this, epoch, Thread.currentThread());
            if (!ACTIVE_EXCLUSIVE.compareAndSet(null, capture)) {
                return Optional.empty();
            }
            try {
                ClientRenderMeasurementCollector.beginCapture();
                return Optional.of(new ExclusiveSession(capture));
            } catch (RuntimeException exception) {
                ACTIVE_EXCLUSIVE.compareAndSet(capture, null);
                capture.invalidate();
                throw exception;
            }
        }
    }

    /**
     * Reserves the internal P7 capability for the exact controller's current exclusive session.
     *
     * <p>The opaque return value intentionally has no public carrier type or factory. Direct,
     * reflected, deserialized, split-package, or otherwise non-controller callers are rejected
     * before any ledger object is minted.</p>
     */
    public Object reserveP7ExclusiveCaptureCapability(ClientRenderMeasurementSession session) {
        requireExactCaller(P7_CONTROLLER);
        if (!(session instanceof ExclusiveSession exclusive)) {
            throw new SecurityException("P7 admission requires this service's opaque exclusive session");
        }
        ExclusiveCapture capture = exclusive.capture;
        if (Thread.currentThread() != capture.ownerThread || ACTIVE_EXCLUSIVE.get() != capture || capture.invalidated.get()) {
            throw new SecurityException("P7 admission requires the active exclusive render owner");
        }
        return capture.reserveP7Capability();
    }

    /** Internal authority bridge. The caller is checked rather than exposing a capability API. */
    public static boolean transferP7ExclusiveCaptureCapability(Object candidate, long epoch, long renderThreadId) {
        requireExactCaller(P7_AUTHORITY);
        return candidate instanceof P7ExclusiveCapability capability && capability.transfer(epoch, renderThreadId);
    }

    /** Internal authority bridge. A revoked owner cannot publish or seal a capture. */
    public static boolean isTransferredP7ExclusiveCaptureCapabilityLive(Object candidate, long epoch, long renderThreadId) {
        requireExactCaller(P7_AUTHORITY);
        return candidate instanceof P7ExclusiveCapability capability && capability.isLive(epoch, renderThreadId);
    }

    /** Internal authority bridge used by disconnect/stop cancellation and failed writer cleanup. */
    public static void revokeTransferredP7ExclusiveCaptureCapability(Object candidate) {
        requireExactCaller(P7_AUTHORITY);
        if (candidate instanceof P7ExclusiveCapability capability) {
            capability.revoke();
        }
    }

    /** Starts a legacy capture. It can invalidate, but never replace or silently stop, an exclusive owner. */
    public void beginCapture() {
        synchronized (EXCLUSIVE_LOCK) {
            ExclusiveCapture active = ACTIVE_EXCLUSIVE.get();
            if (active != null) {
                active.invalidate();
                return;
            }
            ClientRenderMeasurementCollector.beginCapture();
        }
    }

    /** Completes a legacy frame only when no exclusive owner is live. */
    public Optional<ClientRenderMeasurementSnapshot> completeFrame() {
        ExclusiveCapture active = ACTIVE_EXCLUSIVE.get();
        if (active != null) {
            active.invalidate();
            return Optional.empty();
        }
        return ClientRenderMeasurementCollector.completeFrame(animationMetrics);
    }

    /** Stops a legacy capture only when no exclusive owner is live. */
    public void endCapture() {
        synchronized (EXCLUSIVE_LOCK) {
            ExclusiveCapture active = ACTIVE_EXCLUSIVE.get();
            if (active != null) {
                active.invalidate();
                return;
            }
            ClientRenderMeasurementCollector.endCapture();
        }
    }

    /** Whether the calling render/extraction thread currently has collector capture enabled. */
    public boolean capturing() {
        return ClientRenderMeasurementCollector.capturing();
    }

    /** Test-only global cleanup seam; production callers have no reset route. */
    static void resetExclusiveForTests() {
        synchronized (EXCLUSIVE_LOCK) {
            ExclusiveCapture capture = ACTIVE_EXCLUSIVE.getAndSet(null);
            if (capture != null) {
                capture.invalidate();
            }
            NEXT_EXCLUSIVE_EPOCH.set(0L);
            ClientRenderMeasurementCollector.endCapture();
        }
    }

    private static void requireExactCaller(String expectedClassName) {
        Class<?> caller = CALLERS.walk(stream -> stream.skip(2).findFirst().map(StackFrame::getDeclaringClass).orElse(null));
        if (caller == null || !expectedClassName.equals(caller.getName())
                || caller.getClassLoader() != ClientRenderMeasurementService.class.getClassLoader()
                || caller.getModule() != ClientRenderMeasurementService.class.getModule()) {
            throw new SecurityException("internal P7 capability bridge rejected a non-owner caller");
        }
    }

    private static final class ExclusiveSession implements ClientRenderMeasurementSession {
        private final ExclusiveCapture capture;

        private ExclusiveSession(ExclusiveCapture capture) {
            this.capture = capture;
        }

        @Override
        public long epoch() {
            return capture.epoch;
        }

        @Override
        public ClientRenderMeasurementSnapshot completeFrame() {
            requireOwner("completeFrame");
            ClientRenderMeasurementSnapshot snapshot = ClientRenderMeasurementCollector
                    .completeFrame(capture.service.animationMetrics)
                    .orElseThrow(() -> invalid("exclusive measurement collector stopped before a completed frame"));
            if (ACTIVE_EXCLUSIVE.get() != capture || capture.invalidated.get()) {
                throw invalid("exclusive measurement session became stale while a frame was completing");
            }
            return snapshot;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != capture.ownerThread) {
                capture.invalidate();
                throw invalid("exclusive measurement session may only close on its owner render thread");
            }
            if (!capture.closed.compareAndSet(false, true)) {
                capture.invalidate();
                throw invalid("exclusive measurement session is stale or already closed");
            }
            try {
                ClientRenderMeasurementCollector.endCapture();
            } finally {
                capture.closeSession();
                // A transferred P7 capability retains the exclusive ledger while its writer is
                // live. This prevents a legacy begin/end caller from taking over between render
                // handoff and atomic publication; authority revocation releases it exactly once.
                if (!capture.hasTransferredP7Capability() && !ACTIVE_EXCLUSIVE.compareAndSet(capture, null)) {
                    capture.invalidate();
                    throw invalid("exclusive measurement session lost its active ledger while closing");
                }
            }
        }

        private void requireOwner(String operation) {
            if (Thread.currentThread() != capture.ownerThread) {
                capture.invalidate();
                throw invalid("exclusive measurement session may only " + operation + " on its owner render thread");
            }
            if (capture.closed.get() || ACTIVE_EXCLUSIVE.get() != capture || capture.invalidated.get()) {
                throw invalid("exclusive measurement session is stale, invalidated, or already closed");
            }
        }

        private IllegalStateException invalid(String message) {
            return new IllegalStateException(message + " (epoch=" + capture.epoch + ")");
        }
    }

    private static final class ExclusiveCapture {
        private final ClientRenderMeasurementService service;
        private final long epoch;
        private final Thread ownerThread;
        private final AtomicBoolean invalidated = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<P7ExclusiveCapability> p7Capability = new AtomicReference<>();

        private ExclusiveCapture(ClientRenderMeasurementService service, long epoch, Thread ownerThread) {
            this.service = service;
            this.epoch = epoch;
            this.ownerThread = ownerThread;
        }

        private Object reserveP7Capability() {
            P7ExclusiveCapability reserved = new P7ExclusiveCapability(this);
            if (!p7Capability.compareAndSet(null, reserved)) {
                throw new SecurityException("exclusive session already reserved a P7 admission capability");
            }
            return reserved;
        }

        private void invalidate() {
            invalidated.set(true);
            P7ExclusiveCapability capability = p7Capability.get();
            if (capability != null) {
                capability.revoke();
            }
        }

        private void closeSession() {
            P7ExclusiveCapability capability = p7Capability.get();
            if (capability != null && !capability.transferred()) {
                capability.revoke();
            }
        }

        private boolean hasTransferredP7Capability() {
            P7ExclusiveCapability capability = p7Capability.get();
            return capability != null && capability.transferred();
        }
    }

    /** Private identity object accepted only through the service's identity ledger. */
    private static final class P7ExclusiveCapability {
        private enum State { RESERVED, TRANSFERRED, REVOKED }

        private final ExclusiveCapture capture;
        private final AtomicReference<State> state = new AtomicReference<>(State.RESERVED);

        private P7ExclusiveCapability(ExclusiveCapture capture) {
            this.capture = capture;
        }

        private boolean transfer(long expectedEpoch, long expectedThreadId) {
            return capture.epoch == expectedEpoch
                    && capture.ownerThread.threadId() == expectedThreadId
                    && ACTIVE_EXCLUSIVE.get() == capture
                    && !capture.invalidated.get()
                    && state.compareAndSet(State.RESERVED, State.TRANSFERRED);
        }

        private boolean isLive(long expectedEpoch, long expectedThreadId) {
            return capture.epoch == expectedEpoch
                    && capture.ownerThread.threadId() == expectedThreadId
                    && ACTIVE_EXCLUSIVE.get() == capture
                    && !capture.invalidated.get()
                    && state.get() == State.TRANSFERRED;
        }

        private boolean transferred() {
            return state.get() == State.TRANSFERRED;
        }

        private void revoke() {
            state.set(State.REVOKED);
            ACTIVE_EXCLUSIVE.compareAndSet(capture, null);
        }
    }
}
