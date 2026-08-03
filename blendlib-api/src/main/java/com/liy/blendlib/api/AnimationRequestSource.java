package com.liy.blendlib.api;

/**
 * Source-safe functional source of semantic animation requests for one host type.
 *
 * @param <H> consumer's platform-neutral host type
 */
@FunctionalInterface
public interface AnimationRequestSource<H> {
    /**
     * Produces the current semantic animation request for a host.
     *
     * <p>Implementations must not perform resource I/O, model parsing, provider discovery, or
     * rendering work. A platform adapter invokes this only in its appropriate extraction phase.</p>
     *
     * @param host the typed host supplied to the registration
     * @return a non-null immutable semantic request
     */
    AnimationRequest requestFor(H host);
}
