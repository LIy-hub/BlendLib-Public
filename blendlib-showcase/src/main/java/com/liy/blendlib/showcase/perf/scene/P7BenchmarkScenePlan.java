package com.liy.blendlib.showcase.perf.scene;

import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, server-safe projection of the frozen P7 true-in-frustum reference contract.
 *
 * <p>The plan deliberately retains the canonical client model key next to every placement, while
 * leaving model selection to the client adapter. This makes the server-side benchmark command
 * mechanically traceable to {@link P7ReferenceScenario} without transmitting model resources or
 * loading any asset on a dedicated server.</p>
 */
public final class P7BenchmarkScenePlan {
    private static final P7BenchmarkScenePlan STANDARD = createStandard();

    private final List<Placement> placements;
    private final P7ReferenceScenario.CameraPose camera;

    private P7BenchmarkScenePlan(List<Placement> placements, P7ReferenceScenario.CameraPose camera) {
        this.placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        this.camera = Objects.requireNonNull(camera, "camera");
        validate();
    }

    /** Returns the immutable plan derived exactly from the frozen P7 scenario. */
    public static P7BenchmarkScenePlan standard() {
        return STANDARD;
    }

    public List<Placement> placements() {
        return placements;
    }

    public P7ReferenceScenario.CameraPose camera() {
        return camera;
    }

    public int rigidCount() {
        return (int) placements.stream().filter(placement -> placement.kind() == P7ReferenceScenario.Kind.RIGID).count();
    }

    public int skinnedCount() {
        return (int) placements.stream().filter(placement -> placement.kind() == P7ReferenceScenario.Kind.SKINNED).count();
    }

    public void validate() {
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();
        Map<P7ReferenceScenario.Kind, P7ReferenceScenario.Asset> assets = assetsByKind(scenario.assets());
        if (placements.size() != scenario.instances().size()) {
            throw new IllegalArgumentException("P7 benchmark plan has an unexpected placement count");
        }
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            P7ReferenceScenario.Instance source = scenario.instances().get(index);
            P7ReferenceScenario.Asset asset = assets.get(source.kind());
            if (placement.kind() != source.kind()
                    || placement.ordinal() != source.ordinal()
                    || Double.compare(placement.x(), source.x()) != 0
                    || Double.compare(placement.y(), source.y()) != 0
                    || Double.compare(placement.z(), source.z()) != 0
                    || !placement.modelKey().equals(asset.modelKey())) {
                throw new IllegalArgumentException("P7 benchmark plan must preserve the canonical placement and model key");
            }
            validateAcceptedTrueInFrustumLayout(placement);
        }
        if (rigidCount() != P7ReferenceScenario.RIGID_INSTANCE_COUNT
                || skinnedCount() != P7ReferenceScenario.SKINNED_INSTANCE_COUNT) {
            throw new IllegalArgumentException("P7 benchmark plan no longer has the frozen instance counts");
        }
        if (!camera.equals(scenario.camera())) {
            throw new IllegalArgumentException("P7 benchmark plan must retain the accepted capture camera");
        }
    }

    private static P7BenchmarkScenePlan createStandard() {
        P7ReferenceScenario scenario = P7ReferenceScenario.standard();
        Map<P7ReferenceScenario.Kind, P7ReferenceScenario.Asset> assets = assetsByKind(scenario.assets());
        List<Placement> placements = scenario.instances().stream()
                .map(instance -> new Placement(
                        instance.kind(),
                        instance.ordinal(),
                        assets.get(instance.kind()).modelKey(),
                        instance.x(),
                        instance.y(),
                        instance.z()))
                .toList();
        return new P7BenchmarkScenePlan(placements, scenario.camera());
    }

    private static Map<P7ReferenceScenario.Kind, P7ReferenceScenario.Asset> assetsByKind(
            List<P7ReferenceScenario.Asset> assets) {
        Map<P7ReferenceScenario.Kind, P7ReferenceScenario.Asset> byKind =
                new EnumMap<>(P7ReferenceScenario.Kind.class);
        for (P7ReferenceScenario.Asset asset : assets) {
            if (byKind.put(asset.kind(), asset) != null) {
                throw new IllegalArgumentException("P7 scenario must define exactly one asset for each kind");
            }
        }
        return byKind;
    }

    private static void validateAcceptedTrueInFrustumLayout(Placement placement) {
        int ordinal = placement.ordinal();
        double expectedX;
        double expectedY;
        if (placement.kind() == P7ReferenceScenario.Kind.RIGID) {
            if (ordinal >= P7ReferenceScenario.RIGID_INSTANCE_COUNT) {
                throw new IllegalArgumentException("P7 rigid benchmark placement has an invalid ordinal");
            }
            expectedX = -18.0d + 1.5d * (ordinal % 25);
            expectedY = 64.0d + 2.0d * (ordinal / 25);
        } else {
            if (ordinal >= P7ReferenceScenario.SKINNED_INSTANCE_COUNT) {
                throw new IllegalArgumentException("P7 skinned benchmark placement has an invalid ordinal");
            }
            expectedX = -18.0d + 1.5d * ordinal;
            expectedY = 72.0d;
        }
        if (Double.compare(placement.x(), expectedX) != 0
                || Double.compare(placement.y(), expectedY) != 0
                || Double.compare(placement.z(), 0.0d) != 0) {
            throw new IllegalArgumentException("P7 benchmark placement violates the accepted true-in-frustum layout");
        }
    }

    /** A canonical position plus the client-only semantic model key that must render it. */
    public record Placement(P7ReferenceScenario.Kind kind, int ordinal, String modelKey, double x, double y, double z) {
        public Placement {
            kind = Objects.requireNonNull(kind, "kind");
            modelKey = Objects.requireNonNull(modelKey, "modelKey");
            if (modelKey.isBlank() || ordinal < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("P7 benchmark placement is invalid");
            }
        }
    }
}
