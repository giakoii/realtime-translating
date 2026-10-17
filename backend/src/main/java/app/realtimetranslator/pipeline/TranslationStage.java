package app.realtimetranslator.pipeline;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.outbound.GptClient;
import app.realtimetranslator.session.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class TranslationStage implements PipelineStage {
    private final GptClient client;
    private final SessionRepository sessions;
    private final AppProperties properties;

    @Override
    public void process(PipelineContext context) {
        String source = context.getDetectedLanguage();
        if (!"ja".equals(source) && !"vi".equals(source)) {
            context.abort("Unsupported language");
            return;
        }
        var buffer = sessions.get(context.getSessionId());
        if (buffer.isEmpty()) {
            context.abort("Session closed");
            return;
        }
        context.setTargetLanguage("ja".equals(source) ? "vi" : "ja");
        try {
            String translated = client.translate(context.getOriginalText(), source, context.getTargetLanguage(),
                    buffer.get().getRecentOriginalLines(properties.translation().contextWindowSize()));
            context.setTranslatedText(translated == null ? "" : translated.trim());
        } catch (Exception failure) {
            context.setTranslatedText("");
            log.warn("[TRL001] Translation failed: sequence={}, cause={}", context.getSequenceNumber(),
                    failure.getClass().getSimpleName());
        }
    }
}
