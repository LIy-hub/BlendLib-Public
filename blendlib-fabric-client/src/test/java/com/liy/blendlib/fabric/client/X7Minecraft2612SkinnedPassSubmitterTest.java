package com.liy.blendlib.fabric.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.core.animation.runtime.LocalPose;
import com.liy.blendlib.core.animation.runtime.NodePalette;
import com.liy.blendlib.core.animation.runtime.PreparedSkinnedGeometry;
import com.liy.blendlib.core.animation.runtime.SkinPalette;
import com.liy.blendlib.core.model.MeshPrimitive;
import com.liy.blendlib.core.model.ModelNode;
import com.liy.blendlib.core.model.Skin;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.fabric.client.render.PreparedSkinnedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderMaterial;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedFrameProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance;
import com.liy.blendlib.fabric.client.render.x7gpu.X7SkinnedTexelProvenance.PaletteUpload;
import com.mojang.blaze3d.systems.RenderPass;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Focused same-D1-record, source-bind, and exact-one terminal-owner contracts for the isolated T4 candidate. */
class X7Minecraft2612SkinnedPassSubmitterTest {
    private final List<X7SkinnedTexelProvenance> closeAfterEach = new ArrayList<>();

    @AfterEach
    void clearFailureInjector() {
        X7Minecraft2612SkinnedPassSubmitter.clearFailureInjectorForTest();
        for (X7SkinnedTexelProvenance provenance : closeAfterEach) {
            provenance.close();
        }
        closeAfterEach.clear();
    }

    @Test
    void exactD1TokensMoveOnePreparedChildBeforeDrawAndRetainItUntilPositiveFence() {
        Fixture fixture = new Fixture();
        MutableFence fence = new MutableFence();
        TrackingClose transientClose = new TrackingClose();
        RecordingDriver driver = new RecordingDriver(fence, transientClose);

        X7Minecraft2612SkinnedPassSubmitter.Result result = X7Minecraft2612SkinnedPassSubmitter.execute(
                fixture.submission, fixture.resources, fixture.inputs(), fixture.scope, driver);

        assertEquals(X7Minecraft2612SkinnedPassSubmitter.Disposition.COMMAND_RECORDED, result.disposition());
        assertNull(result.failureOrNull());
        assertNotNull(result.receiptOrNull());
        assertTrue(driver.called);
        assertEquals(1, fixture.submission.recordCalls);
        assertEquals(0, fixture.submission.cancelCalls);
        assertEquals(0, fixture.completion.noCommandCloseCalls);
        assertEquals(0, fixture.completion.completedCalls);
        assertEquals(X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State.PENDING,
                result.receiptOrNull().poll(() -> { }));
        assertEquals(0, fixture.completion.completedCalls);
        assertEquals(0, transientClose.closeCalls);

        fence.complete = true;
        assertEquals(X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State.COMPLETED,
                result.receiptOrNull().poll(() -> { }));
        assertEquals(1, fixture.completion.completedCalls);
        assertEquals(0, fixture.completion.terminalRetainCalls);
        assertEquals(0, fixture.completion.noCommandCloseCalls);
        assertEquals(1, fence.closeCalls);
        assertEquals(1, transientClose.closeCalls);
    }

    @Test
    void exactRecordOrFrameMismatchCancelsBeforeACommandAndDoesNotPrepareOrMoveTheChild() {
        Fixture fixture = new Fixture();
        fixture.scope.frame = new Object();
        RecordingDriver driver = new RecordingDriver(new MutableFence(), new TrackingClose());

        X7Minecraft2612SkinnedPassSubmitter.Result result = X7Minecraft2612SkinnedPassSubmitter.execute(
                fixture.submission, fixture.resources, fixture.inputs(), fixture.scope, driver);

        assertEquals(X7Minecraft2612SkinnedPassSubmitter.Disposition.OMITTED_BEFORE_COMMAND, result.disposition());
        assertNotNull(result.failureOrNull());
        assertFalse(driver.called);
        assertEquals(0, fixture.submission.recordCalls);
        assertEquals(1, fixture.submission.cancelCalls);
        assertEquals(0, fixture.completion.completedCalls);
        assertEquals(0, fixture.completion.noCommandCloseCalls);
    }

