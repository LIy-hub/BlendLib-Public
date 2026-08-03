package com.liy.blendlib.spi.experimental;

/**
 * Immutable-generation pin held by a snapshot consumer after provider-session publish.
 *
 * <p>Closing a lease is idempotent. Both generation-scoped retire callbacks and provider-global
 * close callbacks wait for every issued lease to close, then run in that order.</p>
 */
@ExperimentalBlendLibSpi
public interface ProviderLease extends AutoCloseable {
    /**
     * Returns the pinned resource-generation number.
     *
     * @return pinned generation
     */
    long generation();

    /**
     * Returns whether this individual lease already released its pin.
     *
     * @return whether this lease is closed
     */
    boolean isClosed();

    /** Releases this one generation pin without closing any other lease. */
    @Override
    void close();
}
