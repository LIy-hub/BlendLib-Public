package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalNodePaletteTest {
    @Test
    void usesDefaultSceneRootOrderAndOnlyReachableDescendants() {
        List<ModelNode> nodes = nodes();
        LocalPose activeOnlyPose = new LocalPose(Map.of(
                0, translate(1.0f, 0.0f, 0.0f),
                1, translate(0.0f, 2.0f, 0.0f),
                3, translate(0.0f, 0.0f, 3.0f),
                5, translate(-1.0f, 0.0f, 0.0f)));

        NodePalette palette = NodePalette.fromCanonicalScene(activeOnlyPose, nodes, List.of(5, 0));

        assertEquals(List.of(5, 0, 1, 3), List.copyOf(palette.worldTransforms().keySet()));
        assertTranslation(-1.0f, 0.0f, 0.0f, palette.worldTransform(5));
        assertTranslation(1.0f, 2.0f, 0.0f, palette.worldTransform(1));
        assertTranslation(1.0f, 2.0f, 3.0f, palette.worldTransform(3));
        assertFalse(palette.worldTransforms().containsKey(2));
        assertFalse(palette.worldTransforms().containsKey(4));
        assertThrows(IllegalArgumentException.class, () -> palette.worldTransform(2));
        assertThrows(UnsupportedOperationException.class,
                () -> palette.worldTransforms().put(9, Transform.IDENTITY));
    }

    @Test
    void rejectsDuplicateOrInvalidCanonicalRootsAndDuplicateActiveReachability() {
        LocalPose completePose = completePose();

        assertThrows(IllegalArgumentException.class,
                () -> NodePalette.fromCanonicalScene(completePose, nodes(), List.of(0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> NodePalette.fromCanonicalScene(completePose, nodes(), List.of(99)));
        assertThrows(IllegalArgumentException.class,
                () -> NodePalette.fromCanonicalScene(completePose, nodes(), List.of(0, 1)));
    }

    @Test
    void validatesNodeChildrenAndStructuralParentAmbiguity() {
        LocalPose pose = new LocalPose(Map.of(
                0, Transform.IDENTITY,
                1, Transform.IDENTITY,
                2, Transform.IDENTITY));
        List<ModelNode> missingChild = List.of(
                node(0, List.of(9)),
                node(1, List.of()));
        List<ModelNode> multipleParents = List.of(
                node(0, List.of(2)),
                node(1, List.of(2)),
                node(2, List.of()));
        List<ModelNode> duplicateNodeIndex = List.of(
                node(0, List.of()),
                node(0, List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> NodePalette.fromCanonicalScene(pose, missingChild, List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> NodePalette.fromCanonicalScene(pose, multipleParents, List.of(0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> NodePalette.fromCanonicalScene(pose, duplicateNodeIndex, List.of(0)));
    }

    @Test
    void legacyFromStillComposesAllStructuralRoots() {
        NodePalette structural = NodePalette.from(completePose(), nodes());

        assertEquals(List.of(0, 2, 5, 1, 4, 3), List.copyOf(structural.worldTransforms().keySet()));
        assertTranslation(20.0f, 0.0f, 0.0f, structural.worldTransform(2));
        assertTranslation(20.0f, 4.0f, 0.0f, structural.worldTransform(4));
    }

    private static List<ModelNode> nodes() {
        return List.of(
                node(0, List.of(1)),
                node(1, List.of(3)),
                node(2, List.of(4)),
                node(3, List.of()),
                node(4, List.of()),
                node(5, List.of()));
    }

    private static LocalPose completePose() {
        return new LocalPose(Map.of(
                0, translate(1.0f, 0.0f, 0.0f),
                1, translate(0.0f, 2.0f, 0.0f),
                2, translate(20.0f, 0.0f, 0.0f),
                3, translate(0.0f, 0.0f, 3.0f),
                4, translate(0.0f, 4.0f, 0.0f),
                5, translate(-1.0f, 0.0f, 0.0f)));
    }

    private static ModelNode node(int index, List<Integer> children) {
        return new ModelNode(index, "node_" + index, Transform.IDENTITY, children, -1, -1, false);
    }

    private static Transform translate(float x, float y, float z) {
        return new Transform(new Vec3(x, y, z), Quaternion.IDENTITY, Vec3.ONE);
    }

    private static void assertTranslation(float x, float y, float z, Transform transform) {
        assertEquals(x, transform.translation().x(), 1.0e-5f);
        assertEquals(y, transform.translation().y(), 1.0e-5f);
        assertEquals(z, transform.translation().z(), 1.0e-5f);
    }
}
