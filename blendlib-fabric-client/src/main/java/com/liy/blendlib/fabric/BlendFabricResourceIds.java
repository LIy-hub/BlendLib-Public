package com.liy.blendlib.fabric;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * 26.1.2 adapter convenience conversions. This type is intentionally outside the pure API.
 */
public final class BlendFabricResourceIds {
    private BlendFabricResourceIds() {
    }

    public static Identifier toIdentifier(BlendResourceId resourceId) {
        Objects.requireNonNull(resourceId, "resourceId");
        return Identifier.fromNamespaceAndPath(resourceId.namespace(), resourceId.path());
    }

    public static BlendResourceId fromIdentifier(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return BlendResourceId.of(identifier.getNamespace(), identifier.getPath());
    }
}
