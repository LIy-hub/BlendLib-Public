package com.liy.blendlib.datagen;

import com.liy.blendlib.api.BlendResourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable strict descriptor animation graph declaration.
 *
 * <p><strong>Stable boundary:</strong> graph state identifiers are canonical resource IDs and are
 * serialized deterministically. It cannot introduce provider discovery, networking, or platform types.</p>
 *
 * @param initialState canonical state selected at instance initialization
 * @param states keyed immutable state declarations
 */
public record DatagenAnimationGraph(
        BlendResourceId initialState,
        Map<BlendResourceId, DatagenAnimationState> states) {
    /**
     * Validates immutable animation graph declarations.
     */
    public DatagenAnimationGraph {
        initialState = Objects.requireNonNull(initialState, "initialState");
        states = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(states, "states")));
        if (states.isEmpty() || states.containsKey(null) || states.values().stream().anyMatch(Objects::isNull)
                || !states.containsKey(initialState)) {
            throw new IllegalArgumentException("states must be non-empty, non-null, and contain initialState");
        }
        DatagenLimits.requireAtMost("animation states", states.size(), DatagenLimits.MAX_ANIMATION_STATES);
        long totalEvents = 0L;
        for (Map.Entry<BlendResourceId, DatagenAnimationState> entry : states.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().stateId())) {
                throw new IllegalArgumentException("animation state map key must equal the state declaration identity");
            }
            BlendResourceId next = entry.getValue().next().orElse(null);
            if (next != null && !states.containsKey(next)) {
                throw new IllegalArgumentException("animation next state must be declared: " + next);
            }
            totalEvents = Math.addExact(totalEvents, entry.getValue().events().size());
            if (totalEvents > DatagenLimits.MAX_VISUAL_EVENTS_PER_DESCRIPTOR) {
                throw new IllegalArgumentException("animation events exceed strict descriptor limit of "
                        + DatagenLimits.MAX_VISUAL_EVENTS_PER_DESCRIPTOR);
            }
        }
    }
}
