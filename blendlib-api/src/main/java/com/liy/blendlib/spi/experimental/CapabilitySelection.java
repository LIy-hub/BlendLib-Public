package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.BlendDiagnosticSeverity;
import java.util.Objects;
import java.util.Optional;

/** Immutable registry-produced selection or safe-fallback outcome for one requested capability. */
@ExperimentalBlendLibSpi
public final class CapabilitySelection {
    private final CapabilityRequest request;
    private final CapabilitySelectionOutcome outcome;
    private final Optional<CapabilityOffer> selectedOffer;
    private final Optional<CapabilityDiagnostic> diagnostic;

    CapabilitySelection(
            CapabilityRequest request,
            CapabilitySelectionOutcome outcome,
            Optional<CapabilityOffer> selectedOffer,
            Optional<CapabilityDiagnostic> diagnostic) {
        this.request = Objects.requireNonNull(request, "request");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.selectedOffer = Objects.requireNonNull(selectedOffer, "selectedOffer");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        validateFrozenSemantics();
    }

    /**
     * Returns the original immutable request.
     *
     * @return capability request frozen by the registry
     */
    public CapabilityRequest request() {
        return request;
    }

    /**
     * Returns the selected, fallback, or failed outcome.
     *
     * @return final outcome
     */
    public CapabilitySelectionOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the selected provider offer only for a selected outcome.
     *
     * @return optional selected offer
     */
    public Optional<CapabilityOffer> selectedOffer() {
        return selectedOffer;
    }

    /**
     * Returns the fallback or failure diagnostic when present.
     *
     * @return optional structured diagnostic
     */
    public Optional<CapabilityDiagnostic> diagnostic() {
        return diagnostic;
    }

    /**
     * Returns whether this outcome may participate in a publishable generation.
     *
     * @return whether selection is selected or safely fallen back
     */
    public boolean isPublishable() {
        return outcome != CapabilitySelectionOutcome.FAILED;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CapabilitySelection that)) {
            return false;
        }
        return request.equals(that.request)
                && outcome == that.outcome
                && selectedOffer.equals(that.selectedOffer)
                && diagnostic.equals(that.diagnostic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(request, outcome, selectedOffer, diagnostic);
    }

    @Override
    public String toString() {
        return "CapabilitySelection[request=" + request + ", outcome=" + outcome
                + ", selectedOffer=" + selectedOffer + ", diagnostic=" + diagnostic + ']';
    }

    void validateFrozenSemantics() {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(selectedOffer, "selectedOffer");
        Objects.requireNonNull(diagnostic, "diagnostic");
        switch (outcome) {
            case SELECTED -> validateSelected();
            case FALLBACK -> validateFallback();
            case FAILED -> validateFailed();
        }
    }

    private void validateSelected() {
        if (selectedOffer.isEmpty() || diagnostic.isPresent()) {
            throw new IllegalArgumentException("A selected capability needs one offer and no diagnostic");
        }
        CapabilityOffer offer = selectedOffer.orElseThrow();
        if (!offer.capabilityId().equals(request.capabilityId())) {
            throw new IllegalArgumentException("A selected offer must match the requested capability identity");
        }
        if (!request.supportedVersions().contains(offer.protocolVersion())) {
            throw new IllegalArgumentException("A selected offer must be inside the requested protocol-version range");
        }
    }

    private void validateFallback() {
        if (selectedOffer.isPresent() || diagnostic.isEmpty()
                || diagnostic.orElseThrow().severity() != BlendDiagnosticSeverity.WARNING) {
            throw new IllegalArgumentException("A fallback needs one warning diagnostic and no provider offer");
        }
        CapabilityDiagnostic fallbackDiagnostic = diagnostic.orElseThrow();
        if (request.requirement() != CapabilityRequirement.OPTIONAL || request.fallback().isEmpty()) {
            throw new IllegalArgumentException("Only an optional request with an explicit fallback may fall back");
        }
        if (fallbackDiagnostic.code() != CapabilityErrorCode.OPTIONAL_FALLBACK
                || !fallbackDiagnostic.capabilityId().equals(Optional.of(request.capabilityId()))
                || fallbackDiagnostic.providerId().isPresent()) {
            throw new IllegalArgumentException("Fallback diagnostic must identify the requested capability");
        }
    }

    private void validateFailed() {
        if (selectedOffer.isPresent() || diagnostic.isEmpty()
                || diagnostic.orElseThrow().severity() != BlendDiagnosticSeverity.ERROR) {
            throw new IllegalArgumentException("A failed selection needs one error diagnostic and no provider offer");
        }
        CapabilityDiagnostic failureDiagnostic = diagnostic.orElseThrow();
        if (request.requirement() != CapabilityRequirement.REQUIRED) {
            throw new IllegalArgumentException("An optional request with a declared fallback cannot be marked failed");
        }
        if (!failureDiagnostic.capabilityId().equals(Optional.of(request.capabilityId()))
                || failureDiagnostic.providerId().isPresent()
                || failureDiagnostic.code() == CapabilityErrorCode.OPTIONAL_FALLBACK) {
            throw new IllegalArgumentException("Failure diagnostic must identify the required capability");
        }
    }
}
