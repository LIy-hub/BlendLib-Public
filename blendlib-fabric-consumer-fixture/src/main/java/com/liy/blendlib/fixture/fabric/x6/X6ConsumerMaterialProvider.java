package com.liy.blendlib.fixture.fabric.x6;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.spi.experimental.CapabilityOffer;
import com.liy.blendlib.spi.experimental.CapabilityVersion;
import com.liy.blendlib.spi.experimental.MaterialProvider;
import com.liy.blendlib.spi.experimental.ProviderLifecycleContext;
import com.liy.blendlib.spi.experimental.ProviderLifecycleStage;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consumer-side X6 example of the public, metadata-only material capability contract.
 *
 * <p>The fixture intentionally exposes neither a render pipeline nor any client adapter type.
 * BlendLib's client adapter owns capability discovery, generation freezing, and render-plan use.
 */
public final class X6ConsumerMaterialProvider implements MaterialProvider {
    public static final BlendResourceId PROVIDER_ID = BlendResourceId.parse("consumer:x6_standard_material_provider");
    public static final BlendResourceId STANDARD_MATERIAL_CAPABILITY = BlendResourceId.parse("consumer:x6_standard_material");

    private final List<ProviderLifecycleStage> lifecycleStages = new CopyOnWriteArrayList<>();

    @Override
    public BlendResourceId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityOffer> offers() {
        return List.of(new CapabilityOffer(
                PROVIDER_ID,
                STANDARD_MATERIAL_CAPABILITY,
                CapabilityVersion.CURRENT_PROTOCOL,
                25));
    }

    @Override
    public Set<BlendResourceId> supportedMaterialCapabilities() {
        return Set.of(STANDARD_MATERIAL_CAPABILITY);
    }

    @Override
    public void prepare(ProviderLifecycleContext context) {
        lifecycleStages.add(context.stage());
    }

    @Override
    public void apply(ProviderLifecycleContext context) {
        lifecycleStages.add(context.stage());
    }

    @Override
    public void retire(ProviderLifecycleContext context) {
        lifecycleStages.add(context.stage());
    }

    @Override
    public void close() {
        lifecycleStages.add(ProviderLifecycleStage.CLOSE);
    }

    /** Returns the observed non-hot lifecycle order for this fixture-only provider instance. */
    public List<ProviderLifecycleStage> lifecycleStages() {
        return List.copyOf(lifecycleStages);
    }
}
