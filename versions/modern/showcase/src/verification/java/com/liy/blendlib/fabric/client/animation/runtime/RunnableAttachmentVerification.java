package com.liy.blendlib.fabric.client.animation.runtime;

import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.*;
import com.liy.blendlib.examples.runnable.*;
import com.liy.blendlib.fabric.client.animation.*;
import com.liy.blendlib.fabric.client.animation.extract.*;
import com.liy.blendlib.fabric.client.api.*;
import com.liy.blendlib.fabric.client.entity.*;
import com.liy.blendlib.fabric.client.reload.*;
import com.liy.blendlib.fabric.client.render.*;
import java.io.IOException;
import java.util.*;

/** Runs the shipped consumer assembly with packaged resources and real CPU extraction, without Minecraft. */
final class RunnableAttachmentVerification {
    private static final String NS = "blendlib_runnable_examples:";
    private static final BlendModelKey ACTOR = key("appearance_actor"), MARKER = key("marker"), WAND = key("wand");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse(NS + "walk");
    private static final BlendResourceId TIP = BlendResourceId.parse(NS + "tip");
    private static final int LIGHT = 0x00f000f0, OVERLAY = 0x00123456;
    private record EqualActor(int id) { }

    static void verify() {
        verifyBounds();
        verifyExtendedAssembly();
        var h = new Harness();
        h.publish(1, true, true);
        h.runtime.onPlayInit();
        var owners = new ExampleAttachmentOwners();
        var owner = new EqualActor(7);
        var rootKey = h.runtime.entityKey(7);
        var childKey = owners.key(owner, "session-a", h.runtime::retire);
        var first = h.capture(rootKey, childKey, 0);
        var later = h.capture(rootKey, childKey, 8);
        var firstOrnament = ornament(first);
        var laterOrnament = ornament(later);
        require(!positions(firstOrnament).equals(positions(laterOrnament)), "ornament must really animate CPU-skinned vertices");
        require(h.lifecycle.registry().find(rootKey).orElseThrow().modelKey().equals(ACTOR),
                "child extraction must never rebind the actor owner to wand");
        require(h.lifecycle.registry().find(childKey).orElseThrow().modelKey().equals(WAND), "ornament has a distinct real runtime owner");
        require(h.lifecycle.registry().size() == 2, "only root and animated ornament own clocks; rigid weapon owns no clock");
        require(owners.key(owner, "session-a", h.runtime::retire).equals(childKey), "same actual actor retains child clock");
        var equalOwner = new EqualActor(7);
        var otherKey = owners.key(equalOwner, "session-a", h.runtime::retire);
        require(!otherKey.equals(childKey), "equal numeric IDs do not share ornament owners");
        h.capture(rootKey, otherKey, 8);
        owners.remove(owner, h.runtime::retire);
        require(h.lifecycle.registry().find(childKey).isEmpty() && h.lifecycle.registry().find(otherKey).isPresent(),
                "actor unload retires only its identity-owned ornament");
        var switched = owners.key(owner, "session-b", h.runtime::retire);
        require(h.lifecycle.registry().find(otherKey).isEmpty() && !switched.equals(otherKey), "session switch retires old child clocks");
        h.capture(rootKey, switched, 9);
        var retained = later;
        h.publish(2, true, true);
        var reloaded = h.capture(rootKey, switched, 10);
        require(reloaded.generation() == 2 && ornament(reloaded).generation() == 2, "reload captures fresh root and children");
        require(retained.generation() == 1 && ornament(retained).generation() == 1, "retained frames remain immutable across reload");
        var stale = BlendEntityAttachmentComposition.capture(reloaded.withAttachments(retained.attachments()));
        require(stale.attachments().isEmpty() && stale.diagnostics().size() == 1
                && stale.diagnostics().getFirst().reason() == BlendEntityAttachmentComposition.Reason.STALE_GENERATION,
                "stale weapon omits its whole subtree at composition");
        // A stale root socket must not let capture bind a current-generation child to an old root.
        var staleSockets = new BlendEntitySockets(1, h.lastSockets.sockets());
        require(ExampleAttachmentScene.capture(h.lookup, h.runtime, switched, request(11), staleSockets, OVERLAY).isEmpty(),
                "capture rejects generation mismatch");
        require(h.lifecycle.registry().find(switched).isEmpty(), "generation mismatch retires ornament");
        h.capture(rootKey, switched, 12);
        h.publish(3, true, false);
        var noOrnament = h.capture(rootKey, switched, 13);
        require(noOrnament.attachments().size() == 1 && noOrnament.attachments().getFirst().snapshot().attachments().isEmpty(),
                "missing ornament preserves rigid weapon without stale child");
        require(h.lifecycle.registry().find(switched).isEmpty(), "missing ornament retires its playback");
        h.publish(4, true, true);
        h.capture(rootKey, switched, 14);
        h.publish(5, false, true);
        require(h.capture(rootKey, switched, 15).attachments().isEmpty(), "missing weapon omits entire branch");
        require(h.lifecycle.registry().find(switched).isEmpty(), "missing weapon retires ornament");
        h.publish(6, true, true);
        h.capture(rootKey, switched, 16);
        require(ExampleAttachmentScene.capture(h.lookup, h.runtime, switched, request(17),
                new BlendEntitySockets(6, Map.of()), OVERLAY).isEmpty(), "missing tip omits branch");
        require(h.lifecycle.registry().find(switched).isEmpty(), "missing tip retains no child clock");
        h.capture(rootKey, switched, 18);
        owners.clear(h.runtime::retire);
        require(h.lifecycle.registry().find(switched).isEmpty(), "disconnect owner clear retires ornament");
        h.runtime.onWorldDisconnect();
        require(h.lifecycle.registry().size() == 0, "disconnect leaves no root or child runtime state");
        System.out.println("Verified shipped three-level attachment capture, real animated CPU ornament, owner identity, unload/session/disconnect, reload and missing-resource retirement, and all-pose culling envelope");
    }

