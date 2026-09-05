package com.liy.blendlib.showcase.client.x4;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import com.liy.blendlib.fabric.client.api.ClientModelLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executable, client-only consumer examples for every X4 host target.
 *
 * <p>All ordinary marker-item behavior remains on the existing item adapter; the held-item
 * fixture uses X4's distinct hand/context target instead. Callers deliberately choose whether
 * to include the three Experimental fixtures by passing an explicit opt-in token.</p>
 */
public final class X4ShowcaseCatalog {
    /** Existing Showcase static model used by every isolated target fixture. */
    public static final BlendModelKey MODEL_KEY = BlendModelKey.parse("blendlib_showcase:fixtures/static_model");

    private X4ShowcaseCatalog() {
    }

    /** Returns the seven formal X4 target fixtures with fully immutable configuration. */
    public static List<X4ShowcaseFixture<?>> formalFixtures(
            ClientModelLookup models,
            BlendRenderer renderer,
            com.liy.blendlib.spi.experimental.ProviderLifecycleSession generationSession) {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(generationSession, "generationSession");
        List<X4ShowcaseFixture<?>> fixtures = new ArrayList<>();

        com.liy.blendlib.fabric.client.host.X4HostIdentity armor = identity("armor");
        fixtures.add(new X4ShowcaseFixture<>(
                "armor",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.armor(models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(armor)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.Armor(
                                com.liy.blendlib.fabric.client.host.X4ArmorSlot.CHEST,
                                com.liy.blendlib.fabric.client.host.X4ArmorLayer.OVERLAY,
                                armor.scope(),
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.Armor(
                        frame(armor),
                        com.liy.blendlib.fabric.client.host.X4ArmorSlot.CHEST,
                        com.liy.blendlib.fabric.client.host.X4ArmorLayer.OVERLAY)));

        com.liy.blendlib.fabric.client.host.X4HostIdentity projectile = identity("projectile");
        fixtures.add(new X4ShowcaseFixture<>(
                "projectile",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.projectile(models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(projectile)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.Projectile(
                                120L,
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.Projectile(frame(projectile), 6L, 30.0F, 0.0F)));

        com.liy.blendlib.fabric.client.host.X4HostIdentity heldItem = identity("held-item");
        fixtures.add(new X4ShowcaseFixture<>(
                "held-item",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.heldItem(models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(heldItem)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.HeldItem(
                                com.liy.blendlib.fabric.client.host.X4RenderHand.MAIN,
                                com.liy.blendlib.fabric.client.host.X4HeldItemContext.THIRD_PERSON,
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.HeldItem(
                        frame(heldItem),
                        com.liy.blendlib.fabric.client.host.X4RenderHand.MAIN,
                        com.liy.blendlib.fabric.client.host.X4HeldItemContext.THIRD_PERSON)));

        com.liy.blendlib.fabric.client.host.X4HostIdentity firstPerson = identity("first-person-hand");
        fixtures.add(new X4ShowcaseFixture<>(
                "first-person-hand",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.firstPersonHand(models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(firstPerson)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.FirstPersonHand(
                                com.liy.blendlib.fabric.client.host.X4RenderHand.OFF,
                                firstPerson.scope(),
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.FirstPersonHand(
                        frame(firstPerson),
                        com.liy.blendlib.fabric.client.host.X4RenderHand.OFF,
                        firstPerson.scope())));

        com.liy.blendlib.fabric.client.host.X4HostIdentity gui = identity("gui-preview");
        fixtures.add(new X4ShowcaseFixture<>(
                "gui-preview",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.guiPreview(models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(gui)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.GuiPreview(
                                512,
                                512,
                                16.0F,
                                0.5F,
                                2.0F,
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.GuiPreview(frame(gui), 256, 256, 0L)));

        com.liy.blendlib.fabric.client.host.X4HostIdentity world = identity("world-object");
        fixtures.add(new X4ShowcaseFixture<>(
                "world-object",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.worldObject(models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(world)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.WorldObject(
                                world.scope(),
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.WorldObject(frame(world), world.scope(), 0L)));

        com.liy.blendlib.fabric.client.host.X4HostIdentity vfx = identity("persistent-vfx");
        fixtures.add(new X4ShowcaseFixture<>(
                "persistent-vfx",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.persistentVfx(models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(vfx)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.PersistentVfx(
                                vfx.scope(),
                                240L,
                                8,
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.PersistentVfx(frame(vfx), vfx.scope(), 0L, 1)));

        return List.copyOf(fixtures);
    }

    /** Returns the three explicitly opted-in Experimental fixtures, never enabled by default. */
    public static List<X4ShowcaseFixture<?>> experimentalFixtures(
            com.liy.blendlib.fabric.client.host.X4ExperimentalAccess access,
            ClientModelLookup models,
            BlendRenderer renderer,
            com.liy.blendlib.spi.experimental.ProviderLifecycleSession generationSession) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(generationSession, "generationSession");
        List<X4ShowcaseFixture<?>> fixtures = new ArrayList<>();

        com.liy.blendlib.fabric.client.host.X4HostIdentity player = identity("player-replacement");
        fixtures.add(new X4ShowcaseFixture<>(
                "player-replacement-experimental",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.playerReplacement(
                        access, models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(player)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.PlayerReplacement(
                                access,
                                player.scope(),
                                true,
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.PlayerReplacement(
                        frame(player), player.scope(), true)));

        com.liy.blendlib.fabric.client.host.X4HostIdentity mountRoot = identity("mount-root");
        com.liy.blendlib.fabric.client.host.X4HostIdentity mountChild = identity("mount-child");
        com.liy.blendlib.fabric.client.host.X4CompositeGraph mountGraph =
                new com.liy.blendlib.fabric.client.host.X4CompositeGraph(java.util.Map.of(
                        mountRoot, List.of(mountChild), mountChild, List.of()));
        fixtures.add(new X4ShowcaseFixture<>(
                "mount-composite-experimental",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.mountComposite(
                        access, models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(mountRoot)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.MountComposite(
                                access,
                                mountGraph,
                                mountRoot,
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.MountComposite(
                        frame(mountRoot), mountGraph, mountRoot)));

        com.liy.blendlib.fabric.client.host.X4HostIdentity multiRoot = identity("multi-root");
        com.liy.blendlib.fabric.client.host.X4HostIdentity multiChild = identity("multi-child");
        com.liy.blendlib.fabric.client.host.X4CompositeGraph multiGraph =
                new com.liy.blendlib.fabric.client.host.X4CompositeGraph(java.util.Map.of(
                        multiRoot, List.of(multiChild), multiChild, List.of()));
        fixtures.add(new X4ShowcaseFixture<>(
                "multi-entity-composite-experimental",
                com.liy.blendlib.fabric.client.host.X4HostAdapters.multiEntityComposite(
                        access, models, renderer, generationSession)
                        .model(MODEL_KEY)
                        .identity(multiRoot)
                        .configuration(new com.liy.blendlib.fabric.client.host.X4HostConfigurations.MultiEntityComposite(
                                access,
                                multiGraph,
                                multiRoot,
                                com.liy.blendlib.fabric.client.host.X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT))
                        .build(),
                new com.liy.blendlib.fabric.client.host.X4HostFrames.MultiEntityComposite(
                        frame(multiRoot), multiGraph, multiRoot)));

        return List.copyOf(fixtures);
    }

    private static com.liy.blendlib.fabric.client.host.X4HostIdentity identity(String local) {
        return new com.liy.blendlib.fabric.client.host.X4HostIdentity(
                BlendResourceId.parse("blendlib_showcase:x4-session"),
                BlendResourceId.parse("blendlib_showcase:x4/" + local));
    }

    private static com.liy.blendlib.fabric.client.host.X4SnapshotFrame frame(
            com.liy.blendlib.fabric.client.host.X4HostIdentity identity) {
        return com.liy.blendlib.fabric.client.host.X4SnapshotFrame.unresolved(
                identity,
                com.liy.blendlib.fabric.client.host.X4Transform.IDENTITY,
                0x00F000F0,
                0,
                0xFFFFFFFF,
                true);
    }
}
