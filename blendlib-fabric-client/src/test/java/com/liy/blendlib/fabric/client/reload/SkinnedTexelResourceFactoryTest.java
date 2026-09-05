package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Quaternion;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderMaterial;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedFrameProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance;
import com.mojang.blaze3d.systems.RenderPass;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Focused T4p proof that D1 owns exact static streams while each frame keeps its separate typed palette unit. */
class SkinnedTexelResourceFactoryTest {
    @Test
    void validFrameProofMaterializesOneExactThreeBufferLeafAndKeepsCurrentPaletteOutOfD1() {
        ProvenanceFixture source = validProvenance(0.0F, 0, 0.0F, 0.0F, 1.0F);
        SkinnedTexelUploadStaging staging = staging(source);
        FakeBindings bindings = new FakeBindings();

        SkinnedTexelResourceFactory.Attempt attempt = SkinnedTexelResourceFactory.prepare(
                X7GpuTestFixtures.key(), staging, new ImmediateAllocator(bindings), (key, upload, allocated) ->
                        new SkinnedTexelGenerationResources(
                                key,
                                upload.staticSource(),
                                upload.vertexCount(),
                                upload.indexCount(),
                                upload.vertexByteCount(),
                                upload.indexByteCount(),
                                upload.sourceByteCount(),
                                allocated));

        assertTrue(attempt.succeeded());
        assertTrue(staging.isClosed());
        assertFalse(source.provenance().isClosed());
        SkinnedTexelGenerationResources leaf = attempt.resourcesOrNull();
        assertTrue(leaf.matchesCurrentFrame(source.provenance()));
        assertEquals(3, leaf.vertexCount());
        assertEquals(3, leaf.indexCount());
        assertEquals(3 * X7SkinnedTexelProvenance.SOURCE_RECORD_BYTES, leaf.sourceByteCount());
        assertEquals(3, leaf.physicalResourceCount());
        assertEquals(3L * 32L + 3L * Integer.BYTES + 3L * X7SkinnedTexelProvenance.SOURCE_RECORD_BYTES,
                leaf.physicalByteCount());
        assertThrows(NullPointerException.class, () -> leaf.bindTo(null));
        assertEquals(0, bindings.bindCalls);

        ByteBuffer palette = source.provenance().paletteUpload().bytesForUpload();
        assertTrue(palette.isDirect());
        assertEquals(8 * 1024, palette.remaining());
        assertThrows(ReadOnlyBufferException.class, () -> palette.put(0, (byte) 1));

        leaf.closeSynchronouslyAfterVerifiedD1Completion();
        assertTrue(leaf.isClosed());
        assertFalse(source.provenance().isClosed(), "D1 must not own a transient frame palette");
        source.provenance().close();
        assertTrue(source.provenance().isClosed());
        assertEquals(1, bindings.closeCalls);
    }

    @Test
    void assemblerFailureRollsBackTheOneExactBindingSetWithoutTakingCurrentPaletteOwnership() {
        ProvenanceFixture source = validProvenance(0.0F, 0, 0.0F, 0.0F, 1.0F);
        SkinnedTexelUploadStaging staging = staging(source);
        FakeBindings bindings = new FakeBindings();
        IllegalStateException failure = new IllegalStateException("assemble");

        SkinnedTexelResourceFactory.Attempt attempt = SkinnedTexelResourceFactory.prepare(
                X7GpuTestFixtures.key(), staging, new ImmediateAllocator(bindings), (key, upload, allocated) -> {
                    throw failure;
                });

        assertFalse(attempt.succeeded());
        assertSame(failure, attempt.failureOrNull());
        assertTrue(staging.isClosed());
        assertFalse(source.provenance().isClosed());
        source.provenance().close();
        assertEquals(1, bindings.closeCalls);
    }

