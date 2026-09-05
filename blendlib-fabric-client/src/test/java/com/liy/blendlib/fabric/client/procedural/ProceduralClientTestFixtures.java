package com.liy.blendlib.fabric.client.procedural;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.ModelInstance;
import com.liy.blendlib.core.animation.v2.AnimationV2Clip;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2ControllerState;
import com.liy.blendlib.core.animation.v2.AnimationV2EvaluationSnapshot;
import com.liy.blendlib.core.animation.v2.AnimationV2InstancePlan;
import com.liy.blendlib.core.animation.v2.AnimationV2InstanceRuntime;
import com.liy.blendlib.core.animation.v2.AnimationV2Keyframe;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerDefinition;
import com.liy.blendlib.core.animation.v2.AnimationV2LayerMode;
import com.liy.blendlib.core.animation.v2.AnimationV2PlaybackMode;
import com.liy.blendlib.core.animation.v2.AnimationV2Pose;
import com.liy.blendlib.core.animation.v2.BoneMask;
import com.liy.blendlib.core.animation.v2.BoneSchema;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.core.procedural.ProceduralAttachmentAnchor;
import com.liy.blendlib.core.procedural.ProceduralAttachmentDescriptor;
import com.liy.blendlib.core.procedural.ProceduralAttachmentPayload;
import com.liy.blendlib.core.procedural.ProceduralAttachmentGraph;
import com.liy.blendlib.core.procedural.ProceduralFrameInput;
import com.liy.blendlib.core.procedural.ProceduralFrameSnapshot;
import com.liy.blendlib.core.procedural.ProceduralRigPlan;
import com.liy.blendlib.core.procedural.ProceduralRigRuntime;
import com.liy.blendlib.core.procedural.ProceduralSocketDefinition;
import com.liy.blendlib.core.procedural.ProceduralVisualEventMarker;
import com.liy.blendlib.core.procedural.ProceduralVisualEventPayload;
import java.util.List;
import java.util.Map;

/** Test-only frozen X3 fixtures; no platform client or live animation owner is involved. */
final class ProceduralClientTestFixtures {
    static final BlendModelKey MODEL = BlendModelKey.of("x3client", "rig");
    static final BlendModelKey CHILD_MODEL = BlendModelKey.of("x3client", "child");
    static final BlendResourceId SOCKET = id("tip");
    private static final BlendResourceId EVENT_CONTROLLER = id("event-controller");
    private static final BlendResourceId EVENT_LAYER = id("event-layer");
    private static final BlendAnimationKey EVENT_TIMELINE = BlendAnimationKey.of("x3client", "event-timeline");

    private ProceduralClientTestFixtures() {
    }