    @Test
    void postDrawFailureRetainsTheMovedChildExactlyOnceAndNeverReopensCallerCancellation() {
        Fixture fixture = new Fixture();
        MutableFence fence = new MutableFence();
        TrackingClose transientClose = new TrackingClose();
        IllegalStateException failure = new IllegalStateException("post-draw failure");
        X7Minecraft2612SkinnedPassSubmitter.Driver driver = (resources, inputs, scope, commands) -> {
            X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receipt =
                    X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.pending(
                            fence, transientClose, commands.completionTransfer());
            commands.installPreallocatedReceipt(receipt);
            commands.recordAllBeforeNativeDraw();
            commands.markNativeDrawAttempted();
            throw failure;
        };

        X7Minecraft2612SkinnedPassSubmitter.Result result = X7Minecraft2612SkinnedPassSubmitter.execute(
                fixture.submission, fixture.resources, fixture.inputs(), fixture.scope, driver);

        assertEquals(X7Minecraft2612SkinnedPassSubmitter.Disposition.COMMAND_RECORDED, result.disposition());
        assertSame(failure, result.failureOrNull());
        assertNotNull(result.receiptOrNull());
        assertEquals(X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State.TERMINAL_NON_CLOSE,
                result.receiptOrNull().state());
        assertEquals(0, fixture.submission.cancelCalls);
        assertEquals(1, fixture.submission.recordCalls);
        assertEquals(1, fixture.completion.terminalRetainCalls);
        assertEquals(0, fixture.completion.noCommandCloseCalls);
        assertEquals(0, fixture.completion.completedCalls);
        assertEquals(0, transientClose.closeCalls);

        result.receiptOrNull().retainTerminalNonClose(new IllegalStateException("duplicate terminal path"));
        assertEquals(1, fixture.completion.terminalRetainCalls);
    }

    @Test
    void errorImmediatelyAfterAtomicChildMoveClosesThroughTheNewOwnerExactlyOnceWithoutCallerCancellation() {
        Fixture fixture = new Fixture();
        MutableFence fence = new MutableFence();
        TrackingClose transientClose = new TrackingClose();
        RecordingDriver driver = new RecordingDriver(fence, transientClose);
        OutOfMemoryError failure = new OutOfMemoryError("after-move");
        X7Minecraft2612SkinnedPassSubmitter.setFailureInjectorForTest(boundary -> {
            if (boundary == X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.CHILD_TRANSFER_AFTER_MOVE) {
                throw failure;
            }
        });

        assertSame(failure, assertThrows(OutOfMemoryError.class,
                () -> X7Minecraft2612SkinnedPassSubmitter.execute(
                        fixture.submission, fixture.resources, fixture.inputs(), fixture.scope, driver)));

        assertEquals(1, fixture.submission.recordCalls);
        assertEquals(0, fixture.submission.cancelCalls);
        assertEquals(1, fixture.completion.noCommandCloseCalls);
        assertEquals(0, fixture.completion.terminalRetainCalls);
        assertEquals(0, fixture.completion.completedCalls);
        assertEquals(1, transientClose.closeCalls);
    }

    @Test
    void receiptConstructionErrorRemainsBeforeTransferAndUsesTheCallerNoCommandPath() {
        Fixture fixture = new Fixture();
        AssertionError failure = new AssertionError("receipt construction");
        X7Minecraft2612SkinnedPassSubmitter.setFailureInjectorForTest(boundary -> {
            if (boundary == X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.RECEIPT_CONSTRUCTION) {
                throw failure;
            }
        });
        X7Minecraft2612SkinnedPassSubmitter.Driver driver = (resources, inputs, scope, commands) -> {
            X7Minecraft2612SkinnedPassSubmitter.hitFailureBoundaryForTest(
                    X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.RECEIPT_CONSTRUCTION);
            throw new AssertionError("unreachable");
        };

        assertSame(failure, assertThrows(AssertionError.class,
                () -> X7Minecraft2612SkinnedPassSubmitter.execute(
                        fixture.submission, fixture.resources, fixture.inputs(), fixture.scope, driver)));

        assertEquals(0, fixture.submission.recordCalls);
        assertEquals(1, fixture.submission.cancelCalls);
        assertEquals(0, fixture.completion.noCommandCloseCalls);
        assertEquals(0, fixture.completion.terminalRetainCalls);
    }

