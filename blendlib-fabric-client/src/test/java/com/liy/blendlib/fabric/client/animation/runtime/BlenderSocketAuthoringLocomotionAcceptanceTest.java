package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.animation.runtime.SocketWorldTransform;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.json.JsonString;
import com.liy.blendlib.core.json.JsonValue;
import com.liy.blendlib.core.json.StrictJsonParser;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Real Blender 5.1.2 socket editor -> strict descriptor/GLB -> Java pose acceptance.
 * Expected poses are evaluated Blender world matrices, not a test-built GLB or Java sampler.
 * Locomotion in the name selects the existing official Minecraft 26.3 test source set;
 * the rigid fixture also preserves the previously authored locomotion and event sections.
 */
class BlenderSocketAuthoringLocomotionAcceptanceTest {
    private static final BlendResourceId HELD_ITEM = BlendResourceId.parse("blendlib_sockets:held_item");
    private static final BlendResourceId HAND_BONE = BlendResourceId.parse("blendlib_sockets:hand_bone");
    private static final BlendResourceId HAND_OBJECT = BlendResourceId.parse("blendlib_sockets:hand_object");
    private static final double EPSILON = 1.0e-5;

    @Test
    void genuineExportsLoadAsStrictRigidAndSkinnedAssetsWithReadableTexturesAndNodeOnlySockets() throws IOException {
        for (String fixture : new String[] {"rigid", "skinned"}) {
            ModelAsset asset = load(fixture, 1);
            assertEquals(fixture.equals("rigid") ? ModelProfile.RIGID_V1 : ModelProfile.SKINNED_V1, asset.profile());
            assertFalse(asset.nodes().isEmpty());
            assertFalse(asset.primitives().isEmpty());
            assertFalse(asset.clips().isEmpty());
            assertEquals(1, asset.unitsPerBlock(), EPSILON);
            assertEquals(1, asset.generation());
            var expected = expected(fixture);
            var descriptor = json(resourcePath(model(fixture).descriptorResourceId()));
            var declared = object(descriptor.get("sockets"));
            assertEquals(object(expected.get("sockets")).values().keySet(), declared.values().keySet());
            assertEquals(declared.size(), asset.sockets().entries().size());
            for (var entry : declared.values().entrySet()) {
                var socket = asset.sockets().get(BlendResourceId.parse(entry.getKey()));
                assertNotNull(socket);
                assertEquals(Set.of("node"), object(entry.getValue()).values().keySet(),
                        "Offsets must live on transformed helper nodes, never in strict socket JSON");
                assertEquals(string(object(object(expected.get("sockets")).get(entry.getKey())).get("node")), socket.nodePath());
                assertEquals(string(object(entry.getValue()).get("node")), socket.nodePath());
                assertEquals(socket.nodePath(), nodePath(asset, socket.nodeIndex()));
            }
            for (var material : asset.materials().values()) {
                var image = ImageIO.read(resourcePath(material.baseColor()).toFile());
                assertNotNull(image);
                assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
            }
        }
    }

    @Test
    void rigidSocketMatchesBlenderEvaluatedTranslationRotationAndScaleForEveryAuthoredClip() throws IOException {
        ModelAsset asset = load("rigid", 7);
        assertNull(asset.skeleton());
        assertEquals("Root/Pivot/Body/Grip", asset.sockets().get(HELD_ITEM).nodePath());
        assertEquals(Set.of(HELD_ITEM, BlendResourceId.parse("blendlib_authoring:hand")), asset.sockets().entries().keySet());
        assertBlenderSamples("rigid", asset, 15);

        // Independent hand-computed anchors make the oracle's axis, nested parent,
        // translation and positive uniform-scale conventions explicit.
        Transform start = query(asset, "blendlib_authoring:walk", 0, HELD_ITEM);
        assertVector(new Vec3(-1.25f, 3.5f, -1.25f), start.translation());
        assertVector(new Vec3(1.25f, 1.25f, 1.25f), start.scale());
        float half = (float) Math.sqrt(0.5);
        Quaternion expectedRotation = new Quaternion(0, half, 0, half)
                .multiply(new Quaternion(half, 0, 0, half))
                .multiply(new Quaternion(0, 0, -half, half));
        assertRotation(expectedRotation, start.rotation());
        Transform end = query(asset, "blendlib_authoring:walk", 1, HELD_ITEM);
        assertVector(new Vec3(-1.25f, 3.5f, -2.25f), end.translation());
        assertRotation(start.rotation(), end.rotation());
    }