    @Test
    void failedPhysicalCloseRetainsTheExactStaticSourceUntilOneDeterministicRetry() {
        ProvenanceFixture source = validProvenance(0.0F, 0, 0.0F, 0.0F, 1.0F);
        SkinnedTexelUploadStaging staging = staging(source);
        FakeBindings bindings = new FakeBindings();
        bindings.closeFailure = new IllegalStateException("first close");
        SkinnedTexelGenerationResources leaf = new SkinnedTexelGenerationResources(
                X7GpuTestFixtures.key(), staging.staticSource(), 3, 3, 96, 12, 108, bindings);
        staging.transferStaticSourceToLeaf(leaf);
        staging.close();

        assertThrows(IllegalStateException.class, leaf::closeSynchronouslyAfterVerifiedD1Completion);
        assertFalse(leaf.isClosed());
        assertFalse(source.provenance().isClosed());
        assertEquals(1, bindings.closeCalls);
        bindings.closeFailure = null;
        leaf.closeSynchronouslyAfterVerifiedD1Completion();
        assertTrue(leaf.isClosed());
        assertFalse(source.provenance().isClosed());
        source.provenance().close();
        assertTrue(source.provenance().isClosed());
        assertEquals(2, bindings.closeCalls);
    }

    @Test
    void generationStaticSourceAcceptsOnlyTheSameGeometryWithANewCurrentPaletteProof() {
        ProvenanceFixture generationFrame = validProvenance(0.0F, 0, 0.0F, 0.0F, 1.0F);
        SkinnedTexelUploadStaging staging = staging(generationFrame);
        FakeBindings bindings = new FakeBindings();
        SkinnedTexelGenerationResources leaf = new SkinnedTexelGenerationResources(
                X7GpuTestFixtures.key(), staging.staticSource(), 3, 3, 96, 12, 108, bindings);
        staging.transferStaticSourceToLeaf(leaf);
        staging.close();

        ProvenanceFixture currentFrame = provenanceFor(
                generationFrame.primitive(), palette(2.0F));
        assertFalse(generationFrame.provenance().isClosed());
        assertFalse(currentFrame.provenance().isClosed());
        assertFalse(generationFrame.provenance().paletteUpload() == currentFrame.provenance().paletteUpload());
        assertTrue(leaf.matchesCurrentFrame(currentFrame.provenance()),
                "D1 must match only its immutable geometry/influence source, not a prior frame palette identity");

        leaf.closeSynchronouslyAfterVerifiedD1Completion();
        assertFalse(currentFrame.provenance().isClosed(), "the current palette remains frame-owned after D1 static close");
        generationFrame.provenance().close();
        currentFrame.provenance().close();
        assertEquals(1, bindings.closeCalls);
    }

    @Test
    void sameCountDifferentGeometryAndRawInputsCannotFabricateAnAcceptedUpload() {
        ProvenanceFixture sourceA = validProvenance(0.0F, 0, 0.0F, 0.0F, 1.0F);
        ProvenanceFixture sourceB = validProvenance(9.0F, 2, 0.0F, 1.0F, 0.0F);
        CountingAllocator allocator = new CountingAllocator();

        assertThrows(IllegalArgumentException.class,
                () -> SkinnedTexelUploadStaging.capture(sourceB.frame(), sourceA.provenance()));
        assertFalse(sourceA.provenance().isClosed());
        assertEquals(0, allocator.allocateCalls);

        // A zero-total-weight primitive never creates a materializable frame proof, and no raw bytes/token overload
        // exists on capture or the factory. The only legal entry is sourceFrame.tryMaterialize().
        assertThrows(IllegalArgumentException.class, () -> preparedGeometry(0.0F, 0));
        assertNotNull(sourceB.provenance().paletteUpload());
        sourceA.provenance().close();
        sourceB.provenance().close();
    }

    private static SkinnedTexelUploadStaging staging(ProvenanceFixture fixture) {
        return SkinnedTexelUploadStaging.capture(fixture.frame(), fixture.provenance());
    }

    private static ProvenanceFixture validProvenance(
            float firstPositionX, int firstIndex, float normalX, float normalY, float normalZ) {
        PreparedSkinnedGeometry geometry = preparedGeometry(1.0F, firstIndex, firstPositionX, normalX, normalY, normalZ);
        PreparedSkinnedRenderPrimitive primitive = new PreparedSkinnedRenderPrimitive(
                0, 0, geometry, RenderMaterial.missing(0xFFFFFFFF));
        return provenanceFor(primitive, identityPalette());
    }

