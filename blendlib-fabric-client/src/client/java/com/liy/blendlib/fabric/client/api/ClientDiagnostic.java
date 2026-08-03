package com.liy.blendlib.fabric.client.api;

import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/**
 * Immutable client-public diagnostic projection.
 *
 * <p>It intentionally contains only stable scalar and pure-API data, rather than exposing a
 * core diagnostic implementation type through the 26.1.2 adapter API.</p>
 */
public record ClientDiagnostic(
        ClientDiagnosticSeverity severity,
        String code,
        BlendResourceId modelKey,
        BlendResourceId resourceId,
        String location,
        String message,
        String causeSummary) {
    public ClientDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = requireText(code, "code");
        location = Objects.requireNonNull(location, "location");
        message = Objects.requireNonNull(message, "message");
        causeSummary = Objects.requireNonNull(causeSummary, "causeSummary");
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
