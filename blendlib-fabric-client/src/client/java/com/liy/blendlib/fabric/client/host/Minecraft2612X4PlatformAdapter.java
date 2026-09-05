package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostKind;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.PlatformAdapter;
import com.liy.blendlib.spi.experimental.PlatformAdapterControl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicit 26.1.2 bridge from X1's stable semantic facade to integration-owned Fabric seams.
 *
 * <p>Installation is a recoverable transaction rather than a local flag around a global call.
 * Before global publish, a concurrent close cancels the transaction. After global publish but
 * before receipt publication, close waits for exact cleanup. An external X1 detach invokes this
 * adapter's {@link #close()} callback; that callback becomes a transaction barrier, preventing a
 * replacement from installing until the in-flight owner has observed that its global ownership was
 * already removed. This is necessary because X1 intentionally exposes no cross-owner raw CAS API.</p>
 */
public final class Minecraft2612X4PlatformAdapter implements PlatformAdapter {
    /** Metadata-only capability claim; it conveys no renderer or resource handle. */
    public static final BlendResourceId CAPABILITY_ID = BlendResourceId.parse("blendlib:x4-stable-host-bridge");
    private static final AtomicLong INSTANCE_SEQUENCE = new AtomicLong();
    private static final Object GLOBAL_X4_CONTROL_GATE = new Object();

    private final Object monitor = new Object();
    private final Object installationOwnerToken = new Object();
    private final OperationHooks operationHooks;
    private final BlendResourceId providerId = BlendResourceId.parse(
            "blendlib:x4-hosts-2612/" + INSTANCE_SEQUENCE.incrementAndGet());
    private final Collection<CapabilityOffer> offers = List.of(new CapabilityOffer(
            providerId, CAPABILITY_ID, CapabilityVersion.INITIAL_PROTOCOL, 0));
    private final List<X4StableHostBinding<?>> bindings = new ArrayList<>();
    private boolean registrationActive;
    private boolean uninstallActive;
    private boolean closed;
    private boolean fatalTerminated;
    private long revision;
    private long installationRevision;
    private long operationEpoch;
    private X4PlatformInstallationReceipt installationReceipt;
    private InstallTransaction activeInstall;
    private DetachTransaction activeDetach;
    private Throwable terminalFailure;

    /** Creates one explicit bridge with production no-op transaction observation. */
    public Minecraft2612X4PlatformAdapter() {
        this(OperationHooks.NOOP);
    }

    /** Package-private test seam for deterministic global-install transaction boundaries. */
    Minecraft2612X4PlatformAdapter(OperationHooks operationHooks) {
        this.operationHooks = Objects.requireNonNull(operationHooks, "operationHooks");
    }

    /** Installs this exact bridge and returns the receipt required for exact later uninstall. */
    public X4PlatformInstallationReceipt install() {
        InstallTransaction transaction;
        synchronized (monitor) {
            requireOpen();
            if (activeInstall != null || activeDetach != null || uninstallActive || installationReceipt != null) {
                throw new IllegalStateException("This X4 platform adapter already has an active installation operation");
            }
            transaction = new InstallTransaction(++operationEpoch, Thread.currentThread());
            activeInstall = transaction;
        }

        Throwable failure = null;
        try {
            operationHooks.beforeGlobalInstall(transaction.epoch);
            requireInstallViable(transaction);
            publishGlobally(transaction);
            operationHooks.afterGlobalInstallBeforeReceipt(transaction.epoch);
            synchronized (monitor) {
                requireCurrentInstall(transaction);
                if (transaction.closeRequested || closed || fatalTerminated) {
                    throw installCancellation();
                }
                if (!ownsGlobalControl()) {
                    transaction.globalDetached = true;
                    throw new IllegalStateException("X4 platform adapter lost exact global ownership before receipt publication");
                }
                installationReceipt = new X4PlatformInstallationReceipt(
                        this, providerId, ++installationRevision, installationOwnerToken);
                activeInstall = null;
                transaction.complete(null);
                return installationReceipt;
            }
        } catch (Throwable installFailure) {
            failure = installFailure;
        }

        Throwable cleanupFailure = rollbackInstallTransaction(transaction);
        Throwable outcome = selectOutcome(failure, cleanupFailure);
        synchronized (monitor) {
            completeFailedInstallLocked(transaction, outcome);
        }
        rethrow(outcome);
        throw new AssertionError("unreachable");
    }

