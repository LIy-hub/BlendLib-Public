package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Immutable acknowledgement that a controlled platform adapter accepted one semantic registration.
 *
 * @param adapterId canonical identity of the accepting adapter
 * @param hostKind accepted host category
 * @param modelKey accepted semantic model key
 */
public record RegistrationReceipt(
        BlendResourceId adapterId,
        HostKind hostKind,
        BlendModelKey modelKey) {

    /** Validates a platform receipt without exposing platform renderer internals. */
    public RegistrationReceipt {
        adapterId = Objects.requireNonNull(adapterId, "adapterId");
        hostKind = Objects.requireNonNull(hostKind, "hostKind");
        modelKey = Objects.requireNonNull(modelKey, "modelKey");
    }
}
