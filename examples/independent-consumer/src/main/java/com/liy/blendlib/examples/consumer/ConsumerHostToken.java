package com.liy.blendlib.examples.consumer;

/** Consumer-owned opaque host token used only to demonstrate a stable semantic specification. */
public record ConsumerHostToken(String id) {
    public ConsumerHostToken {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must be non-blank");
        }
    }
}
