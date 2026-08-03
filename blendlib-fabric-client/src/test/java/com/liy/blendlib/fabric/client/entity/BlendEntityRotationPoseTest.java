package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlendEntityRotationPoseTest {
    private static final BlendEntityRotation QUARTER_TURN_Z =
            BlendEntityRotation.normalized(0.0F, 0.0F, 1.0F, 1.0F);

    @Test
    void adapterPoseIsImmutableAndCanOverrideOnlyKnownNodeRotations() {
        LocalPose coreBase = basePose();
        BlendEntityRotationPose base = BlendEntityRotationPoseAdapter.capture(coreBase);
        BlendEntityRotationPose modified = base.withRotation(1, QUARTER_TURN_Z);

        assertEquals(Set.of(0, 1), base.nodeIndices());
        assertEquals(BlendEntityRotation.IDENTITY, base.rotation(1));
        assertEquals(QUARTER_TURN_Z, modified.rotation(1));
        assertEquals(Map.of(1, QUARTER_TURN_Z), modified.rotationOverrides());
        assertNotSame(base, modified);
        assertThrows(UnsupportedOperationException.class, () -> base.nodeIndices().remove(0));
        assertThrows(UnsupportedOperationException.class,
                () -> modified.rotationOverrides().put(0, BlendEntityRotation.IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> base.withRotation(99, BlendEntityRotation.IDENTITY));
    }

    @Test
    void coreConversionPreservesTheExactNodeDomainTranslationAndScale() {
        LocalPose coreBase = basePose();
        BlendEntityRotationPose capturedBase = BlendEntityRotationPoseAdapter.capture(coreBase);
        LocalPose modified = BlendEntityRotationPoseAdapter.apply(
                coreBase,
                capturedBase,
                capturedBase.withRotation(1, QUARTER_TURN_Z));

        assertEquals(coreBase.transforms().keySet(), modified.transforms().keySet());
        assertSame(coreBase.transform(0), modified.transform(0));
        assertSame(coreBase.transform(1).translation(), modified.transform(1).translation());
        assertSame(coreBase.transform(1).scale(), modified.transform(1).scale());
        assertEquals(
                new Quaternion(
                        QUARTER_TURN_Z.x(),
                        QUARTER_TURN_Z.y(),
                        QUARTER_TURN_Z.z(),
                        QUARTER_TURN_Z.w()),
                modified.transform(1).rotation());
        assertEquals(Transform.IDENTITY.rotation(), coreBase.transform(1).rotation());
    }

    @Test
    void conversionRejectsNullOrForeignCallbackResults() {
        LocalPose coreBase = basePose();
        BlendEntityRotationPose capturedBase = BlendEntityRotationPoseAdapter.capture(coreBase);
        BlendEntityRotationPose foreign = BlendEntityRotationPoseAdapter.capture(coreBase)
                .withRotation(1, QUARTER_TURN_Z);

        assertThrows(IllegalArgumentException.class,
                () -> BlendEntityRotationPoseAdapter.apply(coreBase, capturedBase, null));
        assertThrows(IllegalArgumentException.class,
                () -> BlendEntityRotationPoseAdapter.apply(coreBase, capturedBase, foreign));
        assertSame(coreBase, BlendEntityRotationPoseAdapter.apply(coreBase, capturedBase, capturedBase));
    }

    @Test
    void publicRotationValueRejectsNonFiniteUnnormalizedAndZeroInputs() {
        assertEquals(QUARTER_TURN_Z, new BlendEntityRotation(
                QUARTER_TURN_Z.x(),
                QUARTER_TURN_Z.y(),
                QUARTER_TURN_Z.z(),
                QUARTER_TURN_Z.w()));
        assertThrows(IllegalArgumentException.class,
                () -> new BlendEntityRotation(0.0F, 0.0F, 0.0F, 2.0F));
        assertThrows(IllegalArgumentException.class,
                () -> BlendEntityRotation.normalized(Float.NaN, 0.0F, 0.0F, 1.0F));
        assertThrows(IllegalArgumentException.class,
                () -> BlendEntityRotation.normalized(0.0F, 0.0F, 0.0F, 0.0F));
    }

    private static LocalPose basePose() {
        LinkedHashMap<Integer, Transform> transforms = new LinkedHashMap<>();
        transforms.put(0, Transform.IDENTITY);
        transforms.put(1, new Transform(
                new Vec3(2.0F, 3.0F, 4.0F),
                Quaternion.IDENTITY,
                new Vec3(2.0F, 2.0F, 2.0F)));
        return new LocalPose(transforms);
    }
}
