package app.realtimetranslator.session;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.outbound.ServerMessages;
import app.realtimetranslator.pipeline.SessionTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionLifecycleServiceTest {

    private BufferPersistService persistService;
    private ClosedSessionFlushRetrier retrier;
    private ClientSessionRegistry registry;
    private SessionRepository repository;
    private SessionTaskExecutor taskExecutor;
    private SessionLifecycleService lifecycle;
    private WebSocketSession socket;

    @BeforeEach
    void setUp() {
        persistService = mock(BufferPersistService.class);
        retrier = mock(ClosedSessionFlushRetrier.class);
        registry = mock(ClientSessionRegistry.class);
        socket = mock(WebSocketSession.class);
        repository = new SessionRepository();
        AppProperties properties = properties();
        taskExecutor = new SessionTaskExecutor(properties);
        lifecycle = new SessionLifecycleService(
                persistService, repository, taskExecutor, retrier, registry, properties);
        when(registry.bind("session-1", socket)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        taskExecutor.shutdown();
    }

    @Test
    void open_isIdempotentForStorageButRejectsAnotherLiveOwner() {
        ScriptBuffer opened = lifecycle.open("session-1", socket);
        when(registry.isBoundTo("session-1", socket)).thenReturn(true);

        assertSame(opened, repository.get("session-1").orElseThrow());
        assertTrue(lifecycle.isOpen("session-1"));
        assertSame(opened, lifecycle.open("session-1", socket));
        assertThrows(SessionBusyException.class, () -> lifecycle.open("session-1", mock(WebSocketSession.class)));
        verify(persistService, times(1)).getOrCreateMeeting("session-1", "ja", "vi");
    }

    @Test
    void open_rejectsEndedSessionAndFutureReopen() {
        doThrow(new SessionEndedException("session-1"))
                .when(persistService).getOrCreateMeeting("session-1", "ja", "vi");

        assertThrows(SessionEndedException.class, () -> lifecycle.open("session-1", socket));
        assertThrows(SessionEndedException.class, () -> lifecycle.open("session-1", socket));
        verify(registry).unregister("session-1");
    }

    @Test
    void close_drainsAcceptedTasksThenRespondsBeforeUnregister() throws Exception {
        ScriptBuffer buffer = lifecycle.open("session-1", socket);
        CountDownLatch pipelineStarted = new CountDownLatch(1);
        CountDownLatch releasePipeline = new CountDownLatch(1);
        taskExecutor.submit("session-1", () -> {
            pipelineStarted.countDown();
            await(releasePipeline);
            buffer.add(entry());
        });
        assertTrue(pipelineStarted.await(2, TimeUnit.SECONDS));

        lifecycle.close("session-1");
        verify(persistService, never()).endSession(any());
        releasePipeline.countDown();
        verify(registry, timeout(2_000)).unregister("session-1");

        ArgumentCaptor<ServerMessages.SessionClosed> message =
                ArgumentCaptor.forClass(ServerMessages.SessionClosed.class);
        var inOrder = inOrder(persistService, registry);
        inOrder.verify(persistService).endSession("session-1");
        inOrder.verify(registry).send(eq("session-1"), message.capture());
        inOrder.verify(registry).unregister("session-1");
        assertEquals(1, message.getValue().totalMessages());
        assertFalse(lifecycle.isOpen("session-1"));
    }

    @Test
    void close_isIdempotent() {
        lifecycle.open("session-1", socket);

        lifecycle.close("session-1");
        lifecycle.close("session-1");

        verify(registry, timeout(2_000)).unregister("session-1");
        verify(persistService, times(1)).endSession("session-1");
    }

    @Test
    void close_retainsBufferAndRegistersBackgroundRetryAfterFinalFailure() {
        ScriptBuffer opened = lifecycle.open("session-1", socket);
        opened.add(entry());
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistService).endSession("session-1");

        lifecycle.close("session-1");

        verify(retrier, timeout(2_000)).track("session-1");
        assertSame(opened, repository.get("session-1").orElseThrow());
        verify(registry, timeout(2_000)).unregister("session-1");
    }

    @Test
    void runIfOpen_isAtomicWithCloseTransition() throws Exception {
        lifecycle.open("session-1", socket);
        CountDownLatch publicationStarted = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var published = executor.submit(() -> lifecycle.runIfOpen("session-1", () -> {
                publicationStarted.countDown();
                await(releasePublication);
            }));
            assertTrue(publicationStarted.await(2, TimeUnit.SECONDS));
            var closed = executor.submit(() -> lifecycle.close("session-1"));

            Thread.sleep(100);
            assertFalse(closed.isDone());
            releasePublication.countDown();
            assertTrue(published.get(2, TimeUnit.SECONDS));
            closed.get(2, TimeUnit.SECONDS);
        }

        verify(persistService, timeout(2_000)).endSession("session-1");
        assertFalse(lifecycle.runIfOpen("session-1", () -> fail("must not run after close")));
    }

    private AppProperties properties() {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.Session session = mock(AppProperties.Session.class);
        AppProperties.Buffer buffer = mock(AppProperties.Buffer.class);
        when(properties.session()).thenReturn(session);
        when(properties.buffer()).thenReturn(buffer);
        when(session.defaultSourceLang()).thenReturn("ja");
        when(session.defaultTargetLang()).thenReturn("vi");
        when(session.maxQueuedChunks()).thenReturn(10);
        when(session.drainTimeoutSeconds()).thenReturn(1);
        when(buffer.finalFlushMaxAttempts()).thenReturn(1);
        return properties;
    }

    private ScriptEntry entry() {
        return ScriptEntry.create("session-1", "original", "translated",
                "ja", "vi", Instant.now(), 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
