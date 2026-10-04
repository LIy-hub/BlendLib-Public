package com.liy.blendlib.core.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CpuMorphNormalMathTest {
    @Test void rotationCofactorDeterminantPreservesRelativeJointNormalWeights() {
        float sine=(float)Math.sin(Math.PI/8),cosine=(float)Math.cos(Math.PI/8);
        var rotation=new Transform(Vec3.ZERO,new Quaternion(0,0,sine,cosine),Vec3.ONE);
        var nodes=List.of(new ModelNode(0,"Rotated",rotation,List.of(),-1,-1,false),
                new ModelNode(1,"Identity",Transform.IDENTITY,List.of(),-1,-1,false));
        var world=NodePalette.from(new LocalPose(Map.of(0,rotation,1,Transform.IDENTITY)),nodes);
        float[] inverse=new float[32];System.arraycopy(Matrix4.identity().copy(),0,inverse,0,16);System.arraycopy(Matrix4.identity().copy(),0,inverse,16,16);
        var palette=SkinPalette.from(new Skin("Rig",-1,List.of(0,1),inverse),world);
        double[] transformed=new double[3];float[] floatTransformed=new float[3];
        palette.transformNormalInto(0,1D,0D,0D,transformed,0);
        palette.transformNormalInto(0,1F,0F,0F,floatTransformed,0);
        assertEquals(Math.sqrt(.5),transformed[0],2e-7);assertEquals(Math.sqrt(.5),transformed[1],2e-7);
        assertEquals(transformed[0],floatTransformed[0],2e-7);assertEquals(transformed[1],floatTransformed[1],2e-7);
        var primitive=new MeshPrimitive("Surface",new float[]{0,0,0,1,0,0,0,1,0},new float[]{1,0,0,1,0,0,1,0,0},
                new float[]{0,0,1,0,0,1},new int[]{0,1,2},new int[]{0,1,0,0,0,1,0,0,0,1,0,0},
                new float[]{.2F,.8F,0,0,.2F,.8F,0,0,.2F,.8F,0,0});
        var geometry=PreparedSkinnedGeometry.prepare(primitive);
        double x=.8+.2*Math.sqrt(.5),y=.2*Math.sqrt(.5),length=Math.hypot(x,y);
        var skinned=CpuSkinner.skin(geometry,palette).normals();
        assertEquals(x/length,skinned[0],2e-7);assertEquals(y/length,skinned[1],2e-7);
        var bindings=new MorphBindingTable(List.of(new MorphBindingTable.Binding(0,List.of("Tilt"),0,new float[]{1},new float[]{0},new float[]{1})),
                Map.of(BlendResourceId.parse("normal:tilt"),new MorphBindingTable.Control(0,0,0,0,1)));
        var targets=new MorphTargetSet(List.of("Tilt"),3,new float[][]{new float[9]},new float[][]{{0,.25F,0,0,.25F,0,0,.25F,0}});
        var morphed=CpuMorphSkinner.skin(geometry,targets,MorphWeights.defaults(bindings),0,palette).normals();
        x=.8+.2*Math.sqrt(.5)*.75;y=.8*.25+.2*Math.sqrt(.5)*1.25;length=Math.hypot(x,y);
        assertEquals(x/length,morphed[0],2e-7);assertEquals(y/length,morphed[1],2e-7);
    }
}
