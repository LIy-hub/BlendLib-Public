package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.examples.runnable.ExampleTwoBoneIkScene;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.*;
import com.liy.blendlib.fabric.client.api.*;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.*;
import java.io.IOException;
import java.util.*;

/** Loads the shipped consumer JAR's own GLBs/PNG/descriptors and exercises ordinary CPU extraction. */
final class RunnableTwoBoneIkVerification {
    private static final String NS = "blendlib_runnable_examples:";
    private static final int LIGHT = 0x00F000F0, OVERLAY = 0;
    private RunnableTwoBoneIkVerification() { }

    static void verify() {
        verifyOptIn();
        var models = new ClientModelRegistry();
        var lifecycle = new ClientAnimationLifecycleBridge(32);
        var runtime = new SkinnedAnimationRuntime(models, lifecycle);
        var lookup = lookup(models);
        runtime.onPlayInit();
        var diagnostics = new ArrayList<String>();
        var pipeline = ExampleTwoBoneIkScene.procedural(diagnostics::add);
        var entity = runtime.entityKey(73);
        var other = runtime.entityKey(74);
        ModelRenderSnapshot retained = null;
        List<Vec3> retainedVertices = null;
        Set<Quaternion> mountRotations = new HashSet<>();
        Set<Vec3> mountScales = new HashSet<>();
        Set<Vec3> endpointPositions = new HashSet<>();
        int verticesChecked = 0, samples = 0;
        boolean geometryMoved = false;
        double maxError = 0;
        for (int generation = 1; generation <= 3; generation++) {
            boolean transformedResourcePack = generation == 3;
            publish(models, generation, transformedResourcePack);
            runtime.onActiveGeneration(generation);
            var asset = ((LoadedModelHandle) models.current().find(ExampleTwoBoneIkScene.MODEL).orElseThrow()).asset();
            require(asset.skeleton().skins().size() == 1 && asset.skeleton().skins().getFirst().joints().size() == 3,
                    "packaged mechanical arm has an actual three-joint skin");
            require(asset.primitives().getFirst().geometry().indexCount() >= 300,
                    "arm contains authored volumetric links, joints and end fork");
            require(asset.unitsPerBlock() == (transformedResourcePack ? 2.5 : 1), "strictly loaded descriptor unit contract");
            if (transformedResourcePack) {
                var scene = asset.nodes().stream().filter(n -> n.name().equals("ArmScene")).findFirst().orElseThrow();
                require(!scene.localTransform().translation().equals(Vec3.ZERO)
                        && !scene.localTransform().rotation().equals(Quaternion.IDENTITY)
                        && !scene.localTransform().scale().equals(Vec3.ONE),
                        "resource-pack GLB must really transform the origin before strict asset loading");
            }
            require(asset.clips().size() == 1 && asset.clips().getFirst().channels().size() == 2,
                    "real clip animates the ancestor rotation and scale");
            verifyResourcePackFallback(asset);
            for (int i = 0; i < 49; i++) {
                long tick = generation * 240L + i * 5L;
                float partial = .25F;
                double time = tick + partial;
                var q = new Quaternion(.13F, -.3F, .07F, .94F).normalized();
                var rootTransform = new Transform(new Vec3(.2F, .15F, -.1F), q, new Vec3(1.2F, 1.2F, 1.2F));
                var handle = lookup.resolve(ExampleTwoBoneIkScene.MODEL).renderHandle();
                var request = new BlendEntitySnapshotRequest(ExampleTwoBoneIkScene.MODEL, partial, LIGHT, (float) time,
                        12.5, 64, -18.25, tick, true, 4);
                var input = new SkinnedAnimationRuntimeInput(ExampleTwoBoneIkScene.MODEL, entity, tick, partial,
                        ExampleTwoBoneIkScene.IDLE, Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR,
                        new SkinnedExtractionRequest(rootTransform, LIGHT, OVERLAY, -1,
                                RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true)));
                // This is the same public path used by .poseComponents(...) on the live renderer.
                var frame = runtime.extract(input, pipeline).orElseThrow().frame();
                var repeated = runtime.extract(input, pipeline).orElseThrow().frame();
                var target = ExampleTwoBoneIkScene.target(time).targetModelSpace();
                var endpoint = frame.socketTransforms().get(ExampleTwoBoneIkScene.END);
                double error = endpoint.translation().subtract(target).length();
                maxError = Math.max(maxError, error);
                require(error < 2e-5, "final extracted end socket must reach the moving target; error=" + error);
                require(endpoint.equals(repeated.socketTransforms().get(ExampleTwoBoneIkScene.END)),
                        "same runtime input must retain deterministic final socket");
                endpointPositions.add(endpoint.translation());
                var mount = frame.socketTransforms().get(ExampleTwoBoneIkScene.MOUNT);
                mountRotations.add(mount.rotation()); mountScales.add(mount.scale());
                var sockets = BlendEntitySockets.capture(request, frame);
                var socket = sockets.socket(ExampleTwoBoneIkScene.END).orElseThrow();
                var expectedEntity = rootTransform.transformPoint(target.multiply(handle.unitsToBlocksScale()));
                require(distance(socket.entitySpace(), expectedEntity) < 3e-5,
                        "end socket must retain renderer root rotation/scale/translation");
                require(Math.abs(socket.worldSpace().x() - socket.entitySpace().x() - request.x()) < 1e-9
                        && Math.abs(socket.worldSpace().y() - socket.entitySpace().y() - request.y()) < 1e-9
                        && Math.abs(socket.worldSpace().z() - socket.entitySpace().z() - request.z()) < 1e-9,
                        "final socket world position must include entity origin");
                var attachments = ExampleTwoBoneIkScene.attachments(lookup, request, sockets, OVERLAY);
                require(attachments.size() == 2, "actual consumer captures both markers");
                require(attachments.get(1).placement().equals(socket.attachmentPlacement()),
                        "gold marker consumes actual final extracted socket, never a guessed target");
                require(attachments.get(0).snapshot().handle().modelKey().equals(ExampleTwoBoneIkScene.TARGET_MARKER)
                        && attachments.get(1).snapshot().handle().modelKey().equals(ExampleTwoBoneIkScene.END_MARKER),
                        "markers use the packaged distinct cage and diamond geometry");
                var targetPlacement = attachments.getFirst();
                var actualTarget = compose(targetPlacement.placement(), targetPlacement.offset());
                require(distance(actualTarget, expectedEntity) < 3e-5,
                        "target cage is captured at the same model-space target under the live root; transformed resource pack="
                                + transformedResourcePack);
                require(distance(actualTarget, new Vec3((float) socket.entitySpace().x(),
                        (float) socket.entitySpace().y(), (float) socket.entitySpace().z())) < 3e-5,
                        "cyan target cage and actual final end socket coincide for authored and transformed-resource-pack origins");
                var snapshot = frame.renderSnapshot().withAttachments(attachments);
                var composition = BlendEntityAttachmentComposition.capture(snapshot);
                require(composition.attachments().size() == 2 && composition.diagnostics().isEmpty(),
                        "real captured marker graph composes without fallback");
                var vertices = RunnableAttachmentRenderVerification.positions(snapshot);
                require(!vertices.isEmpty(), "runtime produces real CPU-skinned arm triangles");
                for (var p : vertices) {
                    require(inside(p, ExampleTwoBoneIkScene.ENVELOPE), "real arm vertices fit authored culling envelope");
                    verticesChecked++;
                }
                var inverseRoot = new Quaternion(-q.x(), -q.y(), -q.z(), q.w());
                for (var p : RunnableAttachmentRenderVerification.entityPositions(snapshot)) {
                    var local = inverseRoot.rotate(new Vec3((float) p.x(), (float) p.y(), (float) p.z())
                            .subtract(rootTransform.translation())).multiply(1 / rootTransform.scale().x());
                    require(inside(local, ExampleTwoBoneIkScene.ENVELOPE),
                            "complete real arm and both marker meshes fit the configured pre-root culling envelope");
                }
                if (retained == null) { retained = snapshot; retainedVertices = vertices; }
                else geometryMoved |= !vertices.equals(retainedVertices);
                // One reusable component may serve a second entity in the same extraction frame.
                var otherInput = new SkinnedAnimationRuntimeInput(input.modelKey(), other, tick, partial, input.fallbackAnimation(),
                        Optional.empty(), AnimationUpdateBucket.VISIBLE_NEAR, input.extractionRequest());
                require(runtime.extract(otherInput, pipeline).orElseThrow().frame().socketTransforms()
                        .get(ExampleTwoBoneIkScene.END).translation().subtract(target).length() < 2e-5,
                        "shared component must solve another runtime owner independently");
                require(ExampleTwoBoneIkScene.attachments(lookup, request,
                        new BlendEntitySockets(generation, Map.of()), OVERLAY).isEmpty(), "missing sockets omit both markers safely");
                require(ExampleTwoBoneIkScene.attachments(lookup, request,
                        new BlendEntitySockets(generation + 1, sockets.sockets()), OVERLAY).isEmpty(),
                        "generation-mismatched markers are never attached");
                samples++;
            }
        }
        require(mountRotations.size() >= 8 && mountScales.size() >= 8,
                "endpoint proof actually spans animated ancestor rotation and positive uniform scale");
        require(geometryMoved && endpointPositions.size() > 40 && diagnostics.isEmpty(), "trajectory moves with no chain fallback");
        require(retained.generation() == 1 && RunnableAttachmentRenderVerification.positions(retained).equals(retainedVertices),
                "retained CPU geometry and markers remain immutable after resource reload");
        require(lifecycle.registry().size() == 2, "markers own no animation clocks");
        runtime.onEntityUnload(73);
        require(lifecycle.registry().size() == 1, "unload retires only one arm owner");
        runtime.onWorldDisconnect(); pipeline.reset();
        require(lifecycle.registry().size() == 0, "disconnect clears arm runtime owners");
        System.out.println("Verified packaged standard two-bone IK mechanical arm: " + samples + " runtime extractions, "
                + verticesChecked + " CPU-skinned vertices, max final-socket error=" + maxError
                + "; animated ancestor rotation/scale, translated/rotated/scaled resource-pack origin with non-unit descriptor units, target/end attachments, reload, independent owners and safe rig fallback");
    }

    private static void verifyResourcePackFallback(ModelAsset asset) {
        var original = asset.nodes();
        var renamed = original.stream().map(n -> n.name().equals(ExampleTwoBoneIkScene.MIDDLE_BONE)
                ? rename(n, "ResourcePackRenamedElbow") : n).toList();
        var ambiguous = original.stream().map(n -> n.name().equals("ArmMesh")
                ? rename(n, ExampleTwoBoneIkScene.MIDDLE_BONE) : n).toList();
        var reparented = original.stream().map(n -> {
            if (n.name().equals(ExampleTwoBoneIkScene.ROOT_BONE))
                return new ModelNode(n.index(), n.name(), n.localTransform(), List.of(3, 4), n.meshIndex(), n.skinIndex(), n.cameraOrLightIgnored());
            if (n.name().equals(ExampleTwoBoneIkScene.MIDDLE_BONE))
                return new ModelNode(n.index(), n.name(), n.localTransform(), List.of(), n.meshIndex(), n.skinIndex(), n.cameraOrLightIgnored());
            return n;
        }).toList();
        for (var nodes : List.of(renamed, ambiguous, reparented)) {
            var transforms = new LinkedHashMap<Integer, Transform>();
            nodes.forEach(n -> transforms.put(n.index(), n.localTransform()));
            var pose = new LocalPose(transforms);
            var messages = new ArrayList<String>();
            var pipeline = ExampleTwoBoneIkScene.procedural(messages::add);
            var rig = ClientAnimationRigView.fromNodes(nodes);
            var context = new ClientAnimationPoseContext(BlendInstanceKey.entity("ik-fallback", 1), ExampleTwoBoneIkScene.MODEL,
                    asset.generation(), ExampleTwoBoneIkScene.IDLE, 0, 0, rig);
            require(pipeline.modify(context, pose) == pose && pipeline.modify(context, pose) == pose,
                    "incompatible resource-pack chain returns exact sampled pose");
            require(messages.size() == 1 && messages.getFirst().contains("unique direct chain"),
                    "bad chain gets one explicit bounded generation diagnostic");
            var next = new ClientAnimationPoseContext(context.instanceKey(), context.modelKey(), context.generation()+1,
                    context.animationKey(), 0, 0, rig);
            pipeline.modify(next, pose);
            require(messages.size() == 2, "new resource generation can report a fresh incompatibility");
            var good = new ClientAnimationPoseContext(context.instanceKey(), context.modelKey(), context.generation()+2,
                    context.animationKey(), 0, 0, ClientAnimationRigView.fromNodes(original));
            require(pipeline.modify(good, pose) != pose, "restoring a valid resource chain resumes IK");
        }
    }
    private static ModelNode rename(ModelNode n, String name) {
        return new ModelNode(n.index(), name, n.localTransform(), n.children(), n.meshIndex(), n.skinIndex(), n.cameraOrLightIgnored());
    }

    private static void verifyOptIn() {
        String before = System.getProperty(ExampleTwoBoneIkScene.PROPERTY);
        try {
            System.clearProperty(ExampleTwoBoneIkScene.PROPERTY);
            require(!ExampleTwoBoneIkScene.enabled(), "IK example must be disabled by default");
            System.setProperty(ExampleTwoBoneIkScene.PROPERTY, "true");
            require(ExampleTwoBoneIkScene.enabled(), "client JVM property enables IK scene");
            System.setProperty(ExampleTwoBoneIkScene.PROPERTY, "false");
            require(!ExampleTwoBoneIkScene.enabled(), "false preserves existing actor scene");
        } finally {
            if (before == null) System.clearProperty(ExampleTwoBoneIkScene.PROPERTY);
            else System.setProperty(ExampleTwoBoneIkScene.PROPERTY, before);
        }
    }

    private static void publish(ClientModelRegistry registry, long generation, boolean transformedResourcePack) {
        Map<BlendModelKey, ModelHandle> handles = new LinkedHashMap<>();
        for (String name : List.of("mechanical_arm", "ik_target_marker", "ik_end_marker")) {
            var key = BlendModelKey.parse(NS + name);
            var asset = new ModelAssetLoader().load(key.resourceId(), generation,
                    resource(BlendResourceId.parse(NS + "blend_models/" + name + ".json"), transformedResourcePack),
                    id -> resource(id, transformedResourcePack));
            var handle = asset.skeleton() == null ? StaticRigidRenderHandle.prepare(key, asset) : SkinnedRenderHandle.prepare(key, asset);
            handles.put(key, new LoadedModelHandle(key, asset, handle));
        }
        var png = bytes(BlendResourceId.parse(NS + "textures/mechanical_arm.png")).copy();
        try {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            require(image != null && image.getWidth() == 16 && image.getHeight() == 4,
                    "packaged atlas decodes as the actual authored 16x4 PNG");
            for (int y = 0; y < 4; y++) for (int x = 0; x < 16; x++)
                require((image.getRGB(x, y) >>> 24) == 255, "mechanical atlas remains fully opaque");
        } catch (IOException exception) { throw new AssertionError("Cannot decode packaged mechanical atlas", exception); }
        registry.publish(new ModelRegistryGeneration(generation, handles, Map.of(), List.of()));
    }

    /** Resource-pack overrides modify the packaged bytes before the same strict production loader. */
    private static AssetBytes resource(BlendResourceId id, boolean transformed) {
        var original = bytes(id);
        if (!transformed) return original;
        if (id.value().equals(NS + "blend_models/mechanical_arm.json")) {
            var descriptor = com.google.gson.JsonParser.parseString(new String(original.copy(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            descriptor.addProperty("units_per_block", 2.5);
            return new AssetBytes(id, descriptor.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!id.value().equals(NS + "models3d/mechanical_arm.glb")) return original;
        byte[] source = original.copy();
        var sourceBuffer = java.nio.ByteBuffer.wrap(source).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int oldLength = sourceBuffer.getInt(12);
        var gltf = com.google.gson.JsonParser.parseString(new String(source, 20, oldLength, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        var scene = gltf.getAsJsonArray("nodes").get(0).getAsJsonObject();
        require(scene.get("name").getAsString().equals("ArmScene"), "override must modify the actual origin node");
        scene.add("translation", com.google.gson.JsonParser.parseString("[0.25,0.1,-0.2]"));
        var q = new Quaternion(.11F, .17F, -.08F, .97F).normalized();
        scene.add("rotation", com.google.gson.JsonParser.parseString("["+q.x()+","+q.y()+","+q.z()+","+q.w()+"]"));
        scene.add("scale", com.google.gson.JsonParser.parseString("[1.2,1.2,1.2]"));
        byte[] json = gltf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int paddedLength = (json.length + 3) & ~3;
        int totalLength = source.length - oldLength + paddedLength;
        var rewritten = java.nio.ByteBuffer.allocate(totalLength).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        rewritten.putInt(0x46546C67).putInt(2).putInt(totalLength).putInt(paddedLength).putInt(0x4E4F534A).put(json);
        while (rewritten.position() < 20 + paddedLength) rewritten.put((byte) ' ');
        rewritten.put(source, 20 + oldLength, source.length - 20 - oldLength);
        return new AssetBytes(id, rewritten.array());
    }

    private static ClientModelLookup lookup(ClientModelRegistry models) {
        return new ClientModelLookup() {
            public ClientRegistryView snapshot() {
                var map = new LinkedHashMap<BlendModelKey, ClientModelView>();
                models.current().handles().keySet().forEach(key -> map.put(key, resolve(key)));
                return new ClientRegistryView(models.current().generationId(), map, List.of());
            }
            public ClientModelView resolve(BlendModelKey key) {
                var generation = models.current(); var found = generation.find(key);
                var handle = found.orElseGet(() -> MissingModelHandle.notDiscovered(key, generation.generationId()));
                return new ClientModelView(key, generation.generationId(), found.isPresent(), handle.renderHandle(), Optional.empty());
            }
        };
    }

    private static AssetBytes bytes(BlendResourceId id) {
        String path = "/assets/" + id.value().replace(':', '/');
        try (var stream = RunnableTwoBoneIkVerification.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing packaged IK resource: " + path);
            return new AssetBytes(id, stream.readAllBytes());
        } catch (IOException exception) { throw new IllegalStateException(path, exception); }
    }
    private static boolean inside(Vec3 p, BlendEntityCullingEnvelope e) {
        return p.x() >= e.minX() && p.x() <= e.maxX() && p.y() >= e.minY() && p.y() <= e.maxY()
                && p.z() >= e.minZ() && p.z() <= e.maxZ();
    }
    private static double distance(BlendEntitySocketPose pose, Vec3 p) {
        return Math.sqrt(Math.pow(pose.x()-p.x(), 2) + Math.pow(pose.y()-p.y(), 2) + Math.pow(pose.z()-p.z(), 2));
    }
    private static BlendEntitySocketPose compose(BlendEntitySocketPose parent, BlendEntitySocketPose child) {
        var q = parent.rotation();
        var rotated = new Quaternion(q.x(), q.y(), q.z(), q.w()).rotate(
                new Vec3((float) child.x()*parent.scale(), (float) child.y()*parent.scale(), (float) child.z()*parent.scale()));
        return new BlendEntitySocketPose(parent.x()+rotated.x(), parent.y()+rotated.y(), parent.z()+rotated.z(),
                parent.rotation(), parent.scale()*child.scale());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
