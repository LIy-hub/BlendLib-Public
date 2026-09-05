package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Package-private X6 input limits shared by manifest preparation and code-driven builders. */
final class X6Ids {
    static final int MAX_VARIANTS = 128;
    static final int MAX_RULES = 256;
    static final int MAX_PARTS = 512;
    static final int MAX_RULE_DEPTH = 8;
    static final int MAX_LAYERS = 16;
    /** The isolated manifest is bounded at the byte boundary before UTF-8 decode. */
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    static final int MAX_MANIFEST_LINES = 1_024;
    static final int MAX_CONTEXT_VALUE_LENGTH = 64;
    private static final Pattern CONTEXT_VALUE = Pattern.compile("[a-z0-9._/-]+");

    private X6Ids() {
    }

    static BlendResourceId requireId(BlendResourceId value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static String requireContextValue(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.length() > MAX_CONTEXT_VALUE_LENGTH || !CONTEXT_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must use the bounded canonical X6 context-value grammar");
        }
        return value;
    }

    /**
     * Retains canonical insertion order instead of relying on the unspecified iteration order of
     * {@link Map#copyOf(Map)}. Callers sort before invoking this helper.
     */
    static <K, V> Map<K, V> immutableOrderedMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
    }
}