    @Test
    void drawFailpointAfterMoveButBeforeNativeDrawClosesTheMovedChildAndOmitsTheCommand() {
        Fixture fixture = new Fixture();
        MutableFence fence = new MutableFence();
        TrackingClose transientClose = new TrackingClose();
        IllegalStateException failure = new IllegalStateException("draw boundary");
        X7Minecraft2612SkinnedPassSubmitter.setFailureInjectorForTest(boundary -> {
            if (boundary == X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.DRAW_INDEXED) {
                throw failure;
            }
        });
        X7Minecraft2612SkinnedPassSubmitter.Driver driver = preparedDriverThenBoundary(
                fence, transientClose, X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.DRAW_INDEXED);

        X7Minecraft2612SkinnedPassSubmitter.Result result = X7Minecraft2612SkinnedPassSubmitter.execute(
                fixture.submission, fixture.resources, fixture.inputs(), fixture.scope, driver);

        assertEquals(X7Minecraft2612SkinnedPassSubmitter.Disposition.OMITTED_BEFORE_COMMAND, result.disposition());
        assertSame(failure, result.failureOrNull());
        assertNull(result.receiptOrNull());
        assertEquals(1, fixture.submission.recordCalls);
        assertEquals(0, fixture.submission.cancelCalls);
        assertEquals(1, fixture.completion.noCommandCloseCalls);
        assertEquals(0, fixture.completion.terminalRetainCalls);
        assertEquals(1, transientClose.closeCalls);
    }

    @Test
    void afterDrawPassCloseAndFenceAttachmentFailpointsRetainExactlyOnceAfterTheNativeDrawBoundary() {
        assertPostDrawBoundary(
                X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.AFTER_DRAW_INDEXED,
                "after draw boundary");
        assertPostDrawBoundary(
                X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.PASS_CLOSE,
                "pass close boundary");
        assertPostDrawBoundary(
                X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.FENCE_ATTACHMENT,
                "fence attachment boundary");
    }

    @Test
    void postDrawErrorRetainsExactlyOnceWithoutCallerCancellationThenRethrowsTheError() {
        Fixture fixture = new Fixture();
        MutableFence fence = new MutableFence();
        TrackingClose transientClose = new TrackingClose();
        AssertionError failure = new AssertionError("fence attachment error");
        X7Minecraft2612SkinnedPassSubmitter.FenceReceipt[] capturedReceipt = new X7Minecraft2612SkinnedPassSubmitter.FenceReceipt[1];
        X7Minecraft2612SkinnedPassSubmitter.setFailureInjectorForTest(boundary -> {
            if (boundary == X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.FENCE_ATTACHMENT) {
                throw failure;
            }
        });
        X7Minecraft2612SkinnedPassSubmitter.Driver driver = (resources, inputs, scope, commands) -> {
            X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receipt =
                    X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.pending(
                            fence, transientClose, commands.completionTransfer());
            capturedReceipt[0] = receipt;
            commands.installPreallocatedReceipt(receipt);
            commands.recordAllBeforeNativeDraw();
            commands.markNativeDrawAttempted();
            X7Minecraft2612SkinnedPassSubmitter.hitFailureBoundaryForTest(
                    X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.FENCE_ATTACHMENT);
            return receipt;
        };

        assertSame(failure, assertThrows(AssertionError.class,
                () -> X7Minecraft2612SkinnedPassSubmitter.execute(
                        fixture.submission, fixture.resources, fixture.inputs(), fixture.scope, driver)));

        assertNotNull(capturedReceipt[0]);
        assertEquals(X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State.TERMINAL_NON_CLOSE,
                capturedReceipt[0].state());
        assertEquals(1, fixture.submission.recordCalls);
        assertEquals(0, fixture.submission.cancelCalls);
        assertEquals(0, fixture.completion.noCommandCloseCalls);
        assertEquals(1, fixture.completion.terminalRetainCalls);
        assertEquals(0, transientClose.closeCalls);
    }

