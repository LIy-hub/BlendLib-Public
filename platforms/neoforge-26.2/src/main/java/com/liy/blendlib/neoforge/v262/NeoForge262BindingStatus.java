package com.liy.blendlib.neoforge.v262;

import java.util.List;
import java.util.Objects;

/**
 * Immutable externally visible explanation of the NeoForge 26.2 adapter boundary.
 *
 * <p><strong>WAITING boundary:</strong> source URLs and access date identify the official material
 * that was sufficient for a bridge but insufficient for a production loader binding. It does not
 * imply a successful NeoForge game launch or visual integration.</p>
 *
 * @param state current binding status
 * @param explanation bounded human-readable reason
 * @param officialSources exact official source URLs consulted for this boundary
 * @param sourceAccessDate ISO-8601 access date
 */
public record NeoForge262BindingStatus(
        NeoForge262BindingState state,
        String explanation,
        List<String> officialSources,
        String sourceAccessDate) {
    /**
     * Validates immutable binding-status data.
     */
    public NeoForge262BindingStatus {
        state = Objects.requireNonNull(state, "state");
        explanation = Objects.requireNonNull(explanation, "explanation");
        if (explanation.isBlank() || explanation.length() > 512) {
            throw new IllegalArgumentException("explanation must be non-blank and at most 512 characters");
        }
        officialSources = List.copyOf(Objects.requireNonNull(officialSources, "officialSources"));
        if (officialSources.isEmpty() || officialSources.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("officialSources must contain non-blank official URLs");
        }
        sourceAccessDate = Objects.requireNonNull(sourceAccessDate, "sourceAccessDate");
        if (!sourceAccessDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("sourceAccessDate must use YYYY-MM-DD");
        }
    }
}
