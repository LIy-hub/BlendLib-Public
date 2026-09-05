package com.liy.blendlib.fabric.client.host;

/** Shared X4 primary/cleanup failure precedence used by lifecycle and registry transactions. */
final class X4HostFailureSelector {
    private X4HostFailureSelector() {
    }

    /**
     * Selects one failure without replacing its object identity.
     *
     * <p>A primary {@link Error} always wins. Otherwise an {@link Error} cleanup wins. An ordinary
     * primary wins over an ordinary cleanup. Every distinct non-selected failure is retained as suppressed evidence.
     * This is the same precedence used by the X1 platform control transaction.</p>
     */
    static Throwable select(Throwable primary, Throwable cleanup) {
        if (primary == null) {
            return cleanup;
        }
        if (cleanup == null || cleanup == primary) {
            return primary;
        }
        Throwable selected;
        Throwable suppressed;
        if (isFatal(primary) || !isFatal(cleanup)) {
            selected = primary;
            suppressed = cleanup;
        } else {
            selected = cleanup;
            suppressed = primary;
        }
        try {
            selected.addSuppressed(suppressed);
        } catch (Throwable suppressionFailure) {
            if (isFatal(suppressionFailure)) {
                rethrow(suppressionFailure, "X4 suppression bookkeeping failed");
            }
            // Ordinary suppression metadata must never replace the selected original failure.
        }
        return selected;
    }

    static boolean isFatal(Throwable failure) {
        return failure instanceof Error;
    }

    static void rethrow(Throwable failure, String checkedFailureMessage) {
        if (failure == null) {
            return;
        }
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(checkedFailureMessage, failure);
    }
}