    private static ProvenanceFixture provenanceFor(PreparedSkinnedRenderPrimitive primitive, SkinPalette palette) {
        X7SkinnedFrameProvenance.SealedFrame sealed = X7SkinnedFrameProvenance.capture(primitive, palette);
        X7SkinnedFrameProvenance frame = sealed.provenance();
        X7SkinnedTexelProvenance.Attempt attempt = frame.tryMaterialize();
        assertTrue(attempt.eligible());
        X7SkinnedTexelProvenance provenance = attempt.provenanceOrNull();
        assertNotNull(provenance);
        return new ProvenanceFixture(primitive, frame, provenance);
    }

    private static PreparedSkinnedGeometry preparedGeometry(float firstWeight, int firstIndex) {
        return preparedGeometry(firstWeight, firstIndex, 0.0F, 0.0F, 0.0F, 1.0F);
    }

    private static PreparedSkinnedGeometry preparedGeometry(
            float firstWeight, int firstIndex, float firstPositionX, float normalX, float normalY, float normalZ) {
        return PreparedSkinnedGeometry.prepare(new MeshPrimitive(
                "skinned",
                new float[] {firstPositionX, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[] {normalX, normalY, normalZ, normalX, normalY, normalZ, normalX, normalY, normalZ},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {firstIndex, 1, 2},
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {
                    firstWeight, 0.0F, 0.0F, 0.0F,
                    firstWeight, 0.0F, 0.0F, 0.0F,
                    firstWeight, 0.0F, 0.0F, 0.0F
                }));
    }

    private static SkinPalette identityPalette() {
        return palette(0.0F);
    }

    private static SkinPalette palette(float translationX) {
        Skin skin = new Skin("skin", 0, List.of(0), new float[] {
                1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F
        });
        NodePalette nodes = NodePalette.from(
                new LocalPose(Map.of(0, new Transform(new Vec3(translationX, 0.0F, 0.0F), Quaternion.IDENTITY, Vec3.ONE))),
                List.of(new ModelNode(0, "joint", Transform.IDENTITY, List.of(), -1, -1, false)));
        return SkinPalette.from(skin, nodes);
    }

    private record ProvenanceFixture(
            PreparedSkinnedRenderPrimitive primitive,
            X7SkinnedFrameProvenance frame,
            X7SkinnedTexelProvenance provenance) {
    }

    private static final class ImmediateAllocator implements SkinnedTexelResourceFactory.BufferAllocator {
        private final FakeBindings bindings;

        private ImmediateAllocator(FakeBindings bindings) {
            this.bindings = bindings;
        }

        @Override
        public void assertOnRenderThread() {
        }

        @Override
        public SkinnedTexelGenerationResources.BufferBindings allocate(
                X7GpuGenerationKey key, SkinnedTexelUploadStaging staging) {
            assertEquals(3, staging.vertexCount());
            assertEquals(3 * X7SkinnedTexelProvenance.SOURCE_RECORD_BYTES, staging.sourceBytesForUpload().remaining());
            assertEquals(0.0F, staging.vertexBytesForUpload().getFloat(0));
            assertEquals(1.0F, staging.vertexBytesForUpload().getFloat(5 * Float.BYTES));
            assertEquals(0, staging.indexBytesForUpload().getInt(0));
            assertEquals(0, Short.toUnsignedInt(staging.sourceBytesForUpload().getShort(0)));
            assertEquals(1.0F, staging.sourceBytesForUpload().getFloat(4 * Short.BYTES));
            return bindings;
        }
    }

    private static final class CountingAllocator implements SkinnedTexelResourceFactory.BufferAllocator {
        private int allocateCalls;

        @Override
        public void assertOnRenderThread() {
        }

        @Override
        public SkinnedTexelGenerationResources.BufferBindings allocate(
                X7GpuGenerationKey key, SkinnedTexelUploadStaging staging) {
            allocateCalls++;
            throw new AssertionError("same-count mismatch must fail before native allocation");
        }
    }

    private static final class FakeBindings implements SkinnedTexelGenerationResources.BufferBindings {
        private int bindCalls;
        private int closeCalls;
        private RuntimeException closeFailure;

        @Override
        public void bindTo(RenderPass pass) {
            bindCalls++;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
