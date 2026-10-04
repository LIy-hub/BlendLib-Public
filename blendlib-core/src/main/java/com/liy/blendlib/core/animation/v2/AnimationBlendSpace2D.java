package com.liy.blendlib.core.animation.v2;

import com.liy.blendlib.api.BlendResourceId;
import java.util.*;

/**
 * Bounded directional blendspace: origin/idle plus a sorted ring on one radius. Adjacent-sector
 * barycentric interpolation clamps radially to the polygon hull, not the circle. Inputs and radius
 * use caller-selected units. Authored cycle phases must match; cadence stays fixed at all weights.
 */
public final class AnimationBlendSpace2D {
    public static final double MAX_COORDINATE = 1_000_000;
    private static final double MIN_ANGLE_GAP = 1e-6;
    private final BlendResourceId centerLayerId;
    private final List<Direction> directions;
    private final double radius;
    private final AnimationBlendSpaceSyncGroup group;
    private final double[] xs, ys;

    public AnimationBlendSpace2D(BlendResourceId centerLayerId, List<Direction> directions,
            double radius, double cycleSeconds) {
        this.centerLayerId = Objects.requireNonNull(centerLayerId, "centerLayerId");
        AnimationV2Limits.requireCanonicalIdLength(centerLayerId.value(), "blendspace center id");
        Objects.requireNonNull(directions, "directions");
        if (directions.size() < 3 || directions.size() >= AnimationV2Limits.MAX_CONTROLLERS_PER_INSTANCE)
            throw new IllegalArgumentException("directional blendspace requires 3 to 15 directions plus center");
        if (!Double.isFinite(radius) || radius < 1e-6 || radius > MAX_COORDINATE)
            throw new IllegalArgumentException("radius must be finite and in [0.000001, 1000000]");
        if (!Double.isFinite(cycleSeconds) || cycleSeconds <= 0 || cycleSeconds > AnimationV2Limits.MAX_CLIP_DURATION_SECONDS)
            throw new IllegalArgumentException("cycleSeconds must be finite and in (0, 600]");
        this.radius = radius;
        this.directions = directions.stream().map(d -> Objects.requireNonNull(d, "direction"))
                .sorted(Comparator.comparingDouble(Direction::angleRadians)).toList();
        Set<BlendResourceId> ids = new LinkedHashSet<>(); ids.add(centerLayerId);
        xs = new double[directions.size()]; ys = new double[directions.size()];
        for (int i = 0; i < this.directions.size(); i++) {
            var direction = this.directions.get(i);
            if (!ids.add(direction.layerId())) throw new IllegalArgumentException("duplicate blendspace layer: " + direction.layerId());
            double next = i + 1 == this.directions.size() ? this.directions.getFirst().angleRadians() + 2 * Math.PI
                    : this.directions.get(i + 1).angleRadians();
            double gap = next - direction.angleRadians();
            if (gap < MIN_ANGLE_GAP || gap > Math.PI - MIN_ANGLE_GAP)
                throw new IllegalArgumentException("direction sectors must be in [0.000001, pi-0.000001] radians");
            xs[i] = Math.cos(direction.angleRadians()); ys[i] = Math.sin(direction.angleRadians());
        }
        group = new AnimationBlendSpaceSyncGroup(ids, cycleSeconds);
    }

