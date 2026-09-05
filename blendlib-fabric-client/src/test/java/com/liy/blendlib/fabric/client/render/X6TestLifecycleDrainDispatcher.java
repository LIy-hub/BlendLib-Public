package com.liy.blendlib.fabric.client.render;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic host-owner double for successful X6-plan drain tests.
 *
 * <p>Registration retains a prebuilt runnable before plan pinning. Tests explicitly pump requested
 * work from their nominated lifecycle-owner thread. Its request-failure mode keeps the admitted
 * task registered and queued, modelling the host shutdown emergency-drain obligation. Inline modes
 * are deliberately malformed-host probes. One opt-in timing probe starts a distinct test owner
 * thread and joins it before {@code requestDrain()} returns; it exists only to exercise the bridge's
 * legal owner-before-request-return race.</p>
 */
final class X6TestLifecycleDrainDispatcher implements X6LifecycleDrainDispatcher {
    private final Object lock = new Object();
    private final Map<Runnable, Entry> registered = new IdentityHashMap<>();
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private int registrationCount;
    private int requestCount;
    private int cancelCount;
    private Throwable nextRegistrationFailure;
    private Throwable nextRetainedRequestFailure;
    private boolean inlineNextRegistration;
    private boolean inlineNextRequest;
    private int inlineNextRequestRuns;
    private String nextDistinctOwnerThreadName;
    private Throwable lastDistinctOwnerFailure;
    private Runnable lastRegistered;

    @Override
    public Admission register(Runnable prebuiltDrain) {
        Entry entry;
        boolean inline;
        synchronized (lock) {
            prebuiltDrain = Objects.requireNonNull(prebuiltDrain, "prebuiltDrain");
            if (nextRegistrationFailure != null) {
                Throwable failure = nextRegistrationFailure;
                nextRegistrationFailure = null;
                rethrow(failure);
            }
            entry = new Entry(prebuiltDrain);
            registered.put(prebuiltDrain, entry);
            lastRegistered = prebuiltDrain;
            registrationCount++;
            inline = inlineNextRegistration;
            inlineNextRegistration = false;
        }
        if (inline) {
            prebuiltDrain.run();
        }
        return new TestAdmission(entry);
    }

    void rejectNextRegistration(Throwable rejection) {
        synchronized (lock) {
            nextRegistrationFailure = Objects.requireNonNull(rejection, "rejection");
        }
    }

    void rejectNextRequestAfterRetention(Throwable rejection) {
        synchronized (lock) {
            nextRetainedRequestFailure = Objects.requireNonNull(rejection, "rejection");
        }
    }

    void inlineNextRegistration() {
        synchronized (lock) {
            inlineNextRegistration = true;
        }
    }

    void inlineNextRequest() {
        synchronized (lock) {
            inlineNextRequest = true;
        }
    }

    /** Schedules the same malformed caller-stack invocation repeatedly inside one request call. */
    void inlineNextRequestRuns(int runs) {
        if (runs <= 0) {
            throw new IllegalArgumentException("inline request runs must be positive");
        }
        synchronized (lock) {
            inlineNextRequestRuns = runs;
        }
    }

    /**
     * Forces a real distinct owner thread to run the admitted bridge before this test admission's
     * {@link Admission#requestDrain()} returns. The blocking join is a deterministic test-only
     * interleaving aid, not a production dispatcher recommendation.
     */
    void runNextRequestOnDistinctOwnerBeforeReturn(String ownerThreadName) {
        synchronized (lock) {
            nextDistinctOwnerThreadName = Objects.requireNonNull(ownerThreadName, "ownerThreadName");
            lastDistinctOwnerFailure = null;
        }
    }

    Throwable lastDistinctOwnerFailure() {
        synchronized (lock) {
            return lastDistinctOwnerFailure;
        }
    }

    int registrationCount() {
        synchronized (lock) {
            return registrationCount;
        }
    }

    int requestCount() {
        synchronized (lock) {
            return requestCount;
        }
    }

    int cancelCount() {
        synchronized (lock) {
            return cancelCount;
        }
    }

    int registeredCount() {
        synchronized (lock) {
            return registered.size();
        }
    }

    int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    void runNext() {
        Entry entry;
        synchronized (lock) {
            entry = pending.pollFirst();
        }
        if (entry != null) {
            entry.runnable.run();
        }
    }

    void runAll() {
        while (true) {
            Entry entry;
            synchronized (lock) {
                entry = pending.pollFirst();
            }
            if (entry == null) {
                return;
            }
            entry.runnable.run();
        }
    }

    Runnable lastRegisteredForDefensiveOwnerReplay() {
        synchronized (lock) {
            return Objects.requireNonNull(lastRegistered, "no registered drain");
        }
    }

    private final class TestAdmission implements Admission {
        private final Entry entry;

        private TestAdmission(Entry entry) {
            this.entry = entry;
        }

        @Override
        public void requestDrain() {
            Runnable inline = null;
            int inlineRuns = 0;
            String distinctOwnerThreadName = null;
            Throwable rejection;
            synchronized (lock) {
                if (entry.cancelled) {
                    throw new IllegalStateException("cancelled X6 test admission cannot be requested");
                }
                requestCount++;
                pending.addLast(entry);
                rejection = nextRetainedRequestFailure;
                nextRetainedRequestFailure = null;
                if (inlineNextRequest) {
                    inlineNextRequest = false;
                    inline = entry.runnable;
                    inlineRuns = 1;
                } else if (inlineNextRequestRuns > 0) {
                    inline = entry.runnable;
                    inlineRuns = inlineNextRequestRuns;
                    inlineNextRequestRuns = 0;
                }
                distinctOwnerThreadName = nextDistinctOwnerThreadName;
                nextDistinctOwnerThreadName = null;
            }
            if (inline != null) {
                for (int run = 0; run < inlineRuns; run++) {
                    inline.run();
                }
            }
            if (distinctOwnerThreadName != null) {
                AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
                Thread owner = new Thread(() -> {
                    try {
                        entry.runnable.run();
                    } catch (Throwable failure) {
                        ownerFailure.set(failure);
                    }
                }, distinctOwnerThreadName);
                owner.start();
                try {
                    owner.join(5_000L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for test lifecycle owner", exception);
                }
                if (owner.isAlive()) {
                    throw new AssertionError("test lifecycle owner did not return before request acknowledgement");
                }
                synchronized (lock) {
                    lastDistinctOwnerFailure = ownerFailure.get();
                }
            }
            if (rejection != null) {
                rethrow(rejection);
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                if (entry.cancelled) {
                    return;
                }
                entry.cancelled = true;
                registered.remove(entry.runnable);
                pending.removeIf(candidate -> candidate == entry);
                cancelCount++;
            }
        }
    }

    private static final class Entry {
        private final Runnable runnable;
        private boolean cancelled;

        private Entry(Runnable runnable) {
            this.runnable = runnable;
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("test dispatcher cannot directly throw a checked failure", failure);
    }
}
