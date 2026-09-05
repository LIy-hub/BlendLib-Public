package com.liy.blendlib.fabric.v262;

import com.liy.blendlib.fabric.v262.runtime.Fabric262ClientRuntime;
import com.liy.blendlib.fabric.v262.runtime.Fabric262CloseResult;
import com.liy.blendlib.fabric.v262.runtime.Fabric262InstallationReceipt;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Client entrypoint for the independent Minecraft 26.2 Fabric adapter artifact.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. Startup installs the controlled
 * experimental platform adapter and registers the public Fabric resource listener; it does not
 * read GLB data, create a renderer, or submit geometry on the loader callback.</p>
 */
public final class BlendLibFabric262ClientEntrypoint implements ClientModInitializer {
    private static final System.Logger LOGGER = System.getLogger("BlendLib/Fabric-26.2");

    private Fabric262InstallationReceipt installationReceipt;
    private boolean stopHookRegistered;

    /**
     * Starts the one process-scoped client runtime exactly once.
     *
     * <p>The runtime records an exact installation receipt for the owning integration and keeps
     * all subsequent descriptor/GLB work inside the resource-reload lifecycle.</p>
     */
    @Override
    public synchronized void onInitializeClient() {
        installationReceipt = Fabric262ClientRuntime.global().start();
        if (!stopHookRegistered) {
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> closeAtClientStop());
            stopHookRegistered = true;
        }
    }

    /**
     * Releases only the exact installation owned by this entrypoint at the public client stop
     * lifecycle. A cleanup exception deliberately retains the receipt so a later exact retry is
     * possible; it is not converted into a false terminal success.
     */
    private synchronized void closeAtClientStop() {
        Fabric262InstallationReceipt receipt = installationReceipt;
        if (receipt == null) {
            return;
        }
        try {
            Fabric262CloseResult result = Fabric262ClientRuntime.global().close(receipt);
            if (result == Fabric262CloseResult.CLOSED || result == Fabric262CloseResult.ALREADY_CLOSED) {
                installationReceipt = null;
            }
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Fabric 26.2 BlendLib client shutdown did not complete; exact receipt remains retryable",
                    exception);
        }
    }
}
