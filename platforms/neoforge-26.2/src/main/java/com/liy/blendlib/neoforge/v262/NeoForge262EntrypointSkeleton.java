package com.liy.blendlib.neoforge.v262;

import com.liy.blendlib.neoforge.v262.bridge.NeoForge262PlatformBridge;
import java.util.List;

/**
 * Deliberately non-loader-bound entrypoint skeleton for the NeoForge 26.2 bridge artifact.
 *
 * <p><strong>WAITING boundary:</strong> this class is not annotated with a NeoForge loader marker
 * and is paired with a metadata template rather than {@code neoforge.mods.toml}. It exposes the
 * future replacement seam without fabricating a production-compatible runtime before official
 * 26.2 build/mappings/event/client APIs are confirmed.</p>
 */
public final class NeoForge262EntrypointSkeleton {
    private static final NeoForge262BindingStatus STATUS = new NeoForge262BindingStatus(
            NeoForge262BindingState.WAITING_OFFICIAL_26_2_BINDING,
            "Official NeoForge 26.2 ModDevGradle coordinates, loader metadata range, event lifecycle, and public client render binding were not confirmed.",
            List.of(
                    "https://docs.neoforged.net/docs/gettingstarted/",
                    "https://github.com/neoforged/.github/blob/main/primers/26.2/index.md"),
            "2026-09-05");

    private NeoForge262EntrypointSkeleton() {
    }

    /**
     * Returns the explicit non-runtime binding status for integration tooling and release notes.
     *
     * @return immutable current NeoForge 26.2 binding status
     */
    public static NeoForge262BindingStatus bindingStatus() {
        return STATUS;
    }

    /**
     * Creates a pure strict-resource and semantic-host bridge for a future verified binder.
     *
     * @return new independent pure Java bridge
     */
    public static NeoForge262PlatformBridge createBridge() {
        return new NeoForge262PlatformBridge();
    }
}
