package app.realtimetranslator.session;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.SummaryGenerated;
import app.realtimetranslator.events.TranslationCompleted;
import app.realtimetranslator.summary.SummaryPersistService;
import app.realtimetranslator.summary.SummaryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PersistTriggerListenerTest {

    private BufferPersistService persistService;
    private SummaryPersistService summaryPersistService;
    private SessionRepository repository;
    private AppProperties properties;
    private AppProperties.Buffer bufferProperties;

    @BeforeEach
    void setUp() {
        persistService = mock(BufferPersistService.class);
        summaryPersistService = mock(SummaryPersistService.class);
        repository = new SessionRepository();
        properties = mock(AppProperties.class);
        bufferProperties = mock(AppProperties.Buffer.class);
        when(properties.buffer()).thenReturn(bufferProperties);
        when(bufferProperties.flushThreshold()).thenReturn(100);
        when(bufferProperties.flushRetryCooldownSeconds()).thenReturn(5L);
    }

    @Test
    void automaticFlush_triggersAt100ButNot99() {
        ScriptBuffer buffer = repository.getOrCreate("session-1");
        for (int sequence = 1; sequence <= 99; sequence++) {
            buffer.add(entry(sequence));
        }
        PersistTriggerListener listener = listener(Clock.systemUTC());

        listener.onTranslationCompleted(new TranslationCompleted(this, entry(99)));
        verifyNoInteractions(persistService);

        buffer.add(entry(100));
        when(persistService.tryPersistUnflushedMessages("session-1")).thenAnswer(invocation -> {
            buffer.markFlushed(100);
            return OptionalInt.of(100);
        });
        listener.onTranslationCompleted(new TranslationCompleted(this, entry(100)));

        verify(persistService).tryPersistUnflushedMessages("session-1");
    }

    @Test
    void automaticFlush_failureHonorsCooldownThenRetries() {
        ScriptBuffer buffer = repository.getOrCreate("session-1");
        buffer.add(entry(1));
        when(bufferProperties.flushThreshold()).thenReturn(1);
        Instant initial = Instant.parse("2026-10-17T00:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(initial, initial.plusSeconds(1), initial.plusSeconds(6));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(persistService.tryPersistUnflushedMessages("session-1"))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenAnswer(invocation -> {
                    buffer.markFlushed(1);
                    return OptionalInt.of(1);
                });
        PersistTriggerListener listener = listener(clock);
        TranslationCompleted event = new TranslationCompleted(this, entry(1));

        listener.onTranslationCompleted(event);
        listener.onTranslationCompleted(event);
        verify(persistService, times(1)).tryPersistUnflushedMessages("session-1");

        listener.onTranslationCompleted(event);
        verify(persistService, times(2)).tryPersistUnflushedMessages("session-1");
    }

    @Test
    void summary_isSavedEvenWhenTranscriptFlushFails() {
        PersistTriggerListener listener = listener(Clock.systemUTC());
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistService).persistUnflushedMessages("session-1");
        SummaryResult result = new SummaryResult("summary", List.of());
        SummaryGenerated event = new SummaryGenerated(
                this, "session-1", result, "manual", 3);

        listener.onSummaryGenerated(event);

        verify(summaryPersistService).save("session-1", result, "manual", 3);
    }

    @Test
    void summarySaveFailure_isPropagatedSoSummaryGuardCanRetry() {
        PersistTriggerListener listener = listener(Clock.systemUTC());
        SummaryResult result = new SummaryResult("summary", List.of());
        SummaryGenerated event = new SummaryGenerated(
                this, "session-1", result, "manual", 3);
        doThrow(new IllegalStateException("database unavailable"))
                .when(summaryPersistService).save("session-1", result, "manual", 3);

        assertThrows(IllegalStateException.class, () -> listener.onSummaryGenerated(event));

        verify(persistService).persistUnflushedMessages("session-1");
    }

    private PersistTriggerListener listener(Clock clock) {
        return new PersistTriggerListener(
                repository, persistService, summaryPersistService, properties, clock);
    }

    private ScriptEntry entry(long sequence) {
        return ScriptEntry.create("session-1", "original", "translated",
                "ja", "vi", Instant.now(), sequence);
    }
}
