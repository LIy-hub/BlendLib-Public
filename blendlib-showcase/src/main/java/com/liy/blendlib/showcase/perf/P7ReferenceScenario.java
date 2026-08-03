package com.liy.blendlib.showcase.perf;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable contract for the P7 real-client performance reference scene.
 *
 * <p>This is deliberately a scenario description, not automatic gameplay registration. The
 * generated assets and the corresponding scene must be installed only in an isolated client run
 * directory before a human records the required JFR or profiler evidence. Keeping the contract
 * here makes the target instance, triangle, and joint counts mechanically testable without
 * pretending a unit test proves a frame-rate result.</p>
 */
public final class P7ReferenceScenario {
    public static final String FORMAT = "blendlib-showcase-p7-reference-scene-v1";
    public static final int RIGID_INSTANCE_COUNT = 100;
    public static final int RIGID_TRIANGLES_PER_INSTANCE = 10_000;
    public static final int SKINNED_INSTANCE_COUNT = 25;
    public static final int SKINNED_TRIANGLES_PER_INSTANCE = 20_000;
    public static final int SKINNED_JOINTS_PER_INSTANCE = 64;
    public static final int WARMUP_FRAME_COUNT = 600;
    public static final int SAMPLE_FRAME_COUNT = 1_800;
    public static final int TARGET_FPS = 60;
    public static final long TARGET_FRAME_NANOS = 1_000_000_000L / TARGET_FPS;
    /** Frozen physical framebuffer width for the true-in-frustum reference capture. */
    public static final int CAPTURE_FRAMEBUFFER_WIDTH = 1_920;
    /** Frozen physical framebuffer height for the true-in-frustum reference capture. */
    public static final int CAPTURE_FRAMEBUFFER_HEIGHT = 1_080;
    /** The reference framebuffer's exact numerator in its 16:9 aspect ratio. */
    public static final int CAPTURE_ASPECT_RATIO_WIDTH = 16;
    /** The reference framebuffer's exact denominator in its 16:9 aspect ratio. */
    public static final int CAPTURE_ASPECT_RATIO_HEIGHT = 9;
    /** Frozen vanilla FOV option value for the reference capture. */
    public static final int CAPTURE_FOV_DEGREES = 90;
    /** Vanilla's FOV-effect scale value when dynamic FOV is disabled. */
    public static final double DISABLED_DYNAMIC_FOV_EFFECT_SCALE = 0.0d;
    /** The effective client render distance may not fall below this value during capture. */
    public static final int MIN_CAPTURE_RENDER_DISTANCE_CHUNKS = 8;
    /** Operator command explicitly targets the invoking player; X/Z are centred by vanilla at {@code +0.5}. */
    public static final String CAPTURE_TELEPORT_COMMAND = "/tp @s 0 67 24 180 0";
    /** Vanilla command teleport resolves horizontal block coordinates at an entity's centre. */
    public static final double TELEPORT_HORIZONTAL_CENTER_OFFSET_BLOCKS = 0.5d;
    /** Small tolerance for command/float conversion without admitting a materially different view. */
    public static final double CAMERA_POSITION_TOLERANCE_BLOCKS = 0.05d;
    /** Small circular-angle tolerance for the prescribed camera orientation. */
    public static final double CAMERA_ANGLE_TOLERANCE_DEGREES = 1.0d;

    private static final P7ReferenceScenario STANDARD = createStandard();

    private final List<Asset> assets;
    private final List<Instance> instances;
    private final CameraPose camera;

    private P7ReferenceScenario(List<Asset> assets, List<Instance> instances, CameraPose camera) {
        this.assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
        this.instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
        this.camera = Objects.requireNonNull(camera, "camera");
        validate();
    }

    /** Returns the one frozen P7 target; callers must not lower its counts for a measurement run. */
    public static P7ReferenceScenario standard() {
        return STANDARD;
    }

    public List<Asset> assets() {
        return assets;
    }

    public List<Instance> instances() {
        return instances;
    }

    public CameraPose camera() {
        return camera;
    }

    public int totalTriangleCount() {
        return assets.stream().mapToInt(asset -> Math.multiplyExact(asset.instanceCount(), asset.trianglesPerInstance())).sum();
    }

    /**
     * Returns whether one rendered frame contains the complete frozen target population.
     *
     * <p>The reference scene intentionally measures all 1,500,000 target triangles. A partially
     * culled frame is therefore neither a warm-up frame nor a valid measured sample.</p>
     */
    public static boolean hasExactTargetSubmissions(int rigidSubmissions, int skinnedSubmissions) {
        return rigidSubmissions == RIGID_INSTANCE_COUNT && skinnedSubmissions == SKINNED_INSTANCE_COUNT;
    }

