package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.Matrix4;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.core.model.ModelPrimitive;
import com.liy.blendlib.core.model.ModelProfile;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Exercises the strict P3 loader and P5 runtime math using the actual BlendLib-owned P2
 * rigid and skinned outputs. This test intentionally does not infer any general policy for
 * inactive/default-scene nodes; both assets have one known canonical default scene.
 */
class CanonicalP2RuntimeGoldenTest {
    private static final float TOLERANCE = 1.0e-5f;
    private static final String GOLDEN_RESOURCE = "p5/canonical/canonical-runtime-golden.properties";
    private static final Set<String> FORBIDDEN_ASSET_SUFFIXES = Set.of(".blend", ".fbx", ".obj", ".glb", ".gltf", ".png");

    @Test
    void canonicalGoldenMetadataReferencesOnlyOriginalBlendlibP2AssetsAndFrozenHashes() throws Exception {
        Golden golden = Golden.load();
        assertEquals("blendlib-p5-canonical-runtime-golden-v1", golden.value("format"));
        assertEquals("BlendLib-P2-canonical-runtime-outputs", golden.value("provenance"));
        assertEquals("blendlib-showcase/src/main/resources/assets/blendlib_showcase", golden.value("asset.root"));

        Path repository = repositoryRoot();
        assertNoRuntimeAssetCopies(repository.resolve("blendlib-core/src/test/resources/p5/canonical"));
        assertP2ProvenanceAndHashes(repository, golden, "rigid");
        assertP2ProvenanceAndHashes(repository, golden, "skinned");
    }

    @Test
    void canonicalRigidP2AssetLoadsAndSamplesKnownPulseIntoNodePalette() throws Exception {
        Golden golden = Golden.load();
        ModelAsset asset = loadCanonicalAsset(golden, "rigid", 51L);
        assertEquals(ModelProfile.RIGID_V1, asset.profile());
        assertEquals(golden.value("rigid.clip"), asset.clips().getFirst().name());
        assertEquals(1, asset.clips().size());

        float sampleTime = golden.floatValue("rigid.sample.seconds");
        AnimationClip clip = asset.clips().getFirst();
        AnimationChannel translation = channel(clip, golden.intValue("rigid.arm.node"), AnimationPath.TRANSLATION);
        assertVector(golden.vector("rigid.arm.local.translation"), translation.sample(sampleTime));

        AnimationState state = state("rigid", clip);
        LocalPose localPose = PoseSampler.fromModelAsset(asset).sample(state, sampleTime);
        int armNode = golden.intValue("rigid.arm.node");
        assertTranslation(golden.vector("rigid.arm.local.translation"), localPose.transform(armNode));

        NodePalette palette = NodePalette.from(localPose, asset.nodes());
        assertTranslation(golden.vector("rigid.arm.world.translation"), palette.worldTransform(armNode));
        assertFinite(localPose);
        assertFinite(palette);
    }

