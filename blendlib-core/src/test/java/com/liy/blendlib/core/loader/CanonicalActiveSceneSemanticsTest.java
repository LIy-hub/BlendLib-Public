package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.diagnostic.BlendDiagnosticCodes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Regression coverage for accepted ADR-015 combined-load semantics. */
class CanonicalActiveSceneSemanticsTest {
    private static final BlendResourceId MODEL_KEY = BlendResourceId.parse("blendlib_showcase:canonical_scene_test");
    private static final BlendResourceId DESCRIPTOR_ID = BlendResourceId.parse("blendlib_showcase:blend_models/fixtures/skinned_model.json");
    private static final BlendResourceId MESH_ID = BlendResourceId.parse("blendlib_showcase:models3d/fixtures/skinned_model.glb");

    @Test
    void rejectsRequiredActivePrimitiveSkinJointOutsideDefaultSceneHierarchy() throws IOException {
        String inactiveJointScene = replaceRequired(jsonChunk(skinnedGlb()),
                "\"scenes\":[{\"name\":\"Scene\",\"nodes\":[3]}]",
                "\"scenes\":[{\"name\":\"Scene\",\"nodes\":[1]}]");

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                () -> load(p2Descriptor(), rewriteGlbJson(skinnedGlb(), inactiveJointScene)));

        assertEquals(BlendDiagnosticCodes.SCENE_005, exception.diagnostic().code());
        assertEquals("/skins/0/joints/0", exception.diagnostic().location());
    }

    @Test
    void rejectsAnimationTargetingDetachedSkinJointEvenWhenThatSkinIsNotBoundToActivePrimitive() throws IOException {
        String json = jsonChunk(skinnedGlb());
        json = replaceRequired(json,
                "{\"children\":[2],\"name\":\"SkinnedRoot\"}]",
                "{\"children\":[2],\"name\":\"SkinnedRoot\"},{\"name\":\"DetachedJoint\"}]");
        json = replaceRequired(json,
                "\"skins\":[{\"inverseBindMatrices\":6,\"joints\":[0],\"name\":\"SkinnedArmature\"}]",
                "\"skins\":[{\"inverseBindMatrices\":6,\"joints\":[0],\"name\":\"SkinnedArmature\"},"
                        + "{\"inverseBindMatrices\":6,\"joints\":[4],\"name\":\"DetachedSkin\"}]");
        json = json.replace("\"target\":{\"node\":0", "\"target\":{\"node\":4");
        String detachedTargetJson = json;

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class,
                () -> load(p2Descriptor(), rewriteGlbJson(skinnedGlb(), detachedTargetJson)));

        assertEquals(BlendDiagnosticCodes.SCENE_005, exception.diagnostic().code());
        assertEquals("/animations", exception.diagnostic().location());
    }

    @Test
    void rejectsDescriptorEventPastReferencedDecodedClipDuration() throws IOException {
        AssetBytes descriptor = new AssetBytes(DESCRIPTOR_ID, """
                {"format_version":1,"profile":"blendlib:skinned_v1",
                 "mesh":"blendlib_showcase:models3d/fixtures/skinned_model.glb",
                 "materials":{"SkinnedSurface":{"base_color":"blendlib_showcase:textures/blendlib/fixtures_skinned_model__skinnedsurface.png"}},
                 "animation":{"initial_state":"blendlib_showcase:skinned_wave","states":{
                   "blendlib_showcase:skinned_wave":{"clip":"skinned_wave","loop":true,"speed":1.0,
                    "events":[{"time_seconds":1.0,"event":"blendlib_showcase:late_visual_only"}]}}}}
                """.getBytes(StandardCharsets.UTF_8));

        BlendAssetLoadException exception = assertThrows(BlendAssetLoadException.class, () -> load(descriptor, skinnedGlb()));

        assertEquals(BlendDiagnosticCodes.DESC_002, exception.diagnostic().code());
        assertEquals("/animation/states/blendlib_showcase:skinned_wave/events/0/time_seconds", exception.diagnostic().location());
    }

    private static ModelAssetLoader loader() {
        return new ModelAssetLoader();
    }

    private static void load(AssetBytes descriptor, byte[] glbBytes) {
        AssetBytes glb = new AssetBytes(MESH_ID, glbBytes);
        loader().load(MODEL_KEY, descriptor, ignored -> glb);
    }

    private static AssetBytes p2Descriptor() throws IOException {
        return new AssetBytes(DESCRIPTOR_ID, Files.readAllBytes(assetRoot().resolve("blend_models/fixtures/skinned_model.json")));
    }

    private static byte[] skinnedGlb() throws IOException {
        return Files.readAllBytes(assetRoot().resolve("models3d/fixtures/skinned_model.glb"));
    }

    private static Path assetRoot() {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent()
                .resolve("blendlib-showcase/src/main/resources/assets/blendlib_showcase");
    }

    private static String jsonChunk(byte[] glb) {
        ByteBuffer input = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        return new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim();
    }

    private static byte[] rewriteGlbJson(byte[] source, String json) {
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int jsonLength = input.getInt(12);
        int binaryHeader = 20 + jsonLength;
        int binaryLength = input.getInt(binaryHeader);
        byte[] binary = Arrays.copyOfRange(source, binaryHeader + 8, binaryHeader + 8 + binaryLength);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = (jsonBytes.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJsonLength + 8 + binary.length;
        ByteBuffer output = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(totalLength);
        output.putInt(paddedJsonLength).putInt(0x4E4F534A).put(jsonBytes);
        while (output.position() < 20 + paddedJsonLength) {
            output.put((byte) 0x20);
        }
        output.putInt(binary.length).putInt(0x004E4942).put(binary);
        return output.array();
    }

    private static String replaceRequired(String source, String target, String replacement) {
        String result = source.replace(target, replacement);
        if (result.equals(source)) {
            throw new AssertionError("Fixture shape changed; replacement target not found: " + target);
        }
        return result;
    }
}