    @Test
    void actualExportedArmatureBonePathResolvesToJointWhileObjectSocketRemainsDistinct() throws IOException {
        ModelAsset asset = load("skinned", 3);
        assertNotNull(asset.skeleton());
        assertEquals(Set.of(HAND_BONE, HAND_OBJECT), asset.sockets().entries().keySet());
        var bone = asset.sockets().get(HAND_BONE);
        var object = asset.sockets().get(HAND_OBJECT);
        assertNotEquals(bone.nodeIndex(), object.nodeIndex());
        assertNotEquals(bone.nodePath(), object.nodePath());
        assertEquals("Hand", node(asset, bone.nodeIndex()).name());
        assertEquals("ObjectHand", node(asset, object.nodeIndex()).name());
        var joints = new HashSet<Integer>();
        asset.skeleton().skins().forEach(skin -> joints.addAll(skin.joints()));
        assertTrue(joints.contains(bone.nodeIndex()));
        assertFalse(joints.contains(object.nodeIndex()));
        JsonObject expected = expected("skinned");
        var discovered = (JsonArray) expected.get("discovered_nodes");
        assertTrue(discovered.values().stream().map(BlenderSocketAuthoringLocomotionAcceptanceTest::object)
                .anyMatch(row -> string(row.get("kind")).equals("BONE")
                        && string(row.get("name")).equals("Hand")
                        && string(row.get("owner")).equals("Rig")
                        && string(row.get("path")).equals(bone.nodePath())));
        assertTrue(discovered.values().stream().map(BlenderSocketAuthoringLocomotionAcceptanceTest::object)
                .anyMatch(row -> string(row.get("kind")).equals("OBJECT")
                        && string(row.get("path")).equals(object.nodePath())));
        assertBlenderSamples("skinned", asset, 5);
        assertNotEquals(query(asset, "blendlib_sockets:wave", 0, HAND_BONE).translation(),
                query(asset, "blendlib_sockets:wave", 1, HAND_BONE).translation());
        assertTransform(query(asset, "blendlib_sockets:wave", 0, HAND_OBJECT),
                query(asset, "blendlib_sockets:wave", 1, HAND_OBJECT));
    }

    @Test
    void savedReopenedExportRetainsExactSocketPathsAcrossIndependentJavaAssetGenerations() throws IOException {
        for (String fixture : new String[] {"rigid", "skinned"}) {
            ModelAsset first = load(fixture, 41);
            ModelAsset reloaded = load(fixture, 42);
            assertNotSame(first, reloaded);
            assertEquals(41, first.generation());
            assertEquals(42, reloaded.generation());
            assertEquals(first.sockets().entries(), reloaded.sockets().entries());
            var definitions = AnimationControllerDefinition.fromModelAsset(reloaded);
            for (var state : definitions.states().keySet()) {
                for (var key : reloaded.sockets().entries().keySet()) {
                    assertTransform(query(first, state.value(), .5, key), query(reloaded, state.value(), .5, key));
                }
            }
            var state = definitions.state(definitions.initialState());
            var palette = NodePalette.fromCanonicalScene(PoseSampler.fromModelAsset(reloaded).sample(state, 0),
                    reloaded.nodes(), reloaded.defaultSceneRoots());
            assertTrue(SocketWorldTransform.query(reloaded, palette,
                    BlendResourceId.parse("blendlib_sockets:absent")).isEmpty());
        }
    }

    @Test
    void existingStrictJavaLoaderRejectsOffsetExtensionsAndMissingPathsOnActualExport() throws IOException {
        var model = model("rigid");
        String descriptor = Files.readString(resourcePath(model.descriptorResourceId()));
        String extraOffset = descriptor.replace("\"Root/Pivot/Body/Grip\"",
                "\"Root/Pivot/Body/Grip\", \"offset\": [1, 2, 3]");
        assertNotEquals(descriptor, extraOffset);
        assertThrows(BlendAssetLoadException.class, () -> new ModelAssetLoader().load(model.resourceId(),
                new AssetBytes(model.descriptorResourceId(), extraOffset.getBytes(StandardCharsets.UTF_8)),
                BlenderSocketAuthoringLocomotionAcceptanceTest::resource));
        String missingPath = descriptor.replace("Root/Pivot/Body/Grip", "Root/Pivot/Body/Missing");
        assertThrows(BlendAssetLoadException.class, () -> new ModelAssetLoader().load(model.resourceId(),
                new AssetBytes(model.descriptorResourceId(), missingPath.getBytes(StandardCharsets.UTF_8)),
                BlenderSocketAuthoringLocomotionAcceptanceTest::resource));
    }