    @Test
    void canonicalSkinnedP2AssetLoadsSamplesAndCpuSkinsFiniteGoldenOutput() throws Exception {
        Golden golden = Golden.load();
        ModelAsset asset = loadCanonicalAsset(golden, "skinned", 52L);
        assertEquals(ModelProfile.SKINNED_V1, asset.profile());
        assertEquals(golden.value("skinned.clip"), asset.clips().getFirst().name());
        assertNotNull(asset.skeleton());
        assertEquals(1, asset.skeleton().skins().size());

        float sampleTime = golden.floatValue("skinned.sample.seconds");
        int boneNode = golden.intValue("skinned.bone.node");
        AnimationClip clip = asset.clips().getFirst();
        assertVector(golden.vector("skinned.bone.local.translation"),
                channel(clip, boneNode, AnimationPath.TRANSLATION).sample(sampleTime));
        assertVector(golden.vector("skinned.bone.local.rotation"),
                channel(clip, boneNode, AnimationPath.ROTATION).sample(sampleTime));
        assertVector(golden.vector("skinned.bone.local.scale"),
                channel(clip, boneNode, AnimationPath.SCALE).sample(sampleTime));

        LocalPose localPose = PoseSampler.fromModelAsset(asset).sample(state("skinned", clip), sampleTime);
        Transform bone = localPose.transform(boneNode);
        assertTranslation(golden.vector("skinned.bone.local.translation"), bone);
        assertQuaternion(golden.vector("skinned.bone.local.rotation"), bone.rotation());
        assertVector(golden.vector("skinned.bone.local.scale"), vector(bone.scale()));

        NodePalette nodePalette = NodePalette.from(localPose, asset.nodes());
        Skin skin = asset.skeleton().skins().getFirst();
        assertEquals(List.of(boneNode), skin.joints());
        SkinPalette skinPalette = SkinPalette.from(skin, nodePalette);
        assertEquals(1, skinPalette.jointCount());
        Matrix4 matrix = skinPalette.matrix(0);
        assertEquals(golden.floatValue("skinned.palette.m00"), matrix.get(0, 0), TOLERANCE);
        assertEquals(golden.floatValue("skinned.palette.m01"), matrix.get(0, 1), TOLERANCE);
        assertEquals(golden.floatValue("skinned.palette.m10"), matrix.get(1, 0), TOLERANCE);
        assertEquals(golden.floatValue("skinned.palette.m11"), matrix.get(1, 1), TOLERANCE);
        assertFinite(matrix);

        ModelPrimitive primitive = asset.primitives().stream()
                .filter(value -> value.geometry().skinned())
                .findFirst()
                .orElseThrow();
        CpuSkinnedMesh skinned = CpuSkinner.skin(PreparedSkinnedGeometry.prepare(primitive.geometry()), skinPalette);
        assertVector(golden.vector("skinned.cpu.positions"), skinned.positions());
        assertVector(golden.vector("skinned.cpu.first.normal"), firstVector(skinned.normals()));
        assertFinite(skinned.positions());
        assertFinite(skinned.normals());
    }

    private static ModelAsset loadCanonicalAsset(Golden golden, String fixture, long generation) throws IOException {
        Path assetRoot = repositoryRoot().resolve(golden.value("asset.root"));
        Path descriptorPath = assetRoot.resolve(golden.value(fixture + ".descriptor.path"));
        Path glbPath = assetRoot.resolve(golden.value(fixture + ".glb.path"));
        BlendResourceId descriptorId = BlendResourceId.parse(
                "blendlib_showcase:" + golden.value(fixture + ".descriptor.path"));
        BlendResourceId glbId = BlendResourceId.parse("blendlib_showcase:" + golden.value(fixture + ".glb.path"));
        AssetBytes descriptor = new AssetBytes(descriptorId, Files.readAllBytes(descriptorPath));
        AssetBytes glb = new AssetBytes(glbId, Files.readAllBytes(glbPath));
        ModelAsset asset = new ModelAssetLoader().load(
                BlendResourceId.parse("blendlib_showcase:p5/canonical_" + fixture),
                generation,
                descriptor,
                ignored -> glb);
        assertEquals(golden.value(fixture + ".profile"), asset.profile().serializedName());
        assertEquals(generation, asset.generation());
        return asset;
    }

    private static AnimationState state(String fixture, AnimationClip clip) {
        return new AnimationState(
                BlendAnimationKey.parse("blendlib_showcase:" + fixture), clip, true, 1.0, 0.0, null, List.of());
    }

