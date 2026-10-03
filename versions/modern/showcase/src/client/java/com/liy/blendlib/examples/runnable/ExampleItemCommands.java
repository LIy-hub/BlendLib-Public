package com.liy.blendlib.examples.runnable;

import com.liy.blendlib.fabric.client.item.BlendLibItemAnimations;
import com.liy.blendlib.fabric.client.item.ItemAnimationPlayback;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** Client-only controls always reacquire the actual held stack, rather than an ItemStack copy. */
final class ExampleItemCommands {
    private ExampleItemCommands() { }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommands.literal("blendlib_example")
                        .then(ClientCommands.literal("item")
                                .then(ClientCommands.literal("idle").executes(context -> apply(context.getSource(),
                                        playback -> playback.play(ExampleContent.IDLE, ItemAnimationPlayback.Mode.LOOP))))
                                .then(ClientCommands.literal("attack").executes(context -> apply(context.getSource(),
                                        playback -> playback.play(ExampleContent.ATTACK, ItemAnimationPlayback.Mode.HOLD))))
                                .then(ClientCommands.literal("pause").executes(context -> apply(context.getSource(),
                                        ItemAnimationPlayback::pause)))
                                .then(ClientCommands.literal("resume").executes(context -> apply(context.getSource(),
                                        ItemAnimationPlayback::resume)))
                                .then(ClientCommands.literal("stop").executes(context -> apply(context.getSource(),
                                        ItemAnimationPlayback::stop)))
                                .then(ClientCommands.literal("fast").executes(context -> apply(context.getSource(),
                                        playback -> playback.speed(2.0))))
                                .then(ClientCommands.literal("normal").executes(context -> apply(context.getSource(),
                                        playback -> playback.speed(1.0)))))));
    }

    private static int apply(FabricClientCommandSource source, Consumer<ItemAnimationPlayback> command) {
        var stack = source.getPlayer().getMainHandItem();
        if (!stack.is(ExampleContent.WAND)) {
            source.sendError(Component.literal("Hold the BlendLib animated wand in your main hand first"));
            return 0;
        }
        command.accept(BlendLibItemAnimations.playback(stack));
        source.sendFeedback(Component.literal("Updated this held wand's client-only animation"));
        return 1;
    }
}
