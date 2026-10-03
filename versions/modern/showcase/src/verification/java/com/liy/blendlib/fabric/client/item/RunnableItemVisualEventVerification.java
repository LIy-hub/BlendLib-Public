package com.liy.blendlib.fabric.client.item;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.AnimationVisualEvent;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.examples.runnable.ExampleItemVisualEvents;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only bridge to the real item playback/cursor; excluded from both shipped JARs. */
public final class RunnableItemVisualEventVerification {
    private static final String NS = "blendlib_runnable_examples:";
    private record EqualIdentity(int value) { }

    private RunnableItemVisualEventVerification() { }

    public static void verify(ModelAsset wand, ModelAsset appearanceWand) {
        verifyDescriptor(wand);
        verifyDescriptor(appearanceWand);
        var state = AnimationControllerDefinition.fromModelAsset(wand)
                .state(BlendAnimationKey.parse(NS + "attack"));
        var event = new ItemAnimationVisualEvent(BlendModelKey.parse(wand.modelKey().value()),
                state.key(), wand.generation(), state.events().getFirst());
        verifyBoundedIdentityCounter(event);
        System.out.println("Verified packaged wand markers with real item playback/cursor, "
                + "weak exact-identity bounded counters, immutable inspection and disconnect cleanup");
    }

    private static void verifyDescriptor(ModelAsset asset) {
        var attack = BlendAnimationKey.parse(NS + "attack");
        var state = AnimationControllerDefinition.fromModelAsset(asset).state(attack);
        require(state.events().equals(List.of(new AnimationVisualEvent(0.25,
                BlendResourceId.parse(NS + "attack_whoosh")))), "both packaged wands have the real attack marker");
        require(state.clip().durationSeconds() > 0.25, "attack marker is inside the actual authored clip");
        var model = BlendModelKey.parse(asset.modelKey().value());
        var clock = new AtomicLong();
        var playback = new ItemAnimationPlayback(attack, clock::get).play(attack, ItemAnimationPlayback.Mode.HOLD);
        var counter = new ExampleItemVisualEvents<Object>();
        var first = new EqualIdentity(1);
        var copy = new EqualIdentity(1);
        double duration = state.clip().durationSeconds();
        playback.sample(duration);
        require(playback.events().consume(model, asset.generation(), playback.consumeEventInterval(), duration,
                state.events()).isEmpty(), "first actual item sample must silently baseline");
        clock.set(250_000_000L);
        playback.sample(duration);
        var events = playback.events().consume(model, asset.generation(), playback.consumeEventInterval(), duration,
                state.events());
        require(events.equals(state.events()), "exact right endpoint must cross the real packaged marker");
        // Exactly the callback work registered by ExampleClient, with headless identity tokens.
        events.forEach(marker -> counter.accept(first, new ItemAnimationVisualEvent(model, attack, asset.generation(), marker)));
        var retained = counter.snapshot(first).orElseThrow();
        require(retained.callbacks() == 1 && retained.last().event().eventKey()
                .equals(BlendResourceId.parse(NS + "attack_whoosh")), "live consumer counter records real cursor delivery");
        require(counter.snapshot(copy).isEmpty(), "equal copied identities never inherit callbacks");
        playback.sample(duration);
        require(playback.events().consume(model, asset.generation(), playback.consumeEventInterval(), duration,
                state.events()).isEmpty(), "repeated sample must not replay the callback");

        var originalLocale = Locale.getDefault();
        List<String> lines;
        try {
            Locale.setDefault(Locale.GERMANY);
            lines = ExampleItemVisualEvents.format(true, counter.snapshot(first));
        } finally { Locale.setDefault(originalLocale); }
        String text = String.join("\n", lines);
        require(text.contains("callbacks=1") && text.contains("attack_whoosh") && text.contains("clipSeconds=0.250")
                && text.contains("lastModel=" + model) && text.contains("lastAnimation=" + attack)
                && text.contains("generation=" + asset.generation()) && text.contains("historical callback"),
                "inspection includes immutable callback provenance with stable decimal formatting");
        for (int index = 0; index < 3; index++) require(lines.equals(ExampleItemVisualEvents.format(true,
                counter.snapshot(first))), "inspection must not dispatch, consume or mutate callback evidence");
        try { lines.clear(); throw new AssertionError("mutable callback inspection"); }
        catch (UnsupportedOperationException expected) { }

        playback.play(attack, ItemAnimationPlayback.Mode.HOLD);
        playback.sample(duration);
        require(playback.events().consume(model, asset.generation(), playback.consumeEventInterval(), duration,
                state.events()).isEmpty(), "retrigger baseline is silent");
        clock.addAndGet(250_000_000L);
        playback.sample(duration);
        playback.events().consume(model, asset.generation(), playback.consumeEventInterval(), duration,
                state.events()).forEach(marker -> counter.accept(first,
                        new ItemAnimationVisualEvent(model, attack, asset.generation(), marker)));
        require(counter.snapshot(first).orElseThrow().callbacks() == 2 && retained.callbacks() == 1,
                "retrigger adds a real callback without mutating an earlier inspection result");
        playback.sample(duration);
        require(playback.events().consume(model, asset.generation() + 1, playback.consumeEventInterval(), duration,
                state.events()).isEmpty(), "new-generation baseline never dispatches a backlog");
        require(counter.snapshot(first).orElseThrow().callbacks() == 2, "consumer counts survive resource reload");
        counter.clear();
        require(counter.snapshot(first).isEmpty() && retained.callbacks() == 1,
                "disconnect clears retained counters without mutating prior snapshots");
        require(ExampleItemVisualEvents.format(false, counter.snapshot(first)).getFirst().contains("disabled"),
                "default-disabled mode describes the explicit startup property");
        require(ExampleItemVisualEvents.format(true, counter.snapshot(first)).getFirst().contains("No retained"),
                "no callback evidence is never reported as a sampled or displayed frame");
    }

