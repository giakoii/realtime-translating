package app.realtimetranslator.inbound;

import app.realtimetranslator.events.AudioChunkReceived;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Base64;

/**
 * Receives audio chunks over WebSocket, decodes the base64 PCM payload,
 * and publishes an {@link AudioChunkReceived} event for pipeline processing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        AudioChunkDto dto = objectMapper.readValue(message.getPayload(), AudioChunkDto.class);

        byte[] audioBytes = Base64.getDecoder().decode(dto.data());

        eventPublisher.publishEvent(new AudioChunkReceived(
                this,
                dto.sessionId(),
                audioBytes,
                dto.sampleRate(),
                dto.sequenceNumber(),
                session
        ));

        // ACK back to client so it knows the chunk was accepted
        String ack = objectMapper.writeValueAsString(new AckMessage(
                "CHUNK_RECEIVED",
                dto.sequenceNumber(),
                "processing"
        ));
        session.sendMessage(new TextMessage(ack));

        log.debug("[STT001] Chunk #{} received for session {}", dto.sequenceNumber(), dto.sessionId());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[STT001] WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[STT001] WebSocket closed: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[STT001] Transport error on session {}", session.getId(), exception);
    }

    /** ACK payload sent back to the client after receiving a chunk. */
    private record AckMessage(String type, long sequenceNumber, String status) {}
}