    /**
     * Returns whether a local player is at the frozen P7 capture camera after a vanilla
     * {@code /tp} command. The manifest uses the operator's block-coordinate command target,
     * while the player entity's horizontal position is centred at {@code +0.5} in each axis.
     */
    public boolean isAtCaptureCamera(
            double playerX,
            double playerY,
            double playerZ,
            double playerYawDegrees,
            double playerPitchDegrees) {
        return withinPositionTolerance(playerX, camera.x() + TELEPORT_HORIZONTAL_CENTER_OFFSET_BLOCKS)
                && withinPositionTolerance(playerY, camera.y())
                && withinPositionTolerance(playerZ, camera.z() + TELEPORT_HORIZONTAL_CENTER_OFFSET_BLOCKS)
                && withinAngleTolerance(playerYawDegrees, camera.yawDegrees())
                && withinAngleTolerance(playerPitchDegrees, camera.pitchDegrees());
    }

    /**
     * Generates the checked-in canonical scenario manifest. It is a declaration of a future
     * real-client run, not a generated result or a PASS assertion.
     */
    public String canonicalManifestJson() {
        StringBuilder builder = new StringBuilder(2_048);
        builder.append("{\n")
                .append("  \"format\": \"").append(FORMAT).append("\",\n")
                .append("  \"dormant_by_default\": true,\n")
                .append("  \"activation\": {\n")
                .append("    \"automatic_registration\": false,\n")
                .append("    \"requires_isolated_client_run\": true,\n")
                .append("    \"requires_real_client_jfr_or_profiler\": true\n")
                .append("  },\n")
                .append("  \"camera\": {\n")
                .append("    \"x\": ").append(decimal(camera.x())).append(",\n")
                .append("    \"y\": ").append(decimal(camera.y())).append(",\n")
                .append("    \"z\": ").append(decimal(camera.z())).append(",\n")
                .append("    \"yaw_degrees\": ").append(decimal(camera.yawDegrees())).append(",\n")
                .append("    \"pitch_degrees\": ").append(decimal(camera.pitchDegrees())).append(",\n")
                .append("    \"teleport_command\": \"").append(CAPTURE_TELEPORT_COMMAND).append("\",\n")
                .append("    \"player_center_x\": ")
                .append(decimal(camera.x() + TELEPORT_HORIZONTAL_CENTER_OFFSET_BLOCKS)).append(",\n")
                .append("    \"player_center_z\": ")
                .append(decimal(camera.z() + TELEPORT_HORIZONTAL_CENTER_OFFSET_BLOCKS)).append("\n")
                .append("  },\n")
                .append("  \"client_conditions\": {\n")
                .append("    \"framebuffer_width\": ").append(CAPTURE_FRAMEBUFFER_WIDTH).append(",\n")
                .append("    \"framebuffer_height\": ").append(CAPTURE_FRAMEBUFFER_HEIGHT).append(",\n")
                .append("    \"aspect_ratio\": \"").append(CAPTURE_ASPECT_RATIO_WIDTH).append(":")
                .append(CAPTURE_ASPECT_RATIO_HEIGHT).append("\",\n")
                .append("    \"fov_degrees\": ").append(CAPTURE_FOV_DEGREES).append(",\n")
                .append("    \"dynamic_fov_disabled\": true,\n")
                .append("    \"fov_effect_scale\": ").append(decimal(DISABLED_DYNAMIC_FOV_EFFECT_SCALE)).append(",\n")
                .append("    \"minimum_render_distance_chunks\": ")
                .append(MIN_CAPTURE_RENDER_DISTANCE_CHUNKS).append("\n")
                .append("  },\n")
                .append("  \"measurement\": {\n")
                .append("    \"warmup_frames\": ").append(WARMUP_FRAME_COUNT).append(",\n")
                .append("    \"sample_frames\": ").append(SAMPLE_FRAME_COUNT).append(",\n")
                .append("    \"target_fps\": ").append(TARGET_FPS).append(",\n")
                .append("    \"records\": [\n")
                .append("      \"p50_frame_time\",\n")
                .append("      \"p95_frame_time\",\n")
                .append("      \"animation_preparation\",\n")
                .append("      \"submit_cpu\",\n")
                .append("      \"live_allocation\",\n")
                .append("      \"cache_observation\",\n")
                .append("      \"handle_observation\"\n")
                .append("    ]\n")
                .append("  },\n")
                .append("  \"assets\": [\n");
        for (int index = 0; index < assets.size(); index++) {
            Asset asset = assets.get(index);
            builder.append("    {\n")
                    .append("      \"kind\": \"").append(asset.kind().manifestName()).append("\",\n")
                    .append("      \"model_key\": \"").append(asset.modelKey()).append("\",\n")
                    .append("      \"profile\": \"").append(asset.profile()).append("\",\n")
                    .append("      \"instances\": ").append(asset.instanceCount()).append(",\n")
                    .append("      \"triangles_per_instance\": ").append(asset.trianglesPerInstance()).append(",\n")
                    .append("      \"joints_per_instance\": ").append(asset.jointsPerInstance()).append(",\n")
                    .append("      \"generated_glb\": \"").append(asset.generatedGlbPath()).append("\"\n")
                    .append("    }");
            builder.append(index + 1 == assets.size() ? "\n" : ",\n");
        }
        builder.append("  ],\n")
                .append("  \"layout\": {\n")
                .append("    \"rigid\": {\n")
                .append("      \"rows\": 4,\n")
                .append("      \"columns\": 25,\n")
                .append("      \"ordinal\": \"25*r+c\",\n")
                .append("      \"r_range\": \"0..3\",\n")
                .append("      \"c_range\": \"0..24\",\n")
                .append("      \"x\": \"-18+1.5*c\",\n")
                .append("      \"y\": \"64+2*r\",\n")
                .append("      \"z\": 0\n")
                .append("    },\n")
                .append("    \"skinned\": {\n")
                .append("      \"rows\": 1,\n")
                .append("      \"columns\": 25,\n")
                .append("      \"ordinal\": \"c\",\n")
                .append("      \"c_range\": \"0..24\",\n")
                .append("      \"x\": \"-18+1.5*c\",\n")
                .append("      \"y\": 72,\n")
                .append("      \"z\": 0\n")
                .append("    },\n")
                .append("    \"all_target_instances_must_be_visible_before_sampling\": true\n")
                .append("  },\n")
                .append("  \"total_target_triangles\": ").append(totalTriangleCount()).append("\n")
                .append("}\n");
        return builder.toString();
    }

