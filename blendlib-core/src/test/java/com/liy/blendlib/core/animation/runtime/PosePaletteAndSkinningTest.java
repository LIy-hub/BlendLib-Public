package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.AnimationChannel;
import com.liy.blendlib.core.animation.AnimationClip;
import com.liy.blendlib.core.animation.AnimationPath;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.SocketTable;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PosePaletteAndSkinningTest {
    @Test
    void samplesImmutableRigidTwoNodePaletteAndSocketTransform() {
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "root", Transform.IDENTITY, List.of(1), -1, -1, false),
                new ModelNode(1, "child", translate(0.0f, 1.0f, 0.0f), List.of(), -1, -1, false));
        AnimationClip clip = new AnimationClip("move", List.of(new AnimationChannel(
                0,
                AnimationPath.TRANSLATION,
                Interpolation.LINEAR,
                new float[] {0.0f, 1.0f},
                new float[] {0.0f, 0.0f, 0.0f, 2.0f, 0.0f, 0.0f})));
        AnimationState state = new AnimationState(
                BlendAnimationKey.parse("fixture:move"), clip, false, 1.0, 0.0, null, List.of());
        LocalPose localPose = new PoseSampler(nodes).sample(state, 0.5);
        NodePalette palette = NodePalette.from(localPose, nodes);

        assertEquals(1.0f, palette.worldTransform(0).translation().x(), 1.0e-5f);
        assertEquals(1.0f, palette.worldTransform(1).translation().x(), 1.0e-5f);
        assertEquals(1.0f, palette.worldTransform(1).translation().y(), 1.0e-5f);
        assertThrows(UnsupportedOperationException.class,
                () -> localPose.transforms().put(9, Transform.IDENTITY));

        BlendResourceId socketKey = BlendResourceId.parse("fixture:tip");
        SocketTable sockets = new SocketTable(Map.of(socketKey, new SocketTable.Socket(1, "root/child")));
        Transform socket = SocketWorldTransform.query(sockets, palette, socketKey).orElseThrow();
        assertEquals(1.0f, socket.translation().x(), 1.0e-5f);
        assertEquals(1.0f, socket.translation().y(), 1.0e-5f);
    }

    @Test
    void buildsTwoJointPaletteAndCpuSkinsDeterministicPositionsAndNormals() {
        List<ModelNode> nodes = List.of(
                new ModelNode(0, "root_joint", Transform.IDENTITY, List.of(1), -1, -1, false),
                new ModelNode(1, "child_joint", translate(1.0f, 0.0f, 0.0f), List.of(), -1, -1, false));
        LocalPose pose = new LocalPose(Map.of(0, Transform.IDENTITY, 1, translate(1.0f, 0.0f, 0.0f)));
        Skin skin = new Skin("two_joint", 0, List.of(0, 1), identityMatrices(2));
        SkinPalette palette = SkinPalette.from(skin, NodePalette.from(pose, nodes));
        assertEquals(0.0f, palette.matrix(0).get(3, 0), 1.0e-5f);
        assertEquals(1.0f, palette.matrix(1).get(3, 0), 1.0e-5f);

        MeshPrimitive sourceGeometry = skinnedTriangle(new int[] {
            0, 1, 0, 0,
            1, 0, 0, 0,
            0, 0, 0, 0
        });
        PreparedSkinnedGeometry geometry = PreparedSkinnedGeometry.prepare(sourceGeometry);
        CpuSkinnedMesh result = CpuSkinner.skin(geometry, palette);
        assertEquals(3, result.vertexCount());
        assertSame(geometry.topology(), result.topology());
        assertEquals(result.vertexCount(), result.topology().vertexCount());
        assertArrayEquals(new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}, result.topology().texCoords());
        assertArrayEquals(new int[] {0, 1, 2}, result.topology().indices());
        assertEquals("fixture_material", result.topology().materialSlot());
        assertEquals(0.5f, result.positions()[0], 1.0e-5f);
        assertEquals(0.0f, result.positions()[1], 1.0e-5f);
        assertEquals(0.0f, result.positions()[2], 1.0e-5f);
        assertEquals(1.0f, result.positions()[3], 1.0e-5f);
        assertEquals(1.0f, result.positions()[4], 1.0e-5f);
        assertEquals(0.0f, result.positions()[5], 1.0e-5f);
        assertEquals(0.0f, result.normals()[0], 1.0e-5f);
        assertEquals(1.0f, result.normals()[1], 1.0e-5f);
        assertEquals(0.0f, result.normals()[2], 1.0e-5f);
        CpuSkinnedMesh repeated = CpuSkinner.skin(geometry, palette);
        assertArrayEquals(result.positions(), repeated.positions());
        assertArrayEquals(result.normals(), repeated.normals());
        assertSame(result.topology(), repeated.topology());

        int[] returned = sourceGeometry.joints();
        returned[0] = 99;
        assertEquals(0, sourceGeometry.joints()[0]);
        assertEquals(0, geometry.joints()[0]);
        assertThrows(IllegalArgumentException.class,
                () -> CpuSkinner.skin(PreparedSkinnedGeometry.prepare(skinnedTriangle(new int[] {
                        0, 2, 0, 0,
                        1, 0, 0, 0,
                        0, 0, 0, 0
                })), palette));
    }

    private static Transform translate(float x, float y, float z) {
        return new Transform(new Vec3(x, y, z), com.liy.blendlib.core.model.Quaternion.IDENTITY, Vec3.ONE);
    }

    private static float[] identityMatrices(int count) {
        float[] values = new float[count * 16];
        for (int index = 0; index < count; index++) {
            int offset = index * 16;
            values[offset] = 1.0f;
            values[offset + 5] = 1.0f;
            values[offset + 10] = 1.0f;
            values[offset + 15] = 1.0f;
        }
        return values;
    }

    private static MeshPrimitive skinnedTriangle(int[] joints) {
        return new MeshPrimitive(
                "fixture_material",
                new float[] {
                    0.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 1.0f
                },
                new float[] {
                    0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f
                },
                new float[] {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new int[] {0, 1, 2},
                joints,
                new float[] {
                    0.5f, 0.5f, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f, 0.0f,
                    1.0f, 0.0f, 0.0f, 0.0f
                });
    }
}
