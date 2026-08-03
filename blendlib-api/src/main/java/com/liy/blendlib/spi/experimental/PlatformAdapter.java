package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.api.RegistrationReceipt;

/**
 * Controlled version-specific adapter that accepts stable semantic host registrations.
 *
 * <p>This SPI is intentionally experimental and must be installed by platform bootstrap code. It
 * receives only a typed semantic specification, never GLB internals, a pose, raw graphics state,
 * reflection hooks, or an untyped mutable registration map.</p>
 */
@ExperimentalBlendLibSpi
public interface PlatformAdapter extends BlendProvider {
    /**
     * Accepts one validated typed semantic registration.
     *
     * @param specification complete immutable stable registration specification
     * @param <H> consumer's host type
     * @return immutable acknowledgement matching the submitted host kind and model key
     */
    <H> RegistrationReceipt register(HostRegistrationSpec<H> specification);
}
