package com.liy.blendlib.showcase.perf.scene;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * Administrator-only, opt-in entrypoint for the isolated P7 benchmark scene.
 *
 * <p>Registration does not create a scene. An operator must explicitly invoke
 * {@code /blendlib_showcase p7 spawn} in a disposable development world after preparing the P7
 * client resource pack. The command never moves a player, changes a camera, enables a resource
 * pack, starts profiling, or claims a performance/visual Gate result.</p>
 */
public final class P7BenchmarkCommands {
    private P7BenchmarkCommands() {
    }

    /** Registers the command tree once during the common Showcase initializer. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext, selection) ->
                register(dispatcher));
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("blendlib_showcase")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .then(Commands.literal("p7")
                        .then(Commands.literal("spawn")
                                .executes(context -> spawn(context.getSource())))
                        .then(Commands.literal("clear")
                                .executes(context -> clear(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))));
    }

    private static int spawn(CommandSourceStack source) {
        try {
            P7BenchmarkSceneSpawner.SpawnResult result = P7BenchmarkSceneSpawner.spawn(source.getLevel());
            source.sendSuccess(() -> Component.literal("P7 benchmark hosts created: " + result.rigidCount()
                    + " rigid + " + result.skinnedCount() + " skinned. Prescribed client camera: "
                    + formatCamera(result.prescribedCamera()) + ". This is not a performance or visual result."), false);
            return result.totalCount();
        } catch (IllegalStateException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int clear(CommandSourceStack source) {
        int cleared = P7BenchmarkSceneSpawner.clear(source.getLevel());
        source.sendSuccess(() -> Component.literal("Cleared " + cleared + " P7 benchmark host(s) from this level."), false);
        return cleared;
    }

    private static int status(CommandSourceStack source) {
        P7BenchmarkSceneSpawner.ActiveCounts counts = P7BenchmarkSceneSpawner.countActive(source.getLevel());
        source.sendSuccess(() -> Component.literal("P7 benchmark host status: " + counts.rigid() + " rigid + "
                + counts.skinned() + " skinned. Target is 100 rigid + 25 skinned."), false);
        return counts.total();
    }

    private static String formatCamera(com.liy.blendlib.showcase.perf.P7ReferenceScenario.CameraPose camera) {
        return "x=" + camera.x() + ", y=" + camera.y() + ", z=" + camera.z()
                + ", yaw=" + camera.yawDegrees() + ", pitch=" + camera.pitchDegrees();
    }
}
