package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.AnimationUpdateBucket;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeInput;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Client-only, per-stack animation integrated into the ordinary marker special-renderer pipeline.
 * All playback, extraction, release and clear calls must run on the client/extraction thread.
 */
public final class BlendLibItemAnimations {
    /** Hard bound across all item types; evicted stacks restart when next observed. */
    public static final int MAX_RETAINED_INSTANCES = 256;
    private static final ConcurrentMap<Identifier, BlendAnimationKey> DEFAULTS = new ConcurrentHashMap<>();
    private static final ItemAnimationInstances INSTANCES = new ItemAnimationInstances(
            MAX_RETAINED_INSTANCES, System::nanoTime,
            key -> BlendLibClientServices.skinnedAnimationRuntime().retire(key));

    private BlendLibItemAnimations() { }

    /** Registers a marker binding and its default looping descriptor animation state before bake. */
    public static synchronized void register(BlendLibItemBinding binding, BlendAnimationKey defaultAnimation) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(defaultAnimation, "defaultAnimation");
        BlendAnimationKey previous = DEFAULTS.get(binding.itemId());
        if (previous != null && !previous.equals(defaultAnimation)) {
            throw new IllegalStateException("Marker item already has a different default animation: " + binding.itemId());
        }
        BlendLibItemModelBindings.register(binding);
        DEFAULTS.put(binding.itemId(), defaultAnimation);
    }

    /**
     * Returns controls for this exact stack object. Equal contents and ItemStack.copy() do not share
     * state. Reacquire after release, disconnect or LRU eviction; an old control then stays detached.
     */
    public static ItemAnimationPlayback playback(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("Cannot animate an empty stack");
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        BlendAnimationKey animation = DEFAULTS.get(id);
        if (animation == null) throw new IllegalArgumentException("Item has no animated binding: " + id);
        return INSTANCES.get(stack, animation).playback();
    }

    /** Explicitly releases a stack and its runtime pose/controller cache entries. */
    public static void release(ItemStack stack) { INSTANCES.release(stack); }

    /** Retires all retained item playback; called on play init and disconnect by the adapter. */
    public static void clear() { INSTANCES.clear(); }

    static Optional<ModelRenderSnapshot> extract(
            BlendLibItemBinding binding, ItemStack stack, ModelRenderHandle handle) {
        BlendAnimationKey defaultAnimation = DEFAULTS.get(binding.itemId());
        if (defaultAnimation == null || stack.isEmpty() || handle.missingModel()) return Optional.empty();
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        ItemAnimationInstances.Entry entry = INSTANCES.get(stack, defaultAnimation);
        ItemAnimationPlayback playback = entry.playback();
        var duration = runtime.animationDuration(binding.modelKey(), playback.animation());
        if (duration.isEmpty()) return Optional.empty();
        double seconds = playback.sample(duration.getAsDouble());
        var request = new SkinnedExtractionRequest(Transform.IDENTITY, 0, 0, 0xFFFFFFFF,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
        var input = new SkinnedAnimationRuntimeInput(binding.modelKey(), entry.key(), 0L, 0F,
                playback.animation(), Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR, request);
        return runtime.extractClipAt(input, seconds, null).map(result -> result.frame().renderSnapshot());
    }
}
