package com.liy.blendlib.fabric.v262.runtime;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262DiagnosticCode;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262PlatformException;
import com.liy.blendlib.fabric.v262.model.Fabric262FrameState;
import com.liy.blendlib.fabric.v262.model.Fabric262RenderSnapshot;
import com.liy.blendlib.fabric.v262.resource.Fabric262ResourceReloadCoordinator;
import com.liy.blendlib.fabric.v262.resource.Fabric262ResourceReloadListener;
import com.liy.blendlib.spi.experimental.PlatformAdapterControl;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

/**
 * Process-scoped lifecycle owner for the independent Minecraft 26.2 Fabric adapter artifact.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. Startup installs the experimental
 * {@link Fabric262PlatformAdapter}, its public host dispatcher, and the public Fabric resource
 * listener. Exact close is deliberately staged: retire new resource access, drain dispatcher-held
 * generation pins, release exactly this adapter through {@link PlatformAdapterControl}, then mark
 * the runtime terminal. If a cleanup call throws, the exact receipt keeps the runtime in
 * {@code closing} state so the caller may retry rather than observing a false terminal result.</p>
 */
public final class Fabric262ClientRuntime {
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(
            "blendlib", "fabric_26_2_models");
    private static final Fabric262ClientRuntime GLOBAL = new Fabric262ClientRuntime();

    private long nextEpoch;
    private State state;
    private boolean terminal;
    private Fabric262InstallationReceipt closedReceipt;

    private Fabric262ClientRuntime() {
    }

    /** Returns the one process-scoped Fabric 26.2 runtime owner. */
    public static Fabric262ClientRuntime global() {
        return GLOBAL;
    }

