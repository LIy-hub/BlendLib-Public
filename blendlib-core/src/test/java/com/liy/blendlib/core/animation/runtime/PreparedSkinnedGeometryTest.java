package com.liy.blendlib.core.animation.runtime;

import com.liy.blendlib.core.model.MeshPrimitive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedSkinnedGeometryTest {
    @Test
    void preparationCapturesIndependentImmutableAttributeCopies() {
        MeshPrimitive primitive = triangle(new int[] {
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0
        }, new float[] {
                1.0F, 0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F, 0.0F
        });

        PreparedSkinnedGeometry prepared = PreparedSkinnedGeometry.prepare(primitive);
        assertEquals(3, prepared.vertexCount());
        assertEquals(0.0F, prepared.positions()[0]);
        assertEquals(1.0F, prepared.normals()[1]);
        assertEquals(0, prepared.joints()[0]);
        assertEquals(1.0F, prepared.weights()[0]);
        SkinnedMeshTopology topology = prepared.topology();
        assertEquals("fixture_material", topology.materialSlot());
        assertEquals(3, topology.vertexCount());
        assertEquals(3, topology.indexCount());
        assertArrayEquals(new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}, topology.texCoords());
        assertArrayEquals(new int[] {0, 1, 2}, topology.indices());

        float[] returnedPositions = prepared.positions();
        float[] returnedNormals = prepared.normals();
        int[] returnedJoints = prepared.joints();
        float[] returnedWeights = prepared.weights();
        float[] returnedTexCoords = topology.texCoords();
        int[] returnedIndices = topology.indices();
        returnedPositions[0] = 99.0F;
        returnedNormals[1] = 99.0F;
        returnedJoints[0] = 99;
        returnedWeights[0] = 99.0F;
        returnedTexCoords[0] = 99.0F;
        returnedIndices[0] = 2;
        assertEquals(0.0F, prepared.positions()[0]);
        assertEquals(1.0F, prepared.normals()[1]);
        assertEquals(0, prepared.joints()[0]);
        assertEquals(1.0F, prepared.weights()[0]);
        assertEquals(0.0F, topology.texCoords()[0]);
        assertEquals(0, topology.indices()[0]);

        float[] primitivePositions = primitive.positions();
        float[] primitiveNormals = primitive.normals();
        int[] primitiveJoints = primitive.joints();
        float[] primitiveWeights = primitive.weights();
        float[] primitiveTexCoords = primitive.texCoords();
        int[] primitiveIndices = primitive.indices();
        primitivePositions[0] = 77.0F;
        primitiveNormals[1] = 77.0F;
        primitiveJoints[0] = 77;
        primitiveWeights[0] = 77.0F;
        primitiveTexCoords[0] = 77.0F;
        primitiveIndices[0] = 2;
        assertEquals(0.0F, prepared.positions()[0]);
        assertEquals(1.0F, prepared.normals()[1]);
        assertEquals(0, prepared.joints()[0]);
        assertEquals(1.0F, prepared.weights()[0]);
        assertEquals(0.0F, topology.texCoords()[0]);
        assertEquals(0, topology.indices()[0]);
    }

    @Test
    void preparationRejectsPrimitiveWithoutSkinAttributes() {
        assertThrows(IllegalArgumentException.class, () -> PreparedSkinnedGeometry.prepare(new MeshPrimitive(
                "fixture_material",
                new float[] {
                        0.0F, 0.0F, 0.0F,
                        1.0F, 0.0F, 0.0F,
                        0.0F, 1.0F, 0.0F
                },
                new float[] {
                        0.0F, 1.0F, 0.0F,
                        0.0F, 1.0F, 0.0F,
                        0.0F, 1.0F, 0.0F
                },
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                null,
                null
        )));
    }

    @Test
    void topologyRejectsInvalidUvAndTriangleIndexCardinality() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkinnedMeshTopology("fixture_material", 3, new float[] {0.0F, 0.0F}, new int[] {0, 1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> new SkinnedMeshTopology("fixture_material", 3,
                        new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}, new int[] {0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> new SkinnedMeshTopology("fixture_material", 3,
                        new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F}, new int[] {0, 1, 3}));
    }

    @Test
    void topologyAndOutputDefensivelyPreserveTheirSharedVertexContract() {
        float[] callerTexCoords = new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
        int[] callerIndices = new int[] {0, 1, 2};
        SkinnedMeshTopology topology = new SkinnedMeshTopology("fixture_material", 3, callerTexCoords, callerIndices);

        callerTexCoords[0] = 99.0F;
        callerIndices[0] = 2;
        assertEquals(0.0F, topology.texCoords()[0]);
        assertEquals(0, topology.indices()[0]);
        assertThrows(IllegalArgumentException.class,
                () -> new CpuSkinnedMesh(topology, new float[6], new float[6]));
    }

    private static MeshPrimitive triangle(int[] joints, float[] weights) {
        return new MeshPrimitive(
                "fixture_material",
                new float[] {
                        0.0F, 0.0F, 0.0F,
                        1.0F, 0.0F, 0.0F,
                        0.0F, 1.0F, 0.0F
                },
                new float[] {
                        0.0F, 1.0F, 0.0F,
                        0.0F, 1.0F, 0.0F,
                        0.0F, 1.0F, 0.0F
                },
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                joints,
                weights
        );
    }
}
