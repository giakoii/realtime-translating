package app.realtimetranslator.summary;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.SilenceDetected;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.pipeline.SessionTaskExecutor;
import app.realtimetranslator.session.ScriptBuffer;
import app.realtimetranslator.session.SessionLifecycleService;
import app.realtimetranslator.session.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SummaryListenerTest {

    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final SessionLifecycleService lifecycle = mock(SessionLifecycleService.class);
    private final SummaryPipeline pipeline = mock(SummaryPipeline.class);
    private final ClientSessionRegistry registry = mock(ClientSessionRegistry.class);
    private final SessionTaskExecutor sessionTaskExecutor = mock(SessionTaskExecutor.class);
    private final Executor directExecutor = Runnable::run;
    private SummaryGuard guard;
    private SummaryListener listener;
    private ScriptBuffer buffer;

    @BeforeEach
    void setUp() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.summary()).thenReturn(new AppProperties.Summary(
                "gpt-4o", .5, 2000, 30, 1, 3, 50, 200, 1000));
        guard = new SummaryGuard();
        listener = new SummaryListener(sessionRepository, lifecycle, guard, pipeline, registry, properties,
                sessionTaskExecutor, directExecutor);
        buffer = new ScriptBuffer("session01");
        when(lifecycle.isOpen("session01")).thenReturn(true);
        when(sessionRepository.get("session01")).thenReturn(Optional.of(buffer));
    }

    @Test
    void manualSummaryBelowMinimumSendsSkipped() {
        buffer.add(SummaryPromptBuilderTest.entry(1));

        listener.onSilenceDetected(event("manual"));

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(registry).send(eq("session01"), message.capture());
        assertEquals(Map.of("type", "SUMMARY_SKIPPED", "reason", "NOT_ENOUGH_CONTENT"),
                message.getValue());
        verifyNoInteractions(pipeline);
    }

    @Test
    void failureDoesNotAdvanceLastCountSoSameContentCanRetry() {
        addMinimumEntries();
        when(pipeline.execute(eq("session01"), anyList(), eq("manual")))
                .thenThrow(new IllegalStateException("provider body must not be logged"))
                .thenReturn(new SummaryResult("ok", null));

        listener.onSilenceDetected(event("manual"));
        listener.onSilenceDetected(event("manual"));

        verify(pipeline, times(2)).execute(eq("session01"), anyList(), eq("manual"));
        verify(registry, times(1)).send(eq("session01"), argThat(message ->
                message instanceof Map<?, ?> map && "SUMMARY_FAILED".equals(map.get("code"))));
    }

    @Test
    void closedSessionCannotStartSummaryEvenWhenBufferIsRetained() {
        addMinimumEntries();
        when(lifecycle.isOpen("session01")).thenReturn(false);

        listener.onSilenceDetected(event("manual"));

        verifyNoInteractions(pipeline, registry);
    }

    @Test
    void autoSummarySnapshotsOnlyAfterAcceptedChunkBacklogDrains() {
        buffer.add(SummaryPromptBuilderTest.entry(1));
        ArgumentCaptor<Runnable> queuedControl = ArgumentCaptor.forClass(Runnable.class);

        listener.onSilenceDetected(event("auto_silence"));
        verify(sessionTaskExecutor).submitControl(eq("session01"), queuedControl.capture());
        verifyNoInteractions(pipeline);

        buffer.add(SummaryPromptBuilderTest.entry(2));
        buffer.add(SummaryPromptBuilderTest.entry(3));
        queuedControl.getValue().run();

        verify(pipeline).execute(eq("session01"), argThat(entries -> entries.size() == 3),
                eq("auto_silence"));
    }

    private void addMinimumEntries() {
        buffer.add(SummaryPromptBuilderTest.entry(1));
        buffer.add(SummaryPromptBuilderTest.entry(2));
        buffer.add(SummaryPromptBuilderTest.entry(3));
    }

    private SilenceDetected event(String triggerType) {
        return new SilenceDetected(this, "session01", triggerType, 0);
    }
}
