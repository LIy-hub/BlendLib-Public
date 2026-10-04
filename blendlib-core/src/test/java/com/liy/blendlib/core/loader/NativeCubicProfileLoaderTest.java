package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.*;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.animation.runtime.*;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.descriptor.*;
import com.liy.blendlib.core.diagnostic.*;
import com.liy.blendlib.core.model.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeCubicProfileLoaderTest {
    private static final BlendResourceId KEY=BlendResourceId.parse("cubic:actor");
    private static final BlendResourceId DESC=BlendResourceId.parse("cubic:blend_models/actor.json");
    private static final BlendResourceId MESH=BlendResourceId.parse("cubic:models3d/actor.glb");
    private static final String DESCRIPTOR="""
        {"format_version":2,"profile":"blendlib:skinned_cubic_v1","mesh":"cubic:models3d/actor.glb",
         "materials":{"Surface":{"base_color":"cubic:textures/actor.png"}},
         "sockets":{"cubic:tip":{"node":"Root/Joint"}},
         "animation":{"initial_state":"cubic:wave","states":{"cubic:wave":{"clip":"wave","loop":false,"speed":1,
           "events":[{"time_seconds":0.5,"event":"cubic:apex"}]}}}}
        """;
    private static final float[] OVERSHOOT={0,0,0, 0,0,0, 24,0,0, -24,0,0, 0,0,0, 0,0,0};

    @Test
    void explicitDispatchLoadsCubicButFrozenV1EntryPointsAndExperimentalProfilesStayClosed() {
        var fixture=fixture(new float[]{0,1},OVERSHOOT,"translation",3,1);
        ModelAsset asset=load(DESCRIPTOR,fixture);
        assertEquals(ModelProfile.SKINNED_CUBIC_V1,asset.profile());
        assertEquals(Interpolation.CUBICSPLINE,asset.clips().getFirst().channels().getFirst().interpolation());
        assertThrows(BlendAssetLoadException.class,()->new DescriptorDecoder().decode(KEY,bytes(DESC,DESCRIPTOR)));
        assertThrows(BlendAssetLoadException.class,()->new ModelAssetLoader().load(KEY,bytes(DESC,DESCRIPTOR),id->new AssetBytes(MESH,fixture.glb())));
        assertThrows(BlendAssetLoadException.class,()->ModelAssetLoader.runtimeProfiles().load(KEY,
                bytes(DESC,DESCRIPTOR.replace("skinned_cubic_v1","skinned_v2")),id->new AssetBytes(MESH,fixture.glb())));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR.replace("skinned_cubic_v1","morph_v1"),fixture));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR.replace("\"format_version\":2","\"format_version\":1"),fixture));
        assertThrows(IllegalArgumentException.class,()->ModelProfile.fromSerializedName("blendlib:skinned_cubic_v1"));
        var decoded=new CubicDescriptorDecoder().decode(KEY,bytes(DESC,DESCRIPTOR));
        assertThrows(BlendAssetLoadException.class,()->new ModelAssetLoader().decode(KEY,0,decoded,new AssetBytes(MESH,fixture.glb())));
    }

    @Test
    void overshootOutsideEveryKeyRemainsInsidePreparedBoundsAfterCpuSkinningAndSocketMotion() {
        ModelAsset asset=load(DESCRIPTOR,fixture(new float[]{0,1},OVERSHOOT,"translation",3,1));
        var definition=AnimationControllerDefinition.fromModelAsset(asset);
        var sampler=PoseSampler.fromModelAsset(asset);
        AnimationChannel channel=asset.clips().getFirst().channels().getFirst();
        assertEquals(0,channel.values()[0]); assertEquals(0,channel.values()[3]);
        assertEquals(6,channel.sample(.5f)[0],1e-6);
        assertEquals(8,channel.cubicMagnitudeBound(),1e-6);
        assertTrue(asset.bounds().max().x()>8);
        for(int step=0;step<=100;step++) {
            LocalPose pose=sampler.sample(definition.initialStateDefinition(),step/100.0);
            NodePalette palette=NodePalette.fromCanonicalScene(pose,asset.nodes(),asset.defaultSceneRoots());
            var mesh=CpuSkinner.skin(PreparedSkinnedGeometry.prepare(asset.primitives().getFirst().geometry()),
                    SkinPalette.from(asset.skeleton().skins().getFirst(),palette));
            float[] positions=mesh.positions();
            for(int i=0;i<positions.length;i+=3) {
                assertTrue(positions[i]>=asset.bounds().min().x() && positions[i]<=asset.bounds().max().x());
                assertTrue(positions[i+1]>=asset.bounds().min().y() && positions[i+1]<=asset.bounds().max().y());
            }
        }
        assertEquals(1,definition.initialStateDefinition().events().size());
        assertEquals(1,asset.sockets().entries().size());
    }

    @Test
    void cubicInputTripletCardinalityAndAtLeastTwoKeysAreCheckedBeforeChannelCreation() {
        var wrong=fixture(new float[]{0,1},new float[6],"translation",3,1);
        assertDiagnostic("BLENDLIB-ANIM-007",()->load(DESCRIPTOR,wrong));
        var singleton=fixture(new float[]{0},new float[9],"translation",3,1);
        assertDiagnostic("BLENDLIB-ANIM-007",()->load(DESCRIPTOR,singleton));
        assertDiagnostic("BLENDLIB-ANIM-006",()->load(DESCRIPTOR,fixture(new float[]{0,0},OVERSHOOT,"translation",3,1)));
    }

    @Test
    void unsafeScaleQuaternionAndNonFiniteTangentRejectWithPreparationDiagnostics() {
        float[] scale={0,0,0,1,1,1,-10,-10,-10,10,10,10,1,1,1,0,0,0};
        var failure=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,fixture(new float[]{0,1},scale,"scale",3,1)));
        assertTrue(failure.diagnostic().message().contains("positive interior"));
        float[] quat={0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,-1,0,0,0,0};
        assertDiagnostic("BLENDLIB-ANIM-007",()->load(DESCRIPTOR,fixture(new float[]{0,1},quat,"rotation",4,1)));
        float[] bad=OVERSHOOT.clone(); bad[6]=Float.NaN;
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,fixture(new float[]{0,1},bad,"translation",3,1)));
    }

    @Test
    void inheritedAdvancedFeatureRejectionsRemainClosedUnderTheNewProfile() {
        var valid=fixture(new float[]{0,1},OVERSHOOT,"translation",3,1);
        for(String json:List.of(valid.json.replace("\"POSITION\":0","\"POSITION\":0,\"COLOR_0\":0"),
                valid.json.replace("\"POSITION\":0","\"POSITION\":0,\"TEXCOORD_1\":2"),
                valid.json.replace("\"indices\":5","\"targets\":[{\"POSITION\":0}],\"indices\":5"),
                valid.json.replace("\"asset\":", "\"extensionsRequired\":[\"KHR_draco_mesh_compression\"],\"asset\":"),
                valid.json.replace("\"name\":\"Surface\"","\"name\":\"Surface\",\"emissiveFactor\":[1,0,0]"))) {
            assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,new Fixture(json,valid.bin)));
        }
    }

    @Test
    void sharedSamplerChannelsChargeActualTripletAndTransientStorageBeforeEachAllocation() {
        int keys=1000,channels=900;
        float[] times=new float[keys],triplets=new float[keys*12];
        for(int i=0;i<keys;i++) { times[i]=i*.01f; triplets[i*12+7]=1; }
        String descriptor="{\"format_version\":2,\"profile\":\"blendlib:skinned_cubic_v1\",\"mesh\":\"cubic:models3d/actor.glb\",\"materials\":{\"Surface\":{\"base_color\":\"cubic:textures/actor.png\"}}}";
        var error=assertThrows(BlendAssetLoadException.class,()->load(descriptor,fixture(times,triplets,"rotation",4,channels)));
        assertEquals("BLENDLIB-LIMIT-001",error.diagnostic().code());
        assertTrue(error.diagnostic().message().contains("storage budget"));
    }

    private static ModelAsset load(String descriptor,Fixture fixture) {
        return ModelAssetLoader.runtimeProfiles().load(KEY,7,bytes(DESC,descriptor),id->new AssetBytes(MESH,fixture.glb()));
    }
    private static AssetBytes bytes(BlendResourceId id,String text) { return new AssetBytes(id,text.getBytes(StandardCharsets.UTF_8)); }
    private static void assertDiagnostic(String code,Runnable operation) { assertEquals(code,assertThrows(BlendAssetLoadException.class,operation::run).diagnostic().code()); }

    private static Fixture fixture(float[] times,float[] values,String path,int components,int channelCount) {
        ByteBuffer bin=ByteBuffer.allocate(1024+times.length*4+values.length*4).order(ByteOrder.LITTLE_ENDIAN);
        List<String> views=new ArrayList<>(),accessors=new ArrayList<>();
        add(bin,views,accessors,new float[]{0,0,0,1,0,0,0,1,0},"VEC3",",\"min\":[0,0,0],\"max\":[1,1,0]");
        add(bin,views,accessors,new float[]{0,0,1,0,0,1,0,0,1},"VEC3","");
        add(bin,views,accessors,new float[]{0,0,1,0,0,1},"VEC2","");
        addShort(bin,views,accessors,new short[12],"VEC4");
        add(bin,views,accessors,new float[]{1,0,0,0,1,0,0,0,1,0,0,0},"VEC4","");
        addShort(bin,views,accessors,new short[]{0,1,2},"SCALAR");
        add(bin,views,accessors,Matrix4.identity().copy(),"MAT4","");
        add(bin,views,accessors,times,"SCALAR",",\"min\":["+times[0]+"],\"max\":["+times[times.length-1]+"]");
        add(bin,views,accessors,values,"VEC"+components,"");
        List<String> nodes=new ArrayList<>(),children=new ArrayList<>(),channels=new ArrayList<>();
        nodes.add("{\"name\":\"Joint\"}"); children.add("0");
        for(int i=1;i<channelCount;i++) { nodes.add("{\"name\":\"Extra"+i+"\"}");children.add(""+i); }
        nodes.add("{\"name\":\"Mesh\",\"mesh\":0,\"skin\":0}"); children.add(""+channelCount);
        int root=nodes.size();nodes.add("{\"name\":\"Root\",\"children\":["+String.join(",",children)+"]}");
        for(int i=0;i<channelCount;i++) channels.add("{\"sampler\":0,\"target\":{\"node\":"+i+",\"path\":\""+path+"\"}}");
        String json="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":"+bin.position()+"}],\"bufferViews\":["+String.join(",",views)+"],\"accessors\":["+String.join(",",accessors)+"],"
                +"\"materials\":[{\"name\":\"Surface\"}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"JOINTS_0\":3,\"WEIGHTS_0\":4},\"indices\":5,\"material\":0}]}],"
                +"\"nodes\":["+String.join(",",nodes)+"],\"scenes\":[{\"nodes\":["+root+"]}],\"scene\":0,\"skins\":[{\"joints\":[0],\"inverseBindMatrices\":6,\"skeleton\":0}],"
                +"\"animations\":[{\"name\":\"wave\",\"samplers\":[{\"input\":7,\"output\":8,\"interpolation\":\"CUBICSPLINE\"}],\"channels\":["+String.join(",",channels)+"]}]}";
        return new Fixture(json,Arrays.copyOf(bin.array(),bin.position()));
    }
    private static int components(String type) { return switch(type) { case "SCALAR"->1; case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;case "MAT4"->16;default->throw new AssertionError();}; }
    private static void add(ByteBuffer bin,List<String> views,List<String> accessors,float[] values,String type,String extra) {
        while(bin.position()%4!=0)bin.put((byte)0);int offset=bin.position();for(float value:values)bin.putFloat(value);
        view(views,offset,values.length*4);accessors.add("{\"bufferView\":"+(views.size()-1)+",\"componentType\":5126,\"count\":"+values.length/components(type)+",\"type\":\""+type+"\""+extra+"}");
    }
    private static void addShort(ByteBuffer bin,List<String> views,List<String> accessors,short[] values,String type) {
        while(bin.position()%4!=0)bin.put((byte)0);int offset=bin.position();for(short value:values)bin.putShort(value);
        view(views,offset,values.length*2);accessors.add("{\"bufferView\":"+(views.size()-1)+",\"componentType\":5123,\"count\":"+values.length/components(type)+",\"type\":\""+type+"\"}");
    }
    private static void view(List<String> views,int offset,int length) { views.add("{\"buffer\":0,\"byteOffset\":"+offset+",\"byteLength\":"+length+"}"); }
    private record Fixture(String json,byte[] bin) {
        byte[] glb() {
            byte[] text=json.getBytes(StandardCharsets.UTF_8);int jl=(text.length+3)&~3,bl=(bin.length+3)&~3;
            ByteBuffer buffer=ByteBuffer.allocate(28+jl+bl).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x46546c67).putInt(2).putInt(buffer.capacity()).putInt(jl).putInt(0x4e4f534a).put(text);
            while(buffer.position()<20+jl)buffer.put((byte)32);buffer.putInt(bl).putInt(0x004e4942).put(bin);return buffer.array();
        }
    }
}
