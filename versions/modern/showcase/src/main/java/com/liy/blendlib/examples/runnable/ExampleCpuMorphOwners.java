package com.liy.blendlib.examples.runnable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Objects;

/** Weak identity bookkeeping only: every mutable choice remains on its actor-owned control object. */
public final class ExampleCpuMorphOwners {
    private final ArrayList<WeakReference<ExampleCpuMorphControls>> tracked = new ArrayList<>();
    private long generation = -1;
    private String session;

    public ExampleCpuMorphControls use(ExampleCpuMorphControls owner, long generation, String session) {
        Objects.requireNonNull(owner, "owner");
        if (generation < 0 || Objects.requireNonNull(session, "session").isBlank())
            throw new IllegalArgumentException("A live generation/session is required");
        if (this.generation != generation || !session.equals(this.session)) {
            clear(); this.generation = generation; this.session = session;
        }
        tracked.removeIf(reference -> reference.get() == null);
        if (tracked.stream().noneMatch(reference -> reference.get() == owner)) tracked.add(new WeakReference<>(owner));
        return owner.at(generation, session);
    }

    public void remove(ExampleCpuMorphControls owner) {
        Objects.requireNonNull(owner, "owner").clear();
        tracked.removeIf(reference -> reference.get() == null || reference.get() == owner);
    }

    public void clear() {
        for (var reference : tracked) { var owner = reference.get(); if (owner != null) owner.clear(); }
        tracked.clear(); generation = -1; session = null;
    }
}
