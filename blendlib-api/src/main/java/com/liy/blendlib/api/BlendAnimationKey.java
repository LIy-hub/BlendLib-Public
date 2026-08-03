package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Immutable, namespace-qualified semantic key for one BlendLib animation state.
 *
 * <p>The key deliberately identifies animation intent rather than a GLB clip
 * filename. Creating a key only validates its value; it never discovers assets
 * or performs I/O.</p>
 */
public final class BlendAnimationKey {
    private final BlendResourceId resourceId;

    private BlendAnimationKey(BlendResourceId resourceId) {
        this.resourceId = resourceId;
    }

    /**
     * Parses a canonical {@code namespace:path} animation key.
     *
     * @param value exact untrimmed semantic animation identity
     * @return validated immutable animation key
     */
    public static BlendAnimationKey parse(String value) {
        return fromResourceId(BlendResourceId.parse(value));
    }

    /**
     * Creates a key from separately validated namespace and path components.
     *
     * @param namespace canonical lowercase namespace
     * @param path canonical animation path
     * @return validated immutable animation key
     */
    public static BlendAnimationKey of(String namespace, String path) {
        return fromResourceId(BlendResourceId.of(namespace, path));
    }

    /**
     * Wraps a validated semantic resource identity as an animation key.
     *
     * @param resourceId canonical pure resource identity
     * @return immutable animation key
     */
    public static BlendAnimationKey fromResourceId(BlendResourceId resourceId) {
        return new BlendAnimationKey(Objects.requireNonNull(resourceId, "resourceId"));
    }

    /**
     * Returns the underlying pure resource identity.
     *
     * @return canonical resource identity
     */
    public BlendResourceId resourceId() {
        return resourceId;
    }

    /**
     * Returns the canonical lowercase namespace component.
     *
     * @return namespace component
     */
    public String namespace() {
        return resourceId.namespace();
    }

    /**
     * Returns the canonical extension-free path component.
     *
     * @return path component
     */
    public String path() {
        return resourceId.path();
    }

    /**
     * Returns the canonical {@code namespace:path} form.
     *
     * @return canonical value
     */
    public String value() {
        return resourceId.value();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof BlendAnimationKey that && resourceId.equals(that.resourceId);
    }

    @Override
    public int hashCode() {
        return resourceId.hashCode();
    }

    @Override
    public String toString() {
        return resourceId.toString();
    }
}