    @Test
    void finalPoseLightAndExactNeutralOverlayAreCopiedIntoTheOneInstanceStd140Upload() {
        Fixture fixture = new Fixture();
        Matrix4f modelView = new Matrix4f().translation(7.0F, 11.0F, 13.0F).scale(2.0F, 3.0F, 4.0F);
        Matrix3f normal = new Matrix3f(modelView).invert().transpose();
        X7Minecraft2612SkinnedPassSubmitter.FrameInputs inputs = new X7Minecraft2612SkinnedPassSubmitter.FrameInputs(
                fixture.frame, fixture.palette, modelView, normal, new Vector4f(1.0F, 0.5F, 0.25F, 1.0F),
                0x00B000A0, OverlayTexture.NO_OVERLAY);
        modelView.identity();
        normal.identity();

        ByteBuffer bytes = inputs.packForUpload();
        assertEquals(X7Minecraft2612SkinnedPassSubmitter.INSTANCE_UPLOAD_BYTES, bytes.remaining());
        assertEquals(2.0F, bytes.getFloat(0));
        assertEquals(0.5F, bytes.getFloat(X7Minecraft2612SkinnedPassSubmitter.MODEL_VIEW_BYTES));
        assertEquals(160.0F, bytes.getFloat(128));
        assertEquals(176.0F, bytes.getFloat(132));
        assertThrows(IllegalArgumentException.class, () -> new X7Minecraft2612SkinnedPassSubmitter.FrameInputs(
                fixture.frame, fixture.palette, new Matrix4f(), new Matrix3f(), new Vector4f(1.0F),
                0x00F000F0, 0));
    }

    @Test
    void frozenIndexedDrawUsesThePublicFourArgumentOneInstanceAbi() {
        int[] draw = {-1, -1, -1, -1};
        X7Minecraft2612SkinnedPassSubmitter.issueIndexedDraw(
                (baseVertex, firstIndex, indexCount, instanceCount) -> {
                    draw[0] = baseVertex;
                    draw[1] = firstIndex;
                    draw[2] = indexCount;
                    draw[3] = instanceCount;
                }, 3);
        assertArrayEquals(new int[] {0, 0, 3, 1}, draw);
        assertThrows(IllegalArgumentException.class,
                () -> X7Minecraft2612SkinnedPassSubmitter.issueIndexedDraw((a, b, c, d) -> { }, 0));
    }

    private static X7Minecraft2612SkinnedPassSubmitter.Driver preparedDriverThenBoundary(
            MutableFence fence,
            TrackingClose transientClose,
            X7Minecraft2612SkinnedPassSubmitter.FailureBoundary boundary) {
        return (resources, inputs, scope, commands) -> {
            X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receipt =
                    X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.pending(
                            fence, transientClose, commands.completionTransfer());
            commands.installPreallocatedReceipt(receipt);
            commands.recordAllBeforeNativeDraw();
            if (boundary == X7Minecraft2612SkinnedPassSubmitter.FailureBoundary.DRAW_INDEXED) {
                X7Minecraft2612SkinnedPassSubmitter.hitFailureBoundaryForTest(boundary);
            }
            commands.markNativeDrawAttempted();
            X7Minecraft2612SkinnedPassSubmitter.hitFailureBoundaryForTest(boundary);
            return receipt;
        };
    }