    /** Canonical angle in [0, 2*pi); no implicit wrapping. Zero points along positive X. */
    public record Direction(double angleRadians, BlendResourceId layerId) {
        public Direction {
            if (!Double.isFinite(angleRadians) || angleRadians < 0 || angleRadians >= 2 * Math.PI)
                throw new IllegalArgumentException("angleRadians must be in [0, 2*pi)");
            if (angleRadians == 0) angleRadians = 0; // canonicalize negative zero
            Objects.requireNonNull(layerId, "layerId");
            AnimationV2Limits.requireCanonicalIdLength(layerId.value(), "blendspace direction id");
        }
    }
    /** Validated finite capture; negative zero behaves exactly like zero. */
    public record Input(double x, double y) {
        public Input {
            if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > MAX_COORDINATE || Math.abs(y) > MAX_COORDINATE)
                throw new IllegalArgumentException("directional input coordinates must be finite and within +/-1000000");
        }
    }
    public BlendResourceId centerLayerId() { return centerLayerId; }
    public List<Direction> directions() { return directions; }
    public double radius() { return radius; }
    public Set<BlendResourceId> memberLayerIds() { return group.memberLayerIds(); }
    public double cycleSeconds() { return group.cycleSeconds(); }
    public AnimationBlendSpaceSyncGroup syncGroup() { return group; }
    public AnimationBlendSpaceSyncGroup.Binding bind(AnimationV2InstancePlan plan) { return group.bind(plan); }
    public void validateExternalWeights(AnimationV2LayerWeights weights) { group.validateExternalWeights(weights); }
    public void validateExternalCommands(List<AnimationV2Command> commands) { group.validateExternalCommands(commands); }
    public AnimationV2LayerWeights weights(double x, double y) { return weights(new Input(x, y)); }

    /** Returns all member keys, including explicit zeros. Barycentric residue up to 1e-10
     * snaps to a direction/hull boundary for exact inactive event suppression. No extrapolation. */
    public AnimationV2LayerWeights weights(Input input) {
        Objects.requireNonNull(input, "input");
        var result = new LinkedHashMap<AnimationV2LayerWeights.Key, Float>();
        for (var id : memberLayerIds()) result.put(key(id), 0F);
        if (input.x() == 0 && input.y() == 0) {
            result.put(key(centerLayerId), 1F); return new AnimationV2LayerWeights(result);
        }
        double angle = Math.atan2(input.y(), input.x());
        if (angle < 0) angle += 2 * Math.PI;
        // Handle authored rays before dividing by a nearly-flat sector determinant. atan2(cos,
        // sin) can differ by a few ulps even for exact authored coordinates; absolute angular
        // tolerance is bounded far below the minimum allowed sector gap (1e-6).
        for (var direction : directions) {
            double distance = Math.abs(angle - direction.angleRadians());
            distance = Math.min(distance, 2 * Math.PI - distance);
            if (distance <= 8 * Math.ulp(2 * Math.PI)) {
                double radial = Math.hypot(input.x(), input.y()) / radius;
                float ring = radial >= 1 - 1e-10 ? 1F : (float) radial;
                result.put(key(direction.layerId()), ring);
                result.put(key(centerLayerId), 1F - ring);
                return new AnimationV2LayerWeights(result);
            }
        }
        int right = 0;
        while (right < directions.size() && angle > directions.get(right).angleRadians()) right++;
        right %= directions.size();
        int left = (right + directions.size() - 1) % directions.size();
        double x = input.x() / radius, y = input.y() / radius;
        double determinant = xs[left] * ys[right] - ys[left] * xs[right];
        double a = Math.max(0, (x * ys[right] - y * xs[right]) / determinant);
        double b = Math.max(0, (xs[left] * y - ys[left] * x) / determinant);
        double sum = a + b;
        // A bounded dimensionless tolerance removes trig/cross-product residue at directions and
        // polygon edges. Exact inactive zeros matter to marker suppression, not just visual error.
        boolean onHull = sum >= 1 - 1e-10;
        if (onHull) { a /= sum; b /= sum; }
        if (a <= 1e-10) a = 0;
        if (b <= 1e-10) b = 0;
        float wa, wb, center;
        if (onHull) {
            wa = (float) (a / (a + b));
            wb = 1F - wa;
            center = 0F;
        } else {
            wa = (float) a;
            wb = Math.min(1F - wa, (float) b);
            center = Math.max(0F, 1F - (wa + wb));
        }
        result.put(key(directions.get(left).layerId()), wa);
        result.put(key(directions.get(right).layerId()), wb);
        result.put(key(centerLayerId), center);
        return new AnimationV2LayerWeights(result);
    }
    private static AnimationV2LayerWeights.Key key(BlendResourceId id) { return new AnimationV2LayerWeights.Key(id, id); }
}
