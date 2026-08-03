package com.liy.blendlib.core.tooling;

/** Stable process exit codes for the pure-Java X5 asset validation CLI. */
public enum AssetValidationExitCode {
    SUCCESS(0),
    INVALID_ASSET(2),
    USAGE(64),
    UNEXPECTED_FAILURE(70);

    private final int code;

    AssetValidationExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
