package app.realtimetranslator.outbound;

import app.realtimetranslator.events.MeetingTitleGenerated;
import app.realtimetranslator.events.SummaryGenerated;
import app.realtimetranslator.events.TranslationCompleted;
import app.realtimetranslator.session.ScriptEntry;
import app.realtimetranslator.summary.ActionItem;
import app.realtimetranslator.summary.SummaryResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OverlayPublisherTest {
    private static final Instant NOW = Instant.parse("2026-09-17T14:30:22.410Z");
    private final ClientSessionRegistry registry = mock(ClientSessionRegistry.class);
    private final OverlayPublisher publisher = new OverlayPublisher(registry, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void translationUsesEntryTimestampAndMapsEmptyFallbackToNull() {
        ScriptEntry entry = ScriptEntry.create("session_01", "original", "", "ja", "vi", NOW.minusSeconds(3), 8);
        publisher.onTranslationCompleted(new TranslationCompleted(this, entry));

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(registry).send(eq("session_01"), message.capture());
        ServerMessages.Translation translation = assertInstanceOf(ServerMessages.Translation.class, message.getValue());
        assertEquals(entry.timestamp(), translation.timestamp());
        assertNull(translation.translatedText());
        assertEquals(8, translation.sequenceNumber());
    }

    @Test
    void summaryUsesClockAndPreservesActionItems() {
        List<ActionItem> actions = List.of(new ActionItem("A", "Do it", null));
        publisher.onSummaryGenerated(new SummaryGenerated(this, "session_01",
                new SummaryResult("summary", actions), "manual", 4));

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(registry).send(eq("session_01"), message.capture());
        ServerMessages.Summary summary = assertInstanceOf(ServerMessages.Summary.class, message.getValue());
        assertEquals(NOW, summary.generatedAt());
        assertEquals("summary", summary.summary().rawContent());
        assertEquals(actions, summary.summary().actionItems());
    }

    @Test
    void titleIsSentToOwningSession() {
        publisher.onTitleGenerated(new MeetingTitleGenerated(this, "session_01", "Daily sync"));
        verify(registry).send("session_01", new ServerMessages.Title("TITLE", "session_01", "Daily sync"));
    }
}
