package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendApiDiagnostic;
import com.liy.blendlib.api.BlendApiDiagnosticCode;
import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendRegistrationException;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Controlled installation point for the one active platform adapter used by the stable facade.
 *
 * <p>Platform bootstrap code intentionally opts into this experimental type. The stable facade
 * calls it only after a builder has produced a complete immutable specification. Registrations are
 * explicitly rejected for duplicate host-kind/host targets rather than selecting a winner by call order.
 * Adapter callbacks run outside the control monitor and commit through an epoch-checked operation.</p>
 */
@ExperimentalBlendLibSpi
public final class PlatformAdapterControl {
    private static final PlatformAdapterControl GLOBAL = new PlatformAdapterControl();

    private final List<RegistrationRecord> registrations = new ArrayList<>();
    private PlatformAdapter adapter;
    private BlendResourceId installedAdapterId;
    private ProviderOwnership.Handle adapterOwnership;
    private boolean operationActive;
    private ControlOperation activeOperation;
    private long operationEpoch;

    private PlatformAdapterControl() {
    }

    /**
     * Returns the process-scoped controlled adapter installation point.
     *
     * @return global controlled adapter control
     */
    public static PlatformAdapterControl global() {
        return GLOBAL;
    }

    /**
     * Installs one platform adapter for the current process scope.
     *
     * @param platformAdapter non-null adapter installed by version-specific platform bootstrap
     * @throws BlendRegistrationException when another adapter is already active
     */
    public void install(PlatformAdapter platformAdapter) {
        platformAdapter = Objects.requireNonNull(platformAdapter, "platformAdapter");
        long epoch = beginOperation(ControlOperation.INSTALL, true);
        ProviderOwnership.Handle ownership = null;
        boolean installed = false;
        Throwable primaryFailure = null;
        Throwable rollbackFailure = null;
        try {
            try {
                ownership = ProviderOwnership.acquire(platformAdapter);
            } catch (ProviderOwnership.OwnershipConflictException exception) {
                throw adapterFailure("Platform adapter ownership is unavailable", exception);
            }
            BlendResourceId providerId = Objects.requireNonNull(
                    ExperimentalControlBoundary.callExternal(platformAdapter::providerId),
                    "platformAdapter.providerId()");
            if (!ExperimentalControlBoundary.isValidId(providerId)) {
                throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                        "Platform adapter identity is outside the bounded canonical identity policy");
            }

            synchronized (this) {
                verifyOperation(epoch, ControlOperation.INSTALL);
                if (adapter != null) {
                    throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                            "A platform adapter is already installed");
                }
                adapter = platformAdapter;
                installedAdapterId = providerId;
                adapterOwnership = ownership;
                installed = true;
            }
        } catch (Throwable failure) {
            primaryFailure = failure;
        } finally {
            if (!installed && ownership != null) {
                rollbackFailure = releaseOwnership(ownership);
            }
            endOperation(epoch, ControlOperation.INSTALL);
        }
        if (primaryFailure != null) {
            throw failedInstall(primaryFailure, rollbackFailure);
        }
        if (rollbackFailure != null) {
            throw failedInstall(null, rollbackFailure);
        }
    }

    /**
     * Removes the active adapter after clearing its process-scoped registration targets.
     *
     * <p>This releases the control's identity owner. If lifecycle generations still own the same
     * provider instance, global close is deferred until their final release.</p>
     *
     * @throws BlendRegistrationException when the adapter close callback fails
     */
    public void uninstall() {
        long epoch;
        ProviderOwnership.Handle ownership;
        synchronized (this) {
            rejectCallbackMutation(ControlOperation.UNINSTALL);
            requireNoOperation(ControlOperation.UNINSTALL);
            if (adapterOwnership == null) {
                return;
            }
            operationActive = true;
            activeOperation = ControlOperation.UNINSTALL;
            epoch = ++operationEpoch;
            ownership = adapterOwnership;
            adapter = null;
            installedAdapterId = null;
            adapterOwnership = null;
            registrations.clear();
        }
        Throwable closeFailure = releaseOwnership(ownership);
        endOperation(epoch, ControlOperation.UNINSTALL);
        if (closeFailure != null) {
            ExperimentalControlBoundary.rethrowIfFatal(closeFailure);
            throw adapterFailure("Platform adapter close failed", closeFailure);
        }
    }

    /**
     * Removes the active adapter only when it is the exact supplied instance.
     *
     * <p>This is the lifecycle-safe counterpart to {@link #uninstall()} for a platform runtime
     * that owns one particular adapter instance. Identity comparison and removal occur under the
     * same control monitor, so a stale close receipt cannot observe an adapter ID and later
     * uninstall a replacement adapter with the same provider ID.</p>
     *
     * @param expectedAdapter exact adapter instance owned by the caller
     * @return true when that exact instance was active and removed; false when it was already
     *         absent or another instance is active
     * @throws BlendRegistrationException when the exact adapter close callback fails
     */
    public boolean uninstallIfSame(PlatformAdapter expectedAdapter) {
        PlatformAdapter checkedExpectedAdapter = Objects.requireNonNull(expectedAdapter, "expectedAdapter");
        long epoch;
        ProviderOwnership.Handle ownership;
        synchronized (this) {
            rejectCallbackMutation(ControlOperation.UNINSTALL);
            requireNoOperation(ControlOperation.UNINSTALL);
            if (adapterOwnership == null || adapter != checkedExpectedAdapter) {
                return false;
            }
            operationActive = true;
            activeOperation = ControlOperation.UNINSTALL;
            epoch = ++operationEpoch;
            ownership = adapterOwnership;
            adapter = null;
            installedAdapterId = null;
            adapterOwnership = null;
            registrations.clear();
        }
        Throwable closeFailure = releaseOwnership(ownership);
        endOperation(epoch, ControlOperation.UNINSTALL);
        if (closeFailure != null) {
            ExperimentalControlBoundary.rethrowIfFatal(closeFailure);
            throw adapterFailure("Platform adapter close failed", closeFailure);
        }
        return true;
    }

    /**
     * Returns the active adapter identity when an adapter is installed.
     *
     * @return optional active adapter identity
     */
    public synchronized Optional<BlendResourceId> adapterId() {
        return Optional.ofNullable(installedAdapterId);
    }

    /**
     * Returns the number of accepted registrations in the active process scope.
     *
     * @return accepted registration count
     */
    public synchronized int registrationCount() {
        return registrations.size();
    }

    /**
     * Submits a completed registration to the active platform adapter.
     *
     * @param specification immutable typed registration specification
     * @param <H> consumer host type
     * @return receipt validated against the installed adapter and submitted semantic data
     */
    public <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
        HostRegistrationSpec<H> submittedSpecification = Objects.requireNonNull(specification, "specification");
        long epoch;
        PlatformAdapter installed;
        BlendResourceId expectedAdapterId;
        ProviderOwnership.Handle installedOwnership;
        List<RegistrationRecord> existingRegistrations;
        synchronized (this) {
            rejectCallbackMutation(ControlOperation.REGISTER);
            requireNoOperation(ControlOperation.REGISTER);
            installed = adapter;
            expectedAdapterId = installedAdapterId;
            installedOwnership = adapterOwnership;
            if (installed == null) {
                throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_UNAVAILABLE,
                        "No platform adapter is installed for the requested registration");
            }
            operationActive = true;
            activeOperation = ControlOperation.REGISTER;
            epoch = ++operationEpoch;
            existingRegistrations = List.copyOf(registrations);
        }

        try {
            boolean duplicate;
            try {
                duplicate = hasDuplicateTarget(existingRegistrations, submittedSpecification);
            } catch (Throwable exception) {
                terminateAfterFatal(epoch, ControlOperation.REGISTER, installedOwnership, exception);
                throw adapterFailure("Host registration identity evaluation failed", exception);
            }
            if (duplicate) {
                throw registrationFailure(BlendApiDiagnosticCode.DUPLICATE_TARGET,
                        "Duplicate registration target");
            }
            if (submittedSpecification.hostKind() == com.liy.blendlib.api.HostKind.ITEM) {
                try {
                    ExperimentalControlBoundary.callExternal(
                            () -> submittedSpecification.animationFor(submittedSpecification.host()));
                } catch (BlendRegistrationException exception) {
                    if (exception.diagnostic().code() == BlendApiDiagnosticCode.UNSUPPORTED_ITEM_ANIMATION) {
                        throw registrationFailure(
                                BlendApiDiagnosticCode.UNSUPPORTED_ITEM_ANIMATION,
                                "Item registrations require LOOP playback until per-ItemStack identity is defined");
                    }
                    throw adapterFailure("Item animation source evaluation failed", exception);
                } catch (Throwable exception) {
                    terminateAfterFatal(epoch, ControlOperation.REGISTER, installedOwnership, exception);
                    throw adapterFailure("Item animation source evaluation failed", exception);
                }
            }
            RegistrationReceipt receipt;
            try {
                receipt = ExperimentalControlBoundary.callExternal(() -> installed.register(submittedSpecification));
            } catch (Throwable exception) {
                terminateAfterFatal(epoch, ControlOperation.REGISTER, installedOwnership, exception);
                throw adapterFailure("Platform adapter rejected the requested registration", exception);
            }
            if (receipt == null) {
                throw registrationFailure(BlendApiDiagnosticCode.INVALID_PLATFORM_RECEIPT,
                        "Platform adapter returned a null registration receipt");
            }
            if (!ExperimentalControlBoundary.isValidId(receipt.adapterId())
                    || !receipt.adapterId().equals(expectedAdapterId)
                    || receipt.hostKind() != submittedSpecification.hostKind()
                    || !receipt.modelKey().equals(submittedSpecification.model())) {
                throw registrationFailure(BlendApiDiagnosticCode.INVALID_PLATFORM_RECEIPT,
                        "Platform adapter returned a receipt inconsistent with the submitted registration");
            }
            synchronized (this) {
                verifyOperation(epoch, ControlOperation.REGISTER);
                if (adapter != installed || installedAdapterId != expectedAdapterId) {
                    throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                            "Platform adapter identity changed during registration");
                }
                registrations.add(new RegistrationRecord(
                        submittedSpecification.hostKind(), submittedSpecification.host(), receipt));
            }
            return receipt;
        } finally {
            endOperation(epoch, ControlOperation.REGISTER);
        }
    }

    private synchronized long beginOperation(ControlOperation operation, boolean requireNoAdapter) {
        rejectCallbackMutation(operation);
        requireNoOperation(operation);
        if (requireNoAdapter && adapter != null) {
            throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                    "A platform adapter is already installed");
        }
        operationActive = true;
        activeOperation = operation;
        return ++operationEpoch;
    }

    private synchronized void verifyOperation(long epoch, ControlOperation operation) {
        if (!operationActive || operationEpoch != epoch || activeOperation != operation) {
            throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                    "Platform adapter control operation identity changed during a callback");
        }
    }

    private synchronized void endOperation(long epoch, ControlOperation operation) {
        if (operationActive && operationEpoch == epoch && activeOperation == operation) {
            operationActive = false;
            activeOperation = null;
        }
    }

    private void terminateAfterFatal(
            long epoch,
            ControlOperation operation,
            ProviderOwnership.Handle ownership,
            Throwable failure) {
        if (!ExperimentalControlBoundary.isFatal(failure)) {
            return;
        }
        synchronized (this) {
            verifyOperation(epoch, operation);
            if (adapterOwnership == ownership) {
                adapter = null;
                installedAdapterId = null;
                adapterOwnership = null;
                registrations.clear();
            }
        }
        if (ownership != null) {
            Throwable closeFailure = releaseOwnership(ownership);
            retainSecondary(failure, closeFailure);
        }
        ExperimentalControlBoundary.rethrowIfFatal(failure);
    }

    private static Throwable releaseOwnership(ProviderOwnership.Handle ownership) {
        try {
            return ownership.release();
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static RuntimeException failedInstall(Throwable primaryFailure, Throwable rollbackFailure) {
        if (primaryFailure != null && ExperimentalControlBoundary.isFatal(primaryFailure)) {
            return rethrowFatal(primaryFailure, rollbackFailure);
        }
        if (rollbackFailure != null && ExperimentalControlBoundary.isFatal(rollbackFailure)) {
            return rethrowFatal(rollbackFailure, primaryFailure);
        }
        if (primaryFailure instanceof BlendRegistrationException registrationFailure) {
            if (rollbackFailure == null) {
                return registrationFailure;
            }
            BlendApiDiagnostic diagnostic = registrationFailure.diagnostic();
            String cleanup = "; rollback_close_cause="
                    + ExperimentalControlBoundary.safeThrowableType(rollbackFailure);
            return new BlendRegistrationException(new BlendApiDiagnostic(
                    diagnostic.code(),
                    diagnostic.severity(),
                    boundedMessageWithSuffix(diagnostic.message(), cleanup)));
        }
        if (primaryFailure != null) {
            return adapterFailure("Platform adapter identity lookup failed", primaryFailure, rollbackFailure);
        }
        return adapterFailure("Platform adapter rollback close failed", rollbackFailure);
    }

    private static RuntimeException rethrowFatal(Throwable fatalFailure, Throwable secondaryFailure) {
        retainSecondary(fatalFailure, secondaryFailure);
        ExperimentalControlBoundary.rethrowIfFatal(fatalFailure);
        return new IllegalStateException("Fatal classifier returned without rethrowing");
    }

    private static void retainSecondary(Throwable primaryFailure, Throwable secondaryFailure) {
        if (secondaryFailure == null || secondaryFailure == primaryFailure) {
            return;
        }
        try {
            primaryFailure.addSuppressed(secondaryFailure);
        } catch (Throwable suppressionFailure) {
            ExperimentalControlBoundary.rethrowIfFatal(suppressionFailure);
            // Ordinary suppression bookkeeping cannot replace the selected primary failure.
        }
    }

    private void requireNoOperation(ControlOperation operation) {
        if (operationActive) {
            throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                    "Platform adapter control is busy with another bounded operation");
        }
    }

    private void rejectCallbackMutation(ControlOperation operation) {
        if (ExperimentalControlBoundary.inExternalCallback()) {
            throw registrationFailure(BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                    "Platform adapter control mutation is unavailable during an external callback");
        }
    }

    private static BlendRegistrationException registrationFailure(BlendApiDiagnosticCode code, String message) {
        return new BlendRegistrationException(new BlendApiDiagnostic(
                code, BlendDiagnosticSeverity.ERROR, boundedMessage(message)));
    }

    private static BlendRegistrationException adapterFailure(String prefix, Throwable exception) {
        return adapterFailure(prefix, exception, null);
    }

    private static BlendRegistrationException adapterFailure(
            String prefix,
            Throwable exception,
            Throwable rollbackFailure) {
        String context = prefix + "; cause=" + ExperimentalControlBoundary.safeThrowableType(exception);
        if (rollbackFailure != null) {
            context = boundedMessageWithSuffix(context, "; rollback_close_cause="
                    + ExperimentalControlBoundary.safeThrowableType(rollbackFailure));
        }
        return new BlendRegistrationException(new BlendApiDiagnostic(
                BlendApiDiagnosticCode.PLATFORM_ADAPTER_FAILURE,
                BlendDiagnosticSeverity.ERROR,
                boundedMessage(context)));
    }

    private static boolean hasDuplicateTarget(
            List<RegistrationRecord> existingRegistrations,
            HostRegistrationSpec<?> specification) {
        for (RegistrationRecord registration : existingRegistrations) {
            if (registration.hostKind() == specification.hostKind()
                    && ExperimentalControlBoundary.callExternal(
                            () -> Objects.equals(specification.host(), registration.host()))) {
                return true;
            }
        }
        return false;
    }

    private static String boundedMessage(String message) {
        message = Objects.requireNonNull(message, "message");
        if (message.length() <= BlendApiDiagnostic.MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, BlendApiDiagnostic.MAX_MESSAGE_LENGTH - 3) + "...";
    }

    private static String boundedMessageWithSuffix(String message, String suffix) {
        message = Objects.requireNonNull(message, "message");
        suffix = Objects.requireNonNull(suffix, "suffix");
        int available = BlendApiDiagnostic.MAX_MESSAGE_LENGTH - suffix.length();
        if (message.length() <= available) {
            return message + suffix;
        }
        if (available <= 3) {
            return suffix.substring(0, BlendApiDiagnostic.MAX_MESSAGE_LENGTH);
        }
        return message.substring(0, available - 3) + "..." + suffix;
    }

    private record RegistrationRecord(
            com.liy.blendlib.api.HostKind hostKind,
            Object host,
            RegistrationReceipt receipt) {
        private RegistrationRecord {
            hostKind = Objects.requireNonNull(hostKind, "hostKind");
            host = Objects.requireNonNull(host, "host");
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    private enum ControlOperation {
        INSTALL,
        REGISTER,
        UNINSTALL
    }
}
