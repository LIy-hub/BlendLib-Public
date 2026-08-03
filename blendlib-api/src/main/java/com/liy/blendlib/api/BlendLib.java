package com.liy.blendlib.api;

import com.liy.blendlib.spi.experimental.PlatformAdapterControl;
import java.util.Objects;

/**
 * Stable, platform-neutral entry point for ordinary BlendLib model registrations.
 *
 * <p>Consumers normally only need this facade, existing {@code Blend*Key} values, and immutable
 * semantic request types. A version-specific platform author intentionally installs an adapter through
 * the separately marked experimental SPI; ordinary consumers do not pass renderer, asset, or platform
 * implementation objects through this facade.</p>
 */
public final class BlendLib {
    private BlendLib() {
    }

    /**
     * Starts a typed entity registration.
     *
     * @param host consumer's non-null entity host token
     * @param <H> host-token type
     * @return an entity registration builder
     */
    public static <H> EntityRegistrationBuilder<H> entity(H host) {
        return new EntityRegistrationBuilder<>(host);
    }

    /**
     * Starts a typed block-entity registration.
     *
     * @param host consumer's non-null block-entity host token
     * @param <H> host-token type
     * @return a block-entity registration builder
     */
    public static <H> BlockEntityRegistrationBuilder<H> blockEntity(H host) {
        return new BlockEntityRegistrationBuilder<>(host);
    }

    /**
     * Starts a typed item registration.
     *
     * @param host consumer's non-null item host token
     * @param <H> host-token type
     * @return an item registration builder
     */
    public static <H> ItemRegistrationBuilder<H> item(H host) {
        return new ItemRegistrationBuilder<>(host);
    }

    static <H> HostRegistrationSpec<H> completedSpec(
            HostKind hostKind,
            H host,
            BlendModelKey model,
            AnimationRequestSource<? super H> animationSource) {
        Objects.requireNonNull(hostKind, "hostKind");
        Objects.requireNonNull(host, "host");
        if (model == null) {
            throw registrationFailure(
                    BlendApiDiagnosticCode.MODEL_MISSING,
                    "A " + hostKind + " registration requires a BlendModelKey before register()");
        }
        if (animationSource == null) {
            throw registrationFailure(
                    BlendApiDiagnosticCode.ANIMATION_MISSING,
                    "A " + hostKind + " registration requires an AnimationRequestSource before register()");
        }
        return new HostRegistrationSpec<>(hostKind, host, model, animationSource);
    }

    static <H> RegistrationReceipt register(HostRegistrationSpec<H> specification) {
        return PlatformAdapterControl.global().register(Objects.requireNonNull(specification, "specification"));
    }

    static BlendRegistrationException registrationFailure(BlendApiDiagnosticCode code, String message) {
        return new BlendRegistrationException(new BlendApiDiagnostic(code, BlendDiagnosticSeverity.ERROR, message));
    }
}
