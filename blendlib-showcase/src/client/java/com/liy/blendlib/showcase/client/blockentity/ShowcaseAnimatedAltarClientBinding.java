package com.liy.blendlib.showcase.client.blockentity;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.showcase.blockentity.ShowcaseBlockEntityAnimations;
import java.util.Objects;

/**
 * Client-only semantic binding for the Showcase block-entity loop.
 *
 * <p>The binding deliberately reuses the already exported strict P5 skinned asset and declares
 * no loader, descriptor, registry, or transport implementation detail.</p>
 */
public final class ShowcaseAnimatedAltarClientBinding {
    public static final BlendModelKey MODEL_KEY =
            BlendModelKey.parse("blendlib_showcase:showcase_animation/showcase_actor");

    private ShowcaseAnimatedAltarClientBinding() {
    }

    /** Validates the consumer's semantic loop declaration during client initialization. */
    public static void validateCanonicalContract() {
        Objects.requireNonNull(MODEL_KEY, "modelKey");
        Objects.requireNonNull(ShowcaseBlockEntityAnimations.IDLE_LOOP, "idleLoop");
    }
}
