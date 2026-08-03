package com.liy.blendlib.fabric.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationPoseContext;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigView;
import com.liy.blendlib.fabric.client.animation.runtime.ClientAnimationRigViewTestAccess;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientAnimationPoseModifierValidationTest {
    private static final Quaternion QUARTER_TURN_Z =
            new Quaternion(0.0F, 0.0F, 0.70710677F, 0.70710677F);

    @Test
    void derivesRotationOnlyPoseWithoutReplacingEitherInstancesCachedBasePose() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(8);
        BlendInstanceKey firstKey = BlendInstanceKey.entity("modifier-session", 1);
        BlendInstanceKey secondKey = BlendInstanceKey.entity("modifier-session", 2);
        ClientAnimationInstance first = registry.bind(
                firstKey, ClientAnimationTestFixtures.MODEL, 7L, ClientAnimationTestFixtures.definition());
        ClientAnimationInstance second = registry.bind(
                secondKey, ClientAnimationTestFixtures.MODEL, 7L, ClientAnimationTestFixtures.definition());
        first.advance(0.25D);
        second.advance(0.75D);
        PoseCacheKey firstPoseKey = ClientAnimationTestFixtures.poseKey(firstKey, 7L, 1L);
        PoseCacheKey secondPoseKey = ClientAnimationTestFixtures.poseKey(secondKey, 7L, 1L);
        ClientAnimationPoseSnapshot firstBase = registry.preparePoseSnapshot(
                firstPoseKey, ClientAnimationTestFixtures.sampler());
        ClientAnimationPoseSnapshot secondBase = registry.preparePoseSnapshot(
                secondPoseKey, ClientAnimationTestFixtures.sampler());
        ClientAnimationPoseContext context = context(firstBase, 0.25D, 40.5D);

        ClientAnimationPoseSnapshot derived = registry.applyPoseModifier(
                firstBase,
                context,
                (ignored, basePose) -> rotate(basePose, 0, QUARTER_TURN_Z));

        assertNotSame(firstBase, derived);
        assertNotSame(firstBase.localPose(), derived.localPose());
        assertEquals(QUARTER_TURN_Z, derived.localPose().transform(0).rotation());
        assertEquals(firstBase.localPose().transform(0).translation(), derived.localPose().transform(0).translation());
        assertEquals(firstBase.localPose().transform(0).scale(), derived.localPose().transform(0).scale());
        assertSame(firstBase.localPose(), registry.cachedPose(firstPoseKey).orElseThrow());
        assertSame(firstBase.localPose(), first.latestPose().orElseThrow());
        assertSame(secondBase.localPose(), registry.cachedPose(secondPoseKey).orElseThrow());
        assertSame(secondBase.localPose(), second.latestPose().orElseThrow());
        assertEquals(Transform.IDENTITY.rotation(), secondBase.localPose().transform(0).rotation());
        assertEquals(2, registry.poseCacheMetrics().size());
    }

    @Test
    void rejectsNullNodeDomainTranslationAndScaleChangesBeforeDerivedSnapshotCreation() {
        ClientAnimationInstanceRegistry registry = new ClientAnimationInstanceRegistry(4);
        BlendInstanceKey key = BlendInstanceKey.entity("modifier-validation-session", 3);
        registry.bind(key, ClientAnimationTestFixtures.MODEL, 9L, ClientAnimationTestFixtures.definition());
        PoseCacheKey poseKey = ClientAnimationTestFixtures.poseKey(key, 9L, 1L);
        ClientAnimationPoseSnapshot base = registry.preparePoseSnapshot(
                poseKey, ClientAnimationTestFixtures.sampler());
        ClientAnimationPoseContext context = context(base, 0.0D, 12.0D);

        assertThrows(IllegalArgumentException.class,
                () -> registry.applyPoseModifier(base, context, (ignored, basePose) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.applyPoseModifier(base, context, (ignored, basePose) -> new LocalPose(Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> registry.applyPoseModifier(base, context, (ignored, basePose) -> {
                    Map<Integer, Transform> extra = new LinkedHashMap<>(basePose.transforms());
                    extra.put(1, Transform.IDENTITY);
                    return new LocalPose(extra);
                }));
        assertThrows(IllegalArgumentException.class,
                () -> registry.applyPoseModifier(base, context, (ignored, basePose) -> new LocalPose(Map.of(
                        0, new Transform(
                                basePose.transform(0).translation().add(new Vec3(1.0F, 0.0F, 0.0F)),
                                QUARTER_TURN_Z,
                                basePose.transform(0).scale())))));
        assertThrows(IllegalArgumentException.class,
                () -> registry.applyPoseModifier(base, context, (ignored, basePose) -> new LocalPose(Map.of(
                        0, new Transform(
                                basePose.transform(0).translation(),
                                QUARTER_TURN_Z,
                                new Vec3(2.0F, 2.0F, 2.0F))))));

        assertSame(base.localPose(), registry.cachedPose(poseKey).orElseThrow());
        assertSame(base.localPose(), registry.find(key).orElseThrow().latestPose().orElseThrow());
        assertEquals(1, registry.poseCacheMetrics().size());
    }

    @Test
    void rigViewExposesOnlyReadOnlyUnambiguousNameAndParentQueries() {
        ClientAnimationRigView rig = ClientAnimationRigViewTestAccess.fromNodes(List.of(
                new ModelNode(0, "Root", Transform.IDENTITY, List.of(1, 2, 3), -1, -1, false),
                new ModelNode(1, "Tail", Transform.IDENTITY, List.of(), -1, -1, false),
                new ModelNode(2, "Wing", Transform.IDENTITY, List.of(), -1, -1, false),
                new ModelNode(3, "Wing", Transform.IDENTITY, List.of(), -1, -1, false)));

        assertEquals(4, rig.nodeCount());
        assertEquals(0, rig.requireNodeIndex("Root"));
        assertEquals(1, rig.nodeIndex("Tail").orElseThrow());
        assertEquals(0, rig.parentIndex("Tail").orElseThrow());
        assertFalse(rig.parentIndex("Root").isPresent());
        assertFalse(rig.nodeIndex("Missing").isPresent());
        assertThrows(IllegalArgumentException.class, () -> rig.nodeIndex("Wing"));
        assertThrows(IllegalArgumentException.class, () -> rig.parentIndex(99));
        assertThrows(UnsupportedOperationException.class, () -> rig.uniqueNodeNames().add("Other"));
        assertThrows(UnsupportedOperationException.class, () -> rig.nodeIndices().add(4));
    }

    @Test
    void rigConstructionFactoryIsNotPartOfThePublicClientAdapterAbi() throws ReflectiveOperationException {
        var factory = ClientAnimationRigView.class.getDeclaredMethod("fromNodes", List.class);

        assertFalse(Modifier.isPublic(factory.getModifiers()));
    }

    private static ClientAnimationPoseContext context(
            ClientAnimationPoseSnapshot base, double animationTimeSeconds, double clientGameTimeInTicks) {
        return new ClientAnimationPoseContext(
                base.instanceKey(),
                base.modelKey(),
                base.generation(),
                base.animationKey(),
                animationTimeSeconds,
                clientGameTimeInTicks,
                ClientAnimationRigViewTestAccess.fromNodes(ClientAnimationTestFixtures.sampler().nodes()));
    }

    private static LocalPose rotate(LocalPose basePose, int nodeIndex, Quaternion rotation) {
        Map<Integer, Transform> transforms = new LinkedHashMap<>(basePose.transforms());
        Transform baseTransform = basePose.transform(nodeIndex);
        transforms.put(nodeIndex, new Transform(
                baseTransform.translation(), rotation, baseTransform.scale()));
        return new LocalPose(transforms);
    }
}
