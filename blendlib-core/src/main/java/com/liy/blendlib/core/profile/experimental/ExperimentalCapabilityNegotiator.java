package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, validation-only X9 capability negotiation. */
final class ExperimentalCapabilityNegotiator {
    private ExperimentalCapabilityNegotiator() {
    }

    static Result negotiate(ExperimentalDescriptor descriptor) {
        List<BlendResourceId> selected = new ArrayList<>();
        List<ExperimentalProfileDiagnostic> diagnostics = new ArrayList<>();
        Set<BlendResourceId> requiredIds = new HashSet<>();
        for (ExperimentalCapabilityRequirement requirement : descriptor.requiredCapabilities()) {
            requiredIds.add(requirement.id());
            ExperimentalCapabilityCatalog.Entry entry = ExperimentalCapabilityCatalog.find(requirement.id()).orElse(null);
            if (entry == null || entry.status() != ExperimentalCapabilityCatalog.Status.PROPOSED_VALIDATION
                    || !requirement.includes(entry.version())) {
                throw error("BLENDLIB-EXT-001", capabilityLocation(requirement.id()),
                        "Required X9 capability is unknown, disabled, or version-incompatible");
            }
            selected.add(requirement.id());
        }
        enforceProfileCapabilities(descriptor.profile(), requiredIds);
        for (ExperimentalCapabilityRequirement requirement : descriptor.optionalCapabilities()) {
            ExperimentalCapabilityCatalog.Entry entry = ExperimentalCapabilityCatalog.find(requirement.id()).orElse(null);
            if (entry != null && entry.status() == ExperimentalCapabilityCatalog.Status.PROPOSED_VALIDATION
                    && requirement.includes(entry.version())) {
                selected.add(requirement.id());
                continue;
            }
            if (requirement.fallback() == OptionalCapabilityFallback.METADATA_IGNORE
                    && ExperimentalCapabilityCatalog.isMetadataOnly(requirement.id())) {
                diagnostics.add(new ExperimentalProfileDiagnostic(ExperimentalProfileDiagnostic.Severity.WARN,
                        "BLENDLIB-X9-EXT-002", capabilityLocation(requirement.id()),
                        "Optional metadata capability is not negotiated",
                        OptionalCapabilityFallback.METADATA_IGNORE.serializedName()));
                continue;
            }
            throw error("BLENDLIB-X9-EXT-003", capabilityLocation(requirement.id()),
                    "Optional X9 capability has no declared semantic-equivalent fallback; validation fails closed");
        }
        selected.sort(Comparator.comparing(BlendResourceId::value));
        diagnostics.sort(Comparator.comparing(ExperimentalProfileDiagnostic::code)
                .thenComparing(ExperimentalProfileDiagnostic::location)
                .thenComparing(ExperimentalProfileDiagnostic::message));
        return new Result(List.copyOf(selected), List.copyOf(diagnostics));
    }

    private static void enforceProfileCapabilities(ExperimentalProfile profile, Set<BlendResourceId> ids) {
        require(ids, "blendlib:cubic-spline", profile);
        require(ids, "blendlib:vertex-color", profile);
        require(ids, "blendlib:multiple-uv", profile);
        require(ids, "blendlib:richer-material-metadata", profile);
        if (profile == ExperimentalProfile.MORPH_V1) {
            require(ids, "blendlib:morph-targets", profile);
        }
    }

    private static void require(Set<BlendResourceId> ids, String id, ExperimentalProfile profile) {
        if (!ids.contains(BlendResourceId.parse(id))) {
            throw error("BLENDLIB-X9-DESC-003", capabilityLocation(BlendResourceId.parse(id)),
                    "Profile " + profile.serializedName() + " requires capability " + id);
        }
    }

    private static String capabilityLocation(BlendResourceId id) {
        return "/capabilities/" + id.value().replace("~", "~0").replace("/", "~1");
    }

    private static ExperimentalProfileValidationException error(String code, String location, String message) {
        return new ExperimentalProfileValidationException(new ExperimentalProfileDiagnostic(
                ExperimentalProfileDiagnostic.Severity.ERROR, code, location, message,
                OptionalCapabilityFallback.MISSING_MODEL.serializedName()));
    }

    record Result(List<BlendResourceId> selected, List<ExperimentalProfileDiagnostic> diagnostics) {
    }
}
