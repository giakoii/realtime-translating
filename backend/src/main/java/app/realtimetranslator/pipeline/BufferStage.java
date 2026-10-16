package app.realtimetranslator.pipeline;

import app.realtimetranslator.events.TranslationCompleted;
import app.realtimetranslator.session.ScriptEntry;
import app.realtimetranslator.session.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
@RequiredArgsConstructor
public class BufferStage implements PipelineStage {
    private final SessionRepository sessions;
    private final ApplicationEventPublisher publisher;

    @Override
    public void process(PipelineContext context) {
        var buffer = sessions.get(context.getSessionId());
        if (buffer.isEmpty() || context.getOriginalText() == null || context.getOriginalText().isBlank()
                || !validLanguage(context.getDetectedLanguage()) || !validLanguage(context.getTargetLanguage())) {
            context.abort("Missing session, transcript or language");
            return;
        }
        var entry = ScriptEntry.create(context.getSessionId(), context.getOriginalText(),
                context.getTranslatedText() == null ? "" : context.getTranslatedText(),
                context.getDetectedLanguage(), context.getTargetLanguage(), context.getCapturedAt(),
                context.getSequenceNumber());
        buffer.get().add(entry);
        publisher.publishEvent(new TranslationCompleted(this, entry));
    }

    private boolean validLanguage(String language) {
        return "ja".equals(language) || "vi".equals(language);
    }
}
