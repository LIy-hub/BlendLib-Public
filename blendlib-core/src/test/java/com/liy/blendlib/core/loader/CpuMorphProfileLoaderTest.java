package com.liy.blendlib.core.loader;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.*;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.descriptor.*;
import com.liy.blendlib.core.diagnostic.BlendAssetLoadException;
import com.liy.blendlib.core.model.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class CpuMorphProfileLoaderTest {
    private static final BlendResourceId KEY=BlendResourceId.parse("morph:actor");
    private static final BlendResourceId DESC=BlendResourceId.parse("morph:blend_models/actor.json");
    private static final BlendResourceId MESH=BlendResourceId.parse("morph:models3d/actor.glb");
    private static final String CONTROLS="\"morph:smile\":{\"node\":\"Root/Mesh\",\"target\":\"Smile\",\"min_weight\":-1,\"max_weight\":2},"
            +"\"morph:blink\":{\"node\":\"Root/Mesh\",\"target\":\"Blink\",\"min_weight\":-1,\"max_weight\":2}";
    private static final String DESCRIPTOR="{\"format_version\":2,\"profile\":\"blendlib:skinned_morph_cpu_v1\",\"mesh\":\"morph:models3d/actor.glb\","
            +"\"materials\":{\"Surface\":{\"base_color\":\"morph:textures/actor.png\"}},\"morph_controls\":{"+CONTROLS+"}}";

    @Test void exactDispatcherAndOldEntrypointsAreClosed() {
        Fixture f=fixture(); ModelAsset asset=load(DESCRIPTOR,f);
        assertEquals(ModelProfile.SKINNED_MORPH_CPU_V1,asset.profile());
        assertThrows(IllegalArgumentException.class,()->ModelProfile.fromSerializedName(asset.profile().serializedName()));
        assertThrows(BlendAssetLoadException.class,()->new DescriptorDecoder().decode(KEY,bytes(DESC,DESCRIPTOR)));
        assertThrows(BlendAssetLoadException.class,()->new CubicDescriptorDecoder().decode(KEY,bytes(DESC,DESCRIPTOR)));
        ModelDescriptor descriptor=new MorphDescriptorDecoder().decode(KEY,bytes(DESC,DESCRIPTOR));
        assertThrows(BlendAssetLoadException.class,()->new ModelAssetLoader().decode(KEY,0,descriptor,new AssetBytes(MESH,f.glb())));
        for(String invalid:List.of(DESCRIPTOR.replace("\"format_version\":2","\"format_version\":1"),
                DESCRIPTOR.replace("skinned_morph_cpu_v1","morph_v1"),DESCRIPTOR.replace("skinned_morph_cpu_v1","skinned_v2"))) {
            assertThrows(BlendAssetLoadException.class,()->load(invalid,f));
        }
    }

    @Test void immutableGenerationSidecarsAndWeightOnlyClipsHaveRealDurations() {
        ModelAsset asset=load(DESCRIPTOR,fixture());
        MorphBindingTable.Binding binding=asset.morphBindings().binding(1);
        assertEquals(List.of("Smile","Blink"),binding.targetNames());
        assertEquals(.25f,binding.defaultWeight(0)); assertEquals(-.25f,binding.defaultWeight(1));
        assertEquals(2,asset.morphBindings().weightCount());
        var clip=asset.clips().getFirst(); assertTrue(clip.channels().isEmpty()); assertTrue(clip.hasMorphChannels());
        assertEquals(2.5f,clip.durationSeconds());
        var channel=clip.morphChannels().getFirst(); assertEquals(2,channel.targetCount());
        channel.times()[1]=9; channel.values()[0]=9;
        assertEquals(2.5f,channel.keyTime(1)); assertEquals(-.5f,channel.keyValue(0,0));
        var primitive=asset.primitives().getFirst();
        assertSame(asset.morphTargets(primitive),asset.morphTargets(primitive.geometry()));
        assertEquals(3,asset.morphTargets(primitive).vertexCount());
        assertThrows(UnsupportedOperationException.class,()->asset.morphBindings().controls().clear());
        assertThrows(UnsupportedOperationException.class,()->clip.morphChannels().clear());
        assertSame(MorphBindingTable.empty(),MorphBindingTable.empty());
    }

    @Test void nodeDefaultsOverrideMeshOtherwiseZeroAndSignedWeightsAboveOneAreAccepted() {
        Fixture f=fixture();
        assertEquals(1.5f,load(DESCRIPTOR,f.change("\"mesh\":0,\"skin\":0","\"mesh\":0,\"skin\":0,\"weights\":[1.5,-0.75]")).morphBindings().binding(1).defaultWeight(0));
        assertEquals(0,load(DESCRIPTOR,f.change("\"weights\":[0.25,-0.25],", "")).morphBindings().binding(1).defaultWeight(0));
        assertEquals(1.5f,load(DESCRIPTOR,f).clips().getFirst().morphChannels().getFirst().keyValue(1,0));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,f.change("\"weights\":[0.25,-0.25]","\"weights\":[0.25]")));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,f.change("\"mesh\":0,\"skin\":0","\"mesh\":0,\"skin\":0,\"weights\":[3,0]")));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,f.change("\"name\":\"Joint\"","\"name\":\"Joint\",\"weights\":[0,0]")));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR.replace("\"max_weight\":2","\"max_weight\":1"),f));
    }

    @Test void controlsAreRequiredExactUniqueCompleteAndBounded() {
        Fixture f=fixture();
        for(String invalid:List.of(DESCRIPTOR.replace(",\"morph_controls\":{"+CONTROLS+"}",""),
                DESCRIPTOR.replace(CONTROLS,""),DESCRIPTOR.replace("Root/Mesh","Root/Absent"),
                DESCRIPTOR.replace("\"target\":\"Blink\"","\"target\":\"Smile\""),
                DESCRIPTOR.replace("\"target\":\"Blink\"","\"target\":\"Missing\""),
                DESCRIPTOR.replace("\"min_weight\":-1","\"min_weight\":0.1"),
                DESCRIPTOR.replace("\"max_weight\":2","\"max_weight\":2.1"),
                DESCRIPTOR.replace("\"max_weight\":2","\"max_weight\":1e999"),
                DESCRIPTOR.replace("\"target\":\"Smile\"","\"target\":\" \""),
                DESCRIPTOR.replace("\"max_weight\":2","\"max_weight\":2,\"extra\":0"))) {
            assertThrows(BlendAssetLoadException.class,()->load(invalid,f),invalid);
        }
        String incomplete=DESCRIPTOR.replace(",\"morph:blink\":{\"node\":\"Root/Mesh\",\"target\":\"Blink\",\"min_weight\":-1,\"max_weight\":2}","");
        assertThrows(BlendAssetLoadException.class,()->load(incomplete,f));
    }

    @Test void denseTargetNamesAndAccessorContractsRejectMalformedContent() {
        Fixture f=fixture();
        for(Fixture invalid:List.of(f.change("\"extras\":{\"targetNames\":[\"Smile\",\"Blink\"]},",""),
                f.change("[\"Smile\",\"Blink\"]","[\"Smile\"]"),f.change("[\"Smile\",\"Blink\"]","[\"Smile\",\"Smile\"]"),
                f.change("[\"Smile\",\"Blink\"]","[\"Smile\",\" \" ]"),
                f.change("\"POSITION\":9,\"NORMAL\":10","\"POSITION\":9"),
                f.change("\"POSITION\":9,\"NORMAL\":10","\"POSITION\":9,\"NORMAL\":10,\"TANGENT\":10"),
                f.change("\"POSITION\":9,\"NORMAL\":10","\"POSITION\":7,\"NORMAL\":10"),
                f.change("\"POSITION\":9,\"NORMAL\":10","\"POSITION\":3,\"NORMAL\":10"),
                f.change("\"POSITION\":9,\"NORMAL\":10","\"POSITION\":9,\"NORMAL\":7"),
                f.change("\"bufferView\":9,","\"sparse\":{},\"bufferView\":9,"),
                f.change("\"bufferView\":9,","\"normalized\":true,\"bufferView\":9,"))) {
            assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,invalid),invalid.json);
        }
    }

    @Test void sameMeshNodeReuseSharesTargetsButHasIndependentDefaultsAndBindings() {
        Fixture f=fixture().change("\"children\":[0,1]","\"children\":[0,1,3]")
                .change("\"scenes\":", "\"scenes\":");
        f=f.change("\"children\":[0,1,3]}]","\"children\":[0,1,3]},{\"name\":\"Second\",\"mesh\":0,\"skin\":0,\"weights\":[1.5,0]}]");
        String controls=CONTROLS+","+CONTROLS.replace("morph:smile","morph:second_smile").replace("morph:blink","morph:second_blink").replace("Root/Mesh","Root/Second");
        ModelAsset asset=load(DESCRIPTOR.replace(CONTROLS,controls),f);
        assertEquals(2,asset.primitives().size());
        assertSame(asset.primitives().get(0).geometry(),asset.primitives().get(1).geometry());
        assertSame(asset.morphTargets(asset.primitives().get(0)),asset.morphTargets(asset.primitives().get(1)));
        assertEquals(.25f,asset.morphBindings().binding(1).defaultWeight(0));
        assertEquals(1.5f,asset.morphBindings().binding(3).defaultWeight(0));
        assertEquals(4,asset.morphBindings().weightCount());
        Fixture finalFixture=f; assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,finalFixture));
    }

    @Test void fullIntervalsConservativelyBoundDeltasAndNormalCollapseIsRejected() {
        ModelAsset asset=load(DESCRIPTOR,fixture());
        assertTrue(asset.bounds().max().x()>=5, "Bound includes full declared signed intervals, not just keys/defaults");
        assertTrue(asset.bounds().min().x()<=-5);
        Fixture f=fixture();
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,f.change("\"NORMAL\":10","\"NORMAL\":1")));
        float[][] p={new float[9]},n={new float[9]};
        MorphTargetSet targets=new MorphTargetSet(List.of("A"),3,p,n);p[0][0]=99;n[0][0]=99;
        assertEquals(0,targets.positionDelta(0,0,0));assertEquals(0,targets.normalDelta(0,0,0));
    }

    @Test void cubicWeightsBadOutputCardinalityAndInactiveBindingsAreRejected() {
        Fixture f=fixture();
        for(Fixture invalid:List.of(f.change("\"interpolation\":\"LINEAR\"","\"interpolation\":\"CUBICSPLINE\""),
                f.change("\"output\":8","\"output\":7"),f.change("\"node\":1,\"path\":\"weights\"","\"node\":0,\"path\":\"weights\""),
                f.change("\"children\":[0,1]","\"children\":[0]"),f.change("\"mesh\":0,\"skin\":0","\"mesh\":0"))) {
            assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,invalid));
        }
    }

    @Test void inheritedCubicTrsCoexistsWithGenuineWeightsChannels() {
        Fixture f=fixture().change("\"interpolation\":\"LINEAR\"}]", "\"interpolation\":\"LINEAR\"},{\"input\":7,\"output\":13,\"interpolation\":\"CUBICSPLINE\"}]")
                .change("\"path\":\"weights\"}}]","\"path\":\"weights\"}},{\"sampler\":1,\"target\":{\"node\":0,\"path\":\"translation\"}}]");
        ModelAsset asset=load(DESCRIPTOR,f);
        assertEquals(1,asset.clips().getFirst().channels().size());assertEquals(1,asset.clips().getFirst().morphChannels().size());
        assertEquals(Interpolation.CUBICSPLINE,asset.clips().getFirst().channels().getFirst().interpolation());
    }

    @Test void cumulativeBudgetChargesRepeatedAccessorChannelUsesBeforeAnyAnimationDecode() {
        Fixture f=fixture(4_000);
        String channel="{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"weights\"}}";
        Fixture over=f.change(channel,String.join(",",Collections.nCopies(1200,channel)));
        BlendAssetLoadException error=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,over));
        assertEquals("BLENDLIB-LIMIT-001",error.diagnostic().code());
        assertTrue(error.diagnostic().message().contains("float slots"));
    }

    @Test void materialSplitsRequireIdenticalTargetCardinalityAndPreservePrimitiveOrder() {
        Fixture f=fixture();
        String primitive="{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"JOINTS_0\":3,\"WEIGHTS_0\":4},\"indices\":5,\"material\":0,\"targets\":[{\"POSITION\":9,\"NORMAL\":10},{\"POSITION\":11,\"NORMAL\":12}]}";
        ModelAsset asset=load(DESCRIPTOR,f.change(primitive,primitive+","+primitive));
        assertEquals(2,asset.primitives().size());assertEquals(0,asset.primitives().get(0).primitiveIndex());assertEquals(1,asset.primitives().get(1).primitiveIndex());
        Fixture mismatched=f.change(primitive,primitive+","+primitive.replace(",{\"POSITION\":11,\"NORMAL\":12}",""));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,mismatched));
        String targets=String.join(",",Collections.nCopies(9,"{\"POSITION\":9,\"NORMAL\":10}"));
        assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,f.change("{\"POSITION\":9,\"NORMAL\":10},{\"POSITION\":11,\"NORMAL\":12}",targets)));
    }

    @Test void expandedNodeReuseAndMaterialSplitPairsArePreflightedBeforeAllocations() {
        Fixture f=fixture(2,80_000);
        String meshNode="{\"name\":\"Mesh\",\"mesh\":0,\"skin\":0}";
        Fixture reused=f.change(meshNode,String.join(",",Collections.nCopies(7,meshNode)));
        var reuseError=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,reused));
        assertEquals("BLENDLIB-LIMIT-001",reuseError.diagnostic().code());assertTrue(reuseError.diagnostic().message().contains("vertex-target pair"));
        String primitive="{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"JOINTS_0\":3,\"WEIGHTS_0\":4},\"indices\":5,\"material\":0,\"targets\":[{\"POSITION\":9,\"NORMAL\":10},{\"POSITION\":11,\"NORMAL\":12}]}";
        String eightTargetPrimitive=primitive.replace("{\"POSITION\":9,\"NORMAL\":10},{\"POSITION\":11,\"NORMAL\":12}",
                String.join(",",Collections.nCopies(8,"{\"POSITION\":9,\"NORMAL\":10}")));
        Fixture split=fixture(2,20_000).change(primitive,String.join(",",Collections.nCopies(7,eightTargetPrimitive)));
        var splitError=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,split));
        assertEquals("BLENDLIB-LIMIT-001",splitError.diagnostic().code());assertTrue(splitError.diagnostic().message().contains("vertex-target pair"));
        // Six uses remain below one million pairs but exceed storage once every expanded
        // PreparedSkinnedGeometry/topology and output-copy preparation is charged.
        Fixture preparationOver=f.change(meshNode,String.join(",",Collections.nCopies(6,meshNode)));
        var preparationError=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,preparationOver));
        assertEquals("BLENDLIB-LIMIT-001",preparationError.diagnostic().code());assertTrue(preparationError.diagnostic().message().contains("float slots"));
        Fixture tooManyNodes=fixture().change(meshNode,String.join(",",Collections.nCopies(129,meshNode)));
        var nodeError=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,tooManyNodes));
        assertEquals("BLENDLIB-LIMIT-001",nodeError.diagnostic().code());assertTrue(nodeError.diagnostic().message().contains("Morph node limit"));
    }

    @Test void nonIdentityInverseBindUsesLinearDeltaTransformWithoutItsTranslation() {
        Fixture f=fixture();
        byte[] binary=f.bin.clone();
        // Accessor 6 is the identity inverse bind; use an independent parse of its view offset.
        var json=(com.liy.blendlib.core.json.JsonObject)com.liy.blendlib.core.json.StrictJsonParser.parse(f.json.getBytes(StandardCharsets.UTF_8));
        var views=(com.liy.blendlib.core.json.JsonArray)json.get("bufferViews");
        var view=(com.liy.blendlib.core.json.JsonObject)views.get(6);
        int offset=((com.liy.blendlib.core.json.JsonNumber)view.get("byteOffset")).asIntExact();
        ByteBuffer buffer=ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(offset,2).putFloat(offset+20,2).putFloat(offset+40,2).putFloat(offset+48,10);
        ModelAsset asset=load(DESCRIPTOR,new Fixture(f.json,binary));
        // Max radius is ||IBM * p|| + 2*||linear IBM * (2,0,0)|| + 2*||linear IBM * (0,1,0)|| = 24.
        assertEquals(24*1.01+1e-4,asset.bounds().max().x(),2e-5);
    }

    @Test void nearAffineSkinRowsCannotEscapeMorphBoundsThroughHomogeneousDivision() {
        Fixture f=fixture(); byte[] binary=f.bin.clone();
        var json=(com.liy.blendlib.core.json.JsonObject)com.liy.blendlib.core.json.StrictJsonParser.parse(f.json);
        var views=(com.liy.blendlib.core.json.JsonArray)json.get("bufferViews");
        int matrixOffset=((com.liy.blendlib.core.json.JsonNumber)((com.liy.blendlib.core.json.JsonObject)views.get(6)).get("byteOffset")).asIntExact();
        int deltaOffset=((com.liy.blendlib.core.json.JsonNumber)((com.liy.blendlib.core.json.JsonObject)views.get(9)).get("byteOffset")).asIntExact();
        var buffer=ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        for(int v=0;v<3;v++)buffer.putFloat(deltaOffset+v*12,-500_000);
        ModelAsset affine=load(DESCRIPTOR,new Fixture(f.json,binary));
        buffer.putFloat(matrixOffset+12,1e-6f);
        var error=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,new Fixture(f.json,binary)));
        assertEquals("BLENDLIB-SKIN-001",error.diagnostic().code());
        assertTrue(error.diagnostic().message().contains("exact affine"));
        var original=affine.skeleton().skins().getFirst();var matrix=original.inverseBindMatrices();matrix[3]=1e-6f;
        var skeleton=new Skeleton(List.of(new Skin(original.name(),original.skeletonRoot(),original.joints(),matrix)));
        var targets=new IdentityHashMap<MeshPrimitive,MorphTargetSet>();
        affine.primitives().forEach(p->targets.put(p.geometry(),affine.morphTargets(p)));
        assertThrows(IllegalArgumentException.class,()->new ModelAsset(affine.modelKey(),affine.descriptorId(),8,
                affine.profile(),1,affine.materials(),affine.animationDefinition(),affine.nodes(),affine.defaultSceneRoots(),
                affine.primitives(),skeleton,affine.clips(),affine.sockets(),affine.bounds(),List.of(),affine.morphBindings(),targets));
        // Preserve the inherited old-profile near-affine tolerance on unchanged small base geometry.
        assertDoesNotThrow(()->new ModelAsset(affine.modelKey(),affine.descriptorId(),8,ModelProfile.SKINNED_CUBIC_V1,
                1,affine.materials(),null,affine.nodes(),affine.defaultSceneRoots(),affine.primitives(),skeleton,List.of(),
                affine.sockets(),Bounds.fromPositions(affine.primitives().getFirst().geometry().positions()),List.of()));
    }

    @Test void palettePreparationCopiesCountPerExpandedPrimitiveBeforeSkinValidation() {
        Fixture f=fixture();
        String primitive="{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"JOINTS_0\":3,\"WEIGHTS_0\":4},\"indices\":5,\"material\":0,\"targets\":[{\"POSITION\":9,\"NORMAL\":10},{\"POSITION\":11,\"NORMAL\":12}]}";
        Fixture many=f.change("\"joints\":[0]","\"joints\":["+String.join(",",Collections.nCopies(512,"0"))+"]")
                .change(primitive,String.join(",",Collections.nCopies(600,primitive)));
        var error=assertThrows(BlendAssetLoadException.class,()->load(DESCRIPTOR,many));
        assertEquals("BLENDLIB-LIMIT-001",error.diagnostic().code());
        assertTrue(error.diagnostic().message().contains("float slots"),"palette reservation precedes duplicate-joint decode");
    }

    private static ModelAsset load(String descriptor,Fixture fixture) {
        return ModelAssetLoader.runtimeProfiles().load(KEY,7,bytes(DESC,descriptor),id->new AssetBytes(MESH,fixture.glb()));
    }
    private static AssetBytes bytes(BlendResourceId id,String text) { return new AssetBytes(id,text.getBytes(StandardCharsets.UTF_8)); }
    private static Fixture fixture() { return fixture(2); }
    private static Fixture fixture(int keys) { return fixture(keys,3); }
    private static Fixture fixture(int keys,int vertexCount) {
        float[] times=new float[keys],weights=new float[keys*2];
        for(int i=0;i<keys;i++) { times[i]=2.5f*i/(keys-1);weights[i*2]=i==0?-.5f:1.5f;weights[i*2+1]=i==0?.25f:-.75f; }
        ByteBuffer bin=ByteBuffer.allocate(2048+times.length*4+weights.length*4+vertexCount*104).order(ByteOrder.LITTLE_ENDIAN);
        List<String> views=new ArrayList<>(),accessors=new ArrayList<>();
        add(bin,views,accessors,Arrays.copyOf(new float[]{0,0,0,1,0,0,0,1,0},vertexCount*3),"VEC3",",\"min\":[0,0,0],\"max\":[1,1,0]");
        add(bin,views,accessors,repeat(new float[]{0,0,1},vertexCount),"VEC3","");
        add(bin,views,accessors,repeat(new float[]{0,0},vertexCount),"VEC2","");
        addShort(bin,views,accessors,new short[vertexCount*4],"VEC4");
        add(bin,views,accessors,repeat(new float[]{1,0,0,0},vertexCount),"VEC4","");
        addShort(bin,views,accessors,new short[]{0,1,2},"SCALAR");
        add(bin,views,accessors,Matrix4.identity().copy(),"MAT4","");
        add(bin,views,accessors,times,"SCALAR",",\"min\":[0],\"max\":[2.5]");
        add(bin,views,accessors,weights,"SCALAR","");
        add(bin,views,accessors,repeat(new float[]{2,0,0},vertexCount),"VEC3","");
        add(bin,views,accessors,repeat(new float[]{.2f,0,0},vertexCount),"VEC3","");
        add(bin,views,accessors,repeat(new float[]{0,1,0},vertexCount),"VEC3","");
        add(bin,views,accessors,repeat(new float[]{0,.1f,0},vertexCount),"VEC3","");
        add(bin,views,accessors,new float[]{0,0,0,0,0,0,4,0,0,-4,0,0,0,0,0,0,0,0},"VEC3","");
        String json="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":"+bin.position()+"}],\"bufferViews\":["+String.join(",",views)+"],\"accessors\":["+String.join(",",accessors)+"],"
                +"\"materials\":[{\"name\":\"Surface\"}],\"meshes\":[{\"extras\":{\"targetNames\":[\"Smile\",\"Blink\"]},\"weights\":[0.25,-0.25],\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"JOINTS_0\":3,\"WEIGHTS_0\":4},\"indices\":5,\"material\":0,\"targets\":[{\"POSITION\":9,\"NORMAL\":10},{\"POSITION\":11,\"NORMAL\":12}]}]}],"
                +"\"nodes\":[{\"name\":\"Joint\"},{\"name\":\"Mesh\",\"mesh\":0,\"skin\":0},{\"name\":\"Root\",\"children\":[0,1]}],\"scenes\":[{\"nodes\":[2]}],\"scene\":0,\"skins\":[{\"joints\":[0],\"inverseBindMatrices\":6,\"skeleton\":0}],"
                +"\"animations\":[{\"name\":\"face\",\"samplers\":[{\"input\":7,\"output\":8,\"interpolation\":\"LINEAR\"}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"weights\"}}]}]}";
        return new Fixture(json,Arrays.copyOf(bin.array(),bin.position()));
    }
    private static float[] repeat(float[] vector,int count) { float[] result=new float[vector.length*count];for(int i=0;i<count;i++)System.arraycopy(vector,0,result,i*vector.length,vector.length);return result; }
    private static int components(String type) { return switch(type) { case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;case "MAT4"->16;default->throw new AssertionError();}; }
    private static void add(ByteBuffer bin,List<String> views,List<String> accessors,float[] values,String type,String extra) {
        while(bin.position()%4!=0)bin.put((byte)0);int offset=bin.position();for(float value:values)bin.putFloat(value);
        views.add("{\"buffer\":0,\"byteOffset\":"+offset+",\"byteLength\":"+values.length*4+"}");
        accessors.add("{\"bufferView\":"+(views.size()-1)+",\"componentType\":5126,\"count\":"+values.length/components(type)+",\"type\":\""+type+"\""+extra+"}");
    }
    private static void addShort(ByteBuffer bin,List<String> views,List<String> accessors,short[] values,String type) {
        while(bin.position()%4!=0)bin.put((byte)0);int offset=bin.position();for(short value:values)bin.putShort(value);
        views.add("{\"buffer\":0,\"byteOffset\":"+offset+",\"byteLength\":"+values.length*2+"}");
        accessors.add("{\"bufferView\":"+(views.size()-1)+",\"componentType\":5123,\"count\":"+values.length/components(type)+",\"type\":\""+type+"\"}");
    }
    private record Fixture(String json,byte[] bin) {
        Fixture change(String from,String to) { assertTrue(json.contains(from),"Fixture token missing: "+from);return new Fixture(json.replace(from,to),bin); }
        byte[] glb() {
            byte[] text=json.getBytes(StandardCharsets.UTF_8);int jl=(text.length+3)&~3,bl=(bin.length+3)&~3;
            ByteBuffer buffer=ByteBuffer.allocate(28+jl+bl).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x46546c67).putInt(2).putInt(buffer.capacity()).putInt(jl).putInt(0x4e4f534a).put(text);
            while(buffer.position()<20+jl)buffer.put((byte)32);buffer.putInt(bl).putInt(0x004e4942).put(bin);return buffer.array();
        }
    }
}