    /**
     * Removes only the exact still-current installation represented by {@code receipt}.
     *
     * @return {@code true} when this receipt owned the active global bridge, otherwise false
     */
    public boolean uninstall(X4PlatformInstallationReceipt receipt) {
        X4PlatformInstallationReceipt checked = Objects.requireNonNull(receipt, "receipt");
        DetachTransaction transaction;
        synchronized (monitor) {
            if (checked.adapter() != this
                    || checked.ownerToken() != installationOwnerToken
                    || checked != installationReceipt
                    || activeInstall != null
                    || activeDetach != null
                    || uninstallActive
                    || closed) {
                return false;
            }
            if (!ownsGlobalControl()) {
                return false;
            }
            uninstallActive = true;
            transaction = new DetachTransaction(++operationEpoch, Thread.currentThread());
            activeDetach = transaction;
        }

        Throwable failure = detachExactGlobalOwner(transaction);
        boolean detached;
        synchronized (monitor) {
            detached = transaction.detached || !ownsGlobalControl();
            if (detached) {
                finishDetachedLocked();
            }
            if (activeDetach == transaction) {
                activeDetach = null;
            }
            uninstallActive = false;
            transaction.complete(failure);
        }
        if (failure != null) {
            if (isFatal(failure)) {
                terminalizeLockedOrDirect(failure);
            }
            rethrow(failure);
        }
        return detached;
    }

    @Override
    public BlendResourceId providerId() {
        return Objects.requireNonNull(operationHooks.providerIdForGlobalInstall(providerId), "operationHooks.providerIdForGlobalInstall");
    }

    @Override
    public Collection<CapabilityOffer> offers() {
        return offers;
    }

    /**
     * Accepts one stable registration after item-policy validation and deterministic duplicate
     * checking. User-owned callbacks remain outside this bridge's monitor.
     */
    @Override
    public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
        HostRegistrationSpec<H> checked = Objects.requireNonNull(specification, "specification");
        List<X4StableHostBinding<?>> existing;
        synchronized (monitor) {
            requireOpen();
            if (registrationActive) {
                throw new IllegalStateException("X4 stable registration cannot re-enter while a callback is active");
            }
            registrationActive = true;
            existing = List.copyOf(bindings);
        }

