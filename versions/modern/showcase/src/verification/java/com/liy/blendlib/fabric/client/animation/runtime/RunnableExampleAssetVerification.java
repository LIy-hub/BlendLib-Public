package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.animation.v2.AnimationV2Command;
import com.liy.blendlib.core.animation.v2.AnimationV2DiagnosticCode;
import com.liy.blendlib.core.animation.v2.AnimationV2InstanceRuntime;
import com.liy.blendlib.core.animation.v2.ModelAnimationLayers;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.examples.runnable.ExampleAnimationScene;
import com.liy.blendlib.examples.runnable.ExampleLayerInspection;
import com.liy.blendlib.examples.runnable.ExampleLayerVisualEvents;
import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import java.io.IOException;
import java.util.List;

/** Test-only package bridge to the prepared rig view; excluded from both shipped JARs. */
public final class RunnableExampleAssetVerification {
    private static final String NS = "blendlib_runnable_examples:";
    private RunnableExampleAssetVerification() { }
    private record EqualEntity(int id) { }

    public static void main(String[] args) {
        RunnableAttachmentVerification.verify();
        RunnableTwoBoneIkVerification.verify();
        com.liy.blendlib.fabric.client.reload.RunnableLocomotionVerification.verify();
        ModelAsset actor = load("actor");
        ModelAsset wand = load("wand");
        ModelAsset marker = load("marker");
        require(actor.skeleton() != null && wand.skeleton() != null, "actor and wand must be skinned");
        require(!marker.primitives().isEmpty(), "socket marker must have geometry");
        require(wand.unitsPerBlock() == 2.5, "wand must preserve its display scale");
        verifyMaterialAppearance(load("appearance_actor"), "appearance_actor",
                com.liy.blendlib.examples.runnable.ExampleMaterialAppearance::forName);
        var appearanceWand = load("appearance_wand");
        require(appearanceWand.unitsPerBlock() == 2.5, "appearance wand preserves item scale");
        verifyMaterialAppearance(appearanceWand, "appearance_wand",
                com.liy.blendlib.examples.runnable.ExampleItemMaterialAppearance::forName);
        com.liy.blendlib.fabric.client.item.RunnableItemVisualEventVerification.verify(wand, appearanceWand);
        com.liy.blendlib.fabric.client.render.RunnableNamedSkinVerification.verify(load("appearance_actor"), appearanceWand);
        verifyLayerVisualEvents(actor);
        var layers = new ModelAnimationLayers(actor, ExampleAnimationScene.layers());
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var attack = BlendAnimationKey.parse(NS + "attack");
        var idle = BlendAnimationKey.parse(NS + "idle");
        var commands = new EntityLayerCueCache();
        Object entity = new EqualEntity(42);
        var cue = capture(commands, entity, 1, 29, 31, 1).getFirst();
        runtime.advanceAtFrame(0, List.of(cue));
        var repeated = capture(commands, entity, 1, 29, 35, 1);
        require(repeated.getFirst().equals(cue), "same cue at later extraction must remain byte-for-byte semantic duplicate");
        var frame = runtime.advanceAtFrame(0.2, repeated);
        require(frame.diagnostics().stream().noneMatch(d -> d.code() == AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT),
                "repeated immutable cue must not become a sequence conflict");
        require(frame.playheads().get(ExampleAnimationScene.UPPER).state().equals(attack), "upper action must play");
        require(frame.playheads().get(ExampleAnimationScene.BASE).state().equals(BlendAnimationKey.parse(NS + "walk")),
                "upper action must not replace base walking");
        verifyInspection(frame);
        verifyDynamicWeights(layers, cue);
        verifyItemInspection();
        var pose = layers.localPose(frame.pose());
        var baseOnly = new ModelAnimationLayers(actor, List.of(ExampleAnimationScene.layers().getFirst()));
        var basePose = baseOnly.localPose(new AnimationV2InstanceRuntime(baseOnly.plan()).advance(0.2).pose());
        int root = actor.nodes().stream().filter(n -> n.name().equals("ShowcaseRootBone")).findFirst().orElseThrow().index();
        require(pose.transform(root).equals(basePose.transform(root)), "masked upper action must preserve base root");
        var rig = ClientAnimationRigView.fromNodes(actor.nodes());
        var context = new ClientAnimationPoseContext(BlendInstanceKey.entity("asset-check", 1),
                BlendModelKey.parse(NS + "actor"), actor.generation(), attack, 0.3, 31, rig);
        var modified = ExampleAnimationScene.procedural().modify(context, pose);
        int tip = rig.requireNodeIndex(ExampleAnimationScene.TIP_BONE);
        require(!modified.transform(tip).rotation().equals(pose.transform(tip).rotation()), "procedural tip must change");
        for (var entry : pose.transforms().entrySet()) {
            require(modified.transform(entry.getKey()).translation().equals(entry.getValue().translation()),
                    "procedural layer must preserve translation");
            require(modified.transform(entry.getKey()).scale().equals(entry.getValue().scale()),
                    "procedural layer must preserve scale");
        }
        var socketKey = BlendResourceId.parse(NS + "tip");
        var oldSocket = SocketWorldTransform.query(actor, NodePalette.from(pose, actor.nodes()), socketKey).orElseThrow();
        var finalSocket = SocketWorldTransform.query(actor, NodePalette.from(modified, actor.nodes()), socketKey).orElseThrow();
        Vec3 offset = new Vec3(0, 0.45F, 0);
        Vec3 oldPoint = oldSocket.translation().add(oldSocket.rotation().rotate(offset));
        Vec3 finalPoint = finalSocket.translation().add(finalSocket.rotation().rotate(offset));
        require(finalPoint.subtract(oldPoint).length() > 0.001F, "offset attachment must follow the final procedural socket");
        require(runtime.advance(2).playheads().get(ExampleAnimationScene.UPPER).state().equals(idle),
                "upper one-shot must return to idle");
        var retrigger = runtime.advanceAtFrame(0, List.of(
                new AnimationV2Command(ExampleAnimationScene.UPPER, attack, 2, 0, 1)));
        require(retrigger.playheads().get(ExampleAnimationScene.UPPER).state().equals(attack), "new cue must retrigger");
        // A new controller generation can accept the same authoritative cue after resource reload.
        var reloaded = new ModelAnimationLayers(load("actor"), ExampleAnimationScene.layers());
        var reloadCue = capture(commands, entity, 1, 29, 39, 2);
        require(reloadCue.getFirst().requestedPlayheadSeconds() == 0.5, "reload must refresh elapsed-time capture");
        require(new AnimationV2InstanceRuntime(reloaded.plan()).advanceAtFrame(0, reloadCue)
                .playheads().get(ExampleAnimationScene.UPPER).acceptedSequence() == 1, "reload must accept current cue");
        var other = capture(commands, new EqualEntity(42), 1, 29, 33, 1).getFirst();
        require(other.requestedPlayheadSeconds() == 0.2, "distinct equal-ID entities must capture independently");
        commands.retireEntity(42);
        require(capture(commands, entity, 1, 29, 41, 2).getFirst().requestedPlayheadSeconds() == 0.6,
                "entity unload must retire the captured command");
        commands.clear();
        require(capture(commands, entity, 1, 29, 43, 2).getFirst().requestedPlayheadSeconds() == 0.7,
                "disconnect must clear captured commands");
        System.out.println("Verified packaged actor/wand/marker with strict loader, layers, procedural pose, final socket, duplicate/retrigger and reload cues");
    }

