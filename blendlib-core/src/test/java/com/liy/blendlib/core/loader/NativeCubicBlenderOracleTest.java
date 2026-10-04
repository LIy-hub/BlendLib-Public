package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.Interpolation;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.json.*;
import com.liy.blendlib.core.model.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** Golden observations come from Blender's pose/depsgraph, not the exporter or Java interpolation helpers. */
class NativeCubicBlenderOracleTest {
    private static final double TOLERANCE=2e-5;
    @Test
    void actualBlenderCurveExportMatchesEvaluatedBoneMatricesAndCpuVerticesAtNonKeyTimes() throws Exception {
        Path root=Path.of(System.getProperty("blendlib.projectDir")).getParent().resolve("test-assets/native-cubic");
        Path assets=root.resolve("exported/assets/native_cubic");
        ModelAsset asset=ModelAssetLoader.runtimeProfiles().load(BlendResourceId.parse("native_cubic:eased_actor"),19,
                new AssetBytes(BlendResourceId.parse("native_cubic:blend_models/eased_actor.json"),
                        Files.readAllBytes(assets.resolve("blend_models/eased_actor.json"))),
                id->{try{return new AssetBytes(id,Files.readAllBytes(assets.resolve(id.path())));}catch(Exception e){throw new RuntimeException(e);}});
        assertEquals(ModelProfile.SKINNED_CUBIC_V1,asset.profile());
        assertEquals(3,asset.clips().getFirst().channels().size());
        asset.clips().getFirst().channels().forEach(channel->{assertEquals(Interpolation.CUBICSPLINE,channel.interpolation());assertEquals(3,channel.keyCount());});
        JsonObject oracle=(JsonObject)StrictJsonParser.parse(Files.readAllBytes(root.resolve("oracle-samples.json")));
        assertEquals("5.1.2",((JsonString)oracle.get("blender_version")).value());
        JsonArray samples=(JsonArray)oracle.get("samples"); assertEquals(14,samples.size());
        PoseSampler sampler=PoseSampler.fromModelAsset(asset);
        var state=AnimationControllerDefinition.fromModelAsset(asset).state(BlendAnimationKey.parse("native_cubic:wave"));
        int verticesChecked=0,matricesChecked=0;
        double maximumVertexError=0,maximumMatrixError=0;
        for(JsonValue item:samples.values()) {
            JsonObject sample=(JsonObject)item;
            double seconds=((JsonNumber)sample.get("seconds")).asDouble();
            NodePalette palette=NodePalette.fromCanonicalScene(sampler.sample(state,seconds),asset.nodes(),asset.defaultSceneRoots());
            JsonObject bones=(JsonObject)sample.get("bone_world_matrices");
            for(var entry:bones.values().entrySet()) {
                int node=asset.nodes().stream().filter(n->n.name().equals(entry.getKey())).findFirst().orElseThrow().index();
                Transform world=palette.worldTransform(node); JsonArray expected=(JsonArray)entry.getValue();
                Vec3 origin=world.transformPoint(Vec3.ZERO);
                Vec3[] points={world.transformPoint(new Vec3(1,0,0)),world.transformPoint(new Vec3(0,1,0)),world.transformPoint(new Vec3(0,0,1)),origin};
                for(int column=0;column<4;column++) {
                    Vec3 point=points[column];float[] actual={point.x(),point.y(),point.z()};float[] base={origin.x(),origin.y(),origin.z()};
                    for(int row=0;row<3;row++) {
                        double value=actual[row]-(column<3?base[row]:0);
                        double expectedValue=((JsonNumber)expected.get(row*4+column)).asDouble();
                        maximumMatrixError=Math.max(maximumMatrixError,Math.abs(value-expectedValue));
                        assertEquals(expectedValue,value,TOLERANCE,"bone="+entry.getKey()+" seconds="+seconds);
                    }
                }
                matricesChecked++;
            }
            JsonObject positions=(JsonObject)sample.get("skinned_world_positions");
            for(ModelPrimitive primitive:asset.primitives()) {
                ModelNode node=asset.nodes().stream().filter(n->n.index()==primitive.nodeIndex()).findFirst().orElseThrow();
                JsonArray expected=(JsonArray)positions.get(node.name()); assertNotNull(expected,node.name());
                float[] actual=CpuSkinner.skin(PreparedSkinnedGeometry.prepare(primitive.geometry()),
                        SkinPalette.from(asset.skeleton().skins().get(node.skinIndex()),palette)).positions();
                assertEquals(expected.size()*3,actual.length);
                for(int vertex=0;vertex<expected.size();vertex++) {
                    JsonArray xyz=(JsonArray)expected.get(vertex);
                    for(int component=0;component<3;component++) {
                        double expectedValue=((JsonNumber)xyz.get(component)).asDouble();
                        maximumVertexError=Math.max(maximumVertexError,Math.abs(expectedValue-actual[vertex*3+component]));
                        assertEquals(expectedValue,actual[vertex*3+component],TOLERANCE,node.name()+" vertex="+vertex+" seconds="+seconds);
                    }
                    assertTrue(actual[vertex*3]>=asset.bounds().min().x() && actual[vertex*3]<=asset.bounds().max().x());
                    assertTrue(actual[vertex*3+1]>=asset.bounds().min().y() && actual[vertex*3+1]<=asset.bounds().max().y());
                    assertTrue(actual[vertex*3+2]>=asset.bounds().min().z() && actual[vertex*3+2]<=asset.bounds().max().z());
                    verticesChecked++;
                }
            }
        }
        assertEquals(28,matricesChecked); assertEquals(1008,verticesChecked);
        System.out.println("Blender-to-Java native cubic oracle: 28 matrices, 1008 vertices; max matrix error="+maximumMatrixError+", max vertex error="+maximumVertexError);
    }
}
