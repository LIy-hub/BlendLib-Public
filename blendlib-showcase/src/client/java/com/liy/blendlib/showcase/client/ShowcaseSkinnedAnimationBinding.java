package com.liy.blendlib.showcase.client;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import java.util.List;
import java.util.Objects;

/**
 * Client-only semantic binding for the deterministic P5 Showcase animation asset.
 *
 * <p>This class deliberately declares only public semantic keys. The public 26.1.2 P5
 * extraction-only adapter consumes this exact model/state contract; the binding itself does not
 * reach into a reload registry, controller implementation, decoded asset, or backend implementation.
 * It never infers Blender action names.</p>
 */
final class ShowcaseSkinnedAnimationBinding {
    static final BlendModelKey MODEL_KEY =
            BlendModelKey.parse("blendlib_showcase:showcase_animation/showcase_actor");
    static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_showcase:idle");
    static final BlendAnimationKey WALK = BlendAnimationKey.parse("blendlib_showcase:walk");
    static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("blendlib_showcase:attack");

    private static final CanonicalBinding CANONICAL = new CanonicalBinding(
            MODEL_KEY,
            IDLE,
            List.of(IDLE, WALK, ATTACK));

    private ShowcaseSkinnedAnimationBinding() {
    }

    /**
     * Validates the client-side semantic declaration during Showcase client initialization.
     *
     * <p>This is intentionally allocation-free after class initialization and performs no asset
     * lookup, resource I/O, JSON/GLB parsing, controller sampling, or renderer submission.</p>
     */
    static void validateCanonicalContract() {
        if (!CANONICAL.states().contains(CANONICAL.initialState())) {
            throw new IllegalStateException("Showcase initial animation state is absent from its canonical binding");
        }
    }

    /** Immutable model/state declaration consumed by the public P5 adapter integration. */
    record CanonicalBinding(
            BlendModelKey modelKey,
            BlendAnimationKey initialState,
            List<BlendAnimationKey> states) {
        CanonicalBinding {
            modelKey = Objects.requireNonNull(modelKey, "modelKey");
            initialState = Objects.requireNonNull(initialState, "initialState");
            states = List.copyOf(Objects.requireNonNull(states, "states"));
            if (states.size() != 3 || !states.contains(initialState)) {
                throw new IllegalArgumentException("Showcase canonical animation binding must contain its three declared states");
            }
            if (states.stream().distinct().count() != states.size()) {
                throw new IllegalArgumentException("Showcase canonical animation binding must not duplicate a state key");
            }
        }
    }
}
