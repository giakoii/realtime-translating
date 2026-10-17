package app.realtimetranslator.pipeline;

import app.realtimetranslator.config.AppProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A FIFO, single-worker queue for each active session. */
@Component
@Slf4j
public class SessionTaskExecutor {

    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    private final Set<String> closedSessions = ConcurrentHashMap.newKeySet();
    private final int maxQueuedChunks;
    private final Duration drainTimeout;

    public SessionTaskExecutor(AppProperties properties) {
        this.maxQueuedChunks = properties.session().maxQueuedChunks();
        this.drainTimeout = Duration.ofSeconds(properties.session().drainTimeoutSeconds());
    }

    /** Queues a chunk, or rejects it when the session is closing or backpressured. */
    public void submit(String sessionId, Runnable task) {
        if (closedSessions.contains(sessionId)) {
            throw new RejectedExecutionException("Session queue is closed: " + sessionId);
        }
        Worker worker = workers.computeIfAbsent(sessionId, Worker::new);
        synchronized (worker) {
            if (closedSessions.contains(sessionId)) {
                worker.executor.shutdown();
                workers.remove(sessionId, worker);
                throw new RejectedExecutionException("Session queue is closed: " + sessionId);
            }
            if (worker.closing || worker.executor.isShutdown()) {
                throw new RejectedExecutionException("Session queue is closed: " + sessionId);
            }
            if (worker.executor.getQueue().size() >= maxQueuedChunks) {
                throw new RejectedExecutionException("Session queue is full: " + sessionId);
            }
            worker.executor.execute(safe(sessionId, task));
        }
    }

    /**
     * Queues a small control dispatch on an existing open session worker. Controls
     * preserve FIFO with chunks and are accepted without a backlog-limit check.
     *
     * @throws RejectedExecutionException when no worker exists or close has begun
     */
    public void submitControl(String sessionId, Runnable task) {
        Worker worker = workers.get(sessionId);
        if (worker == null || closedSessions.contains(sessionId)) {
            throw new RejectedExecutionException("Session queue is not open: " + sessionId);
        }
        synchronized (worker) {
            if (workers.get(sessionId) != worker || closedSessions.contains(sessionId)
                    || worker.closing || worker.executor.isShutdown()) {
                throw new RejectedExecutionException("Session queue is not open: " + sessionId);
            }
            worker.executor.execute(safe(sessionId, task));
        }
    }

    /**
     * Appends an unbounded close sentinel. It always runs after accepted chunks and
     * removes the per-session worker after the close action finishes.
     */
    public void submitClose(String sessionId, Runnable closeTask) {
        if (closedSessions.contains(sessionId)) {
            return;
        }
        Worker worker = workers.computeIfAbsent(sessionId, Worker::new);
        synchronized (worker) {
            if (closedSessions.contains(sessionId)) {
                worker.executor.shutdown();
                workers.remove(sessionId, worker);
                return;
            }
            if (worker.closing) {
                return;
            }
            worker.closing = true;
            worker.executor.execute(() -> {
                try {
                    closeTask.run();
                } catch (RuntimeException failure) {
                    log.error("[SES001] Session close task failed: sessionId={}, cause={}",
                            sessionId, failure.getClass().getSimpleName());
                } finally {
                    closedSessions.add(sessionId);
                    worker.executor.shutdown();
                    workers.remove(sessionId, worker);
                }
            });
        }
    }

    public boolean isClosing(String sessionId) {
        Worker worker = workers.get(sessionId);
        return worker != null && worker.closing;
    }

    int workerCount() {
        return workers.size();
    }

    @PreDestroy
    public void shutdown() {
        workers.values().forEach(worker -> worker.executor.shutdown());
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        for (Worker worker : workers.values()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                worker.executor.shutdownNow();
                continue;
            }
            try {
                if (!worker.executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    worker.executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                worker.executor.shutdownNow();
            }
        }
        workers.clear();
        closedSessions.clear();
    }

    private Runnable safe(String sessionId, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException failure) {
                log.error("[SES001] Session task failed: sessionId={}, cause={}",
                        sessionId, failure.getClass().getSimpleName());
            }
        };
    }

    private static final class Worker {
        private final ThreadPoolExecutor executor;
        private volatile boolean closing;

        private Worker(String sessionId) {
            executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    Thread.ofVirtual().name("session-" + sessionId + "-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }
}