    static Harness chain(float firstLength, float secondLength) {
        BoneSchema schema = new BoneSchema(
                List.of("root", "middle", "end"),
                List.of(
                        Transform.IDENTITY,
                        translation(0.0F, 0.0F, firstLength),
                        translation(0.0F, 0.0F, secondLength)));
        ModelInstance instance = new ModelInstance(BlendInstanceKey.ephemeral("x3-client", "chain"), MODEL, 7L);
        AnimationV2EvaluationSnapshot evaluation = new AnimationV2EvaluationSnapshot(
                17L, schema.restPose(), Map.of(), List.of());
        ProceduralRigPlan plan = new ProceduralRigPlan(
                MODEL, 7L, schema, new int[] {-1, 0, 1},
                List.of(new ProceduralSocketDefinition(SOCKET, 2, Transform.IDENTITY)),
                List.of(), List.of(), List.of());
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan, instance);
        ProceduralFrameSnapshot snapshot = runtime.evaluate(new ProceduralFrameInput(instance, evaluation, List.of(), List.of()))
                .snapshot();
        return new Harness(instance, evaluation, plan, runtime, snapshot);
    }

    static Harness unrelatedBones() {
        BoneSchema schema = new BoneSchema(
                List.of("root", "middle", "end"),
                List.of(
                        Transform.IDENTITY,
                        translation(0.0F, 0.0F, 1.0F),
                        translation(0.0F, 0.0F, 2.0F)));
        ModelInstance instance = new ModelInstance(BlendInstanceKey.ephemeral("x3-client", "unrelated"), MODEL, 7L);
        AnimationV2EvaluationSnapshot evaluation = new AnimationV2EvaluationSnapshot(
                19L, schema.restPose(), Map.of(), List.of());
        ProceduralRigPlan plan = new ProceduralRigPlan(
                MODEL, 7L, schema, new int[] {-1, -1, -1},
                List.of(new ProceduralSocketDefinition(SOCKET, 2, Transform.IDENTITY)),
                List.of(), List.of(), List.of());
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan, instance);
        ProceduralFrameSnapshot snapshot = runtime.evaluate(new ProceduralFrameInput(instance, evaluation, List.of(), List.of()))
                .snapshot();
        return new Harness(instance, evaluation, plan, runtime, snapshot);
    }

    static Harness forkedHierarchy() {
        BoneSchema schema = new BoneSchema(
                List.of("root", "middle", "end"),
                List.of(
                        Transform.IDENTITY,
                        translation(0.0F, 0.0F, 1.0F),
                        translation(1.0F, 0.0F, 1.0F)));
        ModelInstance instance = new ModelInstance(BlendInstanceKey.ephemeral("x3-client", "forked"), MODEL, 7L);
        AnimationV2EvaluationSnapshot evaluation = new AnimationV2EvaluationSnapshot(
                20L, schema.restPose(), Map.of(), List.of());
        ProceduralRigPlan plan = new ProceduralRigPlan(
                MODEL, 7L, schema, new int[] {-1, 0, 0},
                List.of(new ProceduralSocketDefinition(SOCKET, 2, Transform.IDENTITY)),
                List.of(), List.of(), List.of());
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan, instance);
        ProceduralFrameSnapshot snapshot = runtime.evaluate(new ProceduralFrameInput(instance, evaluation, List.of(), List.of()))
                .snapshot();
        return new Harness(instance, evaluation, plan, runtime, snapshot);
    }

    static Harness presentationHarness() {
        BoneSchema schema = new BoneSchema(
                List.of("root", "middle", "end"),
                List.of(Transform.IDENTITY, translation(0.0F, 0.0F, 1.0F), translation(0.0F, 0.0F, 1.0F)));
        ModelInstance instance = new ModelInstance(BlendInstanceKey.ephemeral("x3-client", "presentation"), MODEL, 7L);
        List<ProceduralAttachmentDescriptor> attachments = List.of(
                new ProceduralAttachmentDescriptor(id("z-item"), new ProceduralAttachmentAnchor.Bone(0),
                        new ProceduralAttachmentPayload.Item(id("item")), Transform.IDENTITY, true),
                new ProceduralAttachmentDescriptor(id("a-block"), new ProceduralAttachmentAnchor.Socket(SOCKET),
                        new ProceduralAttachmentPayload.Block(id("block")), Transform.IDENTITY, true),
                new ProceduralAttachmentDescriptor(id("m-child"), new ProceduralAttachmentAnchor.Bone(1),
                        new ProceduralAttachmentPayload.ChildModel(CHILD_MODEL), Transform.IDENTITY, true));
        ProceduralAttachmentGraph attachmentGraph = ProceduralAttachmentGraph.compile(7L,
                Map.of(MODEL, attachments, CHILD_MODEL, List.of()));
        ProceduralRigPlan plan = new ProceduralRigPlan(
                MODEL, 7L, schema, new int[] {-1, 0, 1},
                List.of(new ProceduralSocketDefinition(SOCKET, 2, Transform.IDENTITY)),
                List.of(), List.of(), List.of(), attachments, attachmentGraph,
                List.of(
                        marker("sound", 0, 6, new ProceduralVisualEventPayload.Sound(id("sound"), 1.0F, 1.0F)),
                        marker("particle", 1, 5, new ProceduralVisualEventPayload.Particle(id("particle"), 2, Transform.IDENTITY)),
                        marker("shake", 2, 4, new ProceduralVisualEventPayload.CameraShake(1.0F, 1.0F)),
                        marker("trail-start", 3, 3, new ProceduralVisualEventPayload.TrailStart(id("trail"))),
                        marker("trail-stop", 4, 2, new ProceduralVisualEventPayload.TrailStop(id("trail"))),
                        marker("socket", 5, 1, new ProceduralVisualEventPayload.SocketEffect(id("effect"), SOCKET)),
                        marker("custom", 6, 0, new ProceduralVisualEventPayload.CustomClientEvent(id("custom"), Map.of("kind", "spark")))));
        ProceduralRigPlan childPlan = new ProceduralRigPlan(
                CHILD_MODEL, 7L, schema, new int[] {-1, 0, 1},
                List.of(), List.of(), List.of(), List.of(), List.of(), attachmentGraph);
        ProceduralAttachmentGraph.publish(List.of(plan, childPlan));
        AnimationV2InstanceRuntime eventSource = eventSource(schema);
        ProceduralRigRuntime runtime = new ProceduralRigRuntime(plan, instance, eventSource);
        if (!runtime.evaluate(new ProceduralFrameInput(instance, eventSource.advance(0.0D), List.of(), List.of())).published()) {
            throw new IllegalStateException("presentation fixture could not publish its bound X2 baseline");
        }
        AnimationV2EvaluationSnapshot evaluation = eventSource.advance(0.75D);
        ProceduralFrameSnapshot snapshot = runtime.evaluate(new ProceduralFrameInput(instance, evaluation, List.of(), List.of())).snapshot();
        return new Harness(instance, evaluation, plan, runtime, snapshot);
    }

    static ClientIkRequest request(Harness harness, Vec3 target, Vec3 pole) {
        return ClientIkRequest.forRig(ClientIkRigSnapshot.capture(harness.plan(), harness.snapshot()),
                0,
                1,
                2,
                target,
                pole,
                5);
    }

    static BlendResourceId id(String path) {
        return BlendResourceId.of("x3client", path);
    }

    private static Transform translation(float x, float y, float z) {
        return new Transform(new Vec3(x, y, z), Quaternion.IDENTITY, Vec3.ONE);
    }

    private static AnimationV2InstanceRuntime eventSource(BoneSchema schema) {
        AnimationV2LayerDefinition layer = new AnimationV2LayerDefinition(
                EVENT_LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1.0F, BoneMask.all(schema), false);
        AnimationV2Clip clip = new AnimationV2Clip(List.of(
                new AnimationV2Keyframe(0.0D, schema.restPose()),
                new AnimationV2Keyframe(1.0D, schema.restPose())));
        AnimationV2ControllerState state = new AnimationV2ControllerState(
                EVENT_TIMELINE, AnimationV2PlaybackMode.HOLD, 1.0D, 0.0D, null, Map.of(EVENT_LAYER, clip));
        AnimationV2ControllerDefinition controller = new AnimationV2ControllerDefinition(
                EVENT_CONTROLLER, 0, List.of(layer), EVENT_TIMELINE, Map.of(EVENT_TIMELINE, state));
        return new AnimationV2InstanceRuntime(new AnimationV2InstancePlan(schema, List.of(controller)));
    }

    private static ProceduralVisualEventMarker marker(
            String path, int markerIndex, int priority, ProceduralVisualEventPayload payload) {
        return new ProceduralVisualEventMarker(
                EVENT_CONTROLLER, EVENT_TIMELINE, markerIndex, 0.5D, id(path), priority, payload);
    }

    record Harness(
            ModelInstance instance,
            AnimationV2EvaluationSnapshot evaluation,
            ProceduralRigPlan plan,
            ProceduralRigRuntime runtime,
            ProceduralFrameSnapshot snapshot) {
    }
}
