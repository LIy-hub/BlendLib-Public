package com.liy.blendlib.core.model;

import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Load-time conservative envelope for every strict-v1 clip and pose cross-fade.
 *
 * <p>This is deliberately package-private model preparation, not a public asset-format
 * contract. The proof uses the strict-v1 positive uniform-scale rule: for a node-local point of
 * radius {@code r}, every world pose is bounded by {@code A + B*r}. Unit rotations and quaternion
 * slerp do not increase a vector norm. LINEAR/STEP translation and uniform scale, including
 * cross-fade interpolation between two sampled poses, stay inside the extrema of their finite key
 * values. Therefore the hierarchy recurrence is {@code Achild=Aparent+Bparent*Tchild} and
 * {@code Bchild=Bparent*Schild}.</p>
 *
 * <p>Rigid vertices use their actual local radius. A skinned positive-weight influence first uses
 * its inverse-bind affine transform and then the bound for its joint node. CPU skinning divides a
 * non-negative weighted sum by its positive total weight, so the result is a convex combination
 * and cannot exceed the largest influencing-joint bound. Work is linear in nodes, decoded channel
 * values, and four influences per vertex; it performs no temporal sampling.</p>
 */
final class ConservativeAnimatedBounds {
    /*
     * IEEE-754 binary32 unit roundoff is 2^-24. Even assigning a deliberately loose 100 rounded
     * operations to every one of the 256 permitted hierarchy levels, plus palette construction
     * and four-way skin accumulation, gives a standard gamma bound below 0.002. One percent plus
     * an absolute floor therefore covers runtime float composition after the real-arithmetic norm
     * proof. Values that cannot retain this margin as a finite float are rejected at load time.
     */
    private static final double FLOAT_ROUNDOFF_FACTOR = 1.01;
    private static final double FLOAT_ROUNDOFF_ABSOLUTE = 1.0e-4;

    private ConservativeAnimatedBounds() {
    }