    private static void verifyMaterialAppearance(ModelAsset actor, String modelName,
            java.util.function.Function<String, java.util.Map<String,
                    com.liy.blendlib.fabric.client.render.MaterialSlotAppearance>> selection) {
        boolean wand = modelName.equals("appearance_wand");
        var body = wand ? com.liy.blendlib.examples.runnable.ExampleItemMaterialAppearance.BODY_SLOT
                : com.liy.blendlib.examples.runnable.ExampleMaterialAppearance.BODY_SLOT;
        var accessory = wand ? com.liy.blendlib.examples.runnable.ExampleItemMaterialAppearance.ACCESSORY_SLOT
                : com.liy.blendlib.examples.runnable.ExampleMaterialAppearance.ACCESSORY_SLOT;
        require(actor.primitives().size() == 2, "appearance model needs actual body and accessory primitives");
        if (wand) {
            var shaft = actor.primitives().getFirst().geometry();
            require(shaft.indexCount() == 36, "wand shaft has twelve actual 3D triangles");
            var bounds = shaft.localBounds();
            require(bounds.max().y() - bounds.min().y() > 10 * (bounds.max().x() - bounds.min().x())
                    && bounds.max().z() > bounds.min().z(), "wand shaft is slender and three-dimensional");
        }
        require(actor.primitives().stream().map(p -> p.geometry().materialSlot()).toList()
                .equals(List.of(body, accessory)), "selector names must match actual authored GLB primitive slots");
        require(actor.primitives().get(1).geometry().indexCount() == (wand ? 24 : 6),
                "accessory must have real triangles, not just descriptor metadata");
        require(wand ? actor.primitives().get(1).geometry().localBounds().min().y()
                > actor.primitives().get(0).geometry().localBounds().max().y()
                : actor.primitives().get(1).geometry().localBounds().min().x()
                > actor.primitives().get(0).geometry().localBounds().max().x(),
                "accessory must be a distinct side badge rather than overlapping duplicate geometry");
        require(actor.bounds().max().x() >= actor.primitives().get(1).geometry().localBounds().max().x(),
                "authored bounds must conservatively include the visible accessory");
        var orange = selection.apply("Orange");
        var blue = selection.apply("Blue bare");
        require(orange.get(body).rgbTint() == 0xff8844 && blue.get(body).rgbTint() == 0x4488ff,
                "actual live selector must independently color the two actors");
        require(orange.get(accessory).visible() && !blue.get(accessory).visible()
                && orange.get(accessory).rgbTint() == 0xffffff,
                "actual selector must independently hide the accessory without recoloring it");
        require(selection.apply(null).isEmpty()
                && selection.apply("Unconfigured").isEmpty(),
                "unnamed and unconfigured actors must leave all slots unchanged");
        require(selection.apply("Orange bare")
                .get(body).equals(orange.get(body)), "hiding the accessory must preserve body selection");
        require(selection.apply("Blue")
                .get(accessory).visible(), "blue actor supports independently visible accessory too");
        try { orange.clear(); throw new AssertionError("mutable example selection"); }
        catch (UnsupportedOperationException expected) { }
        var handle = com.liy.blendlib.fabric.client.render.SkinnedRenderHandle.prepare(
                BlendModelKey.parse(NS + modelName), actor);
        require(handle.materialSlots().equals(List.of(body, accessory)), "prepared handle must preserve exact slot order");
        var transforms = new java.util.HashMap<Integer, com.liy.blendlib.core.model.Transform>();
        actor.nodes().forEach(node -> transforms.put(node.index(), node.localTransform()));
        var nodes = NodePalette.from(new com.liy.blendlib.core.animation.runtime.LocalPose(transforms), actor.nodes());
        var outputs = handle.skinnedPrimitives().stream().map(primitive ->
                com.liy.blendlib.core.animation.runtime.CpuSkinner.skin(primitive.geometry(),
                        com.liy.blendlib.core.animation.runtime.SkinPalette.from(
                                actor.skeleton().skins().get(primitive.skinIndex()), nodes))).toList();
        var captured = com.liy.blendlib.fabric.client.render.SkinnedRenderSnapshot.capture(handle, outputs);
        var snapshot = com.liy.blendlib.fabric.client.render.ModelRenderSnapshot.skinned(handle,
                com.liy.blendlib.core.model.Transform.IDENTITY, 0, 0, 0xffffffff,
                com.liy.blendlib.fabric.client.render.RenderVisibility.VISIBLE,
                new com.liy.blendlib.fabric.client.render.CullingMetadata(handle.bounds(), true), captured);
        var first = snapshot.withMaterialAppearance(orange);
        var second = snapshot.withMaterialAppearance(blue);
        require(first.unknownMaterialSlots().isEmpty() && second.unknownMaterialSlots().isEmpty(),
                "both actual example selections must capture without fallback");
        require(first.handle() == second.handle() && first.culling().equals(second.culling()),
                "per-actor appearance must share the handle and preserve conservative culling");
        com.liy.blendlib.fabric.client.render.RunnableItemAppearanceVerification.verify(snapshot, first, second);
        var invalid = new java.util.HashMap<>(orange);
        invalid.put("NotAnAuthoredSlot", new com.liy.blendlib.fabric.client.render.MaterialSlotAppearance(0, false));
        require(snapshot.withMaterialAppearance(invalid).unknownMaterialSlots().equals(List.of("NotAnAuthoredSlot")),
                "incompatible resource-pack names must diagnose authored fallback");
        System.out.println("Verified " + modelName + " actual example material selector, two authored slots, real accessory geometry, CPU capture and unknown-name diagnostic");
    }

