package com.liy.blendlib.core.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.descriptor.DescriptorDecoder;
import com.liy.blendlib.core.descriptor.MaterialDefinition;
import com.liy.blendlib.core.descriptor.ModelDescriptor;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import com.liy.blendlib.core.glb.GlbDocument;
import com.liy.blendlib.core.glb.GlbReader;
import com.liy.blendlib.core.json.JsonArray;
import com.liy.blendlib.core.json.JsonNumber;
import com.liy.blendlib.core.json.JsonObject;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies only deterministic strict-v1 derivatives, never upstream external glTF acceptance. */
class KhronosDerivedFixtureTest {
    private static final String RESOURCE_ROOT = "p3/fixtures/khronos/";
    private static final String UPSTREAM_REVISION = "5109ab2a499c5a2c784b86e460fa491d52256e25";
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("blendlib_khronos_fixture:compatibility");

    @Test
    void derivedSimpleSkinLoadsWithRequiredSelfAuthoredAttributesAndSourceSkinSemantics() throws IOException {
        LoadedFixture fixture = load("simple-skin-derived.json", "simple-skin-derived.glb");

        assertEquals(ModelProfile.SKINNED_V1, fixture.asset().profile());
        assertEquals(3, fixture.asset().nodes().size());
        assertEquals(1, fixture.asset().primitives().size());
        MeshPrimitive primitive = fixture.asset().primitives().getFirst().geometry();
        assertEquals(10, primitive.vertexCount());
        assertEquals(24, primitive.indexCount());
        assertTrue(primitive.skinned());
        assertEquals(30, primitive.normals().length);
        assertEquals(20, primitive.texCoords().length);
        for (int offset = 0; offset < primitive.normals().length; offset += 3) {
            assertEquals(0.0f, primitive.normals()[offset]);
            assertEquals(0.0f, primitive.normals()[offset + 1]);
            assertEquals(1.0f, primitive.normals()[offset + 2]);
        }
        assertNotNull(fixture.asset().skeleton());
        assertEquals(1, fixture.asset().skeleton().skins().size());
        assertEquals(2, fixture.asset().skeleton().skins().getFirst().joints().size());
        assertEquals(1, fixture.asset().clips().size());
        assertEquals("SimpleSkinDerivedRotation", fixture.asset().clips().getFirst().name());
        var channel = fixture.asset().clips().getFirst().channels().getFirst();
        assertEquals(AnimationPath.ROTATION, channel.path());
        assertEquals(Interpolation.LINEAR, channel.interpolation());
        assertEquals(12, channel.keyCount());
        assertEquals(5.5f, channel.durationSeconds());

        assertDescriptorManagedExternalTexture(fixture.descriptor(), "SimpleSkinDerived");
        assertExternalTextureFixture("simple-skin-derived.png");
        JsonObject root = strictRoot(fixture.meshId(), fixture.glbBytes());
        assertPrimitiveAttributes(root, "POSITION", "NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0");
        assertEquals(5123, accessorComponentType(root, 0));
        assertNoEmbeddedImageOrTextureRoots(root);
    }

    @Test
    void derivedAnimatedCubeLoadsWithSupportedGeometryAndLinearRotationOnly() throws IOException {
        LoadedFixture fixture = load("animated-cube-derived.json", "animated-cube-derived.glb");

        assertEquals(ModelProfile.RIGID_V1, fixture.asset().profile());
        assertEquals(1, fixture.asset().nodes().size());
        assertEquals(1, fixture.asset().primitives().size());
        MeshPrimitive primitive = fixture.asset().primitives().getFirst().geometry();
        assertEquals(36, primitive.vertexCount());
        assertEquals(36, primitive.indexCount());
        assertFalse(primitive.skinned());
        assertEquals(1, fixture.asset().clips().size());
        assertEquals("animation_AnimatedCube", fixture.asset().clips().getFirst().name());
        var channel = fixture.asset().clips().getFirst().channels().getFirst();
        assertEquals(AnimationPath.ROTATION, channel.path());
        assertEquals(Interpolation.LINEAR, channel.interpolation());
        assertEquals(3, channel.keyCount());
        assertEquals(2.0f, channel.durationSeconds());

        assertDescriptorManagedExternalTexture(fixture.descriptor(), "AnimatedCubeDerived");
        assertExternalTextureFixture("animated-cube-derived.png");
        JsonObject root = strictRoot(fixture.meshId(), fixture.glbBytes());
        assertPrimitiveAttributes(root, "POSITION", "NORMAL", "TEXCOORD_0");
        assertFalse(primitiveAttributes(root).containsKey("TANGENT"));
        assertEquals(5123, accessorComponentType(root, 2));
        assertNoEmbeddedImageOrTextureRoots(root);
    }

    @Test
    void rawExternalGltfPayloadsAreRejectedAsGlbAndNeverAcceptedAsRuntimeFixtures() throws IOException {
        assertRawGltfRejected("SimpleSkin/glTF/SimpleSkin.gltf", "simple-skin-derived.json", "simple-skin-derived.glb");
        assertRawGltfRejected("AnimatedCube/glTF/AnimatedCube.gltf", "animated-cube-derived.json", "animated-cube-derived.glb");
    }