    /**
     * Installs the controlled adapter, public native dispatcher, and public resource listener once.
     *
     * @return exact installation receipt; repeated active calls return the same object
     * @throws Fabric262PlatformException if a terminal runtime is restarted, cleanup is in flight,
     *         or global adapter installation is unavailable
     */
    public synchronized Fabric262InstallationReceipt start() {
        if (state != null) {
            if (state.closing) {
                throw closingFailure();
            }
            return state.receipt;
        }
        if (terminal) {
            throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.RUNTIME_CLOSED,
                    "A closed Fabric 26.2 runtime cannot be restarted because its resource listener remains registered"));
        }
        Fabric262ResourceReloadCoordinator coordinator = new Fabric262ResourceReloadCoordinator();
        Fabric262PlatformAdapter adapter = new Fabric262PlatformAdapter(coordinator);
        boolean adapterInstalled = false;
        boolean listenerRegistered = false;
        try {
            PlatformAdapterControl.global().install(adapter);
            adapterInstalled = true;
            ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                    RELOAD_LISTENER_ID, new Fabric262ResourceReloadListener(coordinator));
            listenerRegistered = true;
        } catch (RuntimeException exception) {
            RuntimeException cleanupFailure = cleanupFailedStart(adapter, coordinator, adapterInstalled);
            if (listenerRegistered) {
                // Fabric exposes no public removal for this listener; preserve its terminal coordinator.
                terminal = true;
            }
            Fabric262PlatformException failure = new Fabric262PlatformException(Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.HOST_TRANSLATION_FAILURE,
                    "Fabric 26.2 runtime installation failed: " + exception.getClass().getSimpleName()), exception);
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        long epoch = ++nextEpoch;
        Fabric262InstallationReceipt receipt = new Fabric262InstallationReceipt(
                Fabric262PlatformAdapter.ADAPTER_ID, epoch, new Object());
        state = new State(receipt, adapter, coordinator);
        return receipt;
    }

    /**
     * Captures an exact active resource-generation snapshot for final standard submission.
     *
     * @param modelKey strict semantic model key
     * @param frame immutable caller-provided per-submit state
     * @return independently closable prepared snapshot
     */
    public synchronized Fabric262RenderSnapshot snapshot(BlendModelKey modelKey, Fabric262FrameState frame) {
        return requireActiveState().coordinator.snapshot(
                Objects.requireNonNull(modelKey, "modelKey"), Objects.requireNonNull(frame, "frame"));
    }

    /** Returns deterministic dispatcher-installed ordinary-host bindings. */
    public synchronized List<com.liy.blendlib.fabric.v262.host.Fabric262HostBinding> hostBindings() {
        return requireActiveState().adapter.hostBindings();
    }

    /**
     * Closes exactly the installation represented by the supplied receipt.
     *
     * <p>Successful cleanup order is fixed: the coordinator retires the active generation and
     * rejects new snapshots; exact adapter release drains dispatcher-held snapshots and removes
     * binding ownership; only then does this runtime publish its terminal receipt. A thrown step
     * leaves its completed marker in place and preserves {@code closing} state for an exact retry.</p>
     *
     * @param receipt exact receipt returned from {@link #start()}
     * @return whether this was the first exact close or an idempotent repeat
     */
    public synchronized Fabric262CloseResult close(Fabric262InstallationReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (state == null) {
            if (terminal && closedReceipt != null && closedReceipt.matches(receipt)) {
                return Fabric262CloseResult.ALREADY_CLOSED;
            }
            throw receiptMismatch();
        }
        if (!state.receipt.matches(receipt)) {
            throw receiptMismatch();
        }
        State closing = state;
        closing.closing = true;
        if (!closing.coordinatorClosed) {
            closing.coordinator.close();
            closing.coordinatorClosed = true;
        }
        if (!closing.adapterReleased) {
            boolean exactAdapterReleased = PlatformAdapterControl.global().uninstallIfSame(closing.adapter);
            if (!exactAdapterReleased) {
                // Another owner already released the exact global slot. Local dispatcher state is
                // still this receipt's responsibility and its close is idempotent/retry-safe.
                closing.adapter.close();
            }
            closing.adapterReleased = true;
        }
        state = null;
        terminal = true;
        closedReceipt = closing.receipt;
        return Fabric262CloseResult.CLOSED;
    }

    private State requireActiveState() {
        State active = state;
        if (active == null) {
            throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.RUNTIME_CLOSED,
                    "Fabric 26.2 runtime is not active"));
        }
        if (active.closing) {
            throw closingFailure();
        }
        return active;
    }

    private static RuntimeException cleanupFailedStart(
            Fabric262PlatformAdapter adapter,
            Fabric262ResourceReloadCoordinator coordinator,
            boolean adapterInstalled) {
        RuntimeException failure = null;
        try {
            if (adapterInstalled) {
                PlatformAdapterControl.global().uninstallIfSame(adapter);
            } else {
                adapter.close();
            }
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            coordinator.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        return failure;
    }

    private static Fabric262PlatformException closingFailure() {
        return new Fabric262PlatformException(Fabric262Diagnostic.error(
                Fabric262DiagnosticCode.RUNTIME_CLOSING,
                "Fabric 26.2 runtime cleanup is in progress; retry close with its exact installation receipt"));
    }

    private static Fabric262PlatformException receiptMismatch() {
        return new Fabric262PlatformException(Fabric262Diagnostic.error(
                Fabric262DiagnosticCode.INSTALLATION_RECEIPT_MISMATCH,
                "Fabric 26.2 runtime close requires the exact installation receipt"));
    }

    private static final class State {
        private final Fabric262InstallationReceipt receipt;
        private final Fabric262PlatformAdapter adapter;
        private final Fabric262ResourceReloadCoordinator coordinator;
        private boolean closing;
        private boolean coordinatorClosed;
        private boolean adapterReleased;

        private State(
                Fabric262InstallationReceipt receipt,
                Fabric262PlatformAdapter adapter,
                Fabric262ResourceReloadCoordinator coordinator) {
            this.receipt = Objects.requireNonNull(receipt, "receipt");
            this.adapter = Objects.requireNonNull(adapter, "adapter");
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        }
    }
}