    private static void verifyLayerVisualEvents(ModelAsset actor) {
        var layers = new ModelAnimationLayers(actor, ExampleAnimationScene.layers());
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var cursor = layers.newVisualEventCursor(runtime);
        var counters = new ExampleLayerVisualEvents();
        var cue = new AnimationV2Command(ExampleAnimationScene.UPPER,
                BlendAnimationKey.parse(NS + "attack"), 1, 0, 1);
        require(cursor.consume(runtime.advanceAtFrame(0, List.of(cue))).isEmpty(),
                "first real descriptor observation silently establishes the callback baseline");
        var frame = runtime.advanceWeightedAtFrame(0.25, List.of(cue), ExampleAnimationScene.clipLayerWeights(20));
        var events = cursor.consume(frame);
        require(events.size() == 2, "real packaged walk and attack markers must both dispatch");
        require(events.stream().allMatch(event -> event.controllerId().equals(event.layerId())
                && event.event().timeSeconds() == 0.25), "descriptor callbacks carry declared IDs and exact marker time");
        require(events.stream().anyMatch(event -> event.controllerId().equals(ExampleAnimationScene.BASE)
                && event.event().eventKey().equals(BlendResourceId.parse(NS + "walk_step"))
                && event.effectiveWeight() == 1F), "base marker identity and weight");
        require(events.stream().anyMatch(event -> event.controllerId().equals(ExampleAnimationScene.UPPER)
                && event.event().eventKey().equals(BlendResourceId.parse(NS + "attack_whoosh"))
                && Math.abs(event.effectiveWeight() - 0.5F) < 1e-6), "upper marker identity and sampled weight");
        // This is the same callback work as ExampleClient, driven by the real descriptor cursor.
        events.forEach(counters::accept);
        var retained = counters.snapshot();
        require(retained.callbacks() == 2 && retained.pairs().size() == 2
                && retained.pairs().stream().allMatch(count -> count.callbacks() == 1),
                "consumer records one real callback per independent layer");
        require(cursor.consume(frame).isEmpty(), "repeated extraction cannot replay consumed callbacks");
        var oldLocale = java.util.Locale.getDefault();
        List<String> lines;
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            lines = ExampleLayerVisualEvents.format(retained);
        } finally { java.util.Locale.setDefault(oldLocale); }
        String text = String.join("\n", lines);
        require(text.contains("Layer visual callbacks=2") && text.contains("walk_step")
                && text.contains("attack_whoosh") && text.contains("effectiveWeight=0.500")
                && text.contains("loopEpoch=") && text.contains("occurrence="), "callback inspection details and locale");
        require(lines.equals(ExampleLayerVisualEvents.format(counters.snapshot()))
                && counters.snapshot().equals(retained), "repeated inspection neither dispatches nor consumes callbacks");
        try { retained.pairs().clear(); throw new AssertionError("mutable callback snapshot"); }
        catch (UnsupportedOperationException expected) { }
        try { lines.add("mutable"); throw new AssertionError("mutable callback inspection lines"); }
        catch (UnsupportedOperationException expected) { }
        double walkDuration = layers.plan().controllers().stream()
                .filter(controller -> controller.id().equals(ExampleAnimationScene.BASE))
                .findFirst().orElseThrow().initialStateDefinition().durationSeconds();
        cursor.consume(runtime.advance(walkDuration)).forEach(counters::accept);
        require(counters.snapshot().callbacks() > retained.callbacks() && retained.callbacks() == 2,
                "later loop callbacks update the consumer without mutating a retained inspection snapshot");
        require(new ExampleLayerVisualEvents().snapshot().callbacks() == 0,
                "a distinct actor-owned consumer cannot inherit another actor's callback totals");

