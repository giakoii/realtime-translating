package app.realtimetranslator.pipeline;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.AudioChunkReceived;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.outbound.ServerMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipelineListenerTest {
    private final TranslationPipeline pipeline = mock(TranslationPipeline.class);
    private final SessionTaskExecutor executor = mock(SessionTaskExecutor.class);
    private final ClientSessionRegistry registry = mock(ClientSessionRegistry.class);
    private PipelineListener listener;

    @BeforeEach
    void setUp() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.logging()).thenReturn(new AppProperties.Logging(false));
        listener = new PipelineListener(pipeline, executor, registry, properties);
    }

    @Test
    void enqueueFailurePropagatesToHandlerAndDoesNotRunPipeline() {
        doThrow(new RejectedExecutionException()).when(executor).submit(eq("session_01"), any());
        assertThrows(RejectedExecutionException.class, () -> listener.onAudioChunkReceived(event()));
        verifyNoInteractions(pipeline);
    }

    @Test
    void queuedTaskBuildsCompleteContext() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(invocation -> {
            PipelineContext context = invocation.getArgument(0);
            context.setOriginalText("recognized");
            context.setDetectedLanguage("ja");
            return null;
        }).when(pipeline).execute(any());
        listener.onAudioChunkReceived(event());
        verify(executor).submit(eq("session_01"), task.capture());
        task.getValue().run();

        ArgumentCaptor<PipelineContext> context = ArgumentCaptor.forClass(PipelineContext.class);
        verify(pipeline).execute(context.capture());
        assertEquals("session_01", context.getValue().getSessionId());
        assertEquals(12L, context.getValue().getSequenceNumber());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), context.getValue().getCapturedAt());
        assertArrayEquals(new byte[] {1, 2}, context.getValue().getAudioBytes());
        verifyNoInteractions(registry);
    }

    @Test
    void taskErrorsAreCaughtAndReportedInsteadOfEscapingWorker() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doThrow(new IllegalStateException("boom")).when(pipeline).execute(any());
        listener.onAudioChunkReceived(event());
        verify(executor).submit(eq("session_01"), task.capture());

        assertDoesNotThrow(() -> task.getValue().run());
        verify(registry).send(eq("session_01"), argThat(message ->
                message instanceof ServerMessages.Error error
                        && error.code().equals("INTERNAL") && error.sequenceNumber() == 12L));
    }

    @Test
    void speechErrorsProduceRetryableSttFailure() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doThrow(new SpeechRecognitionException(new RuntimeException())).when(pipeline).execute(any());
        listener.onAudioChunkReceived(event());
        verify(executor).submit(eq("session_01"), task.capture());
        task.getValue().run();
        verify(registry).send(eq("session_01"), argThat(message ->
                message instanceof ServerMessages.Error error
                        && error.code().equals("STT_FAILED") && error.retryable()));
    }

    private AudioChunkReceived event() {
        return new AudioChunkReceived(this, "session_01", new byte[] {1, 2}, 16000, 12,
                mock(WebSocketSession.class), Instant.parse("2026-01-02T03:04:05Z"));
    }
}
