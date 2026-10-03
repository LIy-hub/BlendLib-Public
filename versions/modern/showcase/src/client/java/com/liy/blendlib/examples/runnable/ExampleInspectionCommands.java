package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** Explicit read-only inspection, registered only by the separate runnable consumer mod. */
final class ExampleInspectionCommands {
    private ExampleInspectionCommands() { }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommands.literal("blendlib_example").then(ClientCommands.literal("inspect")
                        .executes(context -> listActors(context.getSource()))
                        .then(ClientCommands.argument("entity-id", IntegerArgumentType.integer(0))
                                .executes(context -> inspect(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "entity-id")))))));
    }

    private static int listActors(FabricClientCommandSource source) {
        var client = source.getClient();
        if (client.level == null || client.player == null) return 0;
        // A bounded query of already loaded entities. The actor is deliberately not pickable,
        // so vanilla crosshairPickEntity cannot discover it; listing changes no interactions.
        var ids = client.level.getEntitiesOfClass(LayeredActor.class,
                client.player.getBoundingBox().inflate(16.0)).stream().map(LayeredActor::getId).toList();
        ExampleLayerInspection.targets(ids).forEach(line -> source.sendFeedback(Component.literal(line)));
        return ids.isEmpty() ? 0 : 1;
    }

    private static int inspect(FabricClientCommandSource source, int id) {
        var client = source.getClient();
        var entity = client.level == null ? null : client.level.getEntity(id);
        if (!(entity instanceof LayeredActor actor)) {
            source.sendError(Component.literal("No loaded BlendLib example actor with entity ID " + id
                    + "; use /blendlib_example inspect to list nearby actor IDs"));
            return 0;
        }
        ExampleLayerVisualEvents.format(actor.visualEvents().snapshot())
                .forEach(line -> source.sendFeedback(Component.literal(line)));
        if (!BlendLibClientServices.isInitialized()) {
            source.sendError(Component.literal("BlendLib client services are not initialized"));
            return 0;
        }
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        var sampled = runtime.activeEntityKey(actor.getId()).flatMap(runtime::layeredSnapshot);
        if (sampled.isEmpty()) {
            source.sendError(Component.literal("No current-generation sampled layers for actor " + actor.getId()
                    + "; it may not have rendered yet, have reloaded, or have retired. Look at it and retry"));
            return 0;
        }
        source.sendFeedback(Component.literal("Actor " + actor.getId() + " received cueSequence=" + actor.cueSequence()
                + " cueTick=" + actor.cueTick() + " (received cue may be newer than the sampled layers)"));
        ExampleLayerInspection.format(ExampleAnimationScene.layers(), sampled.orElseThrow())
                .forEach(line -> source.sendFeedback(Component.literal(line)));
        return 1;
    }
}
