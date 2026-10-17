package app.realtimetranslator.pipeline;

import app.realtimetranslator.events.TranslationCompleted;
import app.realtimetranslator.session.ScriptBuffer;
import app.realtimetranslator.session.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BufferStageTest {
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final BufferStage stage = new BufferStage(sessions, publisher);

    @Test
    void emitsEntryWithCapturedTimestampAndTranslationFallback() {
        ScriptBuffer buffer = new ScriptBuffer("session_01");
        when(sessions.get("session_01")).thenReturn(Optional.of(buffer));
        Instant captured = Instant.parse("2026-09-17T14:30:22.410Z");
        PipelineContext context = validContext(captured);
        context.setTranslatedText(null);

        stage.process(context);

        assertEquals(1, buffer.size());
        var entry = buffer.getAll().getFirst();
        assertEquals(captured, entry.timestamp());
        assertEquals(42, entry.sequenceNumber());
        assertEquals("", entry.translatedText());
        verify(publisher).publishEvent(argThat(event -> event instanceof TranslationCompleted completed
                && completed.getEntry() == entry));
    }

    @Test
    void refusesZombieSessionAndInvalidRequiredFields() {
        when(sessions.get("session_01")).thenReturn(Optional.empty());
        PipelineContext zombie = validContext(Instant.EPOCH);
        stage.process(zombie);

        ScriptBuffer buffer = new ScriptBuffer("session_01");
        when(sessions.get("session_01")).thenReturn(Optional.of(buffer));
        PipelineContext blank = validContext(Instant.EPOCH);
        blank.setOriginalText(" ");
        PipelineContext badSource = validContext(Instant.EPOCH);
        badSource.setDetectedLanguage("en");
        PipelineContext badTarget = validContext(Instant.EPOCH);
        badTarget.setTargetLanguage(null);
        stage.process(blank);
        stage.process(badSource);
        stage.process(badTarget);

        assertTrue(zombie.isAborted());
        assertTrue(blank.isAborted());
        assertTrue(badSource.isAborted());
        assertTrue(badTarget.isAborted());
        assertEquals(0, buffer.size());
        verifyNoInteractions(publisher);
    }

    private PipelineContext validContext(Instant captured) {
        PipelineContext context = new PipelineContext();
        context.setSessionId("session_01");
        context.setOriginalText("original");
        context.setTranslatedText("translated");
        context.setDetectedLanguage("ja");
        context.setTargetLanguage("vi");
        context.setCapturedAt(captured);
        context.setSequenceNumber(42);
        return context;
    }
}
