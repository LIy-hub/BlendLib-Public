package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collection;

/**
 * Common controlled-provider contract for the separately versioned capability protocol.
 *
 * <p>Metadata methods are read during registration/discovery only. Lifecycle methods run only
 * outside submit, animation advance, and socket-query hot paths. Implementations must not expose
 * platform-private renderer handles through this interface.</p>
 */
@ExperimentalBlendLibSpi
public interface BlendProvider extends AutoCloseable {
    /**
     * Returns the canonical, immutable provider identity.
     *
     * @return canonical provider identity
     */
    BlendResourceId providerId();

    /**
     * Returns metadata-only capability claims.
     *
     * <p>The registry snapshots this collection at registration time and does not depend on later
     * mutation or discovery order.</p>
     *
     * @return provider-owned immutable or snapshot-safe offer metadata
     */
    Collection<CapabilityOffer> offers();

    /**
     * Performs bounded background preparation for a frozen generation.
     *
     * @param context immutable generation-scoped lifecycle context
     */
    default void prepare(ProviderLifecycleContext context) {
    }

    /**
     * Applies prepared state on the adapter's designated apply thread.
     *
     * @param context immutable generation-scoped lifecycle context
     */
    default void apply(ProviderLifecycleContext context) {
    }

    /**
     * Retires only the generation named by the supplied context.
     *
     * <p>A provider instance may be shared by overlapping generations, so this callback must not
     * release provider-global state or disrupt another generation. BlendLib invokes it only after
     * every snapshot lease for this generation drains, immediately before this session releases
     * its shared provider ownership.</p>
     *
     * @param context immutable generation-scoped lifecycle context
     */
    default void retire(ProviderLifecycleContext context) {
    }

    /**
     * Releases provider-global resources after the final session/control owner releases the instance.
     *
     * <p>BlendLib invokes this callback at most once per provider object identity, marks ownership
     * terminal before invocation, and never retries after failure. Implementations must not start
     * discovery, submit work, network traffic, or a new reload.</p>
     */
    @Override
    default void close() {
    }
}