    private void assertPostDrawBoundary(
            X7Minecraft2612SkinnedPassSubmitter.FailureBoundary boundary, String message) {
        Fixture fixture = new Fixture();
        MutableFence fence = new MutableFence();
        TrackingClose transientClose = new TrackingClose();
        IllegalStateException failure = new IllegalStateException(message);
        X7Minecraft2612SkinnedPassSubmitter.setFailureInjectorForTest(hit -> {
            if (hit == boundary) {
                throw failure;
            }
        });

        X7Minecraft2612SkinnedPassSubmitter.Result result = X7Minecraft2612SkinnedPassSubmitter.execute(
                fixture.submission,
                fixture.resources,
                fixture.inputs(),
                fixture.scope,
                preparedDriverThenBoundary(fence, transientClose, boundary));

        assertEquals(X7Minecraft2612SkinnedPassSubmitter.Disposition.COMMAND_RECORDED, result.disposition());
        assertSame(failure, result.failureOrNull());
        assertNotNull(result.receiptOrNull());
        assertEquals(X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.State.TERMINAL_NON_CLOSE,
                result.receiptOrNull().state());
        assertEquals(1, fixture.submission.recordCalls);
        assertEquals(0, fixture.submission.cancelCalls);
        assertEquals(0, fixture.completion.noCommandCloseCalls);
        assertEquals(1, fixture.completion.terminalRetainCalls);
        assertEquals(0, transientClose.closeCalls);
    }

    private static X7SkinnedTexelProvenance materializedPaletteProvenance() {
        PreparedSkinnedGeometry geometry = PreparedSkinnedGeometry.prepare(new MeshPrimitive(
                "skinned",
                new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new int[] {0, 1, 2},
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new float[] {1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F}));
        Skin skin = new Skin("skin", 0, List.of(0), new float[] {
                1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F
        });
        SkinPalette palette = SkinPalette.from(skin, NodePalette.from(
                new LocalPose(Map.of(0, Transform.IDENTITY)),
                List.of(new ModelNode(0, "joint", Transform.IDENTITY, List.of(), -1, -1, false))));
        PreparedSkinnedRenderPrimitive primitive = new PreparedSkinnedRenderPrimitive(
                0, 0, geometry, RenderMaterial.missing(0xFFFFFFFF));
        X7SkinnedTexelProvenance.Attempt attempt =
                X7SkinnedFrameProvenance.capture(primitive, palette).provenance().tryMaterialize();
        assertTrue(attempt.eligible());
        return Objects.requireNonNull(attempt.provenanceOrNull(), "materialized provenance");
    }

    private final class Fixture {
        private final Object record = new Object();
        private final Object geometry = new Object();
        private final Object influence = new Object();
        private final PaletteUpload palette;
        private final Object frame = new Object();
        private final TrackingCompletion completion = new TrackingCompletion();
        private final FakeResources resources;
        private final FakeSubmission submission;
        private final FakeScope scope;

        private Fixture() {
            X7SkinnedTexelProvenance provenance = materializedPaletteProvenance();
            closeAfterEach.add(provenance);
            palette = provenance.paletteUpload();
            resources = new FakeResources(record, geometry, influence, palette);
            submission = new FakeSubmission(record, geometry, influence, palette, frame, completion);
            scope = new FakeScope(frame);
        }

        private X7Minecraft2612SkinnedPassSubmitter.FrameInputs inputs() {
            return new X7Minecraft2612SkinnedPassSubmitter.FrameInputs(
                    frame,
                    palette,
                    new Matrix4f(),
                    new Matrix3f(),
                    new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                    0x00F000F0,
                    OverlayTexture.NO_OVERLAY);
        }
    }

    private static final class FakeResources implements X7Minecraft2612SkinnedPassSubmitter.D1SkinnedResources {
        private final Object record;
        private final Object geometry;
        private final Object influence;
        private FakeResources(Object record, Object geometry, Object influence, PaletteUpload palette) {
            this.record = record;
            this.geometry = geometry;
            this.influence = influence;
        }

        @Override
        public Object exactD1Record() {
            return record;
        }

        @Override
        public Object exactGeometryLeaf() {
            return geometry;
        }

        @Override
        public Object exactInfluenceLeaf() {
            return influence;
        }

        @Override
        public int vertexCount() {
            return 3;
        }

        @Override
        public int indexCount() {
            return 3;
        }

        @Override
        public Identifier textureId() {
            return Identifier.fromNamespaceAndPath("blendlib_test", "skinned");
        }

        @Override
        public void bindTo(RenderPass pass) {
            // The focused transfer tests intentionally do not own a native pass or buffer handle.
        }
    }

