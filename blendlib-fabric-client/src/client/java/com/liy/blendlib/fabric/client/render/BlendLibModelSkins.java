package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Model-scoped, client-startup named texture definitions. Register before the first model reload. */
public final class BlendLibModelSkins {
    public static final int MAX_MODELS = 128;
    public static final int MAX_SKINS_PER_MODEL = 32;
    public static final int MAX_REPLACEMENTS_PER_SKIN = 64;
    private static final Registry REGISTRY = new Registry();

    private BlendLibModelSkins() { }

    /**
     * Copies exact authored slot replacements. Repeating identical definitions is harmless.
     * Conflicting definitions and additions after the first reload starts fail explicitly.
     * Texture existence is checked at reload; PNG decoding remains Minecraft's responsibility.
     */
    public static void register(BlendModelKey model, Map<BlendResourceId, Map<String, BlendResourceId>> skins) {
        REGISTRY.register(model, skins);
    }

    /** Adapter reload bridge: atomically freezes startup registration and returns an immutable snapshot. */
    public static Map<BlendModelKey, Map<BlendResourceId, Map<String, BlendResourceId>>> snapshotForReload() {
        return REGISTRY.snapshotForReload();
    }

    static final class Registry {
        private final Map<BlendModelKey, Map<BlendResourceId, Map<String, BlendResourceId>>> models = new LinkedHashMap<>();
        private boolean frozen;

        synchronized void register(BlendModelKey model, Map<BlendResourceId, Map<String, BlendResourceId>> skins) {
            Objects.requireNonNull(model, "model");
            Map<BlendResourceId, Map<String, BlendResourceId>> copy = copyDefinitions(skins);
            Map<BlendResourceId, Map<String, BlendResourceId>> previous = models.get(model);
            if (previous != null) {
                if (!previous.equals(copy)) throw new IllegalStateException("Different named skins already registered for " + model);
                return;
            }
            if (frozen) throw new IllegalStateException("Named skins must be registered before the first model reload: " + model);
            if (models.size() >= MAX_MODELS) throw new IllegalStateException("Named skin model limit exceeded: " + MAX_MODELS);
            models.put(model, copy);
        }

        synchronized Map<BlendModelKey, Map<BlendResourceId, Map<String, BlendResourceId>>> snapshotForReload() {
            frozen = true;
            return Map.copyOf(models);
        }
    }

    private static Map<BlendResourceId, Map<String, BlendResourceId>> copyDefinitions(
            Map<BlendResourceId, Map<String, BlendResourceId>> skins) {
        Objects.requireNonNull(skins, "skins");
        if (skins.isEmpty() || skins.size() > MAX_SKINS_PER_MODEL) {
            throw new IllegalArgumentException("Named skin count must be 1.." + MAX_SKINS_PER_MODEL);
        }
        Map<BlendResourceId, Map<String, BlendResourceId>> copy = new LinkedHashMap<>();
        for (var entry : skins.entrySet()) {
            BlendResourceId name = Objects.requireNonNull(entry.getKey(), "skin name");
            Map<String, BlendResourceId> replacements = Objects.requireNonNull(entry.getValue(), "replacements");
            if (replacements.isEmpty() || replacements.size() > MAX_REPLACEMENTS_PER_SKIN) {
                throw new IllegalArgumentException("Skin replacement count must be 1.." + MAX_REPLACEMENTS_PER_SKIN);
            }
            Map<String, BlendResourceId> slots = new LinkedHashMap<>();
            replacements.forEach((slot, texture) -> {
                if (Objects.requireNonNull(slot, "slot").isBlank()) throw new IllegalArgumentException("Skin slot must not be blank");
                slots.put(slot, Objects.requireNonNull(texture, "texture"));
            });
            copy.put(name, Map.copyOf(slots));
        }
        return Map.copyOf(copy);
    }
}
