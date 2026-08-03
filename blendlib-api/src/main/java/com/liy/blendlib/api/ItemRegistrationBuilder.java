package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Stable fluent builder for one stateless item host binding.
 *
 * <p>Items currently support looping animation only. One-shot and hold playback require persistent
 * per-ItemStack identity, which remains outside the X1 stable contract.</p>
 *
 * @param <H> consumer's item-host token type
 */
public final class ItemRegistrationBuilder<H> {
    private final H host;
    private BlendModelKey model;
    private AnimationRequestSource<? super H> animationSource;

    ItemRegistrationBuilder(H host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Assigns the semantic model key.
     *
     * @param modelKey non-null model identity
     * @return this builder
     */
    public ItemRegistrationBuilder<H> model(BlendModelKey modelKey) {
        this.model = Objects.requireNonNull(modelKey, "modelKey");
        return this;
    }

    /**
     * Assigns the typed source of looping semantic animation requests.
     *
     * <p>The source is checked for the registered host during {@link #build()} and on every later
     * evaluation so a mutable source cannot introduce one-shot item state.</p>
     *
     * @param source non-null request source
     * @return this builder
     */
    public ItemRegistrationBuilder<H> animation(AnimationRequestSource<? super H> source) {
        this.animationSource = Objects.requireNonNull(source, "source");
        return this;
    }

    /**
     * Assigns one fixed immutable looping semantic animation request.
     *
     * @param request non-null immutable request
     * @return this builder
     */
    public ItemRegistrationBuilder<H> animation(AnimationRequest request) {
        AnimationRequest fixedRequest = Objects.requireNonNull(request, "request");
        return animation(ignoredHost -> fixedRequest);
    }

    /**
     * Validates and returns an immutable typed item registration specification.
     *
     * @return completed immutable specification
     * @throws BlendRegistrationException if model/animation information is missing or playback is not looping
     */
    public HostRegistrationSpec<H> build() {
        HostRegistrationSpec<H> specification = BlendLib.completedSpec(HostKind.ITEM, host, model, animationSource);
        specification.animationFor(host);
        return specification;
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
