package com.liy.blendlib.fabric.client.host;

import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import com.liy.blendlib.spi.experimental.ProviderLifecycleSession;
import java.util.Objects;

/**
 * Factories for the ten target-specific X4 host builders.
 *
 * <p>These public client factories are version-scoped integration seams, not a global bootstrap
 * mechanism. Callers provide a frozen-generation {@link ProviderLifecycleSession}, public model
 * lookup facade, and public renderer facade from an owning client lifecycle. Ordinary consumers
 * must not call them from common/server initialization or treat their existence as acceptance of
 * production X4 wiring for another platform/version.</p>
 */
public final class X4HostAdapters {
    private X4HostAdapters() {
    }

    /** Formal armor host builder. */
    public static X4HostAdapterBuilder<X4HostConfigurations.Armor, X4HostFrames.Armor> armor(
            ClientModelLookup models, BlendRenderer renderer, ProviderLifecycleSession generationSession) {
        return builder(X4HostKind.ARMOR, models, renderer, generationSession);
    }

    /** Formal projectile host builder. */
    public static X4HostAdapterBuilder<X4HostConfigurations.Projectile, X4HostFrames.Projectile> projectile(
            ClientModelLookup models, BlendRenderer renderer, ProviderLifecycleSession generationSession) {
        return builder(X4HostKind.PROJECTILE, models, renderer, generationSession);
    }

    /** Formal held-item host builder, deliberately not the ordinary marker-item adapter. */
    public static X4HostAdapterBuilder<X4HostConfigurations.HeldItem, X4HostFrames.HeldItem> heldItem(
            ClientModelLookup models, BlendRenderer renderer, ProviderLifecycleSession generationSession) {
        return builder(X4HostKind.HELD_ITEM, models, renderer, generationSession);
    }

    /** Formal first-person hand host builder. */
    public static X4HostAdapterBuilder<X4HostConfigurations.FirstPersonHand, X4HostFrames.FirstPersonHand> firstPersonHand(
            ClientModelLookup models, BlendRenderer renderer, ProviderLifecycleSession generationSession) {
        return builder(X4HostKind.FIRST_PERSON_HAND, models, renderer, generationSession);
    }

    /** Formal GUI preview host builder. */
    public static X4HostAdapterBuilder<X4HostConfigurations.GuiPreview, X4HostFrames.GuiPreview> guiPreview(
            ClientModelLookup models, BlendRenderer renderer, ProviderLifecycleSession generationSession) {
        return builder(X4HostKind.GUI_PREVIEW, models, renderer, generationSession);
    }

    /** Formal stable world-object host builder. */
    public static X4HostAdapterBuilder<X4HostConfigurations.WorldObject, X4HostFrames.WorldObject> worldObject(
            ClientModelLookup models, BlendRenderer renderer, ProviderLifecycleSession generationSession) {
        return builder(X4HostKind.WORLD_OBJECT, models, renderer, generationSession);
    }

    /** Formal persistent presentation-only VFX host builder. */
    public static X4HostAdapterBuilder<X4HostConfigurations.PersistentVfx, X4HostFrames.PersistentVfx> persistentVfx(
            ClientModelLookup models, BlendRenderer renderer, ProviderLifecycleSession generationSession) {
        return builder(X4HostKind.PERSISTENT_VFX, models, renderer, generationSession);
    }

    /** Experimental player-replacement builder; a disabled access token is rejected immediately. */
    public static X4HostAdapterBuilder<X4HostConfigurations.PlayerReplacement, X4HostFrames.PlayerReplacement> playerReplacement(
            X4ExperimentalAccess access,
            ClientModelLookup models,
            BlendRenderer renderer,
            ProviderLifecycleSession generationSession) {
        Objects.requireNonNull(access, "access").requireEnabled();
        return builder(X4HostKind.PLAYER_REPLACEMENT, models, renderer, generationSession);
    }

    /** Experimental mount-composite builder; a disabled access token is rejected immediately. */
    public static X4HostAdapterBuilder<X4HostConfigurations.MountComposite, X4HostFrames.MountComposite> mountComposite(
            X4ExperimentalAccess access,
            ClientModelLookup models,
            BlendRenderer renderer,
            ProviderLifecycleSession generationSession) {
        Objects.requireNonNull(access, "access").requireEnabled();
        return builder(X4HostKind.MOUNT_COMPOSITE_ENTITY, models, renderer, generationSession);
    }

    /** Experimental multi-entity-composite builder; a disabled access token is rejected immediately. */
    public static X4HostAdapterBuilder<X4HostConfigurations.MultiEntityComposite, X4HostFrames.MultiEntityComposite> multiEntityComposite(
            X4ExperimentalAccess access,
            ClientModelLookup models,
            BlendRenderer renderer,
            ProviderLifecycleSession generationSession) {
        Objects.requireNonNull(access, "access").requireEnabled();
        return builder(X4HostKind.MULTI_ENTITY_COMPOSITE, models, renderer, generationSession);
    }

    private static <C extends X4HostConfiguration<F>, F extends X4HostFrame> X4HostAdapterBuilder<C, F> builder(
            X4HostKind hostKind,
            ClientModelLookup models,
            BlendRenderer renderer,
            ProviderLifecycleSession generationSession) {
        return new X4HostAdapterBuilder<>(
                Objects.requireNonNull(hostKind, "hostKind"),
                Objects.requireNonNull(models, "models"),
                Objects.requireNonNull(renderer, "renderer"),
                Objects.requireNonNull(generationSession, "generationSession"));
    }
}