        var mutedRuntime = new AnimationV2InstanceRuntime(layers.plan());
        var mutedCursor = layers.newVisualEventCursor(mutedRuntime);
        mutedCursor.consume(mutedRuntime.advanceAtFrame(0, List.of(cue)));
        var muted = mutedCursor.consume(mutedRuntime.advanceWeightedAtFrame(
                0.25, List.of(cue), ExampleAnimationScene.clipLayerWeights(0)));
        require(muted.size() == 1 && muted.getFirst().controllerId().equals(ExampleAnimationScene.BASE),
                "zero upper weight suppresses its actual callback while preserving the base callback");
        require(mutedCursor.consume(mutedRuntime.advance(0.1)).isEmpty(),
                "unmuting after a consumed upper marker does not backfill it");

        var manyLayers = java.util.stream.IntStream.range(0, ExampleLayerVisualEvents.MAX_TRACKED_PAIRS + 2)
                .mapToObj(index -> new ModelAnimationLayers.Layer(BlendResourceId.parse(NS + "counter_" + index),
                        0, com.liy.blendlib.core.animation.v2.AnimationV2LayerMode.OVERRIDE, 1F, List.of(),
                        BlendAnimationKey.parse(NS + "walk"))).toList();
        var many = new ModelAnimationLayers(actor, manyLayers);
        var manyRuntime = new AnimationV2InstanceRuntime(many.plan());
        var manyCursor = many.newVisualEventCursor(manyRuntime);
        manyCursor.consume(manyRuntime.advance(0));
        var bounded = new ExampleLayerVisualEvents();
        manyCursor.consume(manyRuntime.advance(0.25)).forEach(bounded::accept);
        var boundedSnapshot = bounded.snapshot();
        require(boundedSnapshot.callbacks() == manyLayers.size()
                && boundedSnapshot.pairs().size() == ExampleLayerVisualEvents.MAX_TRACKED_PAIRS
                && boundedSnapshot.untrackedPairCallbacks() == 2, "callback telemetry retains a bounded number of pairs");
        require(ExampleLayerVisualEvents.format(boundedSnapshot).getLast().contains("Untracked pair callbacks=2"),
                "bounded telemetry reports omitted pair details without losing the total callback count");
        System.out.println("Verified real descriptor layer callbacks, actor-owned bounded counters, immutable inspection, weights and no replay/backfill");
    }

    private static void verifyDynamicWeights(ModelAnimationLayers layers, AnimationV2Command cue) {
        var runtime = new AnimationV2InstanceRuntime(layers.plan());
        var key = new com.liy.blendlib.core.animation.v2.AnimationV2LayerWeights.Key(
                ExampleAnimationScene.UPPER, ExampleAnimationScene.UPPER);
        var silent = runtime.advanceWeightedAtFrame(0, List.of(cue), ExampleAnimationScene.clipLayerWeights(0));
        var halfway = runtime.advanceWeightedAtFrame(0.1, List.of(cue), ExampleAnimationScene.clipLayerWeights(20));
        var visible = runtime.advanceWeightedAtFrame(0.1, List.of(cue), ExampleAnimationScene.clipLayerWeights(40));
        require(silent.effectiveLayerWeights().get(key) == 0F, "zero clip-layer weight must be captured");
        require(Math.abs(halfway.effectiveLayerWeights().get(key) - 0.5F) < 1e-6, "half fade");
        require(visible.effectiveLayerWeights().get(key) == 1F, "full fade");
        require(Math.abs(visible.playheads().get(ExampleAnimationScene.UPPER).timeSeconds()
                - cue.requestedPlayheadSeconds() - 0.2) < 1e-6, "weight changes must not restart cue clock");
        require(visible.playheads().get(ExampleAnimationScene.UPPER).acceptedSequence() == cue.sequence(),
                "weight changes must preserve accepted cue sequence");
        require(ExampleLayerInspection.format(ExampleAnimationScene.layers(), halfway).stream()
                .anyMatch(line -> line.contains("effectiveWeight=0.500")), "inspection reports captured effective weight");
        require(ExampleLayerInspection.format(ExampleAnimationScene.layers(), silent).stream()
                .anyMatch(line -> line.contains("effectiveWeight=0.000")), "old captured weight stays immutable");
    }

    private static void verifyItemInspection() {
        var missing = com.liy.blendlib.examples.runnable.ExampleItemInspection.format(java.util.Optional.empty());
        require(missing.getFirst().contains("does not create playback"), "item missing status");
        var sample = new com.liy.blendlib.fabric.client.item.ItemAnimationObservation.Sample(
                com.liy.blendlib.api.BlendModelKey.parse("test:wand"),
                com.liy.blendlib.api.BlendAnimationKey.parse("test:idle"), 1, 0.25, 2);
        var status = new com.liy.blendlib.fabric.client.item.ItemAnimationObservation(
                com.liy.blendlib.api.BlendAnimationKey.parse("test:attack"),
                com.liy.blendlib.fabric.client.item.ItemAnimationPlayback.Mode.HOLD,
                1.5, false, 9, java.util.Optional.of(sample), false);
        var oldLocale = java.util.Locale.getDefault();
        java.util.List<String> lines;
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            lines = com.liy.blendlib.examples.runnable.ExampleItemInspection.format(java.util.Optional.of(status));
        } finally { java.util.Locale.setDefault(oldLocale); }
        require(lines.getFirst().contains("test:attack") && lines.getFirst().contains("storedSeconds=9.000")
                && lines.getFirst().contains("speed=1.500"), "item current controls and locale");
        require(lines.get(1).contains("test:idle") && lines.get(1).contains("clipSeconds=0.250")
                && lines.get(1).contains("currentGeneration=false"), "item historical stale sample");
        require(lines.equals(com.liy.blendlib.examples.runnable.ExampleItemInspection.format(java.util.Optional.of(status))),
                "item repeat inspection");
        try { lines.add("mutable"); throw new AssertionError("mutable item lines"); }
        catch (UnsupportedOperationException expected) { }
        var attempt = new com.liy.blendlib.fabric.client.item.ItemAnimationExtractionStatus(sample.model(),
                status.animation(), 2,
                com.liy.blendlib.fabric.client.item.ItemAnimationExtractionStatus.Outcome.ANIMATION_UNAVAILABLE,
                com.liy.blendlib.fabric.client.item.ItemAnimationExtractionStatus.Fallback.MISSING_MODEL, true);
        var unavailable = com.liy.blendlib.examples.runnable.ExampleItemInspection.format(
                java.util.Optional.of(status), java.util.Optional.of(attempt));
        require(unavailable.getLast().contains("ANIMATION_UNAVAILABLE")
                && unavailable.getLast().contains("fallback=MISSING_MODEL")
                && unavailable.getLast().contains("requestedAnimation=test:attack")
                && unavailable.getLast().contains("currentGeneration=true")
                && unavailable.get(1).contains("currentGeneration=false"), "unavailable attempt and stale sample differ");
        require(com.liy.blendlib.examples.runnable.ExampleItemInspection.format(java.util.Optional.of(status),
                java.util.Optional.empty()).getLast().equals("No extraction attempt recorded"), "no attempt is not success");
        var unsampled = new com.liy.blendlib.fabric.client.item.ItemAnimationObservation(status.animation(), status.mode(),
                0, true, 0, java.util.Optional.empty(), false);
        require(com.liy.blendlib.examples.runnable.ExampleItemInspection.format(java.util.Optional.of(unsampled))
                .get(1).startsWith("No successful extraction"), "item retained but unsampled");
    }

    private static void verifyInspection(com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot frame) {
        require(ExampleLayerInspection.targets(List.of()).getFirst().startsWith("No loaded example actors"),
                "empty discovery does not select an actor");
        require(ExampleLayerInspection.targets(List.of(42, 7, 42)).equals(List.of(
                "Loaded example actors in the 16-block search box: 2", "  /blendlib_example inspect 7",
                "  /blendlib_example inspect 42")), "discovery presents sorted unique explicit target commands");
        var many = ExampleLayerInspection.targets(java.util.stream.IntStream.range(0, 20).boxed().toList());
        require(many.size() == 10 && many.getLast().contains("Additional actors omitted"), "discovery output is bounded");
        var locale = java.util.Locale.getDefault();
        List<String> lines;
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            lines = ExampleLayerInspection.format(ExampleAnimationScene.layers(), frame);
        } finally {
            java.util.Locale.setDefault(locale);
        }
        var text = String.join("\n", lines);
        require(text.contains("Last sampled layer revision=" + frame.revision()), "inspection labels last sampled revision");
        require(text.contains("weight=1.000 mask=[all bones]"), "base configured weight and full mask are explicit");
        require(text.contains("mask=[ShowcaseTipBone=1.000]"), "named bone mask is visible");
        require(text.contains("Sampled state=" + NS + "attack clipSeconds=0.300 acceptedSequence=1"),
                "inspection exposes exact sampled state/time/sequence, independent of locale");
        require(text.contains("previousState=") && text.contains("transition="), "transition details are visible");
        require(lines.equals(ExampleLayerInspection.format(ExampleAnimationScene.layers(), frame)),
                "repeated read-only formatting is deterministic");
        try {
            lines.add("mutation");
            throw new AssertionError("inspection must return immutable lines");
        } catch (UnsupportedOperationException expected) { }
        var diagnostic = new com.liy.blendlib.core.animation.v2.AnimationV2Diagnostic(
                AnimationV2DiagnosticCode.COMMAND_SEQUENCE_CONFLICT, ExampleAnimationScene.UPPER, null, -1, "conflict");
        var missing = new com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot(
                frame.revision(), frame.pose(), java.util.Map.of(), java.util.Collections.nCopies(9, diagnostic));
        var absent = String.join("\n", ExampleLayerInspection.format(ExampleAnimationScene.layers(), missing));
        require(absent.contains("No sampled playhead"), "missing playhead is not presented as current configuration state");
        require(absent.contains("Sampled diagnostics=9") && absent.contains("COMMAND_SEQUENCE_CONFLICT")
                && absent.contains("Additional diagnostics omitted"), "diagnostics are visible and chat output is bounded");
        System.out.println("Verified read-only inspection formatting: masks, sampled state/time/sequence, transitions, locale, absence and bounded diagnostics");
    }

    private static List<AnimationV2Command> capture(EntityLayerCueCache cache, Object owner, long sequence,
            long startTick, double ticks, long generation) {
        return cache.capture(RunnableExampleAssetVerification.class, owner, BlendInstanceKey.entity("verify", 42),
                BlendModelKey.parse(NS + "actor"), generation, ticks, List.of(new BlendEntityLayerCue(
                        ExampleAnimationScene.UPPER, BlendAnimationKey.parse(NS + "attack"), sequence, startTick, 1)));
    }

    private static ModelAsset load(String name) {
        var key = BlendResourceId.parse(NS + name);
        return new ModelAssetLoader().load(key, 1,
                bytes(BlendResourceId.parse(NS + "blend_models/" + name + ".json")), RunnableExampleAssetVerification::bytes);
    }

    private static AssetBytes bytes(BlendResourceId id) {
        String resource = "/assets/" + id.value().replace(':', '/');
        try (var input = RunnableExampleAssetVerification.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing bundled resource: " + resource);
            return new AssetBytes(id, input.readAllBytes());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read bundled resource: " + resource, exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
