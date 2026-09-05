package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.api.ClientModelView;
import com.liy.blendlib.fabric.client.api.ClientRegistryView;
import com.liy.blendlib.fabric.client.reload.ClientGenerationLeaseBinding;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import com.liy.blendlib.fabric.client.reload.ClientModelLookupTestSupport;
import com.liy.blendlib.fabric.client.reload.LoadedModelHandle;
import com.liy.blendlib.fabric.client.reload.ModelRegistryGeneration;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.CapabilityVersionRange;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import com.liy.blendlib.spi.experimental.ProviderLease;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleState;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exact ownership and throwable-precedence tests for the X6 post-pin publication boundary. */
@SuppressWarnings("removal")
class X6PreparedRenderPlanFactoryFailureTest {
    private static final BlendModelKey KEY = BlendModelKey.parse("x6_factory_failure:actor/base");
    private static final BlendResourceId PART = id("part");
    private static final BlendResourceId CAPABILITY = id("standard_material");
    private static final CapabilityVersionRange VERSION_RANGE = CapabilityVersion.CURRENT_PROTOCOL_RANGE;
    private static final long WORKER_TIMEOUT_SECONDS = 5L;

    @Test
    void postPinOutOfMemoryKeepsIdentityReleasesLeaseAndLetsGenerationRetireExactlyOnce() {
        TestProvider provider = new TestProvider("oome");
        X6MaterialProviderGeneration providers = providers(301L, provider);
        Inputs inputs = inputs(301L);
        TestOutOfMemoryError fatal = new TestOutOfMemoryError();

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepare(
                inputs,
                providers,
                X6MaterialProviderGeneration::pinSnapshot,
                ignored -> {
                    throw fatal;
                }));

