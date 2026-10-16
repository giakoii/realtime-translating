package app.realtimetranslator.pipeline;

import app.realtimetranslator.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionTaskExecutorTest {

    private SessionTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void submit_runsTasksInFifoOrderForOneSession() throws Exception {
        executor = executor(10);
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);

        for (int value = 1; value <= 3; value++) {
            int item = value;
            executor.submit("session-1", () -> {
                order.add(item);
                done.countDown();
            });
        }

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    void submit_rejectsWhenQueuedChunkLimitIsReached() throws Exception {
        executor = executor(1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit("session-1", () -> {
            running.countDown();
            await(release);
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        executor.submit("session-1", () -> { });

        assertThrows(RejectedExecutionException.class,
                () -> executor.submit("session-1", () -> { }));
        release.countDown();
    }

    @Test
    void submitClose_isAcceptedWhenQueueIsFullAndRunsAfterDrain() throws Exception {
        executor = executor(1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        executor.submit("session-1", () -> {
            running.countDown();
            await(release);
            order.add("first");
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        executor.submit("session-1", () -> order.add("second"));

        assertDoesNotThrow(() -> executor.submitClose("session-1", () -> {
            order.add("close");
            closed.countDown();
        }));
        assertThrows(RejectedExecutionException.class,
                () -> executor.submit("session-1", () -> { }));
        release.countDown();

        assertTrue(closed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("first", "second", "close"), order);
        assertThrows(RejectedExecutionException.class,
                () -> executor.submit("session-1", () -> { }));
    }

    @Test
    void submitControl_ignoresChunkLimitAndRunsBeforeSubsequentClose() throws Exception {
        executor = executor(1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        executor.submit("session-1", () -> {
            running.countDown();
            await(release);
            order.add("running");
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        executor.submit("session-1", () -> order.add("queued-chunk"));

        assertDoesNotThrow(() -> executor.submitControl("session-1", () -> order.add("control")));
        executor.submitClose("session-1", () -> {
            order.add("close");
            closed.countDown();
        });
        release.countDown();

        assertTrue(closed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("running", "queued-chunk", "control", "close"), order);
        assertThrows(RejectedExecutionException.class,
                () -> executor.submitControl("session-1", () -> { }));
    }

    @Test
    void submitControl_rejectsWhenSessionWorkerDoesNotExist() {
        executor = executor(1);

        assertThrows(RejectedExecutionException.class,
                () -> executor.submitControl("session-1", () -> { }));
    }

    @Test
    void taskFailureDoesNotEscapeWorkerOrPreventNextTask() throws Exception {
        executor = executor(10);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch nextRan = new CountDownLatch(1);

        executor.submit("session-1", () -> {
            executions.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        executor.submit("session-1", () -> {
            executions.incrementAndGet();
            nextRan.countDown();
        });

        assertTrue(nextRan.await(2, TimeUnit.SECONDS));
        assertEquals(2, executions.get(), "each accepted task must execute exactly once");
    }

    private SessionTaskExecutor executor(int maxQueuedChunks) {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.Session session = mock(AppProperties.Session.class);
        when(properties.session()).thenReturn(session);
        when(session.maxQueuedChunks()).thenReturn(maxQueuedChunks);
        when(session.drainTimeoutSeconds()).thenReturn(1);
        return new SessionTaskExecutor(properties);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
