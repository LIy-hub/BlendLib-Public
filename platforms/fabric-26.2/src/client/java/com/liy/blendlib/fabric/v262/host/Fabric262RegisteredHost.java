package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.HostRegistrationSpec;
import java.util.Objects;

/**
 * Private production installation retaining the typed stable registration for one public binding.
 *
 * <p>The public {@link Fabric262HostBinding} remains metadata only. This object is never returned
 * by a public API and is the sole owner of the complete {@link HostRegistrationSpec}, including
 * its animation source. It evaluates that source only against the exact typed token passed to the
 * stable facade; a live {@code Entity} or {@code BlockEntity} is never cast into that token type.</p>
 */
final class Fabric262RegisteredHost {
    private final Fabric262HostBinding binding;
    private final HostRegistrationSpec<?> specification;

    Fabric262RegisteredHost(Fabric262HostTarget target, HostRegistrationSpec<?> specification) {
        this.specification = Objects.requireNonNull(specification, "specification");
        this.binding = new Fabric262HostBinding(
                Objects.requireNonNull(target, "target"), specification.model());
    }

    Fabric262HostBinding binding() {
        return binding;
    }

    AnimationRequest animationRequest() {
        return animationRequest(specification);
    }

    private static <H> AnimationRequest animationRequest(HostRegistrationSpec<H> specification) {
        return specification.animationFor(specification.host());
    }
}
