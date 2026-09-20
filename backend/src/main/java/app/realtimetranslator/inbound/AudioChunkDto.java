package app.realtimetranslator.inbound;

/**
 * WebSocket payload from client containing a single audio chunk.
 * The {@code data} field carries base64-encoded PCM Int16 mono audio.
 */
public record AudioChunkDto(
        String type,
        String sessionId,
        String data,
        int sampleRate,
        long sequenceNumber
) {}
