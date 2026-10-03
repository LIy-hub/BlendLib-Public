package com.liy.blendlib.fabric.client.item.consumer;

import com.liy.blendlib.fabric.client.item.BlendLibItemAnimations;
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.ItemAnimationPlayback;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Compiled consumer example: register at client initialization, control the actual rendered stack. */
final class AnimatedItemConsumerExample {
    static void register() {
        BlendLibItemAnimations.register(new BlendLibItemBinding(
                Identifier.parse("example:animated_wand"), BlendModelKey.parse("example:wand"),
                Identifier.withDefaultNamespace("item/stick")), BlendAnimationKey.parse("example:idle"));
    }

    static void use(ItemStack stack) {
        BlendLibItemAnimations.playback(stack)
                .play(BlendAnimationKey.parse("example:activate"), ItemAnimationPlayback.Mode.HOLD)
                .speed(1.5).seek(0.1).pause().resume();
    }

    static void retire(ItemStack stack) {
        BlendLibItemAnimations.playback(stack).stop();
        BlendLibItemAnimations.release(stack);
    }
}