    /** Validates the frozen requirement without assigning a performance Gate outcome. */
    public void validate() {
        Map<Kind, Asset> assetsByKind = new EnumMap<>(Kind.class);
        for (Asset asset : assets) {
            if (assetsByKind.put(asset.kind(), asset) != null) {
                throw new IllegalArgumentException("Each P7 asset kind must appear exactly once");
            }
        }
        if (assetsByKind.size() != Kind.values().length) {
            throw new IllegalArgumentException("The P7 scene must contain rigid and skinned assets");
        }
        assertAsset(assetsByKind.get(Kind.RIGID), RIGID_INSTANCE_COUNT, RIGID_TRIANGLES_PER_INSTANCE, 0, "blendlib:rigid_v1");
        assertAsset(assetsByKind.get(Kind.SKINNED), SKINNED_INSTANCE_COUNT, SKINNED_TRIANGLES_PER_INSTANCE,
                SKINNED_JOINTS_PER_INSTANCE, "blendlib:skinned_v1");
        if (instances.size() != RIGID_INSTANCE_COUNT + SKINNED_INSTANCE_COUNT) {
            throw new IllegalArgumentException("The P7 instance list has an unexpected size");
        }
        Map<Kind, Integer> instanceCounts = new EnumMap<>(Kind.class);
        Map<Kind, Set<Integer>> ordinalsByKind = new EnumMap<>(Kind.class);
        for (Instance instance : instances) {
            if (!Double.isFinite(instance.x()) || !Double.isFinite(instance.y()) || !Double.isFinite(instance.z())) {
                throw new IllegalArgumentException("P7 instance placement must be finite");
            }
            validateTrueInFrustumPlacement(instance);
            instanceCounts.merge(instance.kind(), 1, Math::addExact);
            if (!ordinalsByKind.computeIfAbsent(instance.kind(), ignored -> new HashSet<>()).add(instance.ordinal())) {
                throw new IllegalArgumentException("P7 instance ordinals must be unique within each asset kind");
            }
        }
        if (instanceCounts.getOrDefault(Kind.RIGID, 0) != RIGID_INSTANCE_COUNT
                || instanceCounts.getOrDefault(Kind.SKINNED, 0) != SKINNED_INSTANCE_COUNT) {
            throw new IllegalArgumentException("P7 instance placements do not match the frozen target counts");
        }
        if (ordinalsByKind.getOrDefault(Kind.RIGID, Set.of()).size() != RIGID_INSTANCE_COUNT
                || ordinalsByKind.getOrDefault(Kind.SKINNED, Set.of()).size() != SKINNED_INSTANCE_COUNT) {
            throw new IllegalArgumentException("P7 instance ordinals do not cover the frozen target population");
        }
        if (totalTriangleCount() != 1_500_000) {
            throw new IllegalArgumentException("P7 target triangles must remain 1,500,000");
        }
        if (!camera.equals(new CameraPose(0.0d, 67.0d, 24.0d, 180.0d, 0.0d))) {
            throw new IllegalArgumentException("P7 capture camera must retain the accepted true-in-frustum pose");
        }
    }

