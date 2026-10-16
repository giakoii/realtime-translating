package app.realtimetranslator.pipeline;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.AudioChunkReceived;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.outbound.ServerMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Accept work synchronously, then process on the session's FIFO worker. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineListener {
    private final TranslationPipeline translationPipeline;
    private final SessionTaskExecutor executor;
    private final ClientSessionRegistry registry;
    private final AppProperties properties;

    @EventListener
    @Order(0)
    public void onAudioChunkReceived(AudioChunkReceived event) {
        executor.submit(event.getSessionId(), () -> execute(event));
    }

    private void execute(AudioChunkReceived event) {
        String previousSession = MDC.get("sessionId");
        MDC.put("sessionId", event.getSessionId());
        try {
            var context = new PipelineContext();
            context.setSessionId(event.getSessionId());
            context.setAudioBytes(event.getAudioBytes());
            context.setSampleRate(event.getSampleRate());
            context.setSequenceNumber(event.getSequenceNumber());
            context.setWebSocketSession(event.getWebSocketSession());
            context.setCapturedAt(event.getCapturedAt());
            translationPipeline.execute(context);
            if (!context.isAborted()) {
                log.info("[TRL001] Chunk processed: sequence={}, characters={}, language={}",
                        context.getSequenceNumber(), context.getOriginalText().length(), context.getDetectedLanguage());
                if (properties.logging().logTranscripts()) {
                    log.debug("[TRL001] Transcript: {}", context.getOriginalText());
                }
            }
        } catch (SpeechRecognitionException failure) {
            registry.send(event.getSessionId(), ServerMessages.error("STT_FAILED",
                    "Speech transcription failed. Please try speaking again.", true, event.getSequenceNumber()));
        } catch (Exception failure) {
            log.warn("[TRL001] Pipeline failed: sequence={}, cause={}", event.getSequenceNumber(),
                    failure.getClass().getSimpleName());
            registry.send(event.getSessionId(), ServerMessages.error("INTERNAL",
                    "This audio chunk could not be processed.", true, event.getSequenceNumber()));
        } finally {
            if (previousSession == null) MDC.remove("sessionId");
            else MDC.put("sessionId", previousSession);
        }
    }
}
