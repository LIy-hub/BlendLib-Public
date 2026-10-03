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
import com.liy.blendlib.fabric.client.entity.BlendEntityLayerCue;
import java.io.IOException;
import java.util.List;

/** Test-only package bridge to the prepared rig view; excluded from both shipped JARs. */
public final class RunnableExampleAssetVerification {
    private static final String NS = "blendlib_runnable_examples:";
    private RunnableExampleAssetVerification() { }
    private record EqualEntity(int id) { }

    public static void main(String[] args) {
        ModelAsset actor = load("actor");
        ModelAsset wand = load("wand");
        ModelAsset marker = load("marker");
        require(actor.skeleton() != null && wand.skeleton() != null, "actor and wand must be skinned");
        require(!marker.primitives().isEmpty(), "socket marker must have geometry");
        require(wand.unitsPerBlock() == 2.5, "wand must preserve its display scale");
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
