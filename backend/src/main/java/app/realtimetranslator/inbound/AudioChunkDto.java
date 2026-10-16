package app.realtimetranslator.inbound;

import java.time.Instant;

/** Audio and control envelopes on the single session WebSocket. */
public record AudioChunkDto(String type, String sessionId, String data,
                            Integer sampleRate, Long sequenceNumber, Instant capturedAt) {}