    private static final class Harness {
        final ClientModelRegistry models = new ClientModelRegistry();
        final ClientAnimationLifecycleBridge lifecycle = new ClientAnimationLifecycleBridge(64);
        final SkinnedAnimationRuntime runtime = new SkinnedAnimationRuntime(models, lifecycle);
        BlendEntitySockets lastSockets;
        // Ordinary public read-only consumer lookup, backed by the same published registry as the runtime.
        final ClientModelLookup lookup = new ClientModelLookup() {
            public ClientRegistryView snapshot() {
                var views = new LinkedHashMap<BlendModelKey, ClientModelView>();
                models.current().handles().keySet().forEach(key -> views.put(key, resolve(key)));
                return new ClientRegistryView(models.current().generationId(), views, List.of());
            }
            public ClientModelView resolve(BlendModelKey key) {
                var generation = models.current();
                var found = generation.find(key);
                var handle = found.orElseGet(() -> MissingModelHandle.notDiscovered(key, generation.generationId()));
                return new ClientModelView(key, generation.generationId(), found.isPresent(), handle.renderHandle(), Optional.empty());
            }
        };
        void publish(long generation, boolean weapon, boolean ornament) {
            var loaded = new LinkedHashMap<BlendModelKey, ModelHandle>();
            for (String name : List.of("actor", "appearance_actor", "marker", "wand")) {
                if (name.equals("marker") && !weapon || name.equals("wand") && !ornament) continue;
                var asset = load(name, generation);
                var model = key(name);
                ModelRenderHandle handle = asset.skeleton() == null
                        ? StaticRigidRenderHandle.prepare(model, asset) : SkinnedRenderHandle.prepare(model, asset);
                loaded.put(model, new LoadedModelHandle(model, asset, handle));
            }
            models.publish(new ModelRegistryGeneration(generation, loaded, Map.of(), List.of()));
            runtime.onActiveGeneration(generation);
        }
        ModelRenderSnapshot capture(BlendInstanceKey root, BlendInstanceKey.Ephemeral child, long tick) {
            return capture(root, child, tick, ExampleAttachmentScene.Mode.DEFAULT, BlendEntityRotation.IDENTITY, 0, 0, 0);
        }
        ModelRenderSnapshot capture(BlendInstanceKey root, BlendInstanceKey.Ephemeral child, long tick,
                ExampleAttachmentScene.Mode mode, BlendEntityRotation rotation, double x, double y, double z) {
            var request = new BlendEntitySnapshotRequest(ACTOR, 0, LIGHT, tick, x, y, z, tick, true, 1);
            var transform = new Transform(Vec3.ZERO, new Quaternion(rotation.x(), rotation.y(), rotation.z(), rotation.w()), Vec3.ONE);
            var handle = lookup.resolve(ACTOR).renderHandle();
            var input = new SkinnedAnimationRuntimeInput(ACTOR, root, tick, 0, WALK, Optional.empty(),
                    AnimationUpdateBucket.VISIBLE_NEAR, new SkinnedExtractionRequest(transform, LIGHT, OVERLAY,
                    -1, RenderVisibility.VISIBLE, new CullingMetadata(handle.bounds(), true)));
            var frame = runtime.extractLayered(input, ExampleAnimationScene.layers(), List.of(),
                    ExampleAnimationScene.clipLayerWeights(tick), ExampleAnimationScene.procedural())
                    .orElseThrow().frame();
            lastSockets = BlendEntitySockets.capture(request, frame);
            var children = ExampleAttachmentScene.capture(lookup, runtime, child, request, lastSockets, OVERLAY, mode);
            var rootSnapshot = frame.renderSnapshot().withMaterialAppearance(ExampleMaterialAppearance.forName("Orange"))
                    .withAttachments(children);
            if (lookup.resolve(MARKER).missing()) return rootSnapshot;
            require(children.size() == 1, "root has exactly one weapon");
            var weapon = children.getFirst().snapshot();
            require(weapon.handle() instanceof StaticRigidRenderHandle && !RunnableAttachmentRenderVerification.skinned(weapon),
                    "weapon is real packaged rigid geometry");
            require(children.getFirst().placement().equals(lastSockets.socket(TIP).orElseThrow().attachmentPlacement()),
                    "weapon consumes actual post-procedural root socket");
            require(weapon.packedLight() == LIGHT && weapon.packedOverlay() == OVERLAY, "weapon captures lighting and overlay");
            if (!lookup.resolve(WAND).missing()) {
                var ornament = ornament(rootSnapshot);
                require(ornament.handle() instanceof SkinnedRenderHandle && RunnableAttachmentRenderVerification.skinned(ornament),
                        "third level is a real CPU-skinned frame");
                require(ornament.packedLight() == LIGHT && ornament.packedOverlay() == OVERLAY, "ornament captures lighting and overlay");
                require(RunnableAttachmentRenderVerification.appearance(ornament, 0).rgbTint() == 0x40FFFF
                        && RunnableAttachmentRenderVerification.appearance(ornament, 0).visible(), "ornament captures cyan material appearance");
                require(RunnableAttachmentRenderVerification.appearance(rootSnapshot, 0).rgbTint() == 0xff8844,
                        "live appearance actor retains its orange material selection");
                var composition = BlendEntityAttachmentComposition.capture(rootSnapshot);
                require(composition.attachments().size() == 2 && composition.diagnostics().isEmpty(), "three levels flatten into two valid child draws");
                require(composition.attachments().stream().allMatch(a -> a.snapshot().generation() == rootSnapshot.generation()),
                        "all composed levels have the current root generation");
            }
            return rootSnapshot;
        }
    }

