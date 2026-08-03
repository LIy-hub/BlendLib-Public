package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Stable fluent builder for one block-entity host binding.
 *
 * @param <H> consumer's block-entity-host token type
 */
public final class BlockEntityRegistrationBuilder<H> {
    private final H host;
    private BlendModelKey model;
    private AnimationRequestSource<? super H> animationSource;

    BlockEntityRegistrationBuilder(H host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Assigns the semantic model key.
     *
     * @param modelKey non-null model identity
     * @return this builder
     */
    public BlockEntityRegistrationBuilder<H> model(BlendModelKey modelKey) {
        this.model = Objects.requireNonNull(modelKey, "modelKey");
        return this;
    }

    /**
     * Assigns the typed source of semantic animation requests.
     *
     * @param source non-null request source
     * @return this builder
     */
    public BlockEntityRegistrationBuilder<H> animation(AnimationRequestSource<? super H> source) {
        this.animationSource = Objects.requireNonNull(source, "source");
        return this;
    }

    /**
     * Assigns one fixed immutable semantic animation request.
     *
     * @param request non-null immutable request
     * @return this builder
     */
    public BlockEntityRegistrationBuilder<H> animation(AnimationRequest request) {
        AnimationRequest fixedRequest = Objects.requireNonNull(request, "request");
        return animation(ignoredHost -> fixedRequest);
    }

    /**
     * Validates and returns an immutable typed block-entity registration specification.
     *
     * @return completed immutable specification
     * @throws BlendRegistrationException if model or animation information is missing
     */
    public HostRegistrationSpec<H> build() {
        return BlendLib.completedSpec(HostKind.BLOCK_ENTITY, host, model, animationSource);
    }

    /**
     * Validates, submits, and receives an immutable adapter acknowledgement.
     *
     * @return accepted registration receipt
     * @throws BlendRegistrationException if validation, duplicate detection, or adapter acceptance fails
     */
    public RegistrationReceipt register() {
        return BlendLib.register(build());
    }
}