    private static LoadedFixture load(String descriptorName, String glbName) throws IOException {
        BlendResourceId descriptorId = BlendResourceId.parse("blendlib_khronos_fixture:blend_models/khronos/" + descriptorName);
        byte[] descriptorBytes = classpath(RESOURCE_ROOT + descriptorName);
        ModelDescriptor descriptor = new DescriptorDecoder().decode(MODEL_KEY, new AssetBytes(descriptorId, descriptorBytes));
        BlendResourceId meshId = descriptor.meshId();
        byte[] glbBytes = classpath(RESOURCE_ROOT + glbName);
        ModelAsset asset = new ModelAssetLoader().load(
                MODEL_KEY,
                new AssetBytes(descriptorId, descriptorBytes),
                requested -> {
                    assertEquals(meshId, requested);
                    return new AssetBytes(meshId, glbBytes);
                });
        return new LoadedFixture(descriptor, meshId, glbBytes, asset);
    }

    private static void assertRawGltfRejected(String rawRelativePath, String descriptorName, String glbName) throws IOException {
        LoadedFixture fixture = load(descriptorName, glbName);
        byte[] rawGltf = Files.readAllBytes(thirdPartyRoot().resolve("raw").resolve(rawRelativePath));
        byte[] descriptorBytes = classpath(RESOURCE_ROOT + descriptorName);
        BlendResourceId descriptorId = BlendResourceId.parse("blendlib_khronos_fixture:blend_models/khronos/" + descriptorName);
        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                () -> new ModelAssetLoader().load(
                        MODEL_KEY,
                        new AssetBytes(descriptorId, descriptorBytes),
                        requested -> new AssetBytes(fixture.meshId(), rawGltf)));
        assertEquals(BlendDiagnosticCodes.GLB_001, exception.diagnostic().code());
    }

    private static void assertDescriptorManagedExternalTexture(ModelDescriptor descriptor, String materialName) {
        MaterialDefinition material = descriptor.materials().get(materialName);
        assertNotNull(material);
        assertEquals(MaterialDefinition.Mode.OPAQUE, material.mode());
        assertTrue(material.baseColor().path().startsWith("textures/"));
        assertTrue(material.baseColor().path().endsWith(".png"));
    }

    private static void assertExternalTextureFixture(String fileName) throws IOException {
        byte[] png = classpath(RESOURCE_ROOT + "textures/khronos/" + fileName);
        assertEquals(68, png.length);
        assertEquals(0x89, Byte.toUnsignedInt(png[0]));
        assertEquals('P', Byte.toUnsignedInt(png[1]));
        assertEquals('N', Byte.toUnsignedInt(png[2]));
        assertEquals('G', Byte.toUnsignedInt(png[3]));
    }

    private static JsonObject strictRoot(BlendResourceId meshId, byte[] bytes) {
        GlbDocument document = new GlbReader().read(MODEL_KEY, new AssetBytes(meshId, bytes));
        JsonObject root = document.json();
        assertEquals("2.0", ((com.liy.blendlib.core.json.JsonString) ((JsonObject) root.get("asset")).get("version")).value());
        assertTrue(root.get("buffers") instanceof JsonArray);
        assertTrue(root.get("bufferViews") instanceof JsonArray);
        assertTrue(root.get("accessors") instanceof JsonArray);
        return root;
    }

    private static void assertNoEmbeddedImageOrTextureRoots(JsonObject root) {
        assertNull(root.get("images"));
        assertNull(root.get("textures"));
        assertNull(root.get("samplers"));
    }

    private static void assertPrimitiveAttributes(JsonObject root, String... expected) {
        JsonObject attributes = primitiveAttributes(root);
        assertEquals(expected.length, attributes.size());
        for (String name : expected) {
            assertTrue(attributes.containsKey(name), name);
        }
    }

    private static JsonObject primitiveAttributes(JsonObject root) {
        JsonArray meshes = (JsonArray) root.get("meshes");
        JsonObject mesh = (JsonObject) meshes.get(0);
        JsonArray primitives = (JsonArray) mesh.get("primitives");
        JsonObject primitive = (JsonObject) primitives.get(0);
        return (JsonObject) primitive.get("attributes");
    }

    private static int accessorComponentType(JsonObject root, int accessorIndex) {
        JsonArray accessors = (JsonArray) root.get("accessors");
        JsonObject accessor = (JsonObject) accessors.get(accessorIndex);
        return ((JsonNumber) accessor.get("componentType")).asIntExact();
    }

    private static byte[] classpath(String path) throws IOException {
        try (InputStream stream = KhronosDerivedFixtureTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing classpath fixture: " + path);
            }
            return stream.readAllBytes();
        }
    }

    private static Path thirdPartyRoot() {
        Path module = Path.of(System.getProperty("blendlib.projectDir"));
        Path repository = module.getParent();
        return repository.resolve("test-assets/third_party/khronos/glTF-Sample-Assets").resolve(UPSTREAM_REVISION);
    }

    private record LoadedFixture(ModelDescriptor descriptor, BlendResourceId meshId, byte[] glbBytes, ModelAsset asset) {
    }
}
