package com.liy.blendlib.fixture;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.HostRegistrationSpec;

/**
 * Minimal ordinary-consumer sample that imports no experimental SPI type.
 *
 * <p>Platform bootstrap installs its adapter separately. This source can compile against the
 * stable API alone and shows the expected entity builder experience without depending on a core,
 * renderer, or platform package.</p>
 */
public final class StableFacadeOnlySample {
    private StableFacadeOnlySample() {
    }

    /**
     * Produces the stable entity specification that a normal consumer registers at platform startup.
     *
     * @return immutable stable entity specification
     */
    public static HostRegistrationSpec<String> entitySpecification() {
        return BlendLib.entity("stable-only-entity")
                .model(ApiConsumerFixture.MODEL)
                .animation(host -> AnimationRequest.loop(ApiConsumerFixture.IDLE))
                .build();
    }

    /**
     * Produces a stateless item specification whose public source accessor is LOOP-validated.
     *
     * @return immutable stable item specification
     */
    public static HostRegistrationSpec<String> itemSpecification() {
        return BlendLib.item("stable-only-item")
                .model(ApiConsumerFixture.MODEL)
                .animation(host -> AnimationRequest.loop(ApiConsumerFixture.IDLE))
                .build();
    }
}
