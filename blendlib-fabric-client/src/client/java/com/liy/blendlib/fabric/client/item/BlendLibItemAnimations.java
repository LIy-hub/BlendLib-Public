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
    /** Hard bound across all item types; evicted stacks restart on next extraction or playback acquisition. */
    public static final int MAX_RETAINED_INSTANCES = 256;
    private static final ConcurrentMap<Identifier, Registration> DEFAULTS = new ConcurrentHashMap<>();
    private static final ItemAnimationInstances INSTANCES = new ItemAnimationInstances(
            MAX_RETAINED_INSTANCES, System::nanoTime,
            key -> BlendLibClientServices.skinnedAnimationRuntime().retire(key));

    // All extraction is client-thread-only. Suppress recursion across stacks as well as identities.
    private static boolean dispatchingEvents;

    private record Registration(BlendAnimationKey animation, ItemAnimationVisualEventHandler handler) { }

    private BlendLibItemAnimations() { }

    /** Registers a marker binding and its default looping descriptor animation state before bake. */
    public static synchronized void register(BlendLibItemBinding binding, BlendAnimationKey defaultAnimation) {
        registerInternal(binding, defaultAnimation, null);
    }

    /**
     * Opts this marker item into descriptor visual markers on successful extraction only.
     * Register before bake. Repeating the same callback identity is harmless; a conflicting
     * non-null handler is rejected. Plain registration preserves a handler already installed.
     * Do not capture stacks in the registration-lifetime handler. See the handler contract for
     * callback failures and reentrancy; no callback runs from rendering submission.
     */
    public static synchronized void register(BlendLibItemBinding binding, BlendAnimationKey defaultAnimation,
            ItemAnimationVisualEventHandler handler) {
        registerInternal(binding, defaultAnimation, Objects.requireNonNull(handler, "handler"));
    }

    private static void registerInternal(BlendLibItemBinding binding, BlendAnimationKey defaultAnimation,
            ItemAnimationVisualEventHandler handler) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(defaultAnimation, "defaultAnimation");
        Registration previous = DEFAULTS.get(binding.itemId());
        if (previous != null && (!previous.animation().equals(defaultAnimation)
                || (handler != null && previous.handler() != null && previous.handler() != handler))) {
            throw new IllegalStateException("Marker item already has a different default animation or handler: " + binding.itemId());
        }
        BlendLibItemModelBindings.register(binding);
        DEFAULTS.put(binding.itemId(), new Registration(defaultAnimation,
                handler == null && previous != null ? previous.handler() : handler));
    }

    /**
     * Returns controls for this exact stack object. Equal contents and ItemStack.copy() do not share
     * state. Reacquire after release, disconnect or LRU eviction; an old control then stays detached.
     */
    public static ItemAnimationPlayback playback(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("Cannot animate an empty stack");
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Registration registration = DEFAULTS.get(id);
        if (registration == null) throw new IllegalArgumentException("Item has no animated binding: " + id);
        return INSTANCES.get(stack, registration.animation()).playback();
    }

    /**
     * Observes an already retained exact stack identity on the client/extraction thread.
     * Empty for empty, unseen, copied, released, disconnected or evicted stacks; absence does not
     * identify which cause applies. Does not create playback, read its clock, sample animation,
     * refresh LRU, purge weak entries or retire resources. The immutable return value may allocate.
     * Reload preserves controls but marks a previous-generation successful sample stale.
     */
    public static Optional<ItemAnimationObservation> observe(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) return Optional.empty();
        var entry = INSTANCES.peek(stack);
        if (entry == null) return Optional.empty();
        var sample = entry.playback().lastSample();
        long generation = sample == null || !BlendLibClientServices.isInitialized() ? -1
                : BlendLibClientServices.models().resolve(sample.model()).generationId();
        return Optional.of(entry.playback().observe(generation));
    }

    /**
     * Observes the last extraction attempt without creating playback, touching its clock or LRU.
     * Empty before the first attempt or after release/eviction/disconnect. Compare requestedAnimation
     * with observe(stack).animation() after controls change; the status remains historical.
     */
    public static Optional<ItemAnimationExtractionStatus> extractionStatus(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) return Optional.empty();
        var entry = INSTANCES.peek(stack);
        if (entry == null || entry.playback().lastExtraction() == null) return Optional.empty();
        var last = entry.playback().lastExtraction();
        long generation = BlendLibClientServices.isInitialized()
                ? BlendLibClientServices.models().resolve(last.model()).generationId() : -1;
        return Optional.of(new ItemAnimationExtractionStatus(last.model(), last.requestedAnimation(),
                last.generation(), last.outcome(), last.fallback(), last.generation() == generation));
    }

    /** Explicitly releases a stack and its runtime pose/controller cache entries. */
    public static void release(ItemStack stack) { INSTANCES.release(stack); }

    /** Retires all retained item playback; called on play init and disconnect by the adapter. */
    public static void clear() { INSTANCES.clear(); }

    static Optional<ModelRenderSnapshot> extract(
            BlendLibItemBinding binding, ItemStack stack, ModelRenderHandle handle) {
        Registration registration = DEFAULTS.get(binding.itemId());
        if (registration == null || stack.isEmpty()) return Optional.empty();
        ItemAnimationInstances.Entry entry = INSTANCES.get(stack, registration.animation());
        ItemAnimationPlayback playback = entry.playback();
        if (handle.missingModel()) {
            unavailable(playback, binding, handle, ItemAnimationExtractionStatus.Outcome.MODEL_UNAVAILABLE);
            return Optional.empty();
        }
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        var duration = runtime.animationDuration(binding.modelKey(), playback.animation());
        if (duration.isEmpty()) {
            unavailable(playback, binding, handle, ItemAnimationExtractionStatus.Outcome.ANIMATION_UNAVAILABLE);
            return Optional.empty();
        }
        var checkpoint = playback.checkpoint();
        double seconds = playback.sample(duration.getAsDouble());
        var request = new SkinnedExtractionRequest(Transform.IDENTITY, 0, 0, 0xFFFFFFFF,
                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true));
        var input = new SkinnedAnimationRuntimeInput(binding.modelKey(), entry.key(), 0L, 0F,
                playback.animation(), Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR, request);
        Optional<com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeResult> extracted;
        try {
            extracted = runtime.extractClipAt(input, seconds, null);
        } catch (RuntimeException | Error failure) {
            playback.restore(checkpoint);
            playback.events().invalidate();
            throw failure;
        }
        if (extracted.isEmpty()) {
            playback.restore(checkpoint);
            unavailable(playback, binding, handle, ItemAnimationExtractionStatus.Outcome.EXTRACTION_UNAVAILABLE);
        }
        return extracted.map(result -> {
            var snapshot = result.frame().renderSnapshot();
            playback.sampled(binding.modelKey(), snapshot.generation(), seconds, duration.getAsDouble());
            playback.extracted(new ItemAnimationExtractionStatus(binding.modelKey(), playback.animation(),
                    snapshot.generation(), ItemAnimationExtractionStatus.Outcome.ANIMATED,
                    ItemAnimationExtractionStatus.Fallback.NONE, true));
            var interval = playback.consumeEventInterval();
            var events = playback.events().consume(binding.modelKey(), snapshot.generation(), interval,
                    duration.getAsDouble(), registration.handler() == null ? java.util.List.of()
                            : runtime.animationVisualEvents(binding.modelKey(), playback.animation()));
            if (playback.events().rebaseAfterConsume()) playback.rebaseEventTimeline();
            // The complete interval is consumed above, including when nested extraction occurs.
            if (registration.handler() != null && !dispatchingEvents && !events.isEmpty()) {
                long controls = playback.controlRevision();
                var animation = playback.animation();
                dispatchingEvents = true;
                try {
                    for (var event : events) {
                        if (INSTANCES.peek(stack) != entry || playback.controlRevision() != controls
                                || BlendLibClientServices.models().resolve(binding.modelKey()).generationId()
                                        != snapshot.generation()) break;
                        registration.handler().onVisualEvent(stack,
                                new ItemAnimationVisualEvent(binding.modelKey(), animation, snapshot.generation(), event));
                    }
                } finally {
                    dispatchingEvents = false;
                }
            }
            return snapshot;
        });
    }

    private static void unavailable(ItemAnimationPlayback playback, BlendLibItemBinding binding,
            ModelRenderHandle handle, ItemAnimationExtractionStatus.Outcome outcome) {
        playback.events().invalidate();
        playback.extracted(new ItemAnimationExtractionStatus(binding.modelKey(), playback.animation(),
                handle.generation(), outcome, handle.skinned() || handle.missingModel()
                        ? ItemAnimationExtractionStatus.Fallback.MISSING_MODEL
                        : ItemAnimationExtractionStatus.Fallback.STATIC_MODEL, true));
    }
}
