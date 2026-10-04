package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.animation.extract.SkinnedExtractionRequest;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Explicit animation-free CPU morph opt-in for ordinary marker items. Register before model bake.
 * Controls run on the client/extraction thread. No stacks, weights, clocks or playback instances are
 * retained: copied stacks are independent and omitted weights resume the model's authored defaults.
 * Reload/disconnect during controls discards that extraction. An active play connection is required.
 */
public final class BlendLibItemMorphs {
    private record Registration(BlendLibItemBinding binding, BlendLibItemMorphControls controls) { }
    private static final ConcurrentMap<Identifier, Registration> BINDINGS = new ConcurrentHashMap<>();
    private static boolean extracting;

    private BlendLibItemMorphs() { }

    /** Registers static CPU morph extraction using authored weights, without any animation state. */
    public static void register(BlendLibItemBinding binding) { registerInternal(binding, null); }

    /**
     * Adds an extraction-only callback. Repeating a binding/callback identity is harmless; a different
     * callback or animated mode is rejected. Plain registration preserves an existing callback.
     * Skin and material appearance selectors may be registered through BlendLibItemModelBindings.
     */
    public static void register(BlendLibItemBinding binding, BlendLibItemMorphControls controls) {
        registerInternal(binding, Objects.requireNonNull(controls, "controls"));
    }

    private static void registerInternal(BlendLibItemBinding binding, BlendLibItemMorphControls controls) {
        Objects.requireNonNull(binding, "binding");
        synchronized (BlendLibItemModelBindings.class) {
            if (BlendLibItemAnimations.isRegistered(binding)) {
                throw new IllegalStateException("Marker item already has an animated binding: " + binding.itemId());
            }
            Registration previous = BINDINGS.get(binding.itemId());
            if (previous != null && (!previous.binding().equals(binding)
                    || (previous.controls() != null && controls != null && previous.controls() != controls))) {
                throw new IllegalStateException("Marker item already has different static morph controls: " + binding.itemId());
            }
            BlendLibItemModelBindings.register(binding);
            BINDINGS.put(binding.itemId(), new Registration(binding,
                    previous != null && previous.controls() != null ? previous.controls() : controls));
        }
    }

    static boolean isRegistered(BlendLibItemBinding binding) { return BINDINGS.containsKey(binding.itemId()); }

    static Optional<ModelRenderSnapshot> extract(BlendLibItemBinding binding, ItemStack stack,
            ModelRenderHandle handle) {
        Registration registration = BINDINGS.get(binding.itemId());
        if (registration == null || !registration.binding().equals(binding) || handle.missingModel()
                || !isCurrentStack(binding, stack) || extracting) return Optional.empty();
        var runtime = BlendLibClientServices.skinnedAnimationRuntime();
        long revision = runtime.captureExtractionLifecycleRevision();
        if (!runtime.hasActivePlayConnection()) return Optional.empty();
        extracting = true;
        try {
            var weights = captureControls(stack, registration.controls(), () -> isCurrentStack(binding, stack));
            if (weights.isEmpty()) return Optional.empty();
            return runtime.extractStaticMorph(binding.modelKey(), handle.generation(), revision,
                    weights.orElseThrow(), new SkinnedExtractionRequest(Transform.IDENTITY, 0, 0, 0xFFFFFFFF,
                            RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true)))
                    .map(frame -> frame.renderSnapshot());
        } finally {
            extracting = false;
        }
    }

    /** Capture exactly once; no stack or mutable source map escapes into the render argument. */
    static Optional<MorphFrameOverrides> captureControls(ItemStack stack, BlendLibItemMorphControls controls,
            BooleanSupplier current) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(current, "current");
        if (!current.getAsBoolean()) return Optional.empty();
        var weights = controls == null ? MorphFrameOverrides.empty()
                : Objects.requireNonNull(controls.weights(stack), "captured morph controls");
        return current.getAsBoolean() ? Optional.of(weights) : Optional.empty();
    }

    private static boolean isCurrentStack(BlendLibItemBinding binding, ItemStack stack) {
        return !stack.isEmpty() && binding.itemId().equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