    private static P7ReferenceScenario createStandard() {
        List<Asset> assets = List.of(
                new Asset(Kind.RIGID, "blendlib_showcase:p7/rigid_10k", "blendlib:rigid_v1",
                        RIGID_INSTANCE_COUNT, RIGID_TRIANGLES_PER_INSTANCE, 0, "models3d/p7/rigid_10k.glb"),
                new Asset(Kind.SKINNED, "blendlib_showcase:p7/skinned_20k_64j", "blendlib:skinned_v1",
                        SKINNED_INSTANCE_COUNT, SKINNED_TRIANGLES_PER_INSTANCE, SKINNED_JOINTS_PER_INSTANCE,
                        "models3d/p7/skinned_20k_64j.glb"));
        List<Instance> instances = new ArrayList<>(RIGID_INSTANCE_COUNT + SKINNED_INSTANCE_COUNT);
        // The 4-by-25 wall faces the camera from +Z. The real client must still prove exact
        // 100/25 submissions on every warm-up and sample frame; this static layout is not visual
        // or performance evidence by itself.
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 25; column++) {
                instances.add(new Instance(Kind.RIGID, 25 * row + column, -18.0d + 1.5d * column,
                        64.0d + 2.0d * row, 0.0d));
            }
        }
        for (int column = 0; column < 25; column++) {
            instances.add(new Instance(Kind.SKINNED, column, -18.0d + 1.5d * column, 72.0d, 0.0d));
        }
        return new P7ReferenceScenario(assets, instances, new CameraPose(0.0d, 67.0d, 24.0d, 180.0d, 0.0d));
    }

    private static void assertAsset(Asset asset, int instances, int triangles, int joints, String profile) {
        if (asset == null || asset.instanceCount() != instances || asset.trianglesPerInstance() != triangles
                || asset.jointsPerInstance() != joints || !profile.equals(asset.profile())) {
            throw new IllegalArgumentException("P7 asset contract has been changed");
        }
    }

    private static void validateTrueInFrustumPlacement(Instance instance) {
        int ordinal = instance.ordinal();
        if (instance.kind() == Kind.RIGID) {
            if (ordinal >= RIGID_INSTANCE_COUNT) {
                throw new IllegalArgumentException("P7 rigid ordinal is outside the accepted 4x25 layout");
            }
            int row = ordinal / 25;
            int column = ordinal % 25;
            requireCoordinate(instance, -18.0d + 1.5d * column, 64.0d + 2.0d * row, 0.0d);
            return;
        }
        if (ordinal >= SKINNED_INSTANCE_COUNT) {
            throw new IllegalArgumentException("P7 skinned ordinal is outside the accepted 1x25 layout");
        }
        requireCoordinate(instance, -18.0d + 1.5d * ordinal, 72.0d, 0.0d);
    }

    private static void requireCoordinate(Instance instance, double x, double y, double z) {
        if (Double.compare(instance.x(), x) != 0
                || Double.compare(instance.y(), y) != 0
                || Double.compare(instance.z(), z) != 0) {
            throw new IllegalArgumentException("P7 instance placement does not match the accepted true-in-frustum layout");
        }
    }

    private static boolean withinPositionTolerance(double actual, double expected) {
        return Double.isFinite(actual)
                && Math.abs(actual - expected) <= CAMERA_POSITION_TOLERANCE_BLOCKS + 1.0e-9d;
    }

    private static boolean withinAngleTolerance(double actual, double expected) {
        if (!Double.isFinite(actual)) {
            return false;
        }
        double normalizedDifference = Math.abs((actual - expected) % 360.0d);
        double circularDifference = Math.min(normalizedDifference, 360.0d - normalizedDifference);
        return circularDifference <= CAMERA_ANGLE_TOLERANCE_DEGREES;
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    public enum Kind {
        RIGID("rigid"),
        SKINNED("skinned");

        private final String manifestName;

        Kind(String manifestName) {
            this.manifestName = manifestName;
        }

        public String manifestName() {
            return manifestName;
        }
    }

    public record Asset(
            Kind kind,
            String modelKey,
            String profile,
            int instanceCount,
            int trianglesPerInstance,
            int jointsPerInstance,
            String generatedGlbPath) {
        public Asset {
            kind = Objects.requireNonNull(kind, "kind");
            modelKey = requireNonBlank(modelKey, "modelKey");
            profile = requireNonBlank(profile, "profile");
            generatedGlbPath = requireNonBlank(generatedGlbPath, "generatedGlbPath");
            if (instanceCount <= 0 || trianglesPerInstance <= 0 || jointsPerInstance < 0) {
                throw new IllegalArgumentException("P7 asset counts must be positive, except rigid joints may be zero");
            }
        }
    }

    public record Instance(Kind kind, int ordinal, double x, double y, double z) {
        public Instance {
            kind = Objects.requireNonNull(kind, "kind");
            if (ordinal < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("P7 instance data is invalid");
            }
        }
    }

    /**
     * Actual client conditions observed by the client-only capture controller.
     *
     * <p>This plain value object deliberately carries no Minecraft or Blaze3D type, so the frozen
     * scenario remains server-safe and mechanically testable. The controller must record a value
     * that satisfies {@link #meetsCaptureContract()} before warm-up and on every active frame.</p>
     */
    public record ClientConditions(
            int framebufferWidth,
            int framebufferHeight,
            int renderTargetWidth,
            int renderTargetHeight,
            int fovDegrees,
            double fovEffectScale,
            int configuredRenderDistanceChunks,
            int effectiveRenderDistanceChunks) {
        public ClientConditions {
            if (framebufferWidth < 0 || framebufferHeight < 0
                    || renderTargetWidth < 0 || renderTargetHeight < 0
                    || fovDegrees < 0
                    || !Double.isFinite(fovEffectScale)
                    || configuredRenderDistanceChunks < 0
                    || effectiveRenderDistanceChunks < 0) {
                throw new IllegalArgumentException("P7 client capture conditions are invalid");
            }
        }

        /** Returns whether this observation satisfies every fixed ADR-017 client condition. */
        public boolean meetsCaptureContract() {
            return framebufferWidth == CAPTURE_FRAMEBUFFER_WIDTH
                    && framebufferHeight == CAPTURE_FRAMEBUFFER_HEIGHT
                    && renderTargetWidth == CAPTURE_FRAMEBUFFER_WIDTH
                    && renderTargetHeight == CAPTURE_FRAMEBUFFER_HEIGHT
                    && hasRequiredAspectRatio()
                    && fovDegrees == CAPTURE_FOV_DEGREES
                    && dynamicFovDisabled()
                    && configuredRenderDistanceChunks >= MIN_CAPTURE_RENDER_DISTANCE_CHUNKS
                    && effectiveRenderDistanceChunks >= MIN_CAPTURE_RENDER_DISTANCE_CHUNKS;
        }

        /** Vanilla exposes the dynamic-FOV setting as its FOV-effect scale. */
        public boolean dynamicFovDisabled() {
            return Double.compare(fovEffectScale, DISABLED_DYNAMIC_FOV_EFFECT_SCALE) == 0;
        }

        /** Checks the actual physical framebuffer, avoiding a GUI-scale or logical-window proxy. */
        public boolean hasRequiredAspectRatio() {
            return (long) framebufferWidth * CAPTURE_ASPECT_RATIO_HEIGHT
                    == (long) framebufferHeight * CAPTURE_ASPECT_RATIO_WIDTH;
        }

        /** Stable local diagnostic detail retained in invalid reports without Minecraft object references. */
        public String description() {
            return "framebuffer=" + framebufferWidth + "x" + framebufferHeight
                    + ", renderTarget=" + renderTargetWidth + "x" + renderTargetHeight
                    + ", fov=" + fovDegrees
                    + ", fovEffectScale=" + fovEffectScale
                    + ", dynamicFovDisabled=" + dynamicFovDisabled()
                    + ", configuredRenderDistance=" + configuredRenderDistanceChunks
                    + ", effectiveRenderDistance=" + effectiveRenderDistanceChunks;
        }
    }

    public record CameraPose(double x, double y, double z, double yawDegrees, double pitchDegrees) {
        public CameraPose {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
                throw new IllegalArgumentException("P7 camera pose must be finite");
            }
        }
    }

    private static String requireNonBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
