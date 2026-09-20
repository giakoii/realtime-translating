package app.realtimetranslator.pipeline;

import app.realtimetranslator.outbound.WhisperClient;
import app.realtimetranslator.outbound.WhisperResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * First pipeline stage: sends audio to Whisper for speech-to-text.
 * Populates {@code originalText} and {@code detectedLanguage} on the context,
 * or aborts if the transcription is empty.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SpeechStage implements PipelineStage {

    private final WhisperClient whisperClient;

    @Override
    public void process(PipelineContext context) {
        WhisperResponse response = whisperClient.transcribe(
                context.getAudioBytes(),
                context.getSampleRate()
        );

        if (response == null || response.text() == null || response.text().isBlank()) {
            context.abort("Empty transcription");
            return;
        }

        context.setOriginalText(response.text().trim());
        context.setDetectedLanguage(response.language());
    }
}
