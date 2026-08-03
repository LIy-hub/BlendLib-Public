package com.liy.blendlib.core.profile.experimental;

import com.liy.blendlib.api.BlendResourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Fixed, deterministic capability catalog for the X9 validation candidate. */
final class ExperimentalCapabilityCatalog {
    static final ExperimentalSemVer V1 = new ExperimentalSemVer(1, 0, 0);

    private static final Map<BlendResourceId, Entry> ENTRIES = entries();

    private ExperimentalCapabilityCatalog() {
    }

    static Optional<Entry> find(BlendResourceId id) {
        return Optional.ofNullable(ENTRIES.get(id));
    }

    static boolean isMetadataOnly(BlendResourceId id) {
        return "metadata-hints".equals(id.path()) || id.path().startsWith("metadata/");
    }

    private static Map<BlendResourceId, Entry> entries() {
        Map<BlendResourceId, Entry> entries = new LinkedHashMap<>();
        add(entries, "blendlib:morph-targets", Status.PROPOSED_VALIDATION);
        add(entries, "blendlib:cubic-spline", Status.PROPOSED_VALIDATION);
        add(entries, "blendlib:vertex-color", Status.PROPOSED_VALIDATION);
        add(entries, "blendlib:multiple-uv", Status.PROPOSED_VALIDATION);
        add(entries, "blendlib:richer-material-metadata", Status.PROPOSED_VALIDATION);
        add(entries, "blendlib:metadata-hints", Status.PROPOSED_VALIDATION);
        add(entries, "blendlib:draco", Status.DISABLED);
        add(entries, "blendlib:meshopt", Status.DISABLED);
        add(entries, "blendlib:ktx2", Status.DISABLED);
        return Map.copyOf(entries);
    }

    private static void add(Map<BlendResourceId, Entry> entries, String id, Status status) {
        BlendResourceId parsed = BlendResourceId.parse(id);
        entries.put(parsed, new Entry(parsed, V1, status));
    }

    record Entry(BlendResourceId id, ExperimentalSemVer version, Status status) {
    }

    enum Status {
        PROPOSED_VALIDATION,
        DISABLED
    }
}
