package com.liy.blendlib.fixture.fabric;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.common.animation.BlendAnimations;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

/**
 * Compile-only sample of a separate Fabric mod consuming BlendLib's public semantic surfaces.
 *
 * <p>The fixture deliberately avoids every implementation, loader, parser, resource, renderer,
 * and backend type. It proves a server-side consumer needs only stable semantic keys and the
 * public common animation facade.</p>
 */
public final class FabricConsumerFixture {
    public static final BlendModelKey MODEL_KEY = BlendModelKey.parse("consumer:fixture_actor");
    public static final BlendAnimationKey ATTACK_KEY = BlendAnimationKey.parse("consumer:attack");

    private FabricConsumerFixture() {
    }

    public static BlendResourceId canonicalModelId() {
        return MODEL_KEY.resourceId();
    }

    public static BlendAnimations.EntityAnimationTarget animationTarget(Entity entity) {
        return BlendAnimations.entity(Objects.requireNonNull(entity, "entity"));
    }

    /** Compiles the documented semantic server trigger without reaching a render implementation. */
    public static void triggerAttack(Entity entity, float speed, long seed) {
        animationTarget(entity).trigger(ATTACK_KEY, speed, seed);
    }
}
