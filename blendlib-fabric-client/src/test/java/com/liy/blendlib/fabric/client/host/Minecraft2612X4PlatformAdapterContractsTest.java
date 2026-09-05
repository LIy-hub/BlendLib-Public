package com.liy.blendlib.fabric.client.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.HostKind;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import com.liy.blendlib.spi.experimental.PlatformAdapterControl;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Executable contract tests for X4's controlled X1 PlatformAdapter bridge. */
class Minecraft2612X4PlatformAdapterContractsTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x4_test:platform");
    private static final BlendAnimationKey ANIMATION = BlendAnimationKey.parse("x4_test:idle");

    @Test
    void directBridgeRegistrationRetainsImmutableStableBindingsAndRejectsConflicts() {
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter();

        RegistrationReceipt entity = adapter.register(specification(HostKind.ENTITY, "entity"));
        RegistrationReceipt blockEntity = adapter.register(specification(HostKind.BLOCK_ENTITY, "block"));
        RegistrationReceipt item = adapter.register(specification(HostKind.ITEM, "item"));

        assertEquals(adapter.providerId(), entity.adapterId());
        assertEquals(HostKind.BLOCK_ENTITY, blockEntity.hostKind());
        assertEquals(HostKind.ITEM, item.hostKind());
        assertEquals(3, adapter.bindings().size());
        assertEquals(X4StableHostSeam.ENTITY_RENDERER, adapter.bindings().get(0).seam());
        assertEquals(X4StableHostSeam.BLOCK_ENTITY_RENDERER, adapter.bindings().get(1).seam());
        assertEquals(X4StableHostSeam.MARKER_ITEM_RENDERER, adapter.bindings().get(2).seam());
        assertEquals(3L, adapter.revision());
        assertThrows(UnsupportedOperationException.class, () -> adapter.bindings().add(null));
        assertThrows(IllegalStateException.class, () -> adapter.register(specification(HostKind.ENTITY, "entity")));
        assertEquals(3, adapter.bindings().size(), "a rejected duplicate must not publish a partial binding");
    }

    @Test
    void itemPolicyAndCallbackReentryFailClosedWithoutPublishingBindings() {
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter();
        HostRegistrationSpec<String> invalidItem = new HostRegistrationSpec<>(
                HostKind.ITEM, "invalid-item", KEY, ignored -> AnimationRequest.once(ANIMATION));
        assertThrows(RuntimeException.class, () -> adapter.register(invalidItem));
        assertEquals(0, adapter.bindings().size());

        adapter.register(specification(HostKind.ENTITY, "existing"));
        ReentrantHost reentrant = new ReentrantHost(adapter);
        assertThrows(IllegalStateException.class,
                () -> adapter.register(specification(HostKind.ENTITY, reentrant)));
        assertTrue(reentrant.called);
        assertEquals(1, adapter.bindings().size(), "reentrant equality evaluation cannot commit the outer binding");
        assertFalse(adapter.bindings().getFirst().specification().host().equals(reentrant));
    }

    @Test
    void manualControlledInstallUsesTheStableFacadeAndUninstallOwnsOnlyThisProvider() {
        PlatformAdapterControl.global().uninstall();
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter();
        X4PlatformInstallationReceipt installation = adapter.install();
        try {
            assertEquals(adapter.providerId(),
                    PlatformAdapterControl.global().adapterId().orElseThrow());
            RegistrationReceipt receipt = BlendLib.entity("facade-entity")
                    .model(KEY)
                    .animation(AnimationRequest.loop(ANIMATION))
                    .register();
            assertEquals(adapter.providerId(), receipt.adapterId());
            assertEquals(1, PlatformAdapterControl.global().registrationCount());
            assertEquals(1, adapter.bindings().size());
        } finally {
            assertTrue(adapter.uninstall(installation));
        }
        assertTrue(PlatformAdapterControl.global().adapterId().isEmpty());
        assertThrows(IllegalStateException.class, () -> adapter.register(specification(HostKind.ENTITY, "after-close")));
    }

    @Test
    void exactInstallationReceiptsRejectForeignAndStaleAbaUninstallsWithoutTouchingTheCurrentOwner() {
        PlatformAdapterControl.global().uninstall();
        Minecraft2612X4PlatformAdapter first = new Minecraft2612X4PlatformAdapter();
        Minecraft2612X4PlatformAdapter foreign = new Minecraft2612X4PlatformAdapter();
        X4PlatformInstallationReceipt firstReceipt = first.install();
        try {
            assertFalse(foreign.uninstall(firstReceipt), "a foreign bridge must not detach the installed owner");
            assertEquals(first.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
            assertThrows(RuntimeException.class, foreign::install, "conflicting install must roll back without replacing A");
            assertEquals(first.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
            assertTrue(first.uninstall(firstReceipt));
            assertTrue(PlatformAdapterControl.global().adapterId().isEmpty());

            X4PlatformInstallationReceipt foreignReceipt = foreign.install();
            try {
                assertFalse(first.uninstall(firstReceipt), "stale A receipt must not uninstall a newer B installation");
                assertEquals(foreign.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
            } finally {
                assertTrue(foreign.uninstall(foreignReceipt));
            }
        } finally {
            PlatformAdapterControl.global().uninstall();
        }
    }

    @Test
    void fatalCallbacksDetachLocalStateReleaseExactControlOwnershipAndRethrowTheSameFatal() {
        PlatformAdapterControl.global().uninstall();
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter();
        adapter.install();
        adapter.register(specification(HostKind.ENTITY, "existing"));
        OutOfMemoryError outOfMemory = new OutOfMemoryError("x4-fatal-probe");
        FatalEqualsHost fatalHost = new FatalEqualsHost(outOfMemory);

        assertSame(outOfMemory, assertThrows(OutOfMemoryError.class,
                () -> adapter.register(specification(HostKind.ENTITY, fatalHost))));
        assertTrue(adapter.terminal());
        assertSame(outOfMemory, adapter.terminalFailureForTesting());
        assertEquals(0, adapter.bindings().size(), "fatal rollback must not retain partial stable bindings");
        assertTrue(PlatformAdapterControl.global().adapterId().isEmpty(), "fatal direct bridge call must release exact control ownership");
        assertThrows(IllegalStateException.class, () -> adapter.register(specification(HostKind.ENTITY, "after-fatal")));

        Minecraft2612X4PlatformAdapter threadDeathAdapter = new Minecraft2612X4PlatformAdapter();
        threadDeathAdapter.register(specification(HostKind.ENTITY, "existing-thread"));
        ThreadDeath threadDeath = new ThreadDeath();
        ThreadDeath thrownThreadDeath = assertThrows(
                ThreadDeath.class,
                () -> threadDeathAdapter.register(specification(HostKind.ENTITY, new FatalEqualsHost(threadDeath))));
        assertSame(threadDeath, thrownThreadDeath);
        assertTrue(threadDeathAdapter.terminal());
        assertSame(threadDeath, threadDeathAdapter.terminalFailureForTesting());
        assertEquals(0, threadDeathAdapter.bindings().size());
    }

    @Test
    void concurrentUninstallInstallCannotLeaveAnAbaOwnerOrPermitTheOldReceiptToDetachTheNewBridge() throws Exception {
        PlatformAdapterControl.global().uninstall();
        Minecraft2612X4PlatformAdapter retiring = new Minecraft2612X4PlatformAdapter();
        Minecraft2612X4PlatformAdapter replacement = new Minecraft2612X4PlatformAdapter();
        X4PlatformInstallationReceipt retiringReceipt = retiring.install();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<X4PlatformInstallationReceipt> replacementReceipt = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread uninstalling = new Thread(() -> {
            await(start);
            try {
                assertTrue(retiring.uninstall(retiringReceipt));
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            }
        }, "x4-platform-uninstall");
        Thread installing = new Thread(() -> {
            await(start);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (replacementReceipt.get() == null && System.nanoTime() < deadline) {
                try {
                    replacementReceipt.compareAndSet(null, replacement.install());
                } catch (RuntimeException ignored) {
                    Thread.yield();
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                    return;
                }
            }
            if (replacementReceipt.get() == null) {
                failure.compareAndSet(null, new AssertionError("replacement install did not complete"));
            }
        }, "x4-platform-install");
        uninstalling.start();
        installing.start();
        start.countDown();
        uninstalling.join(Duration.ofSeconds(6).toMillis());
        installing.join(Duration.ofSeconds(6).toMillis());
        assertFalse(uninstalling.isAlive());
        assertFalse(installing.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("concurrent X4 platform operation failed", failure.get());
        }
        X4PlatformInstallationReceipt activeReceipt = replacementReceipt.get();
        assertEquals(replacement.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
        assertFalse(retiring.uninstall(retiringReceipt));
        assertTrue(replacement.uninstall(activeReceipt));
    }

    @Test
    void closeBeforeGlobalPublishCancelsEveryRepeatedInstallTransactionWithoutAnOrphan() throws Exception {
        PlatformAdapterControl.global().uninstall();
        try {
            for (int attempt = 0; attempt < 128; attempt++) {
                CountDownLatch beforeGlobal = new CountDownLatch(1);
                CountDownLatch releaseGlobal = new CountDownLatch(1);
                CountDownLatch closeReturned = new CountDownLatch(1);
                AtomicReference<X4PlatformInstallationReceipt> receipt = new AtomicReference<>();
                AtomicReference<Throwable> installFailure = new AtomicReference<>();
                AtomicReference<Throwable> closeFailure = new AtomicReference<>();
                Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter(
                        new Minecraft2612X4PlatformAdapter.OperationHooks() {
                            @Override
                            public void beforeGlobalInstall(long operationEpoch) {
                                beforeGlobal.countDown();
                                await(releaseGlobal);
                            }
                        });
                Thread installing = new Thread(() -> {
                    try {
                        receipt.set(adapter.install());
                    } catch (Throwable failure) {
                        installFailure.set(failure);
                    }
                }, "x4-install-close-before-global-" + attempt);
                Thread closing = new Thread(() -> {
                    try {
                        adapter.close();
                    } catch (Throwable failure) {
                        closeFailure.set(failure);
                    } finally {
                        closeReturned.countDown();
                    }
                }, "x4-close-before-global-" + attempt);
                installing.start();
                await(beforeGlobal);
                closing.start();
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS), "close must join the live install transaction");
                releaseGlobal.countDown();
                join(installing);
                join(closing);

                assertNull(receipt.get(), "a close-cancelled install must never publish a receipt");
                assertTrue(installFailure.get() instanceof IllegalStateException);
                assertNull(closeFailure.get());
                assertNoInstallationOrOrphan(adapter);
                assertThrows(IllegalStateException.class, adapter::install);
            }
        } finally {
            PlatformAdapterControl.global().uninstall();
        }
    }

    @Test
    void closeAfterGlobalPublishBeforeReceiptRollsBackTheExactTransactionAndWaitsForCleanup() throws Exception {
        PlatformAdapterControl.global().uninstall();
        CountDownLatch afterGlobal = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        CountDownLatch closeRequested = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<X4PlatformInstallationReceipt> receipt = new AtomicReference<>();
        AtomicReference<Throwable> installFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter(
                new Minecraft2612X4PlatformAdapter.OperationHooks() {
                    @Override
                    public void afterGlobalInstallBeforeReceipt(long operationEpoch) {
                        afterGlobal.countDown();
                        await(releaseReceipt);
                    }

                    @Override
                    public void onInstallCloseRequested(long operationEpoch) {
                        closeRequested.countDown();
                    }
                });
        try {
            Thread installing = new Thread(() -> {
                try {
                    receipt.set(adapter.install());
                } catch (Throwable failure) {
                    installFailure.set(failure);
                }
            }, "x4-install-close-after-global");
            Thread closing = new Thread(() -> {
                try {
                    adapter.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeReturned.countDown();
                }
            }, "x4-close-after-global");
            installing.start();
            await(afterGlobal);
            assertEquals(adapter.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
            closing.start();
            await(closeRequested);
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS), "close must wait until exact rollback drains");
            releaseReceipt.countDown();
            join(installing);
            join(closing);

            assertNull(receipt.get());
            assertTrue(installFailure.get() instanceof IllegalStateException);
            assertNull(closeFailure.get());
            assertNoInstallationOrOrphan(adapter);
        } finally {
            releaseReceipt.countDown();
            PlatformAdapterControl.global().uninstall();
        }
    }

    @Test
    void closeRevalidatesAFalseOwnershipReadWhenInstallPublishesBeforeTheTransactionDecision() throws Exception {
        PlatformAdapterControl.global().uninstall();
        CountDownLatch beforeGlobal = new CountDownLatch(1);
        CountDownLatch releaseGlobal = new CountDownLatch(1);
        CountDownLatch afterGlobal = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        CountDownLatch firstCloseOwnershipRead = new CountDownLatch(1);
        CountDownLatch releaseCloseDecision = new CountDownLatch(1);
        CountDownLatch closeRequested = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicInteger closeOwnershipReads = new AtomicInteger();
        AtomicInteger globalInstallStarts = new AtomicInteger();
        AtomicInteger globalInstallPublishes = new AtomicInteger();
        AtomicInteger globalRollbacks = new AtomicInteger();
        AtomicInteger installCloseRequests = new AtomicInteger();
        AtomicInteger globalControlCloses = new AtomicInteger();
        AtomicReference<X4PlatformInstallationReceipt> receipt = new AtomicReference<>();
        AtomicReference<Throwable> installFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter(
                new Minecraft2612X4PlatformAdapter.OperationHooks() {
                    @Override
                    public void beforeGlobalInstall(long operationEpoch) {
                        globalInstallStarts.incrementAndGet();
                        beforeGlobal.countDown();
                        await(releaseGlobal);
                    }

                    @Override
                    public void afterGlobalInstallBeforeReceipt(long operationEpoch) {
                        globalInstallPublishes.incrementAndGet();
                        afterGlobal.countDown();
                        await(releaseReceipt);
                    }

                    @Override
                    public void beforeGlobalRollback(long operationEpoch) {
                        globalRollbacks.incrementAndGet();
                    }

                    @Override
                    public void afterCloseGlobalOwnershipReadBeforeMonitor(boolean globallyOwned) {
                        if (closeOwnershipReads.incrementAndGet() == 1) {
                            assertFalse(globallyOwned, "the pinned stale-read window must begin before global publish");
                            firstCloseOwnershipRead.countDown();
                            await(releaseCloseDecision);
                        }
                    }

                    @Override
                    public void onInstallCloseRequested(long operationEpoch) {
                        installCloseRequests.incrementAndGet();
                        closeRequested.countDown();
                    }

                    @Override
                    public void onGlobalControlClose(long operationEpoch) {
                        globalControlCloses.incrementAndGet();
                    }
                });
        Thread installing = new Thread(() -> {
            try {
                receipt.set(adapter.install());
            } catch (Throwable failure) {
                installFailure.set(failure);
            }
        }, "x4-install-stale-close-ownership");
        Thread closing = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            } finally {
                closeReturned.countDown();
            }
        }, "x4-close-stale-global-ownership");
        try {
            installing.start();
            await(beforeGlobal);
            closing.start();
            await(firstCloseOwnershipRead);
            releaseGlobal.countDown();
            await(afterGlobal);
            assertEquals(adapter.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow(),
                    "install must publish in the exact window after close's false observation");
            releaseCloseDecision.countDown();
            await(closeRequested);
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                    "close must join the cancelled install transaction");
            releaseReceipt.countDown();
            join(installing);
            join(closing);

            assertNull(receipt.get(), "a close-cancelled install must never publish a receipt");
            assertTrue(installFailure.get() instanceof IllegalStateException);
            assertNull(closeFailure.get());
            assertNoInstallationOrOrphan(adapter);
            assertThrows(IllegalStateException.class, adapter::install);
            assertEquals(1, globalInstallStarts.get());
            assertEquals(1, globalInstallPublishes.get());
            assertEquals(1, globalRollbacks.get());
            assertEquals(2, closeOwnershipReads.get(),
                    "the caller close and exact global rollback callback must each enter once");
            assertEquals(2, installCloseRequests.get(),
                    "the cancelling close and exact rollback callback must observe the same transaction");
            assertEquals(1, globalControlCloses.get(), "global ownership release must close locally exactly once");

            AtomicInteger replacementOwnershipReads = new AtomicInteger();
            AtomicInteger replacementGlobalCloses = new AtomicInteger();
            Minecraft2612X4PlatformAdapter replacement = new Minecraft2612X4PlatformAdapter(
                    new Minecraft2612X4PlatformAdapter.OperationHooks() {
                        @Override
                        public void afterCloseGlobalOwnershipReadBeforeMonitor(boolean globallyOwned) {
                            replacementOwnershipReads.incrementAndGet();
                        }

                        @Override
                        public void onGlobalControlClose(long operationEpoch) {
                            replacementGlobalCloses.incrementAndGet();
                        }
                    });
            X4PlatformInstallationReceipt replacementReceipt = replacement.install();
            assertFalse(adapter.uninstall(replacementReceipt),
                    "the cancelled adapter must not detach the replacement's exact receipt");
            assertEquals(replacement.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
            assertTrue(replacement.uninstall(replacementReceipt),
                    "replacement must install and close without raw-global cleanup of the cancelled bridge");
            assertFalse(replacement.uninstall(replacementReceipt));
            replacement.close();
            replacement.close();
            assertTrue(PlatformAdapterControl.global().adapterId().isEmpty());
            assertEquals(3, replacementOwnershipReads.get(),
                    "one control callback and two harmless repeated closes must be observable");
            assertEquals(1, replacementGlobalCloses.get(), "replacement lifecycle must close exactly once");
        } finally {
            releaseGlobal.countDown();
            releaseCloseDecision.countDown();
            releaseReceipt.countDown();
            if (installing.isAlive()) {
                join(installing);
            }
            if (closing.isAlive()) {
                join(closing);
            }
            PlatformAdapterControl.global().uninstall();
        }
    }

    @Test
    void installAndRollbackCallbackFailuresAndFatalsLeaveNoReceiptOrGlobalOwner() throws Exception {
        PlatformAdapterControl.global().uninstall();
        try {
            IllegalStateException installCallbackFailure = new IllegalStateException("install-callback");
            Minecraft2612X4PlatformAdapter failedInstall = new Minecraft2612X4PlatformAdapter(
                    new Minecraft2612X4PlatformAdapter.OperationHooks() {
                        @Override
                        public com.liy.blendlib.api.BlendResourceId providerIdForGlobalInstall(
                                com.liy.blendlib.api.BlendResourceId providerId) {
                            throw installCallbackFailure;
                        }
                    });
            assertThrows(RuntimeException.class, failedInstall::install);
            assertNoInstallationOrOrphan(failedInstall);

            OutOfMemoryError installFatal = new OutOfMemoryError("install-fatal");
            Minecraft2612X4PlatformAdapter fatalInstall = new Minecraft2612X4PlatformAdapter(
                    new Minecraft2612X4PlatformAdapter.OperationHooks() {
                        @Override
                        public com.liy.blendlib.api.BlendResourceId providerIdForGlobalInstall(
                                com.liy.blendlib.api.BlendResourceId providerId) {
                            throw installFatal;
                        }
                    });
            assertSame(installFatal, assertThrows(OutOfMemoryError.class, fatalInstall::install));
            assertTrue(fatalInstall.terminal());
            assertSame(installFatal, fatalInstall.terminalFailureForTesting());
            assertNoInstallationOrOrphan(fatalInstall);

            assertRollbackCallbackFailureLeavesNoOrphan(new IllegalStateException("rollback-callback"));
            OutOfMemoryError rollbackFatal = new OutOfMemoryError("rollback-fatal");
            assertRollbackCallbackFailureLeavesNoOrphan(rollbackFatal);
        } finally {
            // Each probe asserts that it left no semantic global owner. Do not call a second raw
            // global uninstall here: a callback-failure probe intentionally exercises the exact
            // control close path and must not be re-entered after it has already detached.
        }
    }

    @Test
    void externalGlobalDetachBlocksReplacementUntilTheCancelledInstallTransactionObservesItsExactOwnerLoss()
            throws Exception {
        PlatformAdapterControl.global().uninstall();
        CountDownLatch afterGlobal = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        CountDownLatch beforeRollback = new CountDownLatch(1);
        CountDownLatch releaseRollback = new CountDownLatch(1);
        CountDownLatch closeRequested = new CountDownLatch(1);
        CountDownLatch externalControlClose = new CountDownLatch(1);
        AtomicReference<Throwable> installFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Minecraft2612X4PlatformAdapter retiring = new Minecraft2612X4PlatformAdapter(
                new Minecraft2612X4PlatformAdapter.OperationHooks() {
                    @Override
                    public void afterGlobalInstallBeforeReceipt(long operationEpoch) {
                        afterGlobal.countDown();
                        await(releaseReceipt);
                    }

                    @Override
                    public void beforeGlobalRollback(long operationEpoch) {
                        beforeRollback.countDown();
                        await(releaseRollback);
                    }

                    @Override
                    public void onInstallCloseRequested(long operationEpoch) {
                        closeRequested.countDown();
                    }

                    @Override
                    public void onGlobalControlClose(long operationEpoch) {
                        externalControlClose.countDown();
                    }
                });
        Minecraft2612X4PlatformAdapter replacement = new Minecraft2612X4PlatformAdapter();
        try {
            Thread installing = new Thread(() -> {
                try {
                    retiring.install();
                } catch (Throwable failure) {
                    installFailure.set(failure);
                }
            }, "x4-external-detach-install");
            Thread closing = new Thread(() -> {
                try {
                    retiring.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            }, "x4-external-detach-close");
            installing.start();
            await(afterGlobal);
            closing.start();
            await(closeRequested);
            releaseReceipt.countDown();
            await(beforeRollback);

            AtomicReference<Throwable> externalFailure = new AtomicReference<>();
            Thread externalUninstall = new Thread(() -> {
                try {
                    PlatformAdapterControl.global().uninstall();
                } catch (Throwable failure) {
                    externalFailure.set(failure);
                }
            }, "x4-external-global-uninstall");
            externalUninstall.start();
            await(externalControlClose);
            assertTrue(externalUninstall.isAlive(), "the external control must remain blocked until transaction ownership resolves");
            releaseRollback.countDown();
            join(installing);
            join(closing);
            join(externalUninstall);

            assertTrue(installFailure.get() instanceof IllegalStateException);
            assertNull(closeFailure.get());
            assertNull(externalFailure.get());
            assertNoInstallationOrOrphan(retiring);

            X4PlatformInstallationReceipt replacementReceipt = replacement.install();
            try {
                assertEquals(replacement.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
                assertFalse(retiring.uninstall(replacementReceipt), "a cancelled owner must never detach an external replacement");
                assertEquals(replacement.providerId(), PlatformAdapterControl.global().adapterId().orElseThrow());
            } finally {
                assertTrue(replacement.uninstall(replacementReceipt));
            }
        } finally {
            releaseReceipt.countDown();
            releaseRollback.countDown();
            PlatformAdapterControl.global().uninstall();
        }
    }

    private static void assertRollbackCallbackFailureLeavesNoOrphan(Throwable callbackFailure) throws Exception {
        CountDownLatch afterGlobal = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        CountDownLatch closeRequested = new CountDownLatch(1);
        AtomicReference<Throwable> installFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Minecraft2612X4PlatformAdapter adapter = new Minecraft2612X4PlatformAdapter(
                new Minecraft2612X4PlatformAdapter.OperationHooks() {
                    @Override
                    public void afterGlobalInstallBeforeReceipt(long operationEpoch) {
                        afterGlobal.countDown();
                        await(releaseReceipt);
                    }

                    @Override
                    public void onInstallCloseRequested(long operationEpoch) {
                        closeRequested.countDown();
                    }

                    @Override
                    public void onGlobalControlClose(long operationEpoch) {
                        rethrow(callbackFailure);
                    }
                });
        Thread installing = new Thread(() -> {
            try {
                adapter.install();
            } catch (Throwable failure) {
                installFailure.set(failure);
            }
        }, "x4-rollback-callback-install");
        Thread closing = new Thread(() -> {
            try {
                adapter.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "x4-rollback-callback-close");
        installing.start();
        await(afterGlobal);
        closing.start();
        await(closeRequested);
        releaseReceipt.countDown();
        join(installing);
        join(closing);

        assertNoInstallationOrOrphan(adapter);
        assertTrue(installFailure.get() != null, "the transaction owner must observe rollback callback failure");
        if (callbackFailure instanceof VirtualMachineError || callbackFailure instanceof ThreadDeath) {
            assertSame(callbackFailure, installFailure.get());
            assertTrue(adapter.terminal());
            assertSame(callbackFailure, adapter.terminalFailureForTesting());
        }
        if (closeFailure.get() != null && (callbackFailure instanceof VirtualMachineError || callbackFailure instanceof ThreadDeath)) {
            assertSame(callbackFailure, closeFailure.get());
        }
    }

    private static void assertNoInstallationOrOrphan(Minecraft2612X4PlatformAdapter adapter) {
        assertTrue(PlatformAdapterControl.global().adapterId().isEmpty(), "global control must not retain a receiptless bridge");
        assertEquals(0, PlatformAdapterControl.global().registrationCount());
        assertFalse(adapter.hasInstallationForTesting(), "local bridge must not retain a receiptless installation");
        assertEquals(0, adapter.bindings().size());
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(Duration.ofSeconds(5).toMillis());
        assertFalse(thread.isAlive(), "concurrent X4 platform operation did not finish");
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }

    private static <H> HostRegistrationSpec<H> specification(HostKind hostKind, H host) {
        return new HostRegistrationSpec<>(hostKind, host, KEY, ignored -> AnimationRequest.loop(ANIMATION));
    }

    private static final class ReentrantHost {
        private final Minecraft2612X4PlatformAdapter adapter;
        private boolean called;

        private ReentrantHost(Minecraft2612X4PlatformAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public boolean equals(Object other) {
            called = true;
            adapter.register(specification(HostKind.ENTITY, "nested"));
            return false;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private static final class FatalEqualsHost {
        private final Error failure;

        private FatalEqualsHost(Error failure) {
            this.failure = failure;
        }

        @Override
        public boolean equals(Object other) {
            throw failure;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
