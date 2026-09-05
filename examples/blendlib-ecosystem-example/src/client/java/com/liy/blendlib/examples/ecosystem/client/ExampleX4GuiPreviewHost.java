package com.liy.blendlib.examples.ecosystem.client;

import com.liy.blendlib.examples.ecosystem.ExampleKeys;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.fabric.client.host.X4HostAdapter;
import com.liy.blendlib.fabric.client.host.X4HostAdapters;
import com.liy.blendlib.fabric.client.host.X4HostConfigurations;
import com.liy.blendlib.fabric.client.host.X4HostFrames;
import com.liy.blendlib.fabric.client.host.X4HostIdentity;
import com.liy.blendlib.spi.experimental.ExperimentalBlendLibSpi;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import java.util.Objects;

/**
 * A concrete X4 GUI-preview host shape for a version-specific client lifecycle owner.
 *
 * <p>This example deliberately is not called by the ordinary client initializer. Its caller must
 * already own the frozen {@link ProviderLifecycleSession}, public model lookup, public renderer,
 * and scoped host identity for one target-version client lifecycle. Building this adapter does not
 * install a global {@code PlatformAdapter}, discover providers, parse resources, or submit a
 * frame. The owner remains responsible for freeze, prepare/extract, submit, retire, lease drain,
 * and close.</p>
 */
@ExperimentalBlendLibSpi
public final class ExampleX4GuiPreviewHost {
    private ExampleX4GuiPreviewHost() {
    }

    /**
     * Creates a bounded, immutable GUI-preview host using only public X4 facades.
     *
     * @param models public model lookup from the owning client generation
     * @param renderer public renderer facade from the owning client generation
     * @param generationSession externally owned Experimental lifecycle session
     * @param identity scoped client-local identity for this GUI preview
     * @return a newly built adapter awaiting lifecycle-owner freeze and frame preparation
     */
    public static X4HostAdapter<X4HostFrames.GuiPreview> create(
            ClientModelLookup models,
            BlendRenderer renderer,
            ProviderLifecycleSession generationSession,
            X4HostIdentity identity) {
        return X4HostAdapters.guiPreview(
                        Objects.requireNonNull(models, "models"),
                        Objects.requireNonNull(renderer, "renderer"),
                        Objects.requireNonNull(generationSession, "generationSession"))
                .model(ExampleKeys.ACTOR_MODEL)
                .identity(Objects.requireNonNull(identity, "identity"))
                .configuration(new X4HostConfigurations.GuiPreview(
                        512,
                        512,
                        32.0F,
                        0.25F,
                        4.0F,
                        X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                .build();
    }
}