    private static AnimationChannel channel(AnimationClip clip, int node, AnimationPath path) {
        return clip.channels().stream()
                .filter(channel -> channel.targetNode() == node && channel.path() == path)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + path + " channel for node " + node + " in " + clip.name()));
    }

    private static void assertP2ProvenanceAndHashes(Path repository, Golden golden, String fixture) throws Exception {
        Path assetRoot = repository.resolve(golden.value("asset.root"));
        Path descriptor = assetRoot.resolve(golden.value(fixture + ".descriptor.path"));
        Path glb = assetRoot.resolve(golden.value(fixture + ".glb.path"));
        Path p2Hashes = repository.resolve(golden.value(fixture + ".p2.hash.file"));
        assertTrue(Files.isRegularFile(descriptor), () -> "Missing P2 descriptor: " + descriptor);
        assertTrue(Files.isRegularFile(glb), () -> "Missing P2 GLB: " + glb);
        assertTrue(Files.isRegularFile(p2Hashes), () -> "Missing P2 hash record: " + p2Hashes);

        String descriptorHash = golden.value(fixture + ".descriptor.sha256");
        String glbHash = golden.value(fixture + ".glb.sha256");
        assertEquals(descriptorHash, sha256(descriptor));
        assertEquals(glbHash, sha256(glb));
        assertEquals(descriptorHash, jsonHash(p2Hashes, "descriptor"));
        assertEquals(glbHash, jsonHash(p2Hashes, "exported_glb"));
    }

    private static void assertNoRuntimeAssetCopies(Path canonicalResourceRoot) throws IOException {
        assertTrue(Files.isDirectory(canonicalResourceRoot), () -> "Missing canonical golden resource root: " + canonicalResourceRoot);
        try (Stream<Path> paths = Files.walk(canonicalResourceRoot)) {
            List<Path> copiedAssets = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> FORBIDDEN_ASSET_SUFFIXES.stream()
                            .anyMatch(suffix -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)))
                    .toList();
            assertTrue(copiedAssets.isEmpty(), () -> "Canonical golden resources must not copy runtime assets: " + copiedAssets);
        }
    }

    private static String jsonHash(Path hashFile, String key) throws IOException {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"");
        Matcher matcher = pattern.matcher(Files.readString(hashFile));
        assertTrue(matcher.find(), () -> "Missing SHA-256 field " + key + " in " + hashFile);
        return matcher.group(1);
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void assertTranslation(float[] expected, Transform transform) {
        assertVector(expected, vector(transform.translation()));
    }

    private static void assertQuaternion(float[] expected, Quaternion actual) {
        assertVector(expected, new float[] {actual.x(), actual.y(), actual.z(), actual.w()});
    }

    private static float[] vector(Vec3 value) {
        return new float[] {value.x(), value.y(), value.z()};
    }

    private static float[] firstVector(float[] values) {
        if (values.length < 3) {
            throw new AssertionError("Expected one normal vector");
        }
        return new float[] {values[0], values[1], values[2]};
    }

    private static void assertVector(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, "vector length");
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], TOLERANCE, "component " + index);
        }
    }

    private static void assertFinite(LocalPose pose) {
        for (Transform transform : pose.transforms().values()) {
            assertFinite(vector(transform.translation()));
            assertFinite(new float[] {
                transform.rotation().x(), transform.rotation().y(), transform.rotation().z(), transform.rotation().w()
            });
            assertFinite(vector(transform.scale()));
        }
    }

    private static void assertFinite(NodePalette palette) {
        for (Transform transform : palette.worldTransforms().values()) {
            assertFinite(vector(transform.translation()));
            assertFinite(new float[] {
                transform.rotation().x(), transform.rotation().y(), transform.rotation().z(), transform.rotation().w()
            });
            assertFinite(vector(transform.scale()));
        }
    }

    private static void assertFinite(Matrix4 matrix) {
        for (float value : matrix.copy()) {
            assertTrue(Float.isFinite(value), "matrix contains a non-finite component");
        }
    }

    private static void assertFinite(float[] values) {
        for (float value : values) {
            assertTrue(Float.isFinite(value), "array contains a non-finite component");
        }
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent();
    }

    private static final class Golden {
        private final Properties properties;

        private Golden(Properties properties) {
            this.properties = properties;
        }

        static Golden load() throws IOException {
            Properties properties = new Properties();
            try (InputStream input = CanonicalP2RuntimeGoldenTest.class.getClassLoader().getResourceAsStream(GOLDEN_RESOURCE)) {
                assertNotNull(input, () -> "Missing canonical golden resource: " + GOLDEN_RESOURCE);
                properties.load(input);
            }
            return new Golden(properties);
        }

        String value(String key) {
            String value = properties.getProperty(Objects.requireNonNull(key, "key"));
            assertNotNull(value, () -> "Missing canonical golden key: " + key);
            return value;
        }

        float floatValue(String key) {
            return Float.parseFloat(value(key));
        }

        int intValue(String key) {
            return Integer.parseInt(value(key));
        }

        float[] vector(String key) {
            String[] components = value(key).split(",", -1);
            float[] values = new float[components.length];
            for (int index = 0; index < components.length; index++) {
                values[index] = Float.parseFloat(components[index]);
            }
            return values;
        }
    }
}