        try {
            validateStableKind(checked.hostKind());
            validateItemAnimationOutsideMonitor(checked);
            if (hasDuplicateTargetOutsideMonitor(existing, checked)) {
                throw new IllegalStateException("Duplicate X1 stable host registration target");
            }
            X4StableHostBinding<H> accepted;
            synchronized (monitor) {
                requireOpen();
                if (!registrationActive) {
                    throw new IllegalStateException("X4 stable registration operation changed before commit");
                }
                accepted = new X4StableHostBinding<>(checked, ++revision);
                bindings.add(accepted);
            }
            return receiptFor(accepted.specification());
        } catch (Throwable failure) {
            if (isFatal(failure)) {
                terminalizeAfterFatal(failure);
            }
            rethrow(failure);
            throw new AssertionError("unreachable");
        } finally {
            synchronized (monitor) {
                registrationActive = false;
            }
        }
    }

    /** Immutable accepted-binding snapshot, ordered by bridge-local acceptance revision. */
    public List<X4StableHostBinding<?>> bindings() {
        synchronized (monitor) {
            return List.copyOf(bindings);
        }
    }

    /** Current accepted revision; zero means no stable registrations have been committed. */
    public long revision() {
        synchronized (monitor) {
            return revision;
        }
    }

    /** Whether a fatal callback terminalized this exact bridge instance. */
    public boolean terminal() {
        synchronized (monitor) {
            return fatalTerminated;
        }
    }

    /** Package-visible test diagnostic retaining the exact original fatal object identity. */
    Throwable terminalFailureForTesting() {
        synchronized (monitor) {
            return terminalFailure;
        }
    }

    /** Package-visible test evidence that local receipt publication completed. */
    boolean hasInstallationForTesting() {
        synchronized (monitor) {
            return installationReceipt != null;
        }
    }

    /**
     * Clears bridge-owned semantic bindings after control ownership releases this exact instance.
     * A direct close while a completed installation remains globally owned is still rejected; a
     * close that races install instead cancels and joins that in-flight transaction.
     */
    @Override
    public void close() {
        boolean provisionalGlobalOwnership = ownsGlobalControl();
        operationHooks.afterCloseGlobalOwnershipReadBeforeMonitor(provisionalGlobalOwnership);
        InstallTransaction installToAwait = null;
        DetachTransaction detachToAwait = null;
        boolean callbackClose;
        long callbackEpoch = 0L;
        boolean ownerReentry = false;
        boolean installCloseRequested = false;
        synchronized (GLOBAL_X4_CONTROL_GATE) {
            // Whenever both are needed, global ownership is serialized before local state.
            boolean globallyOwned = ownsGlobalControl();
            callbackClose = !globallyOwned;
            synchronized (monitor) {
                if (activeInstall != null) {
                    InstallTransaction transaction = activeInstall;
                    transaction.closeRequested = true;
                    installCloseRequested = true;
                    if (!globallyOwned) {
                        transaction.externalCloseObserved = true;
                        transaction.globalDetached |= transaction.globalPublished;
                    }
                    callbackEpoch = transaction.epoch;
                    ownerReentry = transaction.ownerThread == Thread.currentThread();
                    if (!ownerReentry) {
                        installToAwait = transaction;
                    }
                } else if (activeDetach != null) {
                    DetachTransaction transaction = activeDetach;
                    if (!globallyOwned) {
                        transaction.detached = true;
                        if (transaction.ownerThread != Thread.currentThread()) {
                            transaction.externalDetach = true;
                        }
                        finishDetachedLocked();
                    }
                    callbackEpoch = transaction.epoch;
                    ownerReentry = transaction.ownerThread == Thread.currentThread();
                    if (!ownerReentry) {
                        detachToAwait = transaction;
                    }
                } else {
                    if (closed) {
                        return;
                    }
                    if (registrationActive && !fatalTerminated) {
                        throw new IllegalStateException("X4 platform adapter cannot close during a registration callback");
                    }
                    if (globallyOwned && !uninstallActive && !fatalTerminated) {
                        throw new IllegalStateException("Use the exact X4 platform installation receipt to uninstall this bridge");
                    }
                    callbackEpoch = operationEpoch;
                    finishDetachedLocked();
                }
            }
        }

        if (installCloseRequested) {
            operationHooks.onInstallCloseRequested(callbackEpoch);
        }
        if (callbackClose) {
            try {
                operationHooks.onGlobalControlClose(callbackEpoch);
            } catch (Throwable callbackFailure) {
                if (isFatal(callbackFailure)) {
                    synchronized (monitor) {
                        recordTerminalFailureLocked(callbackFailure);
                    }
                }
                rethrow(callbackFailure);
            }
        }
        if (ownerReentry) {
            return;
        }
        if (installToAwait != null) {
            awaitInstallCompletion(installToAwait);
        }
        if (detachToAwait != null) {
            awaitDetachCompletion(detachToAwait);
        }
    }

    private void publishGlobally(InstallTransaction transaction) {
        synchronized (GLOBAL_X4_CONTROL_GATE) {
            requireInstallViable(transaction);
            PlatformAdapterControl.global().install(this);
            boolean exactOwner = ownsGlobalControl();
            synchronized (monitor) {
                requireCurrentInstall(transaction);
                if (exactOwner) {
                    transaction.globalPublished = true;
                } else {
                    transaction.globalDetached = true;
                }
            }
            if (!exactOwner) {
                throw new IllegalStateException("X4 platform adapter global install did not retain exact instance ownership");
            }
        }
    }

    private void requireInstallViable(InstallTransaction transaction) {
        synchronized (monitor) {
            requireCurrentInstall(transaction);
            if (transaction.closeRequested || closed || fatalTerminated) {
                throw installCancellation();
            }
        }
    }

    private Throwable rollbackInstallTransaction(InstallTransaction install) {
        DetachTransaction detach = null;
        synchronized (monitor) {
            if (install.globalPublished && !install.globalDetached) {
                detach = new DetachTransaction(++operationEpoch, install.ownerThread);
                activeDetach = detach;
            }
        }
        if (detach == null) {
            return null;
        }

        Throwable hookFailure = null;
        try {
            operationHooks.beforeGlobalRollback(install.epoch);
        } catch (Throwable failure) {
            hookFailure = failure;
        }
        Throwable detachFailure = detachExactGlobalOwner(detach);
        Throwable result = selectOutcome(hookFailure, detachFailure);
        synchronized (monitor) {
            if (detach.detached || !ownsGlobalControl()) {
                install.globalDetached = true;
                finishDetachedLocked();
            }
            if (activeDetach == detach) {
                activeDetach = null;
            }
            detach.complete(result);
        }
        return result;
    }

    /**
     * Calls the only X1 detach seam only while this adapter remains the observed global owner.
     * If an external X1 caller wins the race, its close callback marks {@code externalDetach} and
     * blocks that caller until this operation sees the detach, so this method never follows it with
     * a second unqualified uninstall against a replacement.
     */
    private Throwable detachExactGlobalOwner(DetachTransaction transaction) {
        Throwable failure = null;
        synchronized (GLOBAL_X4_CONTROL_GATE) {
            if (!ownsGlobalControl()) {
                synchronized (monitor) {
                    transaction.detached = true;
                }
                return null;
            }
            try {
                PlatformAdapterControl.global().uninstall();
            } catch (Throwable detachFailure) {
                failure = detachFailure;
            } finally {
                synchronized (monitor) {
                    if (!ownsGlobalControl()) {
                        transaction.detached = true;
                    }
                }
            }
        }
        synchronized (monitor) {
            if (transaction.externalDetach && transaction.detached && !isFatal(failure)) {
                // The external owner already detached us while its control call was held behind our
                // callback barrier. Its transient control-busy result is not a failed cleanup.
                return null;
            }
        }
        return failure;
    }

    private void completeFailedInstallLocked(InstallTransaction transaction, Throwable outcome) {
        if (activeInstall == transaction) {
            activeInstall = null;
        }
        if (isFatal(outcome)) {
            recordTerminalFailureLocked(outcome);
        } else if (transaction.closeRequested || transaction.externalCloseObserved || transaction.globalDetached) {
            finishDetachedLocked();
        }
        transaction.complete(outcome);
    }

    private void awaitInstallCompletion(InstallTransaction transaction) {
        try {
            transaction.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for X4 platform install-close transaction", exception);
        }
        Throwable outcome = transaction.outcome();
        if (isFatal(outcome)) {
            rethrow(outcome);
        }
    }

    private void awaitDetachCompletion(DetachTransaction transaction) {
        try {
            transaction.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for X4 platform detach transaction", exception);
        }
        Throwable outcome = transaction.outcome();
        if (isFatal(outcome)) {
            rethrow(outcome);
        }
    }

    private void terminalizeAfterFatal(Throwable fatalFailure) {
        DetachTransaction detach = null;
        synchronized (monitor) {
            recordTerminalFailureLocked(fatalFailure);
            if (activeInstall == null && activeDetach == null) {
                detach = new DetachTransaction(++operationEpoch, Thread.currentThread());
                activeDetach = detach;
            } else if (activeInstall != null) {
                activeInstall.closeRequested = true;
            }
        }
        if (detach != null) {
            Throwable cleanupFailure = detachExactGlobalOwner(detach);
            synchronized (monitor) {
                if (activeDetach == detach) {
                    activeDetach = null;
                }
                detach.complete(cleanupFailure);
            }
            retainSecondary(fatalFailure, cleanupFailure);
        }
    }

    private void terminalizeLockedOrDirect(Throwable fatalFailure) {
        synchronized (monitor) {
            recordTerminalFailureLocked(fatalFailure);
        }
    }

    private void recordTerminalFailureLocked(Throwable fatalFailure) {
        if (terminalFailure == null) {
            terminalFailure = fatalFailure;
        }
        fatalTerminated = true;
        closed = true;
        installationReceipt = null;
        registrationActive = false;
        bindings.clear();
    }

    private void finishDetachedLocked() {
        closed = true;
        installationReceipt = null;
        bindings.clear();
    }

    private void requireCurrentInstall(InstallTransaction transaction) {
        if (activeInstall != transaction) {
            throw new IllegalStateException("X4 platform install transaction identity changed");
        }
    }

    private boolean ownsGlobalControl() {
        return providerId.equals(PlatformAdapterControl.global().adapterId().orElse(null));
    }

    private static IllegalStateException installCancellation() {
        return new IllegalStateException("X4 platform adapter was closed before its installation receipt could be published");
    }

    private static Throwable selectOutcome(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (secondary == null || secondary == primary) {
            return primary;
        }
        if (isFatal(primary)) {
            retainSecondary(primary, secondary);
            return primary;
        }
        if (isFatal(secondary)) {
            retainSecondary(secondary, primary);
            return secondary;
        }
        retainSecondary(primary, secondary);
        return primary;
    }

    private static void validateStableKind(HostKind hostKind) {
        Objects.requireNonNull(hostKind, "specification.hostKind");
        switch (hostKind) {
            case ENTITY, BLOCK_ENTITY, ITEM -> {
                // All current stable kinds have an explicitly documented Fabric seam.
            }
        }
    }

    private static <H> void validateItemAnimationOutsideMonitor(HostRegistrationSpec<H> specification) {
        if (specification.hostKind() == HostKind.ITEM) {
            specification.animationFor(specification.host());
        }
    }

    private static boolean hasDuplicateTargetOutsideMonitor(
            List<X4StableHostBinding<?>> existing,
            HostRegistrationSpec<?> candidate) {
        for (X4StableHostBinding<?> binding : existing) {
            HostRegistrationSpec<?> registered = binding.specification();
            if (registered.hostKind() == candidate.hostKind()
                    && Objects.equals(candidate.host(), registered.host())) {
                return true;
            }
        }
        return false;
    }

    private RegistrationReceipt receiptFor(HostRegistrationSpec<?> specification) {
        BlendModelKey modelKey = specification.model();
        return new RegistrationReceipt(providerId, specification.hostKind(), modelKey);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The X4 platform adapter is closed");
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof Error;
    }

    private static void retainSecondary(Throwable primaryFailure, Throwable secondaryFailure) {
        if (secondaryFailure == null || secondaryFailure == primaryFailure) {
            return;
        }
        try {
            primaryFailure.addSuppressed(secondaryFailure);
        } catch (Throwable suppressionFailure) {
            if (isFatal(suppressionFailure)) {
                rethrow(suppressionFailure);
            }
            // Ordinary metadata failure cannot replace the original failure identity or precedence.
        }
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
        throw new IllegalStateException("X4 platform bridge callback failed", failure);
    }

    /** Package-private deterministic seams used only by the X4 transaction contract tests. */
    interface OperationHooks {
        OperationHooks NOOP = new OperationHooks() {
        };

        default BlendResourceId providerIdForGlobalInstall(BlendResourceId providerId) {
            return providerId;
        }

        default void beforeGlobalInstall(long operationEpoch) {
        }

        default void afterGlobalInstallBeforeReceipt(long operationEpoch) {
        }

        default void beforeGlobalRollback(long operationEpoch) {
        }

        default void afterCloseGlobalOwnershipReadBeforeMonitor(boolean globallyOwned) {
        }

        default void onInstallCloseRequested(long operationEpoch) {
        }

        default void onGlobalControlClose(long operationEpoch) {
        }
    }

    private static final class InstallTransaction {
        private final long epoch;
        private final Thread ownerThread;
        private final CountDownLatch completed = new CountDownLatch(1);
        private boolean closeRequested;
        private boolean globalPublished;
        private boolean globalDetached;
        private boolean externalCloseObserved;
        private Throwable outcome;

        private InstallTransaction(long epoch, Thread ownerThread) {
            this.epoch = epoch;
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        }

        private void complete(Throwable terminalOutcome) {
            outcome = terminalOutcome;
            completed.countDown();
        }

        private void await() throws InterruptedException {
            completed.await();
        }

        private Throwable outcome() {
            return outcome;
        }
    }

    private static final class DetachTransaction {
        private final long epoch;
        private final Thread ownerThread;
        private final CountDownLatch completed = new CountDownLatch(1);
        private boolean detached;
        private boolean externalDetach;
        private Throwable outcome;

        private DetachTransaction(long epoch, Thread ownerThread) {
            this.epoch = epoch;
            this.ownerThread = Objects.requireNonNull(ownerThread, "ownerThread");
        }

        private void complete(Throwable terminalOutcome) {
            outcome = terminalOutcome;
            completed.countDown();
        }

        private void await() throws InterruptedException {
            completed.await();
        }

        private Throwable outcome() {
            return outcome;
        }
    }
}
