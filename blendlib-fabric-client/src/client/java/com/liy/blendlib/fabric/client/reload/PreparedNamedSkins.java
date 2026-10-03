package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendResourceId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen validation result; never retains a resource manager or input maps. */
record PreparedNamedSkins(Map<BlendResourceId, Map<String, BlendResourceId>> valid,
        Map<BlendResourceId, String> invalid) {
    static final PreparedNamedSkins EMPTY = new PreparedNamedSkins(Map.of(), Map.of());
    PreparedNamedSkins {
        Map<BlendResourceId, Map<String, BlendResourceId>> copy = new LinkedHashMap<>();
        valid.forEach((name, slots) -> copy.put(name, Map.copyOf(slots)));
        valid = Map.copyOf(copy);
        invalid = Map.copyOf(invalid);
    }
}