        assertSame(fatal, thrown);
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void postPinThreadDeathKeepsIdentityReleasesLeaseAndLetsGenerationRetireExactlyOnce() {
        TestProvider provider = new TestProvider("thread_death");
        X6MaterialProviderGeneration providers = providers(302L, provider);
        Inputs inputs = inputs(302L);
        ThreadDeath fatal = new ThreadDeath();

        ThreadDeath thrown = assertThrows(ThreadDeath.class, () -> prepare(
                inputs,
                providers,
                X6MaterialProviderGeneration::pinSnapshot,
                ignored -> {
                    throw fatal;
                }));

        assertSame(fatal, thrown);
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void fatalPrimaryRetainsIdentityAndSuppressesOrdinaryCleanupFailure() {
        TestProvider provider = new TestProvider("fatal_primary_cleanup");
        X6MaterialProviderGeneration providers = providers(303L, provider);
        Inputs inputs = inputs(303L);
        TestOutOfMemoryError primary = new TestOutOfMemoryError();
        IllegalStateException cleanup = new IllegalStateException("ordinary cleanup failure");
        TestLease lease = new TestLease(303L, cleanup);

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(primary, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void fatalPrimaryRetainsIdentityAndSuppressesFatalCleanupFailure() {
        TestProvider provider = new TestProvider("fatal_primary_fatal_cleanup");
        X6MaterialProviderGeneration providers = providers(310L, provider);
        Inputs inputs = inputs(310L);
        TestOutOfMemoryError primary = new TestOutOfMemoryError();
        ThreadDeath cleanup = new ThreadDeath();
        TestLease lease = new TestLease(310L, cleanup);

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(primary, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void cleanupAssertionErrorWinsOverControlledPrimaryWithoutBecomingADiagnostic() {
        TestProvider provider = new TestProvider("assertion_cleanup");
        X6MaterialProviderGeneration providers = providers(312L, provider);
        Inputs inputs = inputs(312L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        AssertionError cleanup = new AssertionError("cleanup assertion failure");
        TestLease lease = new TestLease(312L, cleanup);

        AssertionError thrown = assertThrows(AssertionError.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(cleanup, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void cleanupLinkageErrorWinsOverControlledPrimaryWithoutBecomingADiagnostic() {
        TestProvider provider = new TestProvider("linkage_cleanup");
        X6MaterialProviderGeneration providers = providers(313L, provider);
        Inputs inputs = inputs(313L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        LinkageError cleanup = new LinkageError("cleanup linkage failure");
        TestLease lease = new TestLease(313L, cleanup);

        LinkageError thrown = assertThrows(LinkageError.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(cleanup, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void cleanupOutOfMemoryErrorWinsOverControlledPrimaryWithoutBecomingADiagnostic() {
        TestProvider provider = new TestProvider("oome_cleanup");
        X6MaterialProviderGeneration providers = providers(314L, provider);
        Inputs inputs = inputs(314L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        TestOutOfMemoryError cleanup = new TestOutOfMemoryError();
        TestLease lease = new TestLease(314L, cleanup);

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(cleanup, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void assertionErrorPrimaryRetainsIdentityAndSuppressesOrdinaryCleanupFailure() {
        TestProvider provider = new TestProvider("assertion_primary");
        X6MaterialProviderGeneration providers = providers(315L, provider);
        Inputs inputs = inputs(315L);
        AssertionError primary = new AssertionError("primary assertion failure");
        IllegalStateException cleanup = new IllegalStateException("ordinary cleanup failure");
        TestLease lease = new TestLease(315L, cleanup);

        AssertionError thrown = assertThrows(AssertionError.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(primary, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void linkageErrorPrimaryRetainsIdentityAndSuppressesOrdinaryCleanupFailure() {
        TestProvider provider = new TestProvider("linkage_primary");
        X6MaterialProviderGeneration providers = providers(316L, provider);
        Inputs inputs = inputs(316L);
        LinkageError primary = new LinkageError("primary linkage failure");
        IllegalStateException cleanup = new IllegalStateException("ordinary cleanup failure");
        TestLease lease = new TestLease(316L, cleanup);

        LinkageError thrown = assertThrows(LinkageError.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(primary, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void ordinarySuppressionAppenderFailureLeavesTheSelectedThrowableIntact() {
        AssertionError primary = new AssertionError("selected primary");
        IllegalStateException cleanup = new IllegalStateException("cleanup failure");
        AtomicInteger appendCalls = new AtomicInteger();

        X6PreparedRenderPlanFactory.addSuppressedSafely(primary, cleanup, (ignoredPrimary, ignoredSecondary) -> {
            appendCalls.incrementAndGet();
            throw new IllegalStateException("suppression bookkeeping failure");
        });

        assertEquals(1, appendCalls.get());
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void selfSuppressionNeverCallsTheAppenderOrChangesTheSelectedThrowable() {
        AssertionError primary = new AssertionError("selected primary");
        AtomicInteger appendCalls = new AtomicInteger();

        X6PreparedRenderPlanFactory.addSuppressedSafely(primary, primary, (ignoredPrimary, ignoredSecondary) ->
                appendCalls.incrementAndGet());

        assertEquals(0, appendCalls.get());
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void suppressionDisabledThrowableKeepsItsOriginalIdentityWhenMetadataCannotBeRetained() {
        SuppressionDisabledError primary = new SuppressionDisabledError("selected primary");
        IllegalStateException cleanup = new IllegalStateException("cleanup failure");

        X6PreparedRenderPlanFactory.addSuppressedSafely(
                primary, cleanup, Throwable::addSuppressed);

        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void threadDeathThrownBySuppressionAppenderEscapesWithExactIdentity() {
        AssertionError primary = new AssertionError("selected primary");
        IllegalStateException cleanup = new IllegalStateException("cleanup failure");
        ThreadDeath fatal = new ThreadDeath();

        ThreadDeath thrown = assertThrows(ThreadDeath.class, () ->
                X6PreparedRenderPlanFactory.addSuppressedSafely(
                        primary, cleanup, (ignoredPrimary, ignoredSecondary) -> {
                            throw fatal;
                        }));

        assertSame(fatal, thrown);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void outOfMemoryThrownBySuppressionAppenderEscapesWithExactIdentity() {
        AssertionError primary = new AssertionError("selected primary");
        IllegalStateException cleanup = new IllegalStateException("cleanup failure");
        TestOutOfMemoryError fatal = new TestOutOfMemoryError();

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () ->
                X6PreparedRenderPlanFactory.addSuppressedSafely(
                        primary, cleanup, (ignoredPrimary, ignoredSecondary) -> {
                            throw fatal;
                        }));

        assertSame(fatal, thrown);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void launcherConstructionOutOfMemoryFallsBackToTheCallerOwnedLeaseExactlyOnce() {
        TestProvider provider = new TestProvider("launcher_ctor_oome");
        X6MaterialProviderGeneration providers = providers(317L, provider);
        Inputs inputs = inputs(317L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        TestOutOfMemoryError launcherFailure = new TestOutOfMemoryError();
        TestLease lease = new TestLease(317L, null);
        AtomicInteger launchCalls = new AtomicInteger();

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                },
                (ignoredLease, ignoredGeneration) -> {
                    launchCalls.incrementAndGet();
                    throw launcherFailure;
                },
                Throwable::addSuppressed));

        assertSame(launcherFailure, thrown);
        assertEquals(1, launchCalls.get());
        assertEquals(1, lease.closeCalls.get());
        assertTrue(lease.isClosed());
        assertSame(Thread.currentThread(), lease.closeThread.get());
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void launcherStartThreadDeathFallsBackToTheCallerOwnedLeaseExactlyOnce() {
        TestProvider provider = new TestProvider("launcher_start_thread_death");
        X6MaterialProviderGeneration providers = providers(318L, provider);
        Inputs inputs = inputs(318L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        ThreadDeath startFailure = new ThreadDeath();
        TestLease lease = new TestLease(318L, null);
        StartFailingReleaseWorker worker = new StartFailingReleaseWorker(startFailure);

        ThreadDeath thrown = assertThrows(ThreadDeath.class, () -> prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                },
                (ignoredLease, ignoredGeneration) -> worker,
                Throwable::addSuppressed));

        assertSame(startFailure, thrown);
        assertEquals(1, worker.startCalls.get());
        assertEquals(0, worker.awaitCalls.get(), "ownership must not transfer after start throws");
        assertEquals(1, lease.closeCalls.get());
        assertTrue(lease.isClosed());
        assertSame(Thread.currentThread(), lease.closeThread.get());
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void ordinaryWorkerLaunchFailureUsesTheLaunchDiagnosticAndSuccessfulFallback() {
        TestProvider provider = new TestProvider("launcher_runtime");
        X6MaterialProviderGeneration providers = providers(31_901L, provider);
        Inputs inputs = inputs(31_901L);
        IllegalStateException launchFailure = new IllegalStateException("worker constructor failure");
        TestLease lease = new TestLease(31_901L, null);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled primary");

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                },
                (controller, ignoredGeneration) -> {
                    throw launchFailure;
                },
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        X6Diagnostic releaseDiagnostic = result.diagnostics().stream()
                .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "X6 could not launch the controlled provider-generation release worker after render-plan publication "
                        + "failed; the lifecycle owner synchronously released the lease",
                releaseDiagnostic.message());
        assertEquals(List.of(launchFailure), List.of(primary.getSuppressed()));
        assertEquals(1, lease.closeCalls.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void ordinaryLauncherStartFailureIsRetainedWhileSuccessfulFallbackKeepsTheLeaseClosed() {
        TestProvider provider = new TestProvider("launcher_start_runtime");
        X6MaterialProviderGeneration providers = providers(319L, provider);
        Inputs inputs = inputs(319L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        IllegalStateException startFailure = new IllegalStateException("worker start failure");
        TestLease lease = new TestLease(319L, null);
        StartFailingReleaseWorker worker = new StartFailingReleaseWorker(startFailure);

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                },
                (ignoredLease, ignoredGeneration) -> worker,
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.MATERIAL_UNSUPPORTED));
        X6Diagnostic launcherDiagnostic = result.diagnostics().stream()
                .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "X6 could not start the controlled provider-generation release worker after render-plan publication "
                        + "failed; the lifecycle owner synchronously released the lease",
                launcherDiagnostic.message());
        assertEquals(List.of(startFailure), List.of(primary.getSuppressed()));
        assertEquals(1, worker.startCalls.get());
        assertEquals(0, worker.awaitCalls.get(), "ownership must not transfer after start throws");
        assertEquals(1, lease.closeCalls.get());
        assertTrue(lease.isClosed());
        assertSame(Thread.currentThread(), lease.closeThread.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void fatalFallbackCloseFailureWinsOverOrdinaryPrimaryAndLauncherFailure() {
        TestProvider provider = new TestProvider("launcher_fallback_fatal");
        X6MaterialProviderGeneration providers = providers(320L, provider);
        Inputs inputs = inputs(320L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        IllegalStateException startFailure = new IllegalStateException("worker start failure");
        TestOutOfMemoryError fallbackCloseFailure = new TestOutOfMemoryError();
        TestLease lease = new TestLease(320L, fallbackCloseFailure);
        StartFailingReleaseWorker worker = new StartFailingReleaseWorker(startFailure);

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                },
                (ignoredLease, ignoredGeneration) -> worker,
                Throwable::addSuppressed));

        assertSame(fallbackCloseFailure, thrown);
        assertEquals(List.of(primary, startFailure), List.of(thrown.getSuppressed()));
        assertEquals(1, worker.startCalls.get());
        assertEquals(0, worker.awaitCalls.get());
        assertEquals(1, lease.closeCalls.get());
        assertTrue(lease.isClosed());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void fatalLauncherFailureRetainsOrdinaryFallbackCloseFailure() {
        TestProvider provider = new TestProvider("launcher_fallback_ordinary");
        X6MaterialProviderGeneration providers = providers(321L, provider);
        Inputs inputs = inputs(321L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        TestOutOfMemoryError launcherFailure = new TestOutOfMemoryError();
        IllegalStateException fallbackCloseFailure = new IllegalStateException("fallback close failure");
        TestLease lease = new TestLease(321L, fallbackCloseFailure);

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                },
                (ignoredLease, ignoredGeneration) -> {
                    throw launcherFailure;
                },
                Throwable::addSuppressed));

        assertSame(launcherFailure, thrown);
        assertEquals(List.of(primary, fallbackCloseFailure), List.of(thrown.getSuppressed()));
        assertEquals(1, lease.closeCalls.get());
        assertTrue(lease.isClosed());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void ordinaryLauncherAndFallbackFailuresRemainAttachedToTheControlledPrimary() {
        TestProvider provider = new TestProvider("launcher_fallback_both_ordinary");
        X6MaterialProviderGeneration providers = providers(323L, provider);
        Inputs inputs = inputs(323L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        IllegalStateException startFailure = new IllegalStateException("worker start failure");
        IllegalArgumentException fallbackCloseFailure = new IllegalArgumentException("fallback close failure");
        TestLease lease = new TestLease(323L, fallbackCloseFailure);
        StartFailingReleaseWorker worker = new StartFailingReleaseWorker(startFailure);

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                },
                (ignoredLease, ignoredGeneration) -> worker,
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.MATERIAL_UNSUPPORTED));
        X6Diagnostic releaseDiagnostic = result.diagnostics().stream()
                .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "X6 could not start the controlled provider-generation release worker after render-plan publication "
                        + "failed and the synchronous fallback lease close failed; the generation may remain pinned",
                releaseDiagnostic.message());
        assertEquals(List.of(startFailure, fallbackCloseFailure), List.of(primary.getSuppressed()));
        assertEquals(1, worker.startCalls.get());
        assertEquals(0, worker.awaitCalls.get());
        assertEquals(1, lease.closeCalls.get());
        assertTrue(lease.isClosed());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void workerReleaseThenStartFailureCannotDoubleCloseTheControllerOwnedLease() throws InterruptedException {
        TestProvider provider = new TestProvider("handoff_worker_close_then_start_failure");
        X6MaterialProviderGeneration providers = providers(9_101L, provider);
        Inputs inputs = inputs(9_101L);
        TestLease lease = new TestLease(9_101L, null);
        IllegalStateException startFailure = new IllegalStateException("start after worker close");

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw new X6PreparationException(X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled primary");
                },
                (controller, ignoredGeneration) -> new X6PreparedRenderPlanFactory.LeaseReleaseWorker() {
                    @Override
                    public void start() {
                        Thread worker = Thread.ofPlatform().unstarted(() -> controller.releaseOnce());
                        worker.start();
                        try {
                            worker.join();
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        throw startFailure;
                    }

                    @Override
                    public Throwable awaitCompletion() {
                        throw new AssertionError("await must not run after start failure");
                    }
                },
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        assertEquals(1, lease.closeCalls.get(), "worker completion before start failure must not permit fallback double-close");
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void noOpWorkerStartAndFalseCompletionCannotLeakTheControllerOwnedLease() {
        TestProvider provider = new TestProvider("handoff_false_start");
        X6MaterialProviderGeneration providers = providers(9_102L, provider);
        Inputs inputs = inputs(9_102L);
        TestLease lease = new TestLease(9_102L, null);

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw new X6PreparationException(X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled primary");
                },
                (controller, ignoredGeneration) -> new X6PreparedRenderPlanFactory.LeaseReleaseWorker() {
                    @Override
                    public void start() {
                        // Deliberately claim success without ever requesting release.
                    }

                    @Override
                    public Throwable awaitCompletion() {
                        return null;
                    }
                },
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        assertEquals(1, lease.closeCalls.get(), "an apparently successful worker cannot leave a post-pin lease open");
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void workerAwaitFailureCannotLeakTheControllerOwnedLease() {
        TestProvider provider = new TestProvider("handoff_await_failure");
        X6MaterialProviderGeneration providers = providers(9_103L, provider);
        Inputs inputs = inputs(9_103L);
        TestLease lease = new TestLease(9_103L, null);
        IllegalStateException awaitFailure = new IllegalStateException("await failure before release proof");

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw new X6PreparationException(X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled primary");
                },
                (controller, ignoredGeneration) -> new X6PreparedRenderPlanFactory.LeaseReleaseWorker() {
                    @Override
                    public void start() {
                        // Deliberately do not release; factory fallback must use the same owner token.
                    }

                    @Override
                    public Throwable awaitCompletion() {
                        throw awaitFailure;
                    }
                },
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        assertEquals(1, lease.closeCalls.get(), "await failure cannot be mistaken for ownership transfer");
        X6Diagnostic releaseDiagnostic = result.diagnostics().stream()
                .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "X6 could not await the started controlled provider-generation release worker after render-plan publication "
                        + "failed; the lifecycle owner synchronously released the lease",
                releaseDiagnostic.message());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void trueFallbackCloseFailureIsNotReportedAsAStartedWorkerTerminalCloseFailure() {
        TestProvider provider = new TestProvider("true_fallback_close_failure");
        X6MaterialProviderGeneration providers = providers(9_105L, provider);
        Inputs inputs = inputs(9_105L);
        IllegalStateException fallbackCloseFailure = new IllegalStateException("fallback close failure");
        TestLease lease = new TestLease(9_105L, fallbackCloseFailure);

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw new X6PreparationException(X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled primary");
                },
                (controller, ignoredGeneration) -> new X6PreparedRenderPlanFactory.LeaseReleaseWorker() {
                    @Override
                    public void start() {
                        // Deliberately leave exact-once ownership with the synchronous fallback.
                    }

                    @Override
                    public Throwable awaitCompletion() {
                        return null;
                    }
                },
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        X6Diagnostic releaseDiagnostic = result.diagnostics().stream()
                .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "X6 could not synchronously fallback-close the provider-generation lease after render-plan publication "
                        + "failed; the generation may remain pinned",
                releaseDiagnostic.message());
        assertFalse(releaseDiagnostic.message().contains("could not start"));
        assertFalse(releaseDiagnostic.message().contains("terminal lease close failed"));
        assertEquals(1, lease.closeCalls.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void launcherReleaseThenFailureCannotDoubleCloseTheControllerOwnedLease() {
        TestProvider provider = new TestProvider("handoff_launcher_close_then_failure");
        X6MaterialProviderGeneration providers = providers(9_104L, provider);
        Inputs inputs = inputs(9_104L);
        TestLease lease = new TestLease(9_104L, null);

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithSeams(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw new X6PreparationException(X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled primary");
                },
                (controller, ignoredGeneration) -> {
                    controller.releaseOnce();
                    throw new IllegalStateException("launcher failed after close");
                },
                Throwable::addSuppressed);

        assertFalse(result.publishable());
        assertEquals(1, lease.closeCalls.get(), "launcher ambiguity cannot duplicate the raw lease close");
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void blockingRealReleaseRunsOnControlledWorkerAndLeavesTheControllerFree() throws Throwable {
        BlockingRetireProvider provider = new BlockingRetireProvider("blocking_release");
        X6MaterialProviderGeneration providers = providers(311L, provider);
        Inputs inputs = inputs(311L);
        CountDownLatch assemblyEntered = new CountDownLatch(1);
        CountDownLatch failAssembly = new CountDownLatch(1);
        AtomicReference<ProviderLease> realLease = new AtomicReference<>();
        AtomicReference<X6PlanResult<X6PreparedRenderPlan>> result = new AtomicReference<>();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        Thread controller = Thread.currentThread();
        Thread caller = Thread.ofPlatform()
                .name("x6-factory-lifecycle-owner")
                .inheritInheritableThreadLocals(false)
                .unstarted(() -> {
                    try {
                        result.set(prepare(
                                inputs,
                                providers,
                                ignored -> {
                                    ProviderLease lease = providers.pinSnapshot();
                                    CountingLease counted = new CountingLease(lease);
                                    realLease.set(counted);
                                    return counted;
                                },
                                ignored -> {
                                    assemblyEntered.countDown();
                                    awaitLatch(failAssembly, "assembly failure was not released");
                                    throw new X6PreparationException(
                                            X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                                            "controlled blocking publication failure");
                                }));
                    } catch (Throwable failure) {
                        callerFailure.compareAndSet(null, failure);
                    } finally {
                        callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                    }
                });

        try {
            caller.start();
            awaitLatch(assemblyEntered, "factory did not acquire its real X1 pin");
            providers.retire();
            assertEquals(ProviderLifecycleState.RETIRING, providers.lifecycleState().orElseThrow());
            failAssembly.countDown();
            awaitLatch(provider.retireEntered, "real last-pin release did not enter provider retire");

            Thread releaseThread = provider.retireThread.get();
            assertNotSame(controller, releaseThread, "the controller must not execute terminal provider callbacks");
            assertNotSame(caller, releaseThread, "the lifecycle owner must hand release to its cleanup worker");
            assertTrue(releaseThread.getName().startsWith("blendlib-x6-lease-release-g311"));
            assertTrue(releaseThread.isAlive(), "the controlled release worker should be blocked in retire");
            assertTrue(caller.isAlive(), "the synchronous owner must await cleanup before choosing failure precedence");

            caller.interrupt();
            provider.releaseRetire.countDown();
            join(caller, "factory lifecycle-owner worker leaked");
        } finally {
            failAssembly.countDown();
            provider.releaseRetire.countDown();
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(TimeUnit.SECONDS.toMillis(WORKER_TIMEOUT_SECONDS));
            }
        }

        assertFalse(caller.isAlive(), "factory lifecycle-owner worker leaked");
        assertNull(callerFailure.get(), "factory lifecycle-owner worker failed");
        assertTrue(callerInterruptRestored.get(), "interrupted status must be restored after cleanup finishes");
        X6PlanResult<X6PreparedRenderPlan> completed = result.get();
        assertTrue(completed != null && !completed.publishable());
        assertTrue(completed.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.MATERIAL_UNSUPPORTED));
        CountingLease released = (CountingLease) realLease.get();
        assertEquals(1, released.closeCalls.get(), "the real X1 pin must be released exactly once");
        assertTrue(released.isClosed());
        assertFalse(released.closeThread.get().isAlive(), "controlled release worker leaked");
        assertSame(released.closeThread.get(), provider.retireThread.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void preStartFallbackRunsOnTheLifecycleOwnerAndLeavesTheControllerFree() throws Throwable {
        BlockingRetireProvider provider = new BlockingRetireProvider("fallback_owner");
        X6MaterialProviderGeneration providers = providers(322L, provider);
        Inputs inputs = inputs(322L);
        CountDownLatch assemblyEntered = new CountDownLatch(1);
        CountDownLatch failAssembly = new CountDownLatch(1);
        AtomicReference<CountingLease> realLease = new AtomicReference<>();
        AtomicReference<X6PlanResult<X6PreparedRenderPlan>> result = new AtomicReference<>();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        IllegalStateException startFailure = new IllegalStateException("worker start failure");
        StartFailingReleaseWorker worker = new StartFailingReleaseWorker(startFailure);
        Thread controller = Thread.currentThread();
        Thread caller = Thread.ofPlatform()
                .name("x6-factory-fallback-lifecycle-owner")
                .inheritInheritableThreadLocals(false)
                .unstarted(() -> {
                    try {
                        result.set(prepareWithSeams(
                                inputs,
                                providers,
                                ignored -> {
                                    CountingLease counted = new CountingLease(providers.pinSnapshot());
                                    realLease.set(counted);
                                    return counted;
                                },
                                ignored -> {
                                    assemblyEntered.countDown();
                                    awaitLatch(failAssembly, "assembly failure was not released");
                                    throw new X6PreparationException(
                                            X6DiagnosticCode.MATERIAL_UNSUPPORTED,
                                            "controlled fallback publication failure");
                                },
                                (ignoredLease, ignoredGeneration) -> worker,
                                Throwable::addSuppressed));
                    } catch (Throwable failure) {
                        callerFailure.compareAndSet(null, failure);
                    }
                });

        try {
            caller.start();
            awaitLatch(assemblyEntered, "factory did not acquire its real X1 pin");
            providers.retire();
            assertEquals(ProviderLifecycleState.RETIRING, providers.lifecycleState().orElseThrow());
            failAssembly.countDown();
            awaitLatch(provider.retireEntered, "caller fallback did not enter provider retire");

            Thread releaseThread = provider.retireThread.get();
            assertSame(caller, releaseThread, "a pre-start failure leaves release with the lifecycle owner");
            assertNotSame(controller, releaseThread, "the controller must remain free during fallback release");
            assertTrue(caller.isAlive(), "the lifecycle owner should await its synchronous fallback callback");
            assertEquals(1, worker.startCalls.get());
            assertEquals(0, worker.awaitCalls.get(), "a start failure must not transfer ownership");

            provider.releaseRetire.countDown();
            join(caller, "factory fallback lifecycle owner leaked");
        } finally {
            failAssembly.countDown();
            provider.releaseRetire.countDown();
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(TimeUnit.SECONDS.toMillis(WORKER_TIMEOUT_SECONDS));
            }
        }

        assertFalse(caller.isAlive(), "factory fallback lifecycle owner leaked");
        assertNull(callerFailure.get(), "factory fallback lifecycle owner failed");
        X6PlanResult<X6PreparedRenderPlan> completed = result.get();
        assertTrue(completed != null && !completed.publishable());
        assertTrue(completed.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.MATERIAL_UNSUPPORTED));
        assertTrue(completed.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE
                        && value.message().contains("synchronously released")));
        CountingLease released = realLease.get();
        assertEquals(1, released.closeCalls.get(), "the real X1 pin must be released exactly once");
        assertTrue(released.isClosed());
        assertSame(caller, released.closeThread.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void fatalCleanupWinsOverControlledPrimaryAndSuppressesThatPrimary() {
        TestProvider provider = new TestProvider("fatal_cleanup");
        X6MaterialProviderGeneration providers = providers(304L, provider);
        Inputs inputs = inputs(304L);
        X6PreparationException primary = new X6PreparationException(
                X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
        ThreadDeath cleanup = new ThreadDeath();
        TestLease lease = new TestLease(304L, cleanup);

        ThreadDeath thrown = assertThrows(ThreadDeath.class, () -> prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw primary;
                }));

        assertSame(cleanup, thrown);
        assertEquals(1, lease.closeCalls.get());
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void ordinaryCleanupFailureDoesNotMaskControlledDiagnosticOrClaimSafeRelease() {
        TestProvider provider = new TestProvider("ordinary_cleanup");
        X6MaterialProviderGeneration providers = providers(305L, provider);
        Inputs inputs = inputs(305L);
        IllegalStateException cleanup = new IllegalStateException("ordinary cleanup failure");
        TestLease lease = new TestLease(305L, cleanup);

        X6PlanResult<X6PreparedRenderPlan> result = prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw new X6PreparationException(
                            X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
                });

        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.MATERIAL_UNSUPPORTED));
        X6Diagnostic cleanupDiagnostic = result.diagnostics().stream()
                .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                .findFirst()
                .orElseThrow();
        assertTrue(cleanupDiagnostic.message().contains("may remain pinned"));
        assertFalse(cleanupDiagnostic.message().contains("safely released"));
        assertEquals(1, lease.closeCalls.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void startedWorkerTerminalCloseFailureUsesTheTerminalWorkerDiagnosticOnly() {
        TestProvider provider = new TestProvider("started_worker_terminal_close_failure");
        X6MaterialProviderGeneration providers = providers(30_501L, provider);
        Inputs inputs = inputs(30_501L);
        IllegalStateException terminalCloseFailure = new IllegalStateException("terminal close failure");
        TestLease lease = new TestLease(30_501L, terminalCloseFailure);

        X6PlanResult<X6PreparedRenderPlan> result = prepare(
                inputs,
                providers,
                ignored -> lease,
                ignored -> {
                    throw new X6PreparationException(
                            X6DiagnosticCode.MATERIAL_UNSUPPORTED, "controlled preparation failure");
                });

        assertFalse(result.publishable());
        X6Diagnostic releaseDiagnostic = result.diagnostics().stream()
                .filter(value -> value.code() == X6DiagnosticCode.PROVIDER_FAILURE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "X6 started the controlled provider-generation release worker, but its terminal lease close failed "
                        + "after render-plan publication failed; the generation may remain pinned",
                releaseDiagnostic.message());
        assertFalse(releaseDiagnostic.message().contains("could not start"));
        assertFalse(releaseDiagnostic.message().contains("synchronous fallback close failed"));
        assertEquals(1, lease.closeCalls.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void controlledPreparationAndRuntimeFailuresKeepTheirExistingDiagnosticCodes() {
        TestProvider preparationProvider = new TestProvider("controlled_preparation");
        X6MaterialProviderGeneration preparationGeneration = providers(306L, preparationProvider);
        Inputs preparationInputs = inputs(306L);
        X6PlanResult<X6PreparedRenderPlan> preparation = prepare(
                preparationInputs,
                preparationGeneration,
                X6MaterialProviderGeneration::pinSnapshot,
                ignored -> {
                    throw new X6PreparationException(
                            X6DiagnosticCode.LAYER_TARGET_MISSING, "controlled preparation failure");
                });
        assertFalse(preparation.publishable());
        assertTrue(preparation.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.LAYER_TARGET_MISSING));
        assertRetiresExactlyOnce(preparationGeneration, preparationProvider);

        TestProvider runtimeProvider = new TestProvider("controlled_runtime");
        X6MaterialProviderGeneration runtimeGeneration = providers(307L, runtimeProvider);
        Inputs runtimeInputs = inputs(307L);
        X6PlanResult<X6PreparedRenderPlan> runtime = prepare(
                runtimeInputs,
                runtimeGeneration,
                X6MaterialProviderGeneration::pinSnapshot,
                ignored -> {
                    throw new IllegalStateException("controlled runtime failure");
                });
        assertFalse(runtime.publishable());
        assertTrue(runtime.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.GEOMETRY_MISMATCH));
        assertRetiresExactlyOnce(runtimeGeneration, runtimeProvider);
    }

    @Test
    void successfulPlanRetainsLeaseUntilPlanClose() {
        TestProvider provider = new TestProvider("success");
        X6MaterialProviderGeneration providers = providers(308L, provider);
        Inputs inputs = inputs(308L);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                lifecycleOwner).plan().orElseThrow();

        providers.retire();
        assertEquals(ProviderLifecycleState.RETIRING, providers.lifecycleState().orElseThrow());
        assertEquals(0, provider.retireCalls.get());
        assertEquals(0, provider.closeCalls.get());

        plan.close();
        assertEquals(ProviderLifecycleState.RETIRING, providers.lifecycleState().orElseThrow());
        assertEquals(0, provider.retireCalls.get(), "zero-hold close is only a lifecycle-owner signal");
        assertEquals(0, provider.closeCalls.get());
        assertEquals(1, lifecycleOwner.requestCount());
        lifecycleOwner.runAll();
        assertEquals(ProviderLifecycleState.CLOSED, providers.lifecycleState().orElseThrow());
        assertEquals(1, provider.retireCalls.get());
        assertEquals(1, provider.closeCalls.get());
        plan.close();
        providers.close();
        assertEquals(1, provider.retireCalls.get());
        assertEquals(1, provider.closeCalls.get());
    }

    @Test
    void historicalSixAndSevenArgumentDescriptorsKeepTheirExactBaselineDiagnostics() {
        Inputs sixInputs = inputs(319_901L);
        TestProvider sixProvider = new TestProvider("legacy_six");
        X6MaterialProviderGeneration sixProviders = providers(319_901L, sixProvider);
        try {
            X6PlanResult<X6PreparedRenderPlan> six = X6PreparedRenderPlanFactory.prepare(
                    sixInputs.variants(),
                    sixInputs.layers(),
                    sixInputs.materials(),
                    sixInputs.geometry(),
                    sixProviders,
                    sixInputs.bindingSnapshot());
            assertFalse(six.publishable());
            assertEquals(List.of(new X6Diagnostic(
                    X6DiagnosticSeverity.ERROR,
                    X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED,
                    KEY,
                    319_901L,
                    java.util.Optional.empty(),
                    "X6 successful render-plan publication requires an explicit asynchronous lifecycle owner dispatcher")),
                    six.diagnostics());
        } finally {
            sixProviders.close();
        }

        Inputs sevenInputs = inputs(319_902L);
        TestProvider sevenProvider = new TestProvider("legacy_seven");
        X6MaterialProviderGeneration sevenProviders = providers(319_902L, sevenProvider);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        try {
            X6PlanResult<X6PreparedRenderPlan> seven = X6PreparedRenderPlanFactory.prepare(
                    sevenInputs.variants(),
                    sevenInputs.layers(),
                    sevenInputs.materials(),
                    sevenInputs.geometry(),
                    sevenProviders,
                    sevenInputs.bindingSnapshot(),
                    lifecycleOwner);
            assertTrue(seven.publishable());
            assertEquals(List.of(), seven.diagnostics(),
                    "the historical seven-argument provider-only success must not gain a lifecycle warning");
            seven.plan().orElseThrow().close();
            lifecycleOwner.runAll();
        } finally {
            sevenProviders.close();
        }
    }

    @Test
    void managedOverloadTransfersOnlyAfterAdmissionAndDrainsItsExactRegistryParentWithThePlan() {
        Inputs inputs = inputs(320L);
        ClientModelRegistry registry = new ClientModelRegistry();
        registry.publish(loadedRegistryGeneration(320L, inputs.bindingSnapshot().handle()));
        ClientGenerationLeaseBinding callerBinding = ClientModelLookupTestSupport.sourceOwnedBinding(
                registry, KEY, inputs.bindingSnapshot());
        TestProvider rejectedProvider = new TestProvider("managed_admission_rejected");
        X6MaterialProviderGeneration rejectedProviders = providers(320L, rejectedProvider);
        X6TestLifecycleDrainDispatcher rejectingOwner = new X6TestLifecycleDrainDispatcher();
        rejectingOwner.rejectNextRegistration(new IllegalStateException("owner unavailable"));

        X6PlanResult<X6PreparedRenderPlan> rejected = X6PreparedRenderPlanFactory.prepareManaged(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                rejectedProviders,
                callerBinding,
                rejectingOwner);
        assertFalse(rejected.publishable());
        assertDoesNotThrow(callerBinding::requireManagedForPlan,
                "the caller retains the parent when admission fails before factory transfer");
        assertTrue(callerBinding.permitsFrozenCpuRoute(inputs.bindingSnapshot()),
                "admission failure must leave the caller-owned frozen CPU route readable without a second policy admission");
        assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
        callerBinding.close();
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
        assertRetiresExactlyOnce(rejectedProviders, rejectedProvider);

        ClientGenerationLeaseBinding planBinding = ClientModelLookupTestSupport.sourceOwnedBinding(
                registry, KEY, inputs.bindingSnapshot());
        TestProvider provider = new TestProvider("managed_success");
        X6MaterialProviderGeneration providers = providers(320L, provider);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepareManaged(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                planBinding,
                lifecycleOwner).plan().orElseThrow();

        assertThrows(IllegalStateException.class, planBinding::requireManagedForPlan,
                "successful admission moves the caller binding into the plan-owned receipt before pin/publication");
        assertTrue(plan.permitsFrozenCpuRoute(),
                "the managed plan retains only the preparation-time CPU route primitive after transfer");
        planBinding.close();
        assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                "caller close after transfer must not release the plan-owned D1 parent");
        providers.retire();
        plan.close();
        assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
        lifecycleOwner.runAll();
        assertThrows(IllegalStateException.class, planBinding::requireManagedForPlan,
                "the owner drain must close the transferred exact registry parent exactly once");
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
        assertEquals(1, provider.retireCalls.get());
        assertEquals(1, provider.closeCalls.get());
    }

    @Test
    void managedTransferWinsBeforeProviderPinAndRejectsConcurrentAndSequentialReuse() throws Exception {
        Inputs inputs = inputs(320_001L);
        ClientModelRegistry registry = new ClientModelRegistry();
        registry.publish(loadedRegistryGeneration(320_001L, inputs.bindingSnapshot().handle()));
        ClientGenerationLeaseBinding binding = ClientModelLookupTestSupport.sourceOwnedBinding(
                registry, KEY, inputs.bindingSnapshot());
        TestProvider firstProvider = new TestProvider("managed_transfer_winner");
        X6MaterialProviderGeneration firstProviders = providers(320_001L, firstProvider);
        X6TestLifecycleDrainDispatcher firstOwner = new X6TestLifecycleDrainDispatcher();
        CountDownLatch pinEntered = new CountDownLatch(1);
        CountDownLatch releasePin = new CountDownLatch(1);
        AtomicInteger firstPinCalls = new AtomicInteger();
        AtomicReference<X6PlanResult<X6PreparedRenderPlan>> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread firstPrepare = new Thread(() -> {
            try {
                firstResult.set(X6PreparedRenderPlanFactory.prepare(
                        inputs.variants(),
                        inputs.layers(),
                        inputs.materials(),
                        inputs.geometry(),
                        firstProviders,
                        inputs.bindingSnapshot(),
                        ignored -> {
                            firstPinCalls.incrementAndGet();
                            pinEntered.countDown();
                            awaitLatch(releasePin, "managed transfer winner did not resume provider pin");
                            return firstProviders.pinSnapshot();
                        },
                        X6PreparedRenderPlanFactoryFailureTest::assembleOwnerPlan,
                        firstOwner,
                        binding));
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        }, "x6-managed-transfer-winner");
        firstPrepare.start();
        try {
            awaitLatch(pinEntered, "managed transfer did not reach the provider-pin barrier");
            assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                    "the transfer receipt must retain D1 while provider pin is blocked");
            binding.close();
            assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                    "caller close after transfer must not release the live plan parent");

            TestProvider concurrentProvider = new TestProvider("managed_transfer_concurrent_loser");
            X6MaterialProviderGeneration concurrentProviders = providers(320_001L, concurrentProvider);
            X6TestLifecycleDrainDispatcher concurrentOwner = new X6TestLifecycleDrainDispatcher();
            AtomicInteger concurrentPinCalls = new AtomicInteger();
            X6PlanResult<X6PreparedRenderPlan> concurrent = X6PreparedRenderPlanFactory.prepare(
                    inputs.variants(),
                    inputs.layers(),
                    inputs.materials(),
                    inputs.geometry(),
                    concurrentProviders,
                    inputs.bindingSnapshot(),
                    ignored -> {
                        concurrentPinCalls.incrementAndGet();
                        return concurrentProviders.pinSnapshot();
                    },
                    X6PreparedRenderPlanFactoryFailureTest::assembleOwnerPlan,
                    concurrentOwner,
                    binding);
            assertFalse(concurrent.publishable());
            assertEquals(0, concurrentPinCalls.get(), "a concurrent second prepare must fail before provider pin");
            assertRetiresExactlyOnce(concurrentProviders, concurrentProvider);

            releasePin.countDown();
            join(firstPrepare, "managed transfer winner leaked");
            assertNull(firstFailure.get());
            X6PreparedRenderPlan plan = firstResult.get().plan().orElseThrow();
            assertEquals(1, firstPinCalls.get());
            assertEquals(1, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                    "no publishable plan may exist after its sole D1 parent was released");

            TestProvider sequentialProvider = new TestProvider("managed_transfer_sequential_loser");
            X6MaterialProviderGeneration sequentialProviders = providers(320_001L, sequentialProvider);
            X6TestLifecycleDrainDispatcher sequentialOwner = new X6TestLifecycleDrainDispatcher();
            AtomicInteger sequentialPinCalls = new AtomicInteger();
            X6PlanResult<X6PreparedRenderPlan> sequential = X6PreparedRenderPlanFactory.prepare(
                    inputs.variants(),
                    inputs.layers(),
                    inputs.materials(),
                    inputs.geometry(),
                    sequentialProviders,
                    inputs.bindingSnapshot(),
                    ignored -> {
                        sequentialPinCalls.incrementAndGet();
                        return sequentialProviders.pinSnapshot();
                    },
                    X6PreparedRenderPlanFactoryFailureTest::assembleOwnerPlan,
                    sequentialOwner,
                    binding);
            assertFalse(sequential.publishable());
            assertEquals(0, sequentialPinCalls.get(), "a sequential second prepare must fail before provider pin");
            assertRetiresExactlyOnce(sequentialProviders, sequentialProvider);

            plan.close();
            firstOwner.runAll();
            assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry),
                    "the one winning plan owner releases its own receipt only after owner drain");
            assertRetiresExactlyOnce(firstProviders, firstProvider);
        } finally {
            releasePin.countDown();
            if (firstPrepare.isAlive()) {
                join(firstPrepare, "managed transfer winner did not terminate");
            }
        }
    }

    @Test
    void callerCloseBeforeManagedTransferFailsBeforeLifecycleAdmissionOrProviderPin() {
        Inputs inputs = inputs(320_002L);
        ClientModelRegistry registry = new ClientModelRegistry();
        registry.publish(loadedRegistryGeneration(320_002L, inputs.bindingSnapshot().handle()));
        ClientGenerationLeaseBinding binding = ClientModelLookupTestSupport.sourceOwnedBinding(
                registry, KEY, inputs.bindingSnapshot());
        binding.close();
        TestProvider provider = new TestProvider("managed_transfer_close_winner");
        X6MaterialProviderGeneration providers = providers(320_002L, provider);
        X6TestLifecycleDrainDispatcher owner = new X6TestLifecycleDrainDispatcher();
        AtomicInteger pinCalls = new AtomicInteger();

        X6PlanResult<X6PreparedRenderPlan> result = X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                ignored -> {
                    pinCalls.incrementAndGet();
                    return providers.pinSnapshot();
                },
                X6PreparedRenderPlanFactoryFailureTest::assembleOwnerPlan,
                owner,
                binding);

        assertFalse(result.publishable());
        assertEquals(0, owner.registrationCount());
        assertEquals(0, pinCalls.get());
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void sameRawHandleForeignRegistryCannotSupplyAManagedCompositeBeforeProviderPin() {
        Inputs inputs = inputs(321L);
        ClientModelRegistry foreignRegistry = new ClientModelRegistry();
        foreignRegistry.publish(loadedRegistryGeneration(321L, inputs.bindingSnapshot().handle()));
        ClientModelView foreignView = new ClientModelView(
                KEY, 321L, true, inputs.bindingSnapshot().handle(), Optional.empty());
        ClientModelLookup externalForeignLookup = new ClientModelLookup() {
            @Override
            public ClientRegistryView snapshot() {
                return new ClientRegistryView(321L, Map.of(KEY, foreignView), List.of());
            }

            @Override
            public ClientModelView resolve(BlendModelKey modelKey) {
                assertEquals(KEY, modelKey);
                return foreignView;
            }
        };
        ClientGenerationLeaseBinding unavailableForeignBinding = externalForeignLookup
                .acquireGenerationLeaseBinding(KEY, inputs.bindingSnapshot());
        assertFalse(unavailableForeignBinding.managed(),
                "an external B lookup sharing A's raw handle receives only the default no-resource composite");
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(foreignRegistry),
                "B has no source issuer because ClientModelRegistry exposes none without the sealed bootstrap capability");
        TestProvider provider = new TestProvider("managed_unavailable");
        X6MaterialProviderGeneration providers = providers(321L, provider);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        AtomicInteger pinCalls = new AtomicInteger();

        X6PlanResult<X6PreparedRenderPlan> result = X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                ignored -> {
                    pinCalls.incrementAndGet();
                    return providers.pinSnapshot();
                },
                ignored -> {
                    throw new AssertionError("unavailable shared ownership must fail before plan assembly");
                },
                lifecycleOwner,
                unavailableForeignBinding);
        assertFalse(result.publishable());
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED));
        assertEquals(0, pinCalls.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void managedPostPinFailureCancelsTheBridgeAndRollsBackProviderAndSharedParentsExactlyOnce() {
        Inputs inputs = inputs(322L);
        ClientModelRegistry registry = new ClientModelRegistry();
        registry.publish(loadedRegistryGeneration(322L, inputs.bindingSnapshot().handle()));
        ClientGenerationLeaseBinding sharedBinding = ClientModelLookupTestSupport.sourceOwnedBinding(
                registry, KEY, inputs.bindingSnapshot());
        TestProvider provider = new TestProvider("managed_post_pin_failure");
        X6MaterialProviderGeneration providers = providers(322L, provider);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        AtomicReference<CountingLease> pinned = new AtomicReference<>();

        X6PlanResult<X6PreparedRenderPlan> result = X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                ignored -> {
                    CountingLease counted = new CountingLease(providers.pinSnapshot());
                    pinned.set(counted);
                    return counted;
                },
                ignored -> {
                    throw new IllegalStateException("managed post-pin assembly failure");
                },
                lifecycleOwner,
                sharedBinding);

        assertFalse(result.publishable());
        assertEquals(1, lifecycleOwner.cancelCount());
        assertEquals(1, pinned.get().closeCalls.get());
        assertThrows(IllegalStateException.class, sharedBinding::requireManagedForPlan);
        assertEquals(0, ClientModelLookupTestSupport.outstandingLeaseCount(registry));
        assertRetiresExactlyOnce(providers, provider);
    }

    /**
     * A successful-plan caller must explicitly supply an asynchronous lifecycle owner.  This test
     * intentionally uses only the historical public descriptor so its first execution is a valid
     * behavioral RED against r12 rather than a missing-type compilation failure.
     */
    @Test
    void ownerlessPublicFactoryRejectsOtherwisePublishablePlanBeforeLifecycleLeaseEscapes() {
        TestProvider provider = new TestProvider("owner_required");
        X6MaterialProviderGeneration providers = providers(310L, provider);
        Inputs inputs = inputs(310L);
        X6PlanResult<X6PreparedRenderPlan> result = X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot());
        try {
            assertFalse(result.publishable(), "an ownerless public factory call must fail closed before publication");
            assertTrue(result.diagnostics().stream().anyMatch(value -> value.message().contains("lifecycle owner")),
                    "the owner-required failure must be a deterministic migration diagnostic");
            assertEquals(0, provider.retireCalls.get());
            assertEquals(0, provider.closeCalls.get());
            assertEquals(ProviderLifecycleState.PUBLISHED, providers.lifecycleState().orElseThrow());
        } finally {
            result.plan().ifPresent(plan -> {
                try {
                    plan.close();
                } catch (Throwable ignored) {
                    // r12 cleanup is intentionally caller-inline; this test only retains its RED evidence.
                }
            });
            providers.retire();
            providers.close();
        }
        assertEquals(1, provider.retireCalls.get(), "cleanup proves no generation remains leaked after the probe");
        assertEquals(1, provider.closeCalls.get());
    }

    /** Zero-hold close must likewise signal only; owner drain is the physical-release boundary. */
    @Test
    void zeroHoldOwnerPlanCloseDoesNotInvokeProviderLifecycleOnTheCloseCaller() {
        TestProvider provider = new TestProvider("zero_hold_signal_only");
        X6MaterialProviderGeneration providers = providers(311L, provider);
        Inputs inputs = inputs(311L);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan plan = X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                lifecycleOwner).plan().orElseThrow();
        try {
            providers.retire();
            plan.close();
            assertEquals(0, provider.retireCalls.get(), "zero-hold close must only signal an owner drain");
            assertEquals(0, provider.closeCalls.get(), "zero-hold close must never close the provider inline");
            assertEquals(1, lifecycleOwner.requestCount());
            lifecycleOwner.runAll();
            assertEquals(1, provider.retireCalls.get());
            assertEquals(1, provider.closeCalls.get());
        } finally {
            plan.close();
            try {
                lifecycleOwner.runAll();
            } catch (Throwable ignored) {
                // Error-specific fixtures retain their lifecycle-owner terminal evidence.
            }
            providers.close();
        }
    }

    @Test
    void rejectedOrMalformedPrePinAdmissionFailsBeforeAnyProviderLeaseCanEscape() {
        TestProvider rejectingProvider = new TestProvider("admission_rejected");
        X6MaterialProviderGeneration rejectingGeneration = providers(312L, rejectingProvider);
        Inputs rejectingInputs = inputs(312L);
        X6TestLifecycleDrainDispatcher rejectingOwner = new X6TestLifecycleDrainDispatcher();
        RuntimeException rejection = new IllegalStateException("no owner capacity");
        rejectingOwner.rejectNextRegistration(rejection);
        AtomicInteger rejectedPinCalls = new AtomicInteger();
        AtomicInteger rejectedAssemblerCalls = new AtomicInteger();
        X6PlanResult<X6PreparedRenderPlan> rejected = prepareWithLifecycleOwner(
                rejectingInputs,
                rejectingGeneration,
                ignored -> {
                    rejectedPinCalls.incrementAndGet();
                    return rejectingGeneration.pinSnapshot();
                },
                ignored -> {
                    rejectedAssemblerCalls.incrementAndGet();
                    throw new AssertionError("pre-pin admission rejection must not assemble a plan");
                },
                rejectingOwner);
        assertFalse(rejected.publishable());
        assertTrue(rejected.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED));
        assertEquals(0, rejectingOwner.registrationCount(), "rejection happens before a host retains a bridge");
        assertEquals(0, rejectedPinCalls.get(), "registration throw must fail before X1 pinning");
        assertEquals(0, rejectedAssemblerCalls.get(), "registration throw must fail before assembly");
        assertEquals(0, rejectingProvider.retireCalls.get());
        assertEquals(0, rejectingProvider.closeCalls.get());
        assertRetiresExactlyOnce(rejectingGeneration, rejectingProvider);

        TestProvider malformedProvider = new TestProvider("admission_null");
        X6MaterialProviderGeneration malformedGeneration = providers(313L, malformedProvider);
        Inputs malformedInputs = inputs(313L);
        AtomicInteger malformedPinCalls = new AtomicInteger();
        AtomicInteger malformedAssemblerCalls = new AtomicInteger();
        X6PlanResult<X6PreparedRenderPlan> malformed = prepareWithLifecycleOwner(
                malformedInputs,
                malformedGeneration,
                ignored -> {
                    malformedPinCalls.incrementAndGet();
                    return malformedGeneration.pinSnapshot();
                },
                ignored -> {
                    malformedAssemblerCalls.incrementAndGet();
                    throw new AssertionError("null pre-pin admission must not assemble a plan");
                },
                ignored -> null);
        assertFalse(malformed.publishable());
        assertTrue(malformed.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED));
        assertEquals(0, malformedPinCalls.get(), "null admission must fail before X1 pinning");
        assertEquals(0, malformedAssemblerCalls.get(), "null admission must fail before assembly");
        assertEquals(0, malformedProvider.retireCalls.get());
        assertEquals(0, malformedProvider.closeCalls.get());
        assertRetiresExactlyOnce(malformedGeneration, malformedProvider);
    }

    @Test
    void postPinAssemblyFailureCancelsAdmissionBeforeTheExistingLeaseRollbackClosesOnce() {
        TestProvider provider = new TestProvider("admission_rollback");
        X6MaterialProviderGeneration providers = providers(314L, provider);
        Inputs inputs = inputs(314L);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        AtomicReference<CountingLease> acquiredLease = new AtomicReference<>();

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithLifecycleOwner(
                inputs,
                providers,
                ignored -> {
                    CountingLease counted = new CountingLease(providers.pinSnapshot());
                    acquiredLease.set(counted);
                    return counted;
                },
                ignored -> {
                    throw new IllegalStateException("post-pin assembly failure");
                },
                lifecycleOwner);

        assertFalse(result.publishable());
        assertEquals(1, lifecycleOwner.registrationCount());
        assertEquals(1, lifecycleOwner.cancelCount(), "failed publication must revoke the registered bridge first");
        assertEquals(0, lifecycleOwner.registeredCount());
        assertEquals(0, lifecycleOwner.pendingCount());
        assertEquals(1, acquiredLease.get().closeCalls.get(), "the raw X1 pin must use only the existing rollback path");
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void inlineRegistrationFailsBeforePinAndInlineRequestStaysSignalOnlyUntilOwnerEmergencyDrain() {
        TestProvider registrationProvider = new TestProvider("inline_registration");
        X6MaterialProviderGeneration registrationGeneration = providers(315L, registrationProvider);
        Inputs registrationInputs = inputs(315L);
        X6TestLifecycleDrainDispatcher inlineRegistrationOwner = new X6TestLifecycleDrainDispatcher();
        inlineRegistrationOwner.inlineNextRegistration();
        X6PlanResult<X6PreparedRenderPlan> registrationResult = X6PreparedRenderPlanFactory.prepare(
                registrationInputs.variants(),
                registrationInputs.layers(),
                registrationInputs.materials(),
                registrationInputs.geometry(),
                registrationGeneration,
                registrationInputs.bindingSnapshot(),
                inlineRegistrationOwner);
        assertFalse(registrationResult.publishable());
        assertEquals(1, inlineRegistrationOwner.registrationCount());
        assertEquals(1, inlineRegistrationOwner.cancelCount());
        assertEquals(0, registrationProvider.retireCalls.get());
        assertEquals(0, registrationProvider.closeCalls.get());
        assertRetiresExactlyOnce(registrationGeneration, registrationProvider);

        TestProvider requestProvider = new TestProvider("inline_request");
        X6MaterialProviderGeneration requestGeneration = providers(316L, requestProvider);
        Inputs requestInputs = inputs(316L);
        X6TestLifecycleDrainDispatcher inlineRequestOwner = new X6TestLifecycleDrainDispatcher();
        X6PreparedRenderPlan requestPlan = X6PreparedRenderPlanFactory.prepare(
                requestInputs.variants(),
                requestInputs.layers(),
                requestInputs.materials(),
                requestInputs.geometry(),
                requestGeneration,
                requestInputs.bindingSnapshot(),
                inlineRequestOwner).plan().orElseThrow();
        try {
            requestGeneration.retire();
            inlineRequestOwner.inlineNextRequest();
            requestPlan.close();
            assertTrue(requestPlan.lifecycleDrain().completion().inlineViolation());
            assertEquals(0, requestProvider.retireCalls.get(), "malformed inline request may not close on close caller");
            assertEquals(0, requestProvider.closeCalls.get());
            assertEquals(1, inlineRequestOwner.pendingCount(), "pre-admitted bridge remains owner-drainable");
            IllegalStateException ownerObserved = assertThrows(IllegalStateException.class, inlineRequestOwner::runAll);
            assertSame(requestPlan.lifecycleDrain().completion().ownerContractFailure(), ownerObserved,
                    "the malformed inline request is replayed only through lifecycle-owner completion");
            assertEquals(1, requestProvider.retireCalls.get());
            assertEquals(1, requestProvider.closeCalls.get());
        } finally {
            requestPlan.close();
            try {
                inlineRequestOwner.runAll();
            } catch (Throwable ignored) {
                // The owner completion remains the only terminal-failure path.
            }
            requestGeneration.close();
        }
    }

    @Test
    void prePinRegistrationErrorEscapesFactoryExactlyWithoutPinningOrAssembly() {
        TestProvider provider = new TestProvider("admission_error");
        X6MaterialProviderGeneration providers = providers(317L, provider);
        Inputs inputs = inputs(317L);
        AssertionError registrationError = new AssertionError("registration-error");
        AtomicInteger pinCalls = new AtomicInteger();
        AtomicInteger assemblerCalls = new AtomicInteger();

        AssertionError observed = assertThrows(AssertionError.class, () -> prepareWithLifecycleOwner(
                inputs,
                providers,
                ignored -> {
                    pinCalls.incrementAndGet();
                    return providers.pinSnapshot();
                },
                ignored -> {
                    assemblerCalls.incrementAndGet();
                    throw new AssertionError("a pre-pin registration error must not assemble a plan");
                },
                ignored -> {
                    throw registrationError;
                }));

        assertSame(registrationError, observed);
        assertEquals(0, pinCalls.get(), "registration Error must escape before X1 pinning");
        assertEquals(0, assemblerCalls.get(), "registration Error must escape before assembly");
        assertEquals(0, provider.retireCalls.get());
        assertEquals(0, provider.closeCalls.get());
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void postPinCancellationErrorWinsOverOrdinaryAssemblyFailureAndStillRollsBackExactlyOnce() {
        TestProvider provider = new TestProvider("cancel_error");
        X6MaterialProviderGeneration providers = providers(318L, provider);
        Inputs inputs = inputs(318L);
        AtomicReference<CountingLease> acquiredLease = new AtomicReference<>();
        IllegalStateException assemblyFailure = new IllegalStateException("ordinary-assembly-failure");
        LinkageError cancellationError = new LinkageError("admission-cancel-error");
        X6LifecycleDrainDispatcher lifecycleOwner = ignored -> new X6LifecycleDrainDispatcher.Admission() {
            @Override
            public void requestDrain() {
                throw new AssertionError("failed publication must never request an owner drain");
            }

            @Override
            public void cancel() {
                throw cancellationError;
            }
        };

        LinkageError observed = assertThrows(LinkageError.class, () -> prepareWithLifecycleOwner(
                inputs,
                providers,
                ignored -> {
                    CountingLease counted = new CountingLease(providers.pinSnapshot());
                    acquiredLease.set(counted);
                    return counted;
                },
                ignored -> {
                    throw assemblyFailure;
                },
                lifecycleOwner));

        assertSame(cancellationError, observed);
        assertTrue(List.of(observed.getSuppressed()).contains(assemblyFailure));
        assertEquals(1, acquiredLease.get().closeCalls.get(), "cancel Error must not bypass raw-lease rollback");
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void ownerRunAfterLeaseAttachBeforeFactoryReturnFailsPublicationAndRollsBackExactlyOnce() {
        TestProvider provider = new TestProvider("early_owner_run");
        X6MaterialProviderGeneration providers = providers(319L, provider);
        Inputs inputs = inputs(319L);
        X6TestLifecycleDrainDispatcher lifecycleOwner = new X6TestLifecycleDrainDispatcher();
        AtomicReference<CountingLease> acquiredLease = new AtomicReference<>();

        X6PlanResult<X6PreparedRenderPlan> result = prepareWithLifecycleOwner(
                inputs,
                providers,
                ignored -> {
                    CountingLease counted = new CountingLease(providers.pinSnapshot());
                    acquiredLease.set(counted);
                    return counted;
                },
                input -> {
                    lifecycleOwner.lastRegisteredForDefensiveOwnerReplay().run();
                    return assembleOwnerPlan(input);
                },
                lifecycleOwner);

        assertFalse(result.publishable(), "a pre-request owner run must seal publication fail-closed");
        assertTrue(result.diagnostics().stream()
                .anyMatch(value -> value.code() == X6DiagnosticCode.LIFECYCLE_OWNER_REQUIRED));
        assertEquals(1, lifecycleOwner.cancelCount(), "failed publication must cancel the early-run admission");
        assertEquals(0, lifecycleOwner.registeredCount());
        assertEquals(1, acquiredLease.get().closeCalls.get(), "the early-run plan pin must roll back exactly once");
        assertRetiresExactlyOnce(providers, provider);
    }

    @Test
    void fatalPinBeforeLeaseAcquisitionKeepsIdentityAndDoesNotInventARelease() {
        TestProvider provider = new TestProvider("fatal_pin");
        X6MaterialProviderGeneration providers = providers(309L, provider);
        Inputs inputs = inputs(309L);
        TestOutOfMemoryError fatal = new TestOutOfMemoryError();
        AtomicInteger pinCalls = new AtomicInteger();
        AtomicInteger assemblyCalls = new AtomicInteger();

        TestOutOfMemoryError thrown = assertThrows(TestOutOfMemoryError.class, () -> prepare(
                inputs,
                providers,
                ignored -> {
                    pinCalls.incrementAndGet();
                    throw fatal;
                },
                ignored -> {
                    assemblyCalls.incrementAndGet();
                    return null;
                }));

        assertSame(fatal, thrown);
        assertEquals(1, pinCalls.get());
        assertEquals(0, assemblyCalls.get());
        assertEquals(ProviderLifecycleState.PUBLISHED, providers.lifecycleState().orElseThrow());
        assertEquals(0, provider.retireCalls.get(), "the factory must not invent a lifecycle release");
        assertEquals(0, provider.closeCalls.get(), "the factory must not invent a lifecycle release");
        assertRetiresExactlyOnce(providers, provider);
    }

    private static X6PlanResult<X6PreparedRenderPlan> prepare(
            Inputs inputs,
            X6MaterialProviderGeneration providers,
            X6PreparedRenderPlanFactory.ProviderPin providerPin,
            X6PreparedRenderPlanFactory.PostPinPlanAssembler assembler) {
        return X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                providerPin,
                assembler);
    }

    private static X6PlanResult<X6PreparedRenderPlan> prepareWithLifecycleOwner(
            Inputs inputs,
            X6MaterialProviderGeneration providers,
            X6PreparedRenderPlanFactory.ProviderPin providerPin,
            X6PreparedRenderPlanFactory.PostPinPlanAssembler assembler,
            X6LifecycleDrainDispatcher lifecycleOwner) {
        return X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                providerPin,
                assembler,
                lifecycleOwner);
    }

    private static X6PreparedRenderPlan assembleOwnerPlan(X6PreparedRenderPlanFactory.PostPinPlanInput input) {
        return new X6PreparedRenderPlan(
                input.variants(),
                input.layers(),
                input.materials(),
                input.geometry(),
                input.bindingSnapshot().handle(),
                input.boundSkinnedMeshCount(),
                input.providers().materialBindings(),
                input.lifecycleDrain());
    }

    private static X6PlanResult<X6PreparedRenderPlan> prepareWithSeams(
            Inputs inputs,
            X6MaterialProviderGeneration providers,
            X6PreparedRenderPlanFactory.ProviderPin providerPin,
            X6PreparedRenderPlanFactory.PostPinPlanAssembler assembler,
            X6PreparedRenderPlanFactory.LeaseReleaseWorkerLauncher leaseReleaseWorkerLauncher,
            X6PreparedRenderPlanFactory.SuppressionAppender suppressionAppender) {
        return X6PreparedRenderPlanFactory.prepare(
                inputs.variants(),
                inputs.layers(),
                inputs.materials(),
                inputs.geometry(),
                providers,
                inputs.bindingSnapshot(),
                providerPin,
                assembler,
                leaseReleaseWorkerLauncher,
                suppressionAppender);
    }

    private static X6MaterialProviderGeneration providers(long generation, TestProvider provider) {
        X6MaterialProviderGeneration result = X6MaterialProviderGeneration.prepareAndPublish(
                KEY,
                generation,
                List.of(provider),
                List.of(CapabilityRequest.required(CAPABILITY, VERSION_RANGE)));
        assertTrue(result.publishable());
        return result;
    }

    private static ModelRegistryGeneration loadedRegistryGeneration(long generation, ModelRenderHandle handle) {
        ModelAsset asset = new ModelAsset(
                KEY.resourceId(),
                KEY.descriptorResourceId(),
                generation,
                ModelProfile.RIGID_V1,
                1.0D,
                Map.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                new SocketTable(Map.of()),
                handle.bounds(),
                List.of());
        return new ModelRegistryGeneration(
                generation,
                Map.of(KEY, new LoadedModelHandle(KEY, asset, handle)),
                Map.of(),
                List.of());
    }

    private static void assertRetiresExactlyOnce(
            X6MaterialProviderGeneration providers, TestProvider provider) {
        providers.retire();
        providers.close();
        assertEquals(ProviderLifecycleState.CLOSED, providers.lifecycleState().orElseThrow());
        assertEquals(1, provider.retireCalls.get());
        assertEquals(1, provider.closeCalls.get());
    }

    private static Inputs inputs(long generation) {
        PreparedRenderPrimitive primitive = new PreparedRenderPrimitive(
                0,
                StaticGeometry.of(
                        new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                        new int[] {0, 1, 2}),
                new RenderMaterial(id("textures/base.png"), RenderLayer.SOLID, false, false, 0xFFFFFFFF, false));
        X6PreparedGeometryCatalog geometry = new X6PreparedGeometryCatalog(KEY, generation, Map.of(PART, primitive));
        X6MaterialPlan materials = X6MaterialPlan.prepare(KEY, generation, Map.of(
                PART,
                new X6MaterialIntent(
                        id("textures/plan.png"), X6MaterialMode.OPAQUE, false, false, null)))
                .plan().orElseThrow();
        X6VariantApplicationPlan variants = new X6VariantApplicationPlan(
                KEY,
                generation,
                List.of(new X6DrawPrimitive(PART, geometry.binding(PART), materials.material(PART), 0xFFFFFFFF)),
                List.of());
        X6RenderLayerPlan layers = X6RenderLayerPlanner.prepare(KEY, generation, List.of(), geometry)
                .plan().orElseThrow();
        X6TestRenderHandle handle = new X6TestRenderHandle(
                KEY, generation, List.of(primitive), Map.of(0, Transform.IDENTITY), false);
        ModelRenderSnapshot bindingSnapshot = new ModelRenderSnapshot(
                handle,
                Transform.IDENTITY,
                Minecraft2612StaticRigidRenderBackend.FULL_BRIGHT_PACKED_LIGHT,
                0,
                0xFFFFFFFF,
                RenderVisibility.VISIBLE,
                new CullingMetadata(handle.bounds(), true));
        return new Inputs(variants, layers, materials, geometry, bindingSnapshot);
    }

    private static BlendResourceId id(String path) {
        return BlendResourceId.parse("x6_factory_failure:" + path);
    }

    private static void awaitLatch(CountDownLatch latch, String message) {
        try {
            if (!latch.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, failure);
        }
    }

    private static void join(Thread thread, String message) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WORKER_TIMEOUT_SECONDS));
        if (thread.isAlive()) {
            throw new AssertionError(message);
        }
    }

    private record Inputs(
            X6VariantApplicationPlan variants,
            X6RenderLayerPlan layers,
            X6MaterialPlan materials,
            X6PreparedGeometryCatalog geometry,
            ModelRenderSnapshot bindingSnapshot) {
    }

    private static class TestProvider implements MaterialProvider {
        private final BlendResourceId providerId;
        private final AtomicInteger retireCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TestProvider(String name) {
            providerId = id("provider_" + name);
        }

        @Override
        public BlendResourceId providerId() {
            return providerId;
        }

        @Override
        public Collection<CapabilityOffer> offers() {
            return List.of(new CapabilityOffer(
                    providerId, CAPABILITY, CapabilityVersion.CURRENT_PROTOCOL, 1));
        }

        @Override
        public Set<BlendResourceId> supportedMaterialCapabilities() {
            return Set.of(CAPABILITY);
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            retireCalls.incrementAndGet();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class BlockingRetireProvider extends TestProvider {
        private final CountDownLatch retireEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRetire = new CountDownLatch(1);
        private final AtomicReference<Thread> retireThread = new AtomicReference<>();

        private BlockingRetireProvider(String name) {
            super(name);
        }

        @Override
        public void retire(ProviderLifecycleContext context) {
            super.retire(context);
            retireThread.set(Thread.currentThread());
            retireEntered.countDown();
            try {
                if (!releaseRetire.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("blocking retire provider timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("blocking retire provider was interrupted", failure);
            }
        }
    }

    private static final class CountingLease implements ProviderLease {
        private final ProviderLease delegate;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicReference<Thread> closeThread = new AtomicReference<>();

        private CountingLease(ProviderLease delegate) {
            this.delegate = delegate;
        }

        @Override
        public long generation() {
            return delegate.generation();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeThread.set(Thread.currentThread());
            delegate.close();
        }
    }

    private static final class TestLease implements ProviderLease {
        private final long generation;
        private final Throwable closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicReference<Thread> closeThread = new AtomicReference<>();

        private TestLease(long generation, Throwable closeFailure) {
            this.generation = generation;
            this.closeFailure = closeFailure;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public boolean isClosed() {
            return closeCalls.get() > 0;
        }

        @SuppressWarnings("removal")
        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeThread.set(Thread.currentThread());
            if (closeFailure == null) {
                return;
            }
            if (closeFailure instanceof RuntimeException exception) {
                throw exception;
            }
            if (closeFailure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unsupported test cleanup throwable", closeFailure);
        }
    }

    private static final class StartFailingReleaseWorker
            implements X6PreparedRenderPlanFactory.LeaseReleaseWorker {
        private final Throwable startFailure;
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger awaitCalls = new AtomicInteger();

        private StartFailingReleaseWorker(Throwable startFailure) {
            this.startFailure = startFailure;
        }

        @Override
        public void start() {
            startCalls.incrementAndGet();
            if (startFailure instanceof RuntimeException exception) {
                throw exception;
            }
            if (startFailure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unsupported test start throwable", startFailure);
        }

        @Override
        public Throwable awaitCompletion() {
            awaitCalls.incrementAndGet();
            return null;
        }
    }

    private static final class SuppressionDisabledError extends Error {
        private static final long serialVersionUID = 1L;

        private SuppressionDisabledError(String message) {
            super(message, null, false, true);
        }
    }

    private static final class TestOutOfMemoryError extends OutOfMemoryError {
        private static final long serialVersionUID = 1L;
    }
}
