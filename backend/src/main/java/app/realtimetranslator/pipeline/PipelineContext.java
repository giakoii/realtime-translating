package app.realtimetranslator.pipeline;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.socket.WebSocketSession;

/**
 * Mutable carrier threaded through every {@link PipelineStage}.
 * Each stage reads what it needs and writes its output for the next stage.
 */
@Getter
@Setter
public class PipelineContext {

    // --- Input (set by PipelineListener) ---
    private String sessionId;
    private byte[] audioBytes;
    private int sampleRate;
    private long sequenceNumber;
    private WebSocketSession webSocketSession;

    // --- Produced by SpeechStage ---
    private String originalText;
    private String detectedLanguage;

    // --- Produced by TranslationStage (future) ---
    private String translatedText;

    // --- Abort control ---
    private boolean aborted;
    private String abortReason;

    public void abort(String reason) {
        this.aborted = true;
        this.abortReason = reason;
    }
}