    static Bounds includeAnimations(
            Bounds restBounds,
            List<ModelNode> nodes,
            List<Integer> defaultSceneRoots,
            List<ModelPrimitive> primitives,
            Skeleton skeleton,
            List<AnimationClip> clips) {
        Bounds checkedRestBounds = Objects.requireNonNull(restBounds, "restBounds");
        List<AnimationClip> checkedClips = List.copyOf(Objects.requireNonNull(clips, "clips"));
        List<ModelNode> checkedNodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        List<ModelPrimitive> checkedPrimitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
        boolean hasSkinnedPrimitive = checkedPrimitives.stream()
                .anyMatch(primitive -> primitive.geometry().skinned());
        if (checkedClips.isEmpty() && !hasSkinnedPrimitive) {
            return checkedRestBounds;
        }
        if (checkedNodes.isEmpty() || checkedPrimitives.isEmpty()) {
            throw invalid("Animated bounds require nodes and renderable primitives");
        }

        Map<Integer, Integer> nodeOrdinals = new HashMap<>();
        for (int ordinal = 0; ordinal < checkedNodes.size(); ordinal++) {
            int nodeIndex = checkedNodes.get(ordinal).index();
            if (nodeOrdinals.putIfAbsent(nodeIndex, ordinal) != null) {
                throw invalid("Animated bounds require unique model-node indices");
            }
        }

        double[] localTranslationRadius = new double[checkedNodes.size()];
        double[] localScaleMaximum = new double[checkedNodes.size()];
        for (int ordinal = 0; ordinal < checkedNodes.size(); ordinal++) {
            Transform rest = checkedNodes.get(ordinal).localTransform();
            localTranslationRadius[ordinal] = norm(
                    rest.translation().x(), rest.translation().y(), rest.translation().z());
            localScaleMaximum[ordinal] = uniformScale(rest.scale());
        }
        includeChannelExtrema(
                checkedClips, nodeOrdinals, localTranslationRadius, localScaleMaximum);

        double[] worldOffsetRadius = new double[checkedNodes.size()];
        double[] worldScaleMaximum = new double[checkedNodes.size()];
        Arrays.fill(worldOffsetRadius, Double.NaN);
        Arrays.fill(worldScaleMaximum, Double.NaN);
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (Integer rootValue : List.copyOf(Objects.requireNonNull(defaultSceneRoots, "defaultSceneRoots"))) {
            int rootIndex = Objects.requireNonNull(rootValue, "defaultSceneRoot");
            int root = ordinal(nodeOrdinals, rootIndex, "default-scene root");
            if (!Double.isNaN(worldOffsetRadius[root])) {
                throw invalid("Animated bounds require unique default-scene roots");
            }
            worldOffsetRadius[root] = finiteMagnitude(localTranslationRadius[root], "root translation bound");
            worldScaleMaximum[root] = finiteMagnitude(localScaleMaximum[root], "root scale bound");
            pending.addLast(root);
        }
        if (pending.isEmpty()) {
            throw invalid("Animated bounds require a non-empty default scene");
        }

        while (!pending.isEmpty()) {
            int parent = pending.removeFirst();
            for (int childIndex : checkedNodes.get(parent).children()) {
                int child = ordinal(nodeOrdinals, childIndex, "node child");
                if (!Double.isNaN(worldOffsetRadius[child])) {
                    throw invalid("Animated bounds canonical hierarchy reaches a node more than once");
                }
                worldOffsetRadius[child] = safeAdd(
                        worldOffsetRadius[parent],
                        safeMultiply(worldScaleMaximum[parent], localTranslationRadius[child],
                                "hierarchy translation bound"),
                        "hierarchy translation bound");
                worldScaleMaximum[child] = safeMultiply(
                        worldScaleMaximum[parent], localScaleMaximum[child], "hierarchy scale bound");
                pending.addLast(child);
            }
        }

        double radius = 0.0;
        for (ModelPrimitive primitive : checkedPrimitives) {
            int primitiveNode = ordinal(nodeOrdinals, primitive.nodeIndex(), "primitive node");
            if (Double.isNaN(worldOffsetRadius[primitiveNode])) {
                throw invalid("Animated bounds primitive lies outside the canonical scene");
            }
            if (primitive.geometry().skinned()) {
                radius = Math.max(radius, skinnedPrimitiveRadius(
                        checkedNodes.get(primitiveNode), primitive.geometry(), skeleton, nodeOrdinals,
                        worldOffsetRadius, worldScaleMaximum));
            } else {
                double localRadius = maximumPositionRadius(primitive.geometry().positions());
                radius = Math.max(radius, safeAdd(
                        worldOffsetRadius[primitiveNode],
                        safeMultiply(worldScaleMaximum[primitiveNode], localRadius, "rigid vertex bound"),
                        "rigid vertex bound"));
            }
        }

        double runtimeSafeRadius = safeAdd(
                safeMultiply(radius, FLOAT_ROUNDOFF_FACTOR, "runtime roundoff margin"),
                FLOAT_ROUNDOFF_ABSOLUTE,
                "runtime roundoff margin");
        float outwardRadius = outwardFloat(runtimeSafeRadius);
        Bounds animationEnvelope = new Bounds(
                new Vec3(-outwardRadius, -outwardRadius, -outwardRadius),
                new Vec3(outwardRadius, outwardRadius, outwardRadius));
        return checkedRestBounds.union(animationEnvelope);
    }

    private static void includeChannelExtrema(
            List<AnimationClip> clips,
            Map<Integer, Integer> nodeOrdinals,
            double[] translationRadius,
            double[] scaleMaximum) {
        for (AnimationClip clip : clips) {
            for (AnimationChannel channel : clip.channels()) {
                int target = ordinal(nodeOrdinals, channel.targetNode(), "animation target");
                float[] values = channel.values();
                if (channel.path() == AnimationPath.TRANSLATION) {
                    for (int offset = 0; offset < values.length; offset += 3) {
                        translationRadius[target] = Math.max(
                                translationRadius[target], norm(values[offset], values[offset + 1], values[offset + 2]));
                    }
                } else if (channel.path() == AnimationPath.SCALE) {
                    for (int offset = 0; offset < values.length; offset += 3) {
                        scaleMaximum[target] = Math.max(
                                scaleMaximum[target], uniformScale(new Vec3(
                                        values[offset], values[offset + 1], values[offset + 2])));
                    }
                }
            }
        }
    }