    private static void assertBlenderSamples(String fixture, ModelAsset asset, int expectedCount) throws IOException {
        var samples = (JsonArray) expected(fixture).get("samples");
        assertEquals(expectedCount, samples.size());
        for (JsonValue value : samples.values()) {
            JsonObject sample = object(value);
            String state = string(sample.get("state"));
            double time = number(sample.get("time_seconds"));
            JsonObject sockets = object(sample.get("sockets"));
            assertEquals(asset.sockets().entries().size(), sockets.size());
            for (var entry : sockets.values().entrySet()) {
                Transform expected = transform(object(entry.getValue()));
                Transform actual = query(asset, state, time, BlendResourceId.parse(entry.getKey()));
                assertTransform(expected, actual);
            }
        }
    }

    private static Transform query(ModelAsset asset, String state, double time, BlendResourceId socket) {
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        var local = PoseSampler.fromModelAsset(asset).sample(definition.state(BlendAnimationKey.parse(state)), time);
        var palette = NodePalette.fromCanonicalScene(local, asset.nodes(), asset.defaultSceneRoots());
        return SocketWorldTransform.query(asset, palette, socket).orElseThrow();
    }

    private static Transform transform(JsonObject value) {
        var rotation = (JsonArray) value.get("rotation");
        return new Transform(vector((JsonArray) value.get("translation")),
                new Quaternion((float) number(rotation.get(0)), (float) number(rotation.get(1)),
                        (float) number(rotation.get(2)), (float) number(rotation.get(3))),
                vector((JsonArray) value.get("scale")));
    }

    private static Vec3 vector(JsonArray value) {
        return new Vec3((float) number(value.get(0)), (float) number(value.get(1)), (float) number(value.get(2)));
    }

    private static void assertTransform(Transform expected, Transform actual) {
        assertVector(expected.translation(), actual.translation());
        assertRotation(expected.rotation(), actual.rotation());
        assertVector(expected.scale(), actual.scale());
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON, "x component");
        assertEquals(expected.y(), actual.y(), EPSILON, "y component");
        assertEquals(expected.z(), actual.z(), EPSILON, "z component");
    }

    private static void assertRotation(Quaternion expected, Quaternion actual) {
        double dot = expected.x() * actual.x() + expected.y() * actual.y()
                + expected.z() * actual.z() + expected.w() * actual.w();
        assertEquals(1, Math.abs(dot), EPSILON, "q and -q must express the same normalized rotation");
    }

    private static ModelNode node(ModelAsset asset, int index) {
        return asset.nodes().stream().filter(node -> node.index() == index).findFirst().orElseThrow();
    }

    private static String nodePath(ModelAsset asset, int index) {
        ModelNode child = node(asset, index);
        var parents = asset.nodes().stream().filter(node -> node.children().contains(index)).toList();
        assertTrue(parents.size() <= 1);
        return parents.isEmpty() ? child.name() : nodePath(asset, parents.getFirst().index()) + "/" + child.name();
    }

    private static BlendModelKey model(String fixture) {
        return BlendModelKey.parse("blendlib_sockets:" + fixture);
    }

    private static ModelAsset load(String fixture, long generation) {
        BlendModelKey key = model(fixture);
        return new ModelAssetLoader().load(key.resourceId(), generation, resource(key.descriptorResourceId()),
                BlenderSocketAuthoringLocomotionAcceptanceTest::resource);
    }

    private static AssetBytes resource(BlendResourceId id) {
        try {
            return new AssetBytes(id, Files.readAllBytes(resourcePath(id)));
        } catch (IOException exception) {
            throw new UncheckedIOException("Missing genuine Blender socket fixture " + id, exception);
        }
    }

    private static Path fixtureRoot() {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent().resolve("test-assets/blender-sockets");
    }

    private static Path resourcePath(BlendResourceId id) {
        return fixtureRoot().resolve("exported/assets").resolve(id.namespace()).resolve(id.path());
    }

    private static JsonObject expected(String fixture) throws IOException {
        return json(fixtureRoot().resolve(fixture).resolve("expected.json"));
    }

    private static JsonObject json(Path path) throws IOException {
        return object(StrictJsonParser.parse(Files.readAllBytes(path)));
    }

    private static JsonObject object(JsonValue value) {
        return assertInstanceOf(JsonObject.class, value);
    }

    private static String string(JsonValue value) {
        return assertInstanceOf(JsonString.class, value).value();
    }

    private static double number(JsonValue value) {
        return assertInstanceOf(JsonNumber.class, value).asDouble();
    }
}
