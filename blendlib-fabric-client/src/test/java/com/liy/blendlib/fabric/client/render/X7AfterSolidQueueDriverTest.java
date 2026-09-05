package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.junit.jupiter.api.Test;

class X7AfterSolidQueueDriverTest {
    @Test
    void absentPublicSourceOrderProofKeepsHostAndStageAInactive() {
        RecordingPort port = new RecordingPort(false, true, X7AfterSolidQueueDriver.StageBResult.NO_WORK);
        X7AfterSolidQueueDriver driver = new X7AfterSolidQueueDriver(port);

        assertFalse(driver.hasPublicSourceOrderProof());
        assertFalse(driver.mayInstallHostEndpoint());
        driver.onAfterSolid(view(), view());
        driver.onBeforeTranslucent(view(), view());

        assertEquals(0, port.sealCalls);
        assertEquals(0, port.cancelCalls);
        assertEquals(1, port.pollCalls);
        assertEquals(X7AfterSolidQueueDriver.StageBResult.NO_WORK, driver.lastStageBResult());
    }

    @Test
    void provenFuturePortOwnsAfterSolidSealDrainAndBeforeTranslucentCancellation() {
        RecordingPort port = new RecordingPort(
                true,
                true,
                X7AfterSolidQueueDriver.StageBResult.RETAIN_SUBMITTED_OR_UNCERTAIN_NO_CPU_REPLAY);
        X7AfterSolidQueueDriver driver = new X7AfterSolidQueueDriver(port);

        assertTrue(driver.mayInstallHostEndpoint());
        driver.onAfterSolid(view(), view());
        driver.onBeforeTranslucent(view(), view());

        assertEquals(1, port.sealCalls);
        assertEquals(1, port.cancelCalls);
        assertEquals(1, port.pollCalls);
        assertEquals(
                X7AfterSolidQueueDriver.StageBResult.RETAIN_SUBMITTED_OR_UNCERTAIN_NO_CPU_REPLAY,
                driver.lastStageBResult());
    }

    @Test
    void missingTargetStillLetsThePortClassifyTheDrainedEpochAsAPreCommandOmission() {
        RecordingPort port = new RecordingPort(
                true,
                true,
                X7AfterSolidQueueDriver.StageBResult.OMIT_CURRENT_FRAME_AND_DISABLE_FUTURE_GPU);
        X7AfterSolidQueueDriver driver = new X7AfterSolidQueueDriver(port);

        driver.onAfterSolid(null, null);

        assertEquals(1, port.sealCalls);
        assertEquals(0, port.cancelCalls);
        assertEquals(1, port.pollCalls);
        assertNull(port.lastColorTarget);
        assertNull(port.lastDepthTarget);
        assertEquals(
                X7AfterSolidQueueDriver.StageBResult.OMIT_CURRENT_FRAME_AND_DISABLE_FUTURE_GPU,
                driver.lastStageBResult());
    }

    @Test
    void reloadCutoffStopsThePortOnceAndPreventsLaterPhaseExecution() {
        RecordingPort port = new RecordingPort(
                true,
                true,
                X7AfterSolidQueueDriver.StageBResult.OMIT_CURRENT_FRAME_AND_DISABLE_FUTURE_GPU);
        X7AfterSolidQueueDriver driver = new X7AfterSolidQueueDriver(port);

        driver.onReloadOrWorldLeave();
        driver.onReloadOrWorldLeave();
        driver.onAfterSolid(view(), view());

        assertEquals(1, port.closeCalls);
        assertEquals(0, port.sealCalls);
        assertEquals(1, port.pollCalls);
        assertFalse(driver.mayInstallHostEndpoint());
    }

    private static FakeTextureView view() {
        return new FakeTextureView();
    }

    private static final class RecordingPort implements X7AfterSolidQueueDriver.OptionalT4Adapter {
        private final boolean sourceOrderProven;
        private final boolean pipelineRegistered;
        private final X7AfterSolidQueueDriver.StageBResult stageBResult;
        private int sealCalls;
        private int pollCalls;
        private int cancelCalls;
        private int closeCalls;
        private GpuTextureView lastColorTarget;
        private GpuTextureView lastDepthTarget;

        private RecordingPort(
                boolean sourceOrderProven,
                boolean pipelineRegistered,
                X7AfterSolidQueueDriver.StageBResult stageBResult) {
            this.sourceOrderProven = sourceOrderProven;
            this.pipelineRegistered = pipelineRegistered;
            this.stageBResult = stageBResult;
        }

        @Override
        public boolean provesStageABeforeAfterSolidSeal() {
            return sourceOrderProven;
        }

        @Override
        public boolean hasRegisteredT3bPipeline() {
            return pipelineRegistered;
        }

        @Override
        public X7AfterSolidQueueDriver.StageAResult tryPreAdmit(
                X6PreparedRenderPlan plan,
                ModelRenderSnapshot snapshot,
                X6DrawPrimitive draw,
                X6PlanSubmitter.FinalDrawInputs finalDrawInputs) {
            return X7AfterSolidQueueDriver.StageAResult.SUPPRESS_CPU_AFTER_FULL_ADMISSION;
        }

        @Override
        public X7AfterSolidQueueDriver.StageBResult sealDrainAndExecute(
                GpuTextureView colorTarget, GpuTextureView depthTarget) {
            sealCalls++;
            lastColorTarget = colorTarget;
            lastDepthTarget = depthTarget;
            return stageBResult;
        }

        @Override
        public void pollRetainedCompletionFences() {
            pollCalls++;
        }

        @Override
        public void cancelNoCommandForAfterSolidEpoch() {
            cancelCalls++;
        }

        @Override
        public void closeAdmissionAndCancelNoCommand() {
            closeCalls++;
        }
    }

    private static final class FakeTextureView extends GpuTextureView {
        private FakeTextureView() {
            super(null, 0, 1);
        }

        @Override
        public void close() {
            // Test-only target token.
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
