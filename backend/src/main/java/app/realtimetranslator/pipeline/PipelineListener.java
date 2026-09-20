package app.realtimetranslator.pipeline;

import app.realtimetranslator.events.AudioChunkReceived;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Bridges the Spring event bus to the translation pipeline.
 * Runs asynchronously so the WebSocket handler thread is not blocked
 * while Whisper API calls are in progress.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineListener {

    private final TranslationPipeline translationPipeline;

    @Async
    @EventListener
    public void onAudioChunkReceived(AudioChunkReceived event) {
        var context = new PipelineContext();
        context.setSessionId(event.getSessionId());
        context.setAudioBytes(event.getAudioBytes());
        context.setSampleRate(event.getSampleRate());
        context.setSequenceNumber(event.getSequenceNumber());
        context.setWebSocketSession(event.getWebSocketSession());

        log.debug("[STT001] Pipeline started for chunk #{}", event.getSequenceNumber());
        translationPipeline.execute(context);

        if (!context.isAborted()) {
            log.info("[STT001] Transcribed: [{}] {}", context.getDetectedLanguage(), context.getOriginalText());
        }
    }
}
