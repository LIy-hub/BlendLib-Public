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

/** Actual evaluated Blender positions/weights and an independent glTF delta-normal math oracle. */
class CpuMorphBlenderOracleTest {
    @Test void realBlenderMorphExportMatchesWeightsPositionsAndIndependentNormalMath() throws Exception {
        Path root=Path.of(System.getProperty("blendlib.projectDir")).getParent().resolve("test-assets/cpu-morph");
        Path assets=root.resolve("exported/assets/cpu_morph");
        ModelAsset asset=ModelAssetLoader.runtimeProfiles().load(BlendResourceId.parse("cpu_morph:face_actor"),28,
                new AssetBytes(BlendResourceId.parse("cpu_morph:blend_models/face_actor.json"),Files.readAllBytes(assets.resolve("blend_models/face_actor.json"))),
                id->{try{return new AssetBytes(id,Files.readAllBytes(assets.resolve(id.path())));}catch(Exception e){throw new RuntimeException(e);}});
        assertEquals(ModelProfile.SKINNED_MORPH_CPU_V1,asset.profile());
        assertEquals(2,asset.primitives().size()); assertEquals(3,asset.morphBindings().weightCount());
        assertTrue(asset.clips().stream().anyMatch(c->c.channels().isEmpty()&&c.hasMorphChannels()));
        assertTrue(asset.clips().stream().flatMap(c->c.channels().stream()).anyMatch(c->c.interpolation()==Interpolation.CUBICSPLINE));
        JsonObject oracle=(JsonObject)StrictJsonParser.parse(Files.readAllBytes(root.resolve("oracle-samples.json")),
                new StrictJsonParser.Limits(64,16_384,16_384,16_384,300_000,2_000_000,8*1024*1024));
        PoseSampler poses=PoseSampler.fromModelAsset(asset); MorphWeightSampler morphs=MorphWeightSampler.fromModelAsset(asset);
        var definitions=AnimationControllerDefinition.fromModelAsset(asset);
        int samples=0,vertices=0,weights=0,matrices=0; double maxPosition=0,maxNormal=0,maxWeight=0,maxMatrix=0;
        for(var clipItem:((JsonArray)oracle.get("clips")).values()) {
            var clip=(JsonObject)clipItem; String clipName=((JsonString)clip.get("clip")).value();
            var state=definitions.states().values().stream().filter(s->s.clip().name().equals(clipName)).findFirst().orElseThrow();
            for(var sampleItem:((JsonArray)clip.get("samples")).values()) {
                var sample=(JsonObject)sampleItem; double seconds=number(sample.get("seconds")); samples++;
                var palette=NodePalette.fromCanonicalScene(poses.sample(state,seconds),asset.nodes(),asset.defaultSceneRoots());
                var sampled=morphs.sample(state,seconds);
                var expectedWeights=(JsonObject)sample.get("weights");
                for(var binding:asset.morphBindings().bindings()) {
                    var values=(JsonArray)expectedWeights.values().values().iterator().next();
                    for(int t=0;t<binding.targetCount();t++) {
                        double expected=number(values.get(t)),actual=sampled.weight(binding.nodeIndex(),t);
                        maxWeight=Math.max(maxWeight,Math.abs(expected-actual));
                        assertEquals(expected,actual,2e-5,clipName+" weight at "+seconds); weights++;
                    }
                }
                var bones=(JsonObject)sample.get("bone_world_matrices");
                for(var entry:bones.values().entrySet()) {
                    int node=asset.nodes().stream().filter(n->n.name().equals(entry.getKey())).findFirst().orElseThrow().index();
                    var transform=palette.worldTransform(node); Vec3 origin=transform.transformPoint(Vec3.ZERO);
                    Vec3[] columns={transform.transformPoint(new Vec3(1,0,0)),transform.transformPoint(new Vec3(0,1,0)),transform.transformPoint(new Vec3(0,0,1)),origin};
                    for(int col=0;col<4;col++)for(int row=0;row<3;row++) {
                        double actual=component(columns[col],row)-(col<3?component(origin,row):0);
                        double expected=number(((JsonArray)entry.getValue()).get(row*4+col)); maxMatrix=Math.max(maxMatrix,Math.abs(actual-expected));
                        assertEquals(expected,actual,2e-5,clipName+" bone "+entry.getKey()+" at "+seconds);
                    } matrices++;
                }
                var meshes=(JsonObject)sample.get("meshes");
                for(var primitive:asset.primitives()) {
                    var node=asset.nodes().get(primitive.nodeIndex());
                    var entries=(JsonArray)meshes.get(node.name());
                    var expected=(JsonObject)entries.values().stream().map(v->(JsonObject)v)
                            .filter(v->((JsonNumber)v.get("primitive")).asDouble()==primitive.primitiveIndex()).findFirst().orElseThrow();
                    var result=CpuMorphSkinner.skin(PreparedSkinnedGeometry.prepare(primitive.geometry()),asset.morphTargets(primitive),sampled,
                            node.index(),SkinPalette.from(asset.skeleton().skins().get(node.skinIndex()),palette));
                    float[] actualP=result.positions(),actualN=result.normals();
                    var positions=(JsonArray)expected.get("positions");var normals=(JsonArray)expected.get("normals");
                    assertEquals(positions.size()*3,actualP.length);
                    for(int v=0;v<positions.size();v++)for(int c=0;c<3;c++) {
                        double ep=number(((JsonArray)positions.get(v)).get(c)),en=number(((JsonArray)normals.get(v)).get(c));
                        maxPosition=Math.max(maxPosition,Math.abs(ep-actualP[v*3+c]));maxNormal=Math.max(maxNormal,Math.abs(en-actualN[v*3+c]));
                        assertEquals(ep,actualP[v*3+c],2e-5,clipName+" position v="+v+" at "+seconds);
                        assertEquals(en,actualN[v*3+c],2e-5,clipName+" normal v="+v+" at "+seconds);
                        assertTrue(actualP[v*3+c]>=component(asset.bounds().min(),c)&&actualP[v*3+c]<=component(asset.bounds().max(),c));
                    } vertices+=positions.size();
                }
            }
        }
        assertTrue(samples>=40); assertTrue(vertices>1000); assertEquals(samples*3,weights);
        System.out.println("Blender-to-Java CPU morph oracle: samples="+samples+", vertices="+vertices+", weights="+weights+", matrices="+matrices
                +"; max position="+maxPosition+", normal="+maxNormal+", weight="+maxWeight+", matrix="+maxMatrix);
    }
    private static double number(JsonValue value){return ((JsonNumber)value).asDouble();}
    private static float component(Vec3 value,int c){return c==0?value.x():c==1?value.y():value.z();}
}
