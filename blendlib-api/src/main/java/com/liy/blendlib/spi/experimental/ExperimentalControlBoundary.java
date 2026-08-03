package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Package-private defensive boundary for untrusted Experimental SPI metadata and callbacks. */
final class ExperimentalControlBoundary {
    static final int MAX_ID_LENGTH = 256;
    private static final String FALLBACK_THROWABLE_TYPE = "RuntimeException";
    private static final ThreadLocal<Integer> CALLBACK_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ExperimentalControlBoundary() {
    }

    static void requireId(BlendResourceId id, String fieldName) {
        Objects.requireNonNull(id, fieldName);
        if (!isValidId(id)) {
            throw new IllegalArgumentException(fieldName + " exceeds the Experimental SPI identity boundary");
        }
    }

    static String requireText(String value, int maximumLength, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must be non-blank and at most "
                    + maximumLength + " characters");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    || (Character.isSurrogate(value.charAt(offset))
                    && Character.charCount(codePoint) == 1)) {
                throw new IllegalArgumentException(fieldName + " contains a forbidden control or surrogate character");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    static boolean isValidId(BlendResourceId id) {
        if (id == null) {
            return false;
        }
        try {
            String value = id.value();
            if (value == null || value.isEmpty() || value.length() > MAX_ID_LENGTH) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < 0x21 || character > 0x7e) {
                    return false;
                }
            }
            BlendResourceId canonical = BlendResourceId.parse(value);
            return canonical.value().equals(value)
                    && canonical.namespace().equals(id.namespace())
                    && canonical.path().equals(id.path());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static Optional<BlendResourceId> diagnosticId(BlendResourceId id) {
        return isValidId(id) ? Optional.of(id) : Optional.empty();
    }

    static String safeThrowableType(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        String type = FALLBACK_THROWABLE_TYPE;
        try {
            String candidate = throwable.getClass().getSimpleName();
            if (candidate != null && !candidate.isBlank()) {
                type = sanitize(candidate, 96, FALLBACK_THROWABLE_TYPE);
            }
        } catch (Throwable ignored) {
            // Class metadata is diagnostic-only; a secondary failure must never replace the primary failure.
        }
        return type;
    }

    /** Returns whether control-flow should be restored after in-scope state has been made terminal. */
    @SuppressWarnings("removal")
    static boolean isFatal(Throwable throwable) {
        return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
    }

    /** Rethrows VM-fatal failures after the caller has restored its invariants and released ownership. */
    @SuppressWarnings("removal")
    static void rethrowIfFatal(Throwable throwable) {
        if (throwable instanceof VirtualMachineError error) {
            throw error;
        }
        if (throwable instanceof ThreadDeath death) {
            throw death;
        }
    }

    static String sanitize(String value, int maximumLength, String fallback) {
        Objects.requireNonNull(fallback, "fallback");
        if (value == null || maximumLength < 1) {
            return fallback;
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximumLength));
        for (int offset = 0; offset < value.length() && result.length() < maximumLength; ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                result.append('?');
            } else if (codePoint <= Character.MAX_VALUE) {
                result.append((char) codePoint);
            } else if (result.length() + 2 <= maximumLength) {
                result.appendCodePoint(codePoint);
            }
        }
        String sanitized = result.toString();
        return sanitized.isBlank() ? fallback : sanitized;
    }

    static boolean inExternalCallback() {
        return CALLBACK_DEPTH.get() > 0;
    }

    static CallbackScope enterExternalCallback() {
        CALLBACK_DEPTH.set(CALLBACK_DEPTH.get() + 1);
        return new CallbackScope();
    }

    static <T> T callExternal(Supplier<T> callback) {
        Objects.requireNonNull(callback, "callback");
        CallbackScope scope = enterExternalCallback();
        try {
            return callback.get();
        } finally {
            scope.close();
        }
    }

    static void runExternal(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        CallbackScope scope = enterExternalCallback();
        try {
            callback.run();
        } finally {
            scope.close();
        }
    }

    static final class CallbackScope implements AutoCloseable {
        private boolean closed;

        private CallbackScope() {
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            int nextDepth = CALLBACK_DEPTH.get() - 1;
            if (nextDepth <= 0) {
                CALLBACK_DEPTH.remove();
            } else {
                CALLBACK_DEPTH.set(nextDepth);
            }
        }
    }
}
