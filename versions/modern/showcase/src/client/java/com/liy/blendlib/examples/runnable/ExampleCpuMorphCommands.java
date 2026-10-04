package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Client-local commands. Entity IDs resolve loaded objects only; choices are never stored by ID. */
public final class ExampleCpuMorphCommands {
    private ExampleCpuMorphCommands() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            var target = ClientCommands.argument("entity-id", IntegerArgumentType.integer(0))
                    .executes(context -> edit(context.getSource(), IntegerArgumentType.getInteger(context, "entity-id"), state -> { }))
                    .then(ClientCommands.literal("status").executes(context -> edit(context.getSource(),
                            IntegerArgumentType.getInteger(context, "entity-id"), state -> { })))
                    .then(ClientCommands.literal("reset").executes(context -> edit(context.getSource(),
                            IntegerArgumentType.getInteger(context, "entity-id"), ExampleCpuMorphControls::reset)));
            for (String control : ExampleCpuMorphControls.CONTROLS) {
                target.then(ClientCommands.literal(control).then(ClientCommands.argument("value", FloatArgumentType.floatArg())
                        .executes(context -> edit(context.getSource(), IntegerArgumentType.getInteger(context, "entity-id"),
                                state -> setCurrent(state, control, FloatArgumentType.getFloat(context, "value"))))));
            }
            var clips = ClientCommands.literal("clip");
            for (String clip : ExampleCpuMorphControls.CLIPS) {
                clips.then(ClientCommands.literal(clip).executes(context -> edit(context.getSource(),
                        IntegerArgumentType.getInteger(context, "entity-id"), state -> state.selectClip(clip))));
            }
            target.then(clips);
            dispatcher.register(ClientCommands.literal("blendlib_example").then(ClientCommands.literal("morph")
                    .executes(context -> list(context.getSource()))
                    .then(ClientCommands.literal("list").executes(context -> list(context.getSource())))
                    .then(target)));
        });
    }

    private static void setCurrent(ExampleCpuMorphControls state, String control, float value) {
        var proposed = state.proposed(control, value);
        var model = BlendLibClientServices.models().resolve(ExampleCpuMorphControls.MODEL);
        if (!BlendLibClientServices.skinnedAnimationRuntime().validateMorphControls(
                ExampleCpuMorphControls.MODEL, model.generationId(), proposed))
            throw new IllegalArgumentException("CPU morph generation changed; try the command again");
        state.set(control, value);
    }

    private static int list(FabricClientCommandSource source) {
        var client = source.getClient();
        if (client.level == null || client.player == null) return 0;
        var actors = client.level.getEntitiesOfClass(CpuMorphActor.class, client.player.getBoundingBox().inflate(32));
        if (actors.isEmpty()) source.sendFeedback(Component.literal("No CPU morph actors within 32 blocks; summon blendlib_runnable_examples:cpu_morph_actor"));
        actors.stream().limit(16).forEach(actor -> source.sendFeedback(Component.literal("CPU morph actor " + actor.getId())));
        if (actors.size() > 16) source.sendFeedback(Component.literal("Additional actors omitted; move closer to the desired actor"));
        return actors.isEmpty() ? 0 : 1;
    }

    private static int edit(FabricClientCommandSource source, int id, Consumer<ExampleCpuMorphControls> change) {
        var client = source.getClient();
        var entity = client.level == null ? null : client.level.getEntity(id);
        if (!(entity instanceof CpuMorphActor actor) || actor.isRemoved()) {
            source.sendError(Component.literal("No loaded CPU morph actor with ID " + id + "; use /blendlib_example morph list"));
            return 0;
        }
        if (!BlendLibClientServices.isInitialized()
                || BlendLibClientServices.models().resolve(ExampleCpuMorphControls.MODEL).missing()
                || BlendLibClientServices.skinnedAnimationRuntime().activeEntityKey(id).isEmpty()) {
            source.sendError(Component.literal("CPU morph model/session is not ready; check /blendlib diagnostics"));
            return 0;
        }
        var controls = ExampleCpuMorphClient.controls(actor);
        try { change.accept(controls); }
        catch (IllegalArgumentException invalid) { source.sendError(Component.literal(invalid.getMessage())); return 0; }
        source.sendFeedback(Component.literal("Actor " + id + " " + controls.status()));
        return 1;
    }
}