    private static void verifyBoundedIdentityCounter(ItemAnimationVisualEvent event) {
        var counter = new ExampleItemVisualEvents<Object>();
        var identities = new ArrayList<Object>();
        for (int index = 0; index < ExampleItemVisualEvents.MAX_TRACKED_STACKS; index++) {
            var identity = new EqualIdentity(1);
            identities.add(identity);
            counter.accept(identity, event);
        }
        Object oldest = identities.getFirst();
        Object second = identities.get(1);
        for (int index = 0; index < 3; index++) require(counter.snapshot(oldest).orElseThrow().callbacks() == 1,
                "equal stacks each have an independent counter and reads are repeatable");
        var newcomer = new EqualIdentity(1);
        identities.add(newcomer);
        counter.accept(newcomer, event);
        require(counter.snapshot(oldest).isEmpty() && counter.snapshot(second).isPresent(),
                "read-only inspection cannot prevent counter LRU retirement at its fixed bound");
        counter.accept(second, event);
        require(counter.snapshot(second).orElseThrow().callbacks() == 2, "a callback refreshes its own identity");
        counter.accept(oldest, event);
        require(counter.snapshot(identities.get(2)).isEmpty() && counter.snapshot(second).isPresent()
                && counter.snapshot(oldest).orElseThrow().callbacks() == 1,
                "delivery LRU stays bounded and evicted identities start a fresh counter");
        try { counter.accept(null, event); throw new AssertionError("null identity accepted"); }
        catch (NullPointerException expected) { }
        try { counter.accept(second, null); throw new AssertionError("null event accepted"); }
        catch (NullPointerException expected) { }
        try { counter.snapshot(null); throw new AssertionError("null identity inspected"); }
        catch (NullPointerException expected) { }
        require(counter.snapshot(second).orElseThrow().callbacks() == 2, "invalid calls cannot change a counter");
        Reference.reachabilityFence(identities);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
