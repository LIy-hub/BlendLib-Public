package com.liy.blendlib.api;

import java.util.Objects;

/**
 * Immutable, typed semantic specification produced by a stable registration builder.
 *
 * <p>The generic host remains opaque to BlendLib's pure API: no platform type is imported or
 * reflected upon. The selected platform adapter owns host translation after this specification is accepted.</p>
 *
 * @param <H> consumer's host type
 * @param hostKind semantic host category
 * @param host typed host token supplied by the consumer
 * @param model semantic model key
 * @param animationSource typed source of semantic animation requests; item sources are wrapped so
 *                        every public evaluation enforces stateless looping playback
 */
public record HostRegistrationSpec<H>(
        HostKind hostKind,
        H host,
        BlendModelKey model,
        AnimationRequestSource<? super H> animationSource) {

    /** Validates a complete semantic registration specification. */
    public HostRegistrationSpec {
        hostKind = Objects.requireNonNull(hostKind, "hostKind");
        host = Objects.requireNonNull(host, "host");
        model = Objects.requireNonNull(model, "model");
        animationSource = Objects.requireNonNull(animationSource, "animationSource");
        if (hostKind == HostKind.ITEM) {
            AnimationRequestSource<? super H> suppliedSource = animationSource;
            AnimationRequestSource<H> validatedSource = currentHost -> validateItemRequest(
                    suppliedSource.requestFor(currentHost));
            animationSource = validatedSource;
        }
    }

    /**
     * Evaluates the typed source and verifies that it returned an immutable request.
     *
     * <p>Item specifications additionally reject non-looping playback because X1 exposes no
     * persistent per-ItemStack animation identity.</p>
     *
     * @param currentHost the host state to evaluate
     * @return the non-null semantic request
     */
    public AnimationRequest animationFor(H currentHost) {
        return Objects.requireNonNull(animationSource.requestFor(currentHost), "animationSource returned null");
    }

    private static AnimationRequest validateItemRequest(AnimationRequest request) {
        request = Objects.requireNonNull(request, "animationSource returned null");
        if (request.playbackMode() != PlaybackMode.LOOP) {
            throw BlendLib.registrationFailure(
                    BlendApiDiagnosticCode.UNSUPPORTED_ITEM_ANIMATION,
                    "Item registrations require LOOP playback until per-ItemStack identity is defined");
        }
        return request;
    }
}
