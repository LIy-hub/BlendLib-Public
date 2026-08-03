package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable structured diagnostic produced during capability negotiation or provider lifecycle.
 *
 * @param code stable experimental capability code
 * @param severity fail-closed, warning, or informational severity
 * @param capabilityId affected capability when applicable
 * @param providerId affected provider when applicable
 * @param message bounded and control-character-sanitized explanation
 */
@ExperimentalBlendLibSpi
public record CapabilityDiagnostic(
        CapabilityErrorCode code,
        BlendDiagnosticSeverity severity,
        Optional<BlendResourceId> capabilityId,
        Optional<BlendResourceId> providerId,
        String message) {

    /** Maximum retained diagnostic message length. */
    public static final int MAX_MESSAGE_LENGTH = 512;

    /** Validates an immutable bounded diagnostic. */
    public CapabilityDiagnostic {
        code = Objects.requireNonNull(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        providerId = Objects.requireNonNull(providerId, "providerId");
        capabilityId.ifPresent(value -> ExperimentalControlBoundary.requireId(value, "capabilityId"));
        providerId.ifPresent(value -> ExperimentalControlBoundary.requireId(value, "providerId"));
        message = ExperimentalControlBoundary.sanitize(
                Objects.requireNonNull(message, "message"), MAX_MESSAGE_LENGTH, "Diagnostic unavailable");
        message = ExperimentalControlBoundary.requireText(message, MAX_MESSAGE_LENGTH, "message");
    }

    /**
     * Creates a capability-scoped diagnostic without attributing it to a provider.
     *
     * @param code capability code
     * @param severity diagnostic severity
     * @param capabilityId affected capability
     * @param message bounded explanation
     * @return immutable diagnostic
     */
    public static CapabilityDiagnostic capability(
            CapabilityErrorCode code,
            BlendDiagnosticSeverity severity,
            BlendResourceId capabilityId,
            String message) {
        return new CapabilityDiagnostic(code, severity,
                ExperimentalControlBoundary.diagnosticId(Objects.requireNonNull(capabilityId, "capabilityId")),
                Optional.empty(), boundedMessage(message));
    }

    /**
     * Creates a provider-scoped diagnostic without attributing it to a capability.
     *
     * @param code capability code
     * @param severity diagnostic severity
     * @param providerId affected provider
     * @param message bounded explanation
     * @return immutable diagnostic
     */
    public static CapabilityDiagnostic provider(
            CapabilityErrorCode code,
            BlendDiagnosticSeverity severity,
            BlendResourceId providerId,
            String message) {
        return new CapabilityDiagnostic(code, severity, Optional.empty(),
                ExperimentalControlBoundary.diagnosticId(Objects.requireNonNull(providerId, "providerId")),
                boundedMessage(message));
    }

    static CapabilityDiagnostic unscoped(
            CapabilityErrorCode code,
            BlendDiagnosticSeverity severity,
            String message) {
        return new CapabilityDiagnostic(code, severity, Optional.empty(), Optional.empty(), boundedMessage(message));
    }

    static String boundedMessage(String message) {
        message = ExperimentalControlBoundary.sanitize(
                Objects.requireNonNull(message, "message"), MAX_MESSAGE_LENGTH, "Diagnostic unavailable");
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }

    static String causeSummary(Throwable exception) {
        return ExperimentalControlBoundary.safeThrowableType(exception);
    }

    static String causeType(Throwable exception) {
        return ExperimentalControlBoundary.safeThrowableType(exception);
    }
}