    private static void verifyBounds() {
        var marker = load("marker", 1);
        var wand = load("wand", 1);
        require(marker.nodes().stream().allMatch(n -> n.localTransform().equals(Transform.IDENTITY)),
                "static marker roots and mesh nodes must be authored identity");
        require(marker.unitsPerBlock() == 1 && wand.unitsPerBlock() == 2.5, "authored unit scales are part of bounds proof");
        var markerPositions = marker.primitives().getFirst().geometry().positions();
        var expectedPositions = new float[] {-.5F, 0, 2, .5F, 0, 0, 0, 1, 0};
        require(markerPositions.length == expectedPositions.length, "marker has three authored vertices");
        for (int i = 0; i < markerPositions.length; i++)
            require(markerPositions[i] == expectedPositions[i], "bounds proof uses actual marker vertex coordinate " + i);
        require(ExampleAttachmentScene.WEAPON_OFFSET.equals(new BlendEntitySocketPose(0, .20, 0, BlendEntityRotation.IDENTITY, .25F))
                && ExampleAttachmentScene.ORNAMENT_OFFSET.equals(new BlendEntitySocketPose(0, 0, 0, BlendEntityRotation.IDENTITY, 1.5F))
                && ExampleAttachmentScene.MOUNT_TRANSFORM.equals(new Transform(new Vec3(0, .5F, 0), Quaternion.IDENTITY, Vec3.ONE)),
                "authored assembly offsets and scales must match the conservative proof");
        for (String name : List.of("actor", "appearance_actor")) {
            var actor = load(name, 1);
            // All rotations, including arbitrary procedural tip rotation, preserve radius. Translation
            // interpolation/cross-fades stay within these authored extrema; scales are fixed identity.
            double tipRadius = 0;
            for (var node : actor.nodes()) {
                require(node.localTransform().scale().equals(Vec3.ONE), "unit node scale required by envelope proof");
                double translation = norm(node.localTransform().translation());
                for (var clip : actor.clips()) for (var channel : clip.channels()) {
                    if (channel.targetNode() != node.index()) continue;
                    if (channel.path() == AnimationPath.SCALE)
                        for (float scale : channel.values()) require(scale == 1F, "actor scale keys stay identity in envelope proof");
                    if (channel.path() == AnimationPath.TRANSLATION) {
                        var values = channel.values();
                        for (int i = 0; i < values.length; i += 3)
                            translation = Math.max(translation, Math.sqrt(values[i]* (double)values[i]
                                    + values[i+1]*(double)values[i+1] + values[i+2]*(double)values[i+2]));
                    }
                }
                // Sum over all nodes is conservative even for nodes off the tip's ancestor path.
                tipRadius += translation;
            }
            require(Math.abs(tipRadius - .670000024140) < 1e-6, "actual root sway and tip translation envelope");
            double weaponRadius = 0;
            var p = marker.primitives().getFirst().geometry().positions();
            for (int i = 0; i < p.length; i += 3)
                weaponRadius = Math.max(weaponRadius, Math.sqrt(Math.pow(.25*p[i], 2)
                        + Math.pow(.20 + .25*p[i+1], 2) + Math.pow(.25*p[i+2], 2)));
            double wandRadius = skinRadius(wand);
            require(Math.abs(wandRadius - 1.307808796846) < 1e-6, "derived all-clip wand influence radius");
            double weaponEnvelope = (tipRadius + weaponRadius) * 1.01 + .0001;
            double ornamentEnvelope = (tipRadius + .20 + .25*(.5 + 1.5/wand.unitsPerBlock()*wandRadius))*1.01 + .0001;
            double half = Math.min(Math.min(actor.bounds().max().x(), actor.bounds().max().y()), actor.bounds().max().z());
            half = Math.min(half, Math.min(Math.min(-actor.bounds().min().x(), -actor.bounds().min().y()), -actor.bounds().min().z()));
            require(half > weaponEnvelope && half > ornamentEnvelope,
                    "existing " + name + " culling bounds enclose every default weapon/ornament pose, with float margin");
            var extended = ExampleAttachmentScene.Mode.EXTENDED.weaponOffset();
            double extendedWeaponRadius = 0;
            for (int i = 0; i < p.length; i += 3)
                extendedWeaponRadius = Math.max(extendedWeaponRadius,
                        Math.sqrt(Math.pow(extended.x() + extended.scale()*p[i], 2)
                                + Math.pow(extended.y() + extended.scale()*p[i+1], 2)
                                + Math.pow(extended.z() + extended.scale()*p[i+2], 2)));
            double offsetRadius = Math.sqrt(extended.x()*extended.x() + extended.y()*extended.y() + extended.z()*extended.z());
            double extendedOrnamentRadius = tipRadius + offsetRadius
                    + extended.scale()*(.5 + ExampleAttachmentScene.ORNAMENT_OFFSET.scale()/wand.unitsPerBlock()*wandRadius);
            var envelope = ExampleAttachmentScene.Mode.EXTENDED.cullingEnvelope().orElseThrow();
            double envelopeHalf = Math.min(Math.min(Math.min(-envelope.minX(), -envelope.minY()), -envelope.minZ()),
                    Math.min(Math.min(envelope.maxX(), envelope.maxY()), envelope.maxZ()));
            require((tipRadius + extendedWeaponRadius)*1.01 + .0001 < envelopeHalf
                    && extendedOrnamentRadius*1.01 + .0001 < envelopeHalf,
                    "configured extended assembly envelope covers all authored translation extrema and arbitrary socket rotations");
            require(extended.x() == 3 && extended.y() == .20 && extended.z() == 0 && extended.scale() == .25F
                    && extended.rotation().equals(BlendEntityRotation.IDENTITY), "extended proof matches the actual fixed offset");
        }
    }
    private static void verifyExtendedAssembly() {
        String property = ExampleAttachmentScene.EXTENDED_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            require(ExampleAttachmentScene.configuredMode() == ExampleAttachmentScene.Mode.DEFAULT, "extended mode is opt-in");
            System.setProperty(property, "true");
            require(ExampleAttachmentScene.configuredMode() == ExampleAttachmentScene.Mode.EXTENDED, "client JVM property selects extended mode");
            System.setProperty(property, "false");
            require(ExampleAttachmentScene.configuredMode() == ExampleAttachmentScene.Mode.DEFAULT, "false preserves default mode");
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
        require(ExampleAttachmentScene.Mode.DEFAULT.cullingEnvelope().isEmpty(), "default showcase keeps original culling behavior");
        var envelope = ExampleAttachmentScene.Mode.EXTENDED.cullingEnvelope().orElseThrow();
        var h = new Harness();
        h.publish(1, true, true);
        h.runtime.onPlayInit();
        var owners = new ExampleAttachmentOwners();
        var actors = List.of(new EqualActor(21), new EqualActor(22));
        var children = actors.stream().map(a -> owners.key(a, "extended", h.runtime::retire)).toList();
        var roots = actors.stream().map(a -> h.runtime.entityKey(a.id())).toList();
        var rotations = List.of(BlendEntityRotation.IDENTITY,
                BlendEntityRotation.normalized(0, 1, 0, 1),
                BlendEntityRotation.normalized(0, 1, 0, .01F),
                BlendEntityRotation.normalized(.2F, -.3F, .4F, .5F));
        double[][] origins = {{11.125, 67.5, -23.25}, {-34.875, 101.25, 29.75}};
        ModelRenderSnapshot retained = null;
        List<RunnableAttachmentRenderVerification.Position> retainedVertices = List.of();
        boolean exceededOldBounds = false;
        int checkedVertices = 0;
        for (int generation = 1; generation <= 2; generation++) {
            if (generation == 2) h.publish(2, true, true);
            for (int actor = 0; actor < actors.size(); actor++) {
                var o = origins[actor];
                long tick = 200L * generation;
                for (var rotation : rotations) for (int sample = 0; sample < 5; sample++) {
                    tick += 9;
                    var frame = h.capture(roots.get(actor), children.get(actor), tick,
                            ExampleAttachmentScene.Mode.EXTENDED, rotation, o[0], o[1], o[2]);
                    require(frame.generation() == generation && ornament(frame).generation() == generation,
                            "extended assembly captures the current generation for every actor");
                    boolean arbitraryRootRotation = rotation.x() != 0 || rotation.z() != 0;
                    var expanded = RunnableAssemblyCullingVerification.bounds(h.lookup, ACTOR, o[0], o[1], o[2], arbitraryRootRotation, envelope);
                    var prior = RunnableAssemblyCullingVerification.bounds(h.lookup, ACTOR, o[0], o[1], o[2], arbitraryRootRotation, null);
                    var positions = RunnableAttachmentRenderVerification.entityPositions(frame);
                    require(!positions.isEmpty(), "extended scene has actual transformed triangle vertices");
                    var undoRootRotation = new Quaternion(-rotation.x(), -rotation.y(), -rotation.z(), rotation.w());
                    for (var p : positions) {
                        var unrotated = undoRootRotation.rotate(new Vec3((float)p.x(), (float)p.y(), (float)p.z()));
                        require(unrotated.x() >= envelope.minX() && unrotated.x() <= envelope.maxX()
                                && unrotated.y() >= envelope.minY() && unrotated.y() <= envelope.maxY()
                                && unrotated.z() >= envelope.minZ() && unrotated.z() <= envelope.maxZ(),
                                "actual prepared vertices fit the configured pre-root-rotation box itself");
                        double x = o[0]+p.x(), y = o[1]+p.y(), z = o[2]+p.z();
                        require(contains(expanded, x, y, z), "real rotated, translated attachment vertex lies in the culling-entry envelope");
                        exceededOldBounds |= !contains(prior, x, y, z);
                        checkedVertices++;
                    }
                    // A bounded result still rejects distant regions; this is not a global always-render box.
                    require(!expanded.intersects(new net.minecraft.world.phys.AABB(o[0]+100, o[1]+100, o[2]+100,
                            o[0]+101, o[1]+101, o[2]+101)), "explicit envelope remains spatially bounded");
                    if (retained == null) {
                        retained = frame;
                        retainedVertices = positions;
                        var regular = h.capture(roots.get(actor), children.get(actor), tick,
                                ExampleAttachmentScene.Mode.DEFAULT, rotation, o[0], o[1], o[2]);
                        require(!positions.equals(RunnableAttachmentRenderVerification.entityPositions(regular)),
                                "extended selection actually moves real geometry, not just culling metadata");
                        require(RunnableAttachmentRenderVerification.entityPositions(
                                RunnableAttachmentRenderVerification.hidden(frame)).isEmpty(),
                                "expanded culling never overrides whole-root visibility");
                        var weapon = frame.attachments().getFirst();
                        var hiddenWeapon = new BlendEntityAttachment(weapon.placement(), weapon.offset(),
                                RunnableAttachmentRenderVerification.hidden(weapon.snapshot()));
                        var hiddenBranch = frame.withAttachments(List.of(hiddenWeapon));
                        require(RunnableAttachmentRenderVerification.entityPositions(hiddenBranch).equals(
                                RunnableAttachmentRenderVerification.entityPositions(frame.withAttachments(List.of()))),
                                "hidden weapon still suppresses itself and its skinned ornament");
                    }
                }
            }
        }
        require(exceededOldBounds, "extended prepared triangles must really escape the old root-only culling envelope");
        require(retained.generation() == 1 && ornament(retained).generation() == 1
                && retainedVertices.equals(RunnableAttachmentRenderVerification.entityPositions(retained)),
                "old-generation extended geometry and placements stay immutable after reload");
        require(!children.get(0).equals(children.get(1)) && h.lifecycle.registry().size() == 4,
                "two independently translated actors retain separate root and ornament runtime owners");
        owners.clear(h.runtime::retire);
        h.runtime.onWorldDisconnect();
        require(h.lifecycle.registry().size() == 0, "extended assembly leaves no state after disconnect");
        System.out.println("Verified extended assembly: " + checkedVertices
                + " actual prepared vertices, yaw/arbitrary rotation, two world positions, reload, bounded culling union and hidden subtree suppression");
    }

