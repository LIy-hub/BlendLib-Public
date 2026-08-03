package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Immutable, extension-free key for one BlendLib model.
 *
 * <p>A key is a semantic identity rather than a concrete resource filename. Its descriptor is always
 * {@code assets/<namespace>/blend_models/<path>.json}; creating or converting a key never performs I/O.</p>
 */
public final class BlendModelKey {
    private static final String DESCRIPTOR_DIRECTORY = "blend_models/";
    private static final String DESCRIPTOR_SUFFIX = ".json";

    private final BlendResourceId resourceId;

    private BlendModelKey(BlendResourceId resourceId) {
        this.resourceId = resourceId;
    }

    /**
     * Parses a canonical {@code namespace:path} model key.
     *
     * @param value exact untrimmed model identity
     * @return validated immutable model key
     */
    public static BlendModelKey parse(String value) {
        return fromResourceId(BlendResourceId.parse(value));
    }

    /**
     * Creates a key from separately validated namespace and extension-free path components.
     *
     * @param namespace canonical lowercase namespace
     * @param path canonical extension-free model path
     * @return validated immutable model key
     */
    public static BlendModelKey of(String namespace, String path) {
        return fromResourceId(BlendResourceId.of(namespace, path));
    }

    /**
     * Wraps a validated semantic resource identity as a model key.
     *
     * @param resourceId canonical extension-free resource identity
     * @return immutable model key
     */
    public static BlendModelKey fromResourceId(BlendResourceId resourceId) {
        Objects.requireNonNull(resourceId, "resourceId");
        if (resourceId.path().endsWith(DESCRIPTOR_SUFFIX)) {
            throw new IllegalArgumentException("Blend model keys must not include the descriptor .json suffix: " + resourceId);
        }
        return new BlendModelKey(resourceId);
    }

    /**
     * Converts an exact descriptor resource identity back into its semantic model key.
     *
     * @param descriptorResourceId canonical descriptor resource identity
     * @return immutable semantic model key
     */
    public static BlendModelKey fromDescriptorResourceId(BlendResourceId descriptorResourceId) {
        Objects.requireNonNull(descriptorResourceId, "descriptorResourceId");
        String descriptorPath = descriptorResourceId.path();
        if (!descriptorPath.startsWith(DESCRIPTOR_DIRECTORY) || !descriptorPath.endsWith(DESCRIPTOR_SUFFIX)) {
            throw new IllegalArgumentException("Descriptor resource must be under blend_models and end in .json: " + descriptorResourceId);
        }

        String keyPath = descriptorPath.substring(DESCRIPTOR_DIRECTORY.length(), descriptorPath.length() - DESCRIPTOR_SUFFIX.length());
        return fromResourceId(BlendResourceId.of(descriptorResourceId.namespace(), keyPath));
    }

    /**
     * Returns the extension-free semantic resource identity used by core loading APIs.
     *
     * @return extension-free model resource identity
     */
    public BlendResourceId resourceId() {
        return resourceId;
    }

    /**
     * Returns the only descriptor resource location accepted for this key.
     *
     * @return canonical descriptor resource identity
     */
    public BlendResourceId descriptorResourceId() {
        return BlendResourceId.of(resourceId.namespace(), DESCRIPTOR_DIRECTORY + resourceId.path() + DESCRIPTOR_SUFFIX);
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
     * Returns the canonical extension-free model path component.
     *
     * @return model path component
     */
    public String path() {
        return resourceId.path();
    }

    /**
     * Returns the canonical {@code namespace:path} model key.
     *
     * @return canonical model key value
     */
    public String value() {
        return resourceId.value();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof BlendModelKey that && resourceId.equals(that.resourceId);
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