    private static final class FakeSubmission implements X7Minecraft2612SkinnedPassSubmitter.D1Submission {
        private final Object record;
        private final Object geometry;
        private final Object influence;
        private final PaletteUpload palette;
        private final Object frame;
        private final X7Minecraft2612SkinnedPassSubmitter.CompletionReceipt completion;
        private int recordCalls;
        private int cancelCalls;

        private FakeSubmission(
                Object record,
                Object geometry,
                Object influence,
                PaletteUpload palette,
                Object frame,
                X7Minecraft2612SkinnedPassSubmitter.CompletionReceipt completion) {
            this.record = record;
            this.geometry = geometry;
            this.influence = influence;
            this.palette = palette;
            this.frame = frame;
            this.completion = completion;
        }

        @Override
        public Object exactFrameIdentity() {
            return frame;
        }

        @Override
        public Object exactD1Record() {
            return record;
        }

        @Override
        public Object exactGeometryLeaf() {
            return geometry;
        }

        @Override
        public Object exactInfluenceLeaf() {
            return influence;
        }

        @Override
        public PaletteUpload paletteUpload() {
            return palette;
        }

        @Override
        public X7Minecraft2612SkinnedPassSubmitter.CompletionReceipt preallocatedCompletionReceipt() {
            return completion;
        }

        @Override
        public void recordAllBeforeNativeDraw(X7Minecraft2612SkinnedPassSubmitter.CompletionTransfer transfer) {
            recordCalls++;
            transfer.acceptMovedChild();
        }

        @Override
        public void cancelNoCommand() {
            cancelCalls++;
        }
    }

    private static final class FakeScope implements X7Minecraft2612SkinnedPassSubmitter.AfterSolidScope {
        private Object frame;

        private FakeScope(Object frame) {
            this.frame = frame;
        }

        @Override
        public Object exactFrameIdentity() {
            return frame;
        }

        @Override
        public com.mojang.blaze3d.textures.GpuTextureView colorTarget() {
            return null;
        }

        @Override
        public com.mojang.blaze3d.textures.GpuTextureView depthTarget() {
            return null;
        }
    }

    private static final class TrackingCompletion implements X7Minecraft2612SkinnedPassSubmitter.CompletionReceipt {
        private int noCommandCloseCalls;
        private int completedCalls;
        private int terminalRetainCalls;

        @Override
        public void closeNoCommand() {
            noCommandCloseCalls++;
        }

        @Override
        public void completeAfterVerifiedFence() {
            completedCalls++;
        }

        @Override
        public void retainTerminalNonClose(Throwable failure) {
            terminalRetainCalls++;
        }
    }

    private static final class MutableFence implements X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.Fence {
        private boolean complete;
        private int closeCalls;

        @Override
        public boolean awaitCompletionZero() {
            return complete;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class TrackingClose implements X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.TransientClose {
        private int closeCalls;

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class RecordingDriver implements X7Minecraft2612SkinnedPassSubmitter.Driver {
        private final MutableFence fence;
        private final TrackingClose transientClose;
        private boolean called;

        private RecordingDriver(MutableFence fence, TrackingClose transientClose) {
            this.fence = fence;
            this.transientClose = transientClose;
        }

        @Override
        public X7Minecraft2612SkinnedPassSubmitter.FenceReceipt record(
                X7Minecraft2612SkinnedPassSubmitter.D1SkinnedResources resources,
                X7Minecraft2612SkinnedPassSubmitter.FrameInputs inputs,
                X7Minecraft2612SkinnedPassSubmitter.AfterSolidScope scope,
                X7Minecraft2612SkinnedPassSubmitter.CommandsRecorded commands) {
            called = true;
            X7Minecraft2612SkinnedPassSubmitter.FenceReceipt receipt =
                    X7Minecraft2612SkinnedPassSubmitter.FenceReceipt.pending(
                            fence, transientClose, commands.completionTransfer());
            commands.installPreallocatedReceipt(receipt);
            commands.recordAllBeforeNativeDraw();
            commands.markNativeDrawAttempted();
            return receipt;
        }
    }
}
