package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Explicit semantic-equivalent fallback declared for one optional capability.
 *
 * <p>The fallback is an identity and explanation, not an opaque implementation handle. An adapter
 * must prove its own fallback is safe before publishing a generation that selects it.</p>
 *
 * @param fallbackId canonical fallback identity
 * @param explanation bounded explanation of the semantic-equivalence guarantee
 */
@ExperimentalBlendLibSpi
public record CapabilityFallback(BlendResourceId fallbackId, String explanation) {
    /** Maximum retained explanation length. */
    public static final int MAX_EXPLANATION_LENGTH = 512;

    /** Validates an explicit, bounded semantic-equivalence fallback declaration. */
    public CapabilityFallback {
        fallbackId = Objects.requireNonNull(fallbackId, "fallbackId");
        ExperimentalControlBoundary.requireId(fallbackId, "fallbackId");
        explanation = ExperimentalControlBoundary.requireText(
                explanation, MAX_EXPLANATION_LENGTH, "explanation");
    }
}
