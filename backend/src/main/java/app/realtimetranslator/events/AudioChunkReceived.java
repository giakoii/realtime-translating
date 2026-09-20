package app.realtimetranslator.events;

import org.springframework.context.ApplicationEvent;
import org.springframework.web.socket.WebSocketSession;

/**
 * Published when an audio chunk arrives over WebSocket and passes
 * basic validation. Carries decoded PCM bytes ready for the pipeline.
 */
public class AudioChunkReceived extends ApplicationEvent {

    private final String sessionId;
    private final byte[] audioBytes;
    private final int sampleRate;
    private final long sequenceNumber;
    private final WebSocketSession webSocketSession;

    public AudioChunkReceived(Object source,
                              String sessionId,
                              byte[] audioBytes,
                              int sampleRate,
                              long sequenceNumber,
                              WebSocketSession webSocketSession) {
        super(source);
        this.sessionId = sessionId;
        this.audioBytes = audioBytes;
        this.sampleRate = sampleRate;
        this.sequenceNumber = sequenceNumber;
        this.webSocketSession = webSocketSession;
    }

    public String getSessionId() { return sessionId; }
    public byte[] getAudioBytes() { return audioBytes; }
    public int getSampleRate() { return sampleRate; }
    public long getSequenceNumber() { return sequenceNumber; }
    public WebSocketSession getWebSocketSession() { return webSocketSession; }
}