    private static boolean contains(net.minecraft.world.phys.AABB bounds, double x, double y, double z) {
        double epsilon = 1e-5;
        return x >= bounds.minX-epsilon && x <= bounds.maxX+epsilon && y >= bounds.minY-epsilon
                && y <= bounds.maxY+epsilon && z >= bounds.minZ-epsilon && z <= bounds.maxZ+epsilon;
    }

    /** Independent influence-radius proof from actual keys, hierarchy, skin weights and inverse binds. */
    private static double skinRadius(ModelAsset asset) {
        var local = new HashMap<Integer, Double>();
        var parent = new HashMap<Integer, Integer>();
        for (var node : asset.nodes()) {
            require(node.localTransform().scale().equals(Vec3.ONE), "wand has unit scales");
            local.put(node.index(), norm(node.localTransform().translation()));
            node.children().forEach(child -> parent.put(child, node.index()));
        }
        for (var clip : asset.clips()) for (var channel : clip.channels()) {
            if (channel.path() == AnimationPath.SCALE)
                for (float scale : channel.values()) require(scale == 1F, "wand scale keys stay identity in envelope proof");
            if (channel.path() == AnimationPath.TRANSLATION) {
                var values = channel.values();
                for (int i=0; i<values.length; i+=3) {
                    double radius = Math.sqrt(values[i]*(double)values[i] + values[i+1]*(double)values[i+1] + values[i+2]*(double)values[i+2]);
                    local.merge(channel.targetNode(), radius, Math::max);
                }
            }
        }
        double result = 0;
        for (var primitive : asset.primitives()) {
            var geometry = primitive.geometry();
            var skin = asset.skeleton().skins().get(asset.nodes().stream()
                    .filter(node -> node.index() == primitive.nodeIndex()).findFirst().orElseThrow().skinIndex());
            var p = geometry.positions(); var joints = geometry.joints(); var weights = geometry.weights();
            for (int vertex=0; vertex<geometry.vertexCount(); vertex++) for (int influence=0; influence<4; influence++) {
                int offset = vertex*4+influence;
                if (weights[offset] <= 0) continue;
                int slot = joints[offset];
                double chain = 0;
                Integer node = skin.joints().get(slot);
                while (node != null) { chain += local.get(node); node = parent.get(node); }
                var m = skin.inverseBindMatrix(slot);
                double x=p[vertex*3], y=p[vertex*3+1], z=p[vertex*3+2];
                double bx=m.get(0,0)*x+m.get(1,0)*y+m.get(2,0)*z+m.get(3,0);
                double by=m.get(0,1)*x+m.get(1,1)*y+m.get(2,1)*z+m.get(3,1);
                double bz=m.get(0,2)*x+m.get(1,2)*y+m.get(2,2)*z+m.get(3,2);
                result=Math.max(result, chain+Math.sqrt(bx*bx+by*by+bz*bz));
            }
        }
        return result;
    }
    private static double norm(Vec3 p) { return Math.sqrt(p.x()*(double)p.x() + p.y()*(double)p.y() + p.z()*(double)p.z()); }
    private static ModelRenderSnapshot ornament(ModelRenderSnapshot root) {
        var weapon = root.attachments().getFirst().snapshot();
        require(weapon.attachments().size() == 1, "weapon has exactly one ornament");
        return weapon.attachments().getFirst().snapshot();
    }
    private static List<Vec3> positions(ModelRenderSnapshot snapshot) {
        return RunnableAttachmentRenderVerification.positions(snapshot);
    }
    private static BlendEntitySnapshotRequest request(long tick) {
        return new BlendEntitySnapshotRequest(ACTOR, 0, LIGHT, tick, 0, 0, 0, tick, true, 1);
    }
    private static BlendModelKey key(String name) { return BlendModelKey.parse(NS + name); }
    private static ModelAsset load(String name, long generation) {
        return new ModelAssetLoader().load(BlendResourceId.parse(NS + name), generation,
                bytes(BlendResourceId.parse(NS + "blend_models/" + name + ".json")), RunnableAttachmentVerification::bytes);
    }
    private static AssetBytes bytes(BlendResourceId id) {
        String path = "/assets/" + id.value().replace(':', '/');
        try (var stream = RunnableAttachmentVerification.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing packaged asset " + path);
            return new AssetBytes(id, stream.readAllBytes());
        } catch (IOException e) { throw new IllegalStateException(path, e); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
