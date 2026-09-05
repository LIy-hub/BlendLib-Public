package com.liy.blendlib.examples.ecosystem;

import com.liy.blendlib.api.BlendResourceId;
import java.util.List;

/**
 * Immutable configuration boundary for X3, X4, X6, and X7 example intent.
 *
 * <p>These values are deliberately semantic. They do not install a host adapter, discover a
 * provider, invoke GL, or create a render backend. A version-specific lifecycle owner must
 * explicitly opt into and validate Experimental SPI wiring.</p>
 */
public final class ExampleFeatureCatalog {
    public static final BlendResourceId X3_PRESENTATION_EVENT = ExampleKeys.ATTACK_WHOOSH;
    public static final BlendResourceId X4_HOST_ADAPTER_CAPABILITY =
            BlendResourceId.parse(ExampleKeys.MOD_ID + ":x4_host_adapter");
    public static final BlendResourceId X6_STANDARD_MATERIAL_CAPABILITY =
            BlendResourceId.parse(ExampleKeys.MOD_ID + ":opaque_standard");
    public static final BlendResourceId X7_PUBLIC_BACKEND =
            BlendResourceId.parse(ExampleKeys.MOD_ID + ":public_standard_backend");
    public static final BlendResourceId X7_CPU_FALLBACK =
            BlendResourceId.parse(ExampleKeys.MOD_ID + ":cpu_standard_fallback");

    private ExampleFeatureCatalog() {
    }

    /** Returns preference order only; it is not provider discovery or a submit-time decision. */
    public static List<BlendResourceId> backendPreference() {
        return List.of(X7_PUBLIC_BACKEND, X7_CPU_FALLBACK);
    }
}
