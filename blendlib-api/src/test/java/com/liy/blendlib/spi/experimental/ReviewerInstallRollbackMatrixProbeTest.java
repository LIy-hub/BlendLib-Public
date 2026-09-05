package com.liy.blendlib.spi.experimental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendApiDiagnosticCode;
import com.liy.blendlib.api.BlendRegistrationException;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ReviewerInstallRollbackMatrixProbeTest {
    @AfterEach
    void clearControl() {
        try {
            PlatformAdapterControl.global().uninstall();
        } catch (Throwable ignored) {
            // Every scenario independently checks terminal detachment before this safety cleanup.
        }
    }

    @Test
    @SuppressWarnings("removal")
    void independentPrimaryByCleanupMatrixConsumesRollbackExactlyOnceAndSelectsFailure() {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        int scenario = 0;
        for (PrimaryKind primaryKind : PrimaryKind.values()) {
            for (CleanupKind cleanupKind : CleanupKind.values()) {
                scenario++;
                MatrixAdapter adapter = new MatrixAdapter(scenario, primaryKind, cleanupKind);
                Throwable thrown = assertThrows(Throwable.class, () -> control.install(adapter));

                assertTrue(control.adapterId().isEmpty());
                assertEquals(0, control.registrationCount());
                assertEquals(1, adapter.providerIdCalls.get());
                assertEquals(1, adapter.closeCalls.get());

                if (primaryKind == PrimaryKind.FATAL) {
                    assertSame(adapter.primaryFatal, thrown);
                    if (cleanupKind == CleanupKind.SUCCESS) {
                        assertEquals(0, thrown.getSuppressed().length);
                    } else {
                        assertEquals(1, thrown.getSuppressed().length);
                        assertSame(adapter.cleanupFailure(), thrown.getSuppressed()[0]);
                    }
                } else if (cleanupKind == CleanupKind.FATAL) {
                    assertSame(adapter.cleanupFatal, thrown);
                    assertEquals(1, thrown.getSuppressed().length);
                    if (primaryKind == PrimaryKind.ORDINARY) {
                        assertSame(adapter.primaryOrdinary, thrown.getSuppressed()[0]);
                    } else {
                        assertTrue(thrown.getSuppressed()[0] instanceof BlendRegistrationException);
                    }
                } else {
                    BlendRegistrationException stable = (BlendRegistrationException) thrown;
                    assertEquals(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE, stable.diagnostic().code());
                    assertNull(stable.getCause());
                    assertEquals(0, stable.getSuppressed().length);
                    assertFalse(stable.diagnostic().message().contains("hostile-message"));
                    if (primaryKind == PrimaryKind.ORDINARY) {
                        assertTrue(stable.diagnostic().message().contains("ReviewOrdinary"));
                    } else {
                        assertTrue(stable.diagnostic().message().contains("bounded canonical identity policy"));
                    }
                    if (cleanupKind == CleanupKind.CONTAINABLE) {
                        assertTrue(stable.diagnostic().message().contains("ReviewCleanup"));
                    }
                }

                MatrixAdapter replacement = new MatrixAdapter(10_000 + scenario, null, CleanupKind.SUCCESS);
                control.install(replacement);
                control.uninstall();
                assertEquals(1, replacement.closeCalls.get());
            }
        }
        assertEquals(9, scenario);
    }

    @Test
    void rollbackCloseHelperCannotReattachUntilOuterInstallEnds() throws Exception {
        PlatformAdapterControl control = PlatformAdapterControl.global();
        MatrixAdapter replacement = new MatrixAdapter(20_000, null, CleanupKind.SUCCESS);
        AtomicReference<Throwable> helperFailure = new AtomicReference<>();
        MatrixAdapter outer = new MatrixAdapter(20_001, PrimaryKind.ORDINARY, CleanupKind.SUCCESS);
        outer.closeHook = () -> {
            Thread helper = Thread.ofPlatform().start(() -> {
                try {
                    control.install(replacement);
                } catch (Throwable failure) {
                    helperFailure.set(failure);
                }
            });
            try {
                helper.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            assertFalse(helper.isAlive());
        };

        assertThrows(BlendRegistrationException.class, () -> control.install(outer));
        assertTrue(helperFailure.get() instanceof BlendRegistrationException);
        assertEquals(0, replacement.providerIdCalls.get());
        control.install(replacement);
        control.uninstall();
        assertEquals(1, outer.closeCalls.get());
        assertEquals(1, replacement.closeCalls.get());
    }

    private enum PrimaryKind { ORDINARY, INVALID, FATAL }
    private enum CleanupKind { SUCCESS, CONTAINABLE, FATAL }

    @SuppressWarnings("serial")
    private static final class ReviewFatal extends VirtualMachineError {
    }

    @SuppressWarnings("serial")
    private static final class ReviewOrdinary extends RuntimeException {
        @Override
        public String getMessage() {
            throw new AssertionError("hostile-message");
        }

        @Override
        public String toString() {
            throw new AssertionError("hostile-message");
        }
    }

    @SuppressWarnings("serial")
    private static final class ReviewCleanup extends RuntimeException {
        @Override
        public String getMessage() {
            throw new AssertionError("hostile-message");
        }
    }

    private static final class MatrixAdapter implements PlatformAdapter {
        private final BlendResourceId normalId;
        private final PrimaryKind primaryKind;
        private final CleanupKind cleanupKind;
        private final ReviewOrdinary primaryOrdinary = new ReviewOrdinary();
        private final ReviewFatal primaryFatal = new ReviewFatal();
        private final ReviewCleanup cleanupOrdinary = new ReviewCleanup();
        private final ReviewFatal cleanupFatal = new ReviewFatal();
        private final AtomicInteger providerIdCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private Runnable closeHook = () -> { };

        private MatrixAdapter(int scenario, PrimaryKind primaryKind, CleanupKind cleanupKind) {
            this.normalId = BlendResourceId.parse("review:matrix_" + scenario);
            this.primaryKind = primaryKind;
            this.cleanupKind = cleanupKind;
        }

        @Override
        public BlendResourceId providerId() {
            providerIdCalls.incrementAndGet();
            if (primaryKind == PrimaryKind.ORDINARY) {
                throw primaryOrdinary;
            }
            if (primaryKind == PrimaryKind.FATAL) {
                throw primaryFatal;
            }
            if (primaryKind == PrimaryKind.INVALID) {
                return BlendResourceId.parse("review:" + "x".repeat(300));
            }
            return normalId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of();
        }

        @Override
        public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
            return new RegistrationReceipt(normalId, specification.hostKind(), specification.model());
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeHook.run();
            if (cleanupKind == CleanupKind.CONTAINABLE) {
                throw cleanupOrdinary;
            }
            if (cleanupKind == CleanupKind.FATAL) {
                throw cleanupFatal;
            }
        }

        private Throwable cleanupFailure() {
            return cleanupKind == CleanupKind.FATAL ? cleanupFatal : cleanupOrdinary;
        }
    }
}
