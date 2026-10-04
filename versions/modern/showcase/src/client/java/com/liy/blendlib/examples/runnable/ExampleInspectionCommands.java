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

    static void register(boolean locomotionRules, boolean blendSpace, boolean directional) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommands.literal("blendlib_example").then(ClientCommands.literal("inspect")
                        .executes(context -> listActors(context.getSource()))
                        .then(ClientCommands.argument("entity-id", IntegerArgumentType.integer(0))
                                .executes(context -> inspect(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "entity-id"), locomotionRules, blendSpace, directional))))));
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

    private static int inspect(FabricClientCommandSource source, int id, boolean locomotionRules, boolean blendSpace, boolean directional) {
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
        if (locomotionRules || blendSpace || directional) source.sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                "Received locomotion grounded=%s speed=%.4f blocks/tick (may be newer than sampled layers)",
                actor.locomotionGrounded(), actor.locomotionSpeed())));
        ExampleLayerInspection.format(directional ? ExampleDirectionalScene.layers() : blendSpace ? ExampleBlendSpaceScene.layers() : locomotionRules
                        ? ExampleLocomotionScene.layers() : ExampleAnimationScene.layers(),
                sampled.orElseThrow())
                .forEach(line -> source.sendFeedback(Component.literal(line)));
        if (directional) {
            var local = ExampleDirectionalScene.localVelocity(actor.directionalDx(), actor.directionalDz(), actor.getYRot());
            source.sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                    "Received local forward=%.4f left=%.4f blocks/tick (may be newer than sampled weights)", local.x(), local.y())));
            for (var layer : ExampleDirectionalScene.layers().subList(0, 5)) {
                var duration = runtime.animationDuration(ExampleDirectionalScene.MODEL, layer.initialState());
                var head = sampled.orElseThrow().playheads().get(layer.id());
                if (duration.isPresent() && head != null) source.sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                        "%s normalizedPhase=%.4f sequence=%d", layer.id(), head.timeSeconds()/duration.orElseThrow(), head.acceptedSequence())));
            }
        }
        if (blendSpace) {
            var durations = new java.util.LinkedHashMap<com.liy.blendlib.api.BlendResourceId, Double>();
            for (var layer : ExampleBlendSpaceScene.layers().subList(0, 3)) {
                runtime.animationDuration(ExampleBlendSpaceScene.MODEL, layer.initialState())
                        .ifPresent(duration -> durations.put(layer.id(), duration));
            }
            ExampleLayerInspection.formatBlendSpace(sampled.orElseThrow(), java.util.Map.copyOf(durations))
                    .forEach(line -> source.sendFeedback(Component.literal(line)));
        }
        return 1;
    }
}
