package app.realtimetranslator.outbound;

import app.realtimetranslator.events.MeetingTitleGenerated;
import app.realtimetranslator.events.SummaryGenerated;
import app.realtimetranslator.events.TranslationCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
@RequiredArgsConstructor
public class OverlayPublisher {
    private final ClientSessionRegistry registry;
    private final Clock clock;

    @EventListener
    public void onTranslationCompleted(TranslationCompleted event) {
        var entry = event.getEntry();
        registry.send(entry.sessionId(), new ServerMessages.Translation("TRANSLATION", entry.sessionId(),
                entry.sequenceNumber(), entry.originalText(), entry.translatedText().isBlank() ? null : entry.translatedText(),
                entry.sourceLang(), entry.targetLang(), entry.timestamp()));
    }

    @EventListener
    @Order(2)
    public void onSummaryGenerated(SummaryGenerated event) {
        registry.send(event.getSessionId(), new ServerMessages.Summary("SUMMARY", event.getSessionId(),
                event.getTriggerType(), event.getMessagesCount(), clock.instant(),
                new ServerMessages.SummaryBody(event.getResult().content(), event.getResult().actionItems())));
    }

    @EventListener
    public void onTitleGenerated(MeetingTitleGenerated event) {
        registry.send(event.getSessionId(), new ServerMessages.Title("TITLE", event.getSessionId(), event.getTitle()));
    }
}
