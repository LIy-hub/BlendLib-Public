package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendModelKey;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * Explicit client-only binding between one vanilla marker item and one immutable BlendLib model key.
 *
 * <p>The marker's JSON remains an ordinary vanilla item model. The {@link #baseModelId()} identifies
 * the same vanilla model used by {@code SpecialModelWrapper} to obtain standard item render
 * properties; it is not a BlendLib descriptor or raw GLB reference.</p>
 */
public record BlendLibItemBinding(Identifier itemId, BlendModelKey modelKey, Identifier baseModelId) {
    public BlendLibItemBinding {
        itemId = Objects.requireNonNull(itemId, "itemId");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
        baseModelId = Objects.requireNonNull(baseModelId, "baseModelId");
    }
}
