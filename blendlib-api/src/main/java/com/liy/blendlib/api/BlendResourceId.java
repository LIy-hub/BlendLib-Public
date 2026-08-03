package com.liy.blendlib.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, namespace-qualified BlendLib resource identity.
 *
 * <p>The namespace grammar is {@code [a-z0-9._-]+}; the path grammar is
 * {@code [a-z0-9._/-]+}. Paths cannot start or end with a slash, contain an
 * empty segment, or contain {@code .} or {@code ..} segments. Construction is
 * validation-only and never performs I/O.</p>
 */
public final class BlendResourceId {
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9._/-]+");

    private final String namespace;
    private final String path;
    private final String value;

    private BlendResourceId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
        this.value = namespace + ':' + path;
    }

    /**
     * Parses a canonical {@code namespace:path} resource identity.
     *
     * @param value the exact, untrimmed identity value
     * @return the validated resource identity
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if the value is not a canonical identity
     */
    public static BlendResourceId parse(String value) {
        Objects.requireNonNull(value, "value");

        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw new IllegalArgumentException("Blend resource id must be exactly namespace:path: " + value);
        }

        return of(value.substring(0, separator), value.substring(separator + 1));
    }

    /**
     * Creates a resource identity from separately validated namespace and path values.
     *
     * @param namespace the namespace component
     * @param path the path component
     * @return the validated resource identity
     * @throws NullPointerException if either component is null
     * @throws IllegalArgumentException if either component is outside the v1 grammar
     */
    public static BlendResourceId of(String namespace, String path) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        validateNamespace(namespace);
        validatePath(path);
        return new BlendResourceId(namespace, path);
    }

    /**
     * Returns the canonical lowercase namespace component.
     *
     * @return namespace component
     */
    public String namespace() {
        return namespace;
    }

    /**
     * Returns the canonical slash-separated path component.
     *
     * @return path component
     */
    public String path() {
        return path;
    }

    /**
     * Returns the canonical {@code namespace:path} value accepted by {@link #parse(String)}.
     *
     * @return canonical identity value
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlendResourceId that)) {
            return false;
        }
        return namespace.equals(that.namespace) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }

    @Override
    public String toString() {
        return value;
    }

    private static void validateNamespace(String namespace) {
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid Blend resource namespace: " + namespace);
        }
    }

    private static void validatePath(String path) {
        if (!PATH_PATTERN.matcher(path).matches()
                || path.startsWith("/")
                || path.endsWith("/")
                || path.contains("//")) {
            throw new IllegalArgumentException("Invalid Blend resource path: " + path);
        }

        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Blend resource paths cannot contain traversal segments: " + path);
            }
        }
    }
}
