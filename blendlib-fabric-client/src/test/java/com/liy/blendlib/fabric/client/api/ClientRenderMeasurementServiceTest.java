package com.liy.blendlib.fabric.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.perf.ClientRenderMeasurementCollector;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientRenderMeasurementServiceTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("blendlib_test:measurement/session");

    @AfterEach
    void cleanUp() {
        ClientRenderMeasurementService.resetExclusiveForTests();
    }

    @Test
    void onlyOneExclusiveSessionWinsUnderConcurrencyAndEpochsAreMonotonic() throws Exception {
        ClientRenderMeasurementService first = service();
        ClientRenderMeasurementService second = service();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(2);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable contender = () -> {
            try {
                start.await();
                Optional<ClientRenderMeasurementSession> session = first.tryBeginExclusiveCapture();
                if (session.isPresent()) {
                    winners.incrementAndGet();
                }
                attempted.countDown();
                assertTrue(attempted.await(10, TimeUnit.SECONDS));
                if (session.isPresent()) {
                    session.orElseThrow().close();
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        };
        Thread left = new Thread(contender, "exclusive-measurement-left");
        Thread right = new Thread(contender, "exclusive-measurement-right");
        left.start();
        right.start();
        start.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertEquals(null, failure.get());
        assertEquals(1, winners.get());

        ClientRenderMeasurementSession next = second.tryBeginExclusiveCapture().orElseThrow();
        assertTrue(next.epoch() > 0L);
        next.close();
    }

    @Test
    void ownerDrainsFramesAndLegacyCallsCannotStealOrSilentlyStopItsLease() {
        ClientRenderMeasurementService service = service();
        ClientRenderMeasurementSession session = service.tryBeginExclusiveCapture().orElseThrow();
        ClientRenderMeasurementCollector.finishSubmit(ClientRenderMeasurementCollector.startSubmit(), MODEL);

        ClientRenderMeasurementSnapshot snapshot = session.completeFrame();
        assertEquals(1, snapshot.submittedModelCounts().get(MODEL));

        service.beginCapture();
        assertThrows(IllegalStateException.class, session::completeFrame);
        session.close();
        assertFalse(service.capturing());
    }

    @Test
    void wrongThreadAndDoubleCloseAreRejectedWithoutGrantingAnotherOwner() throws Exception {
        ClientRenderMeasurementService service = service();
        ClientRenderMeasurementSession session = service.tryBeginExclusiveCapture().orElseThrow();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                session.completeFrame();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "wrong-measurement-thread");
        wrongThread.start();
        wrongThread.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertFalse(service.tryBeginExclusiveCapture().isPresent());
        assertThrows(IllegalStateException.class, session::completeFrame);
        session.close();
        assertThrows(IllegalStateException.class, session::close);
    }

    @Test
    void legacyCaptureMayRunOnlyWhenNoExclusiveOwnerIsLive() {
        ClientRenderMeasurementService service = service();
        service.beginCapture();
        assertTrue(service.completeFrame().isPresent());
        service.endCapture();

        Optional<ClientRenderMeasurementSession> session = service.tryBeginExclusiveCapture();
        assertTrue(session.isPresent());
        service.endCapture();
        assertThrows(IllegalStateException.class, session.orElseThrow()::completeFrame);
        session.orElseThrow().close();
    }

    @Test
    void publicCallersCannotMintOrTransferTheOpaqueP7AdmissionCapability() {
        ClientRenderMeasurementService service = service();
        ClientRenderMeasurementSession session = service.tryBeginExclusiveCapture().orElseThrow();

        assertThrows(SecurityException.class, () -> service.reserveP7ExclusiveCaptureCapability(session));
        assertThrows(SecurityException.class,
                () -> ClientRenderMeasurementService.transferP7ExclusiveCaptureCapability(new Object(), session.epoch(),
                        Thread.currentThread().threadId()));
        session.close();
    }

    private static ClientRenderMeasurementService service() {
        return new ClientRenderMeasurementService(() -> new ClientAnimationRuntimeMetrics(true, 0, 1, 0L, 0L, 0L, 0, 0));
    }
}