    private static double skinnedPrimitiveRadius(
            ModelNode primitiveNode,
            MeshPrimitive geometry,
            Skeleton skeleton,
            Map<Integer, Integer> nodeOrdinals,
            double[] worldOffsetRadius,
            double[] worldScaleMaximum) {
        if (skeleton == null || primitiveNode.skinIndex() < 0
                || primitiveNode.skinIndex() >= skeleton.skins().size()) {
            throw invalid("Animated skinned bounds require the primitive's decoded skin");
        }
        Skin skin = skeleton.skins().get(primitiveNode.skinIndex());
        float[] positions = geometry.positions();
        int[] joints = geometry.joints();
        float[] weights = geometry.weights();
        double result = 0.0;
        for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
            int positionOffset = vertex * 3;
            int influenceOffset = vertex * 4;
            boolean positiveInfluence = false;
            for (int influence = 0; influence < 4; influence++) {
                if (!(weights[influenceOffset + influence] > 0.0f)) {
                    continue;
                }
                positiveInfluence = true;
                int jointSlot = joints[influenceOffset + influence];
                if (jointSlot < 0 || jointSlot >= skin.joints().size()) {
                    throw invalid("Animated bounds found a joint slot outside the primitive skin");
                }
                int joint = ordinal(nodeOrdinals, skin.joints().get(jointSlot), "skin joint");
                if (Double.isNaN(worldOffsetRadius[joint])) {
                    throw invalid("Animated bounds skin joint lies outside the canonical scene");
                }
                double inverseBoundRadius = inverseBoundRadius(
                        skin.inverseBindMatrix(jointSlot),
                        positions[positionOffset], positions[positionOffset + 1], positions[positionOffset + 2]);
                result = Math.max(result, safeAdd(
                        worldOffsetRadius[joint],
                        safeMultiply(worldScaleMaximum[joint], inverseBoundRadius, "skinned vertex bound"),
                        "skinned vertex bound"));
            }
            if (!positiveInfluence) {
                throw invalid("Animated bounds require a positive skin influence for every vertex");
            }
        }
        return result;
    }

    private static double inverseBoundRadius(Matrix4 matrix, float x, float y, float z) {
        double transformedX = matrix.get(0, 0) * x + matrix.get(1, 0) * y
                + matrix.get(2, 0) * z + matrix.get(3, 0);
        double transformedY = matrix.get(0, 1) * x + matrix.get(1, 1) * y
                + matrix.get(2, 1) * z + matrix.get(3, 1);
        double transformedZ = matrix.get(0, 2) * x + matrix.get(1, 2) * y
                + matrix.get(2, 2) * z + matrix.get(3, 2);
        double transformedW = matrix.get(0, 3) * x + matrix.get(1, 3) * y
                + matrix.get(2, 3) * z + matrix.get(3, 3);
        if (!Double.isFinite(transformedW) || Math.abs(transformedW - 1.0) > 1.0e-5) {
            throw invalid("Animated bounds require affine inverse-bind point transforms");
        }
        return norm(transformedX, transformedY, transformedZ);
    }

    private static double maximumPositionRadius(float[] positions) {
        double result = 0.0;
        for (int offset = 0; offset < positions.length; offset += 3) {
            result = Math.max(result, norm(positions[offset], positions[offset + 1], positions[offset + 2]));
        }
        return result;
    }

    private static double uniformScale(Vec3 scale) {
        Transform.validateStrictV1Scale(scale);
        return finiteMagnitude(Math.max(scale.x(), Math.max(scale.y(), scale.z())), "local scale bound");
    }

    private static double norm(double x, double y, double z) {
        return finiteMagnitude(Math.hypot(Math.hypot(x, y), z), "vector norm bound");
    }

    private static int ordinal(Map<Integer, Integer> ordinals, int nodeIndex, String role) {
        Integer ordinal = ordinals.get(nodeIndex);
        if (ordinal == null) {
            throw invalid("Animated bounds references an absent " + role + ": " + nodeIndex);
        }
        return ordinal;
    }

    private static double safeMultiply(double left, double right, String role) {
        return finiteMagnitude(left * right, role);
    }

    private static double safeAdd(double left, double right, String role) {
        return finiteMagnitude(left + right, role);
    }

    private static double finiteMagnitude(double value, String role) {
        if (!Double.isFinite(value) || value < 0.0 || value > Float.MAX_VALUE) {
            throw invalid("Animated bounds " + role + " exceeds the finite float envelope");
        }
        return value;
    }

    private static float outwardFloat(double radius) {
        double checkedRadius = finiteMagnitude(radius, "radius");
        float result = (float) checkedRadius;
        if ((double) result < checkedRadius) {
            result = Math.nextUp(result);
        }
        if (!Float.isFinite(result)) {
            throw invalid("Animated bounds radius cannot be represented as a finite float");
        }
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
