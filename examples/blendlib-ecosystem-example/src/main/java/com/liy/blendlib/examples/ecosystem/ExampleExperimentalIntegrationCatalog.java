package com.liy.blendlib.examples.ecosystem;

import com.liy.blendlib.spi.experimental.CapabilityFallback;
import com.liy.blendlib.spi.experimental.CapabilityRequest;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;

/**
 * Explicit Experimental SPI request metadata for the X4/X6/X7 handoff.
 *
 * <p>These methods construct immutable request values only. They do not register/discover a
 * provider, install an adapter, trigger reload, create a backend, or run in submit. A
 * version-specific lifecycle owner must validate and schedule any actual use.</p>
 */
@ExperimentalBlendLibSpi
public final class ExampleExperimentalIntegrationCatalog {
    private ExampleExperimentalIntegrationCatalog() {
    }

    /** Required X4 host-adapter capability; no safe generic adapter fallback exists. */
    public static CapabilityRequest requiredX4HostAdapter() {
        return CapabilityRequest.required(
                ExampleFeatureCatalog.X4_HOST_ADAPTER_CAPABILITY,
                CapabilityVersion.CURRENT_PROTOCOL_RANGE);
    }

    /** Optional X6 standard material route with an explicitly equivalent CPU standard fallback. */
    public static CapabilityRequest optionalX6Material() {
        return CapabilityRequest.optional(
                ExampleFeatureCatalog.X6_STANDARD_MATERIAL_CAPABILITY,
                CapabilityVersion.CURRENT_PROTOCOL_RANGE,
                new CapabilityFallback(
                        ExampleFeatureCatalog.X7_CPU_FALLBACK,
                        "CPU standard route preserves the declared opaque material semantics."));
    }

    /** Optional X7 backend request with a bounded CPU standard fallback. */
    public static CapabilityRequest optionalX7Backend() {
        return CapabilityRequest.optional(
                ExampleFeatureCatalog.X7_PUBLIC_BACKEND,
                CapabilityVersion.CURRENT_PROTOCOL_RANGE,
                new CapabilityFallback(
                        ExampleFeatureCatalog.X7_CPU_FALLBACK,
                        "CPU standard backend preserves the approved public rendering route."));
    }
}
