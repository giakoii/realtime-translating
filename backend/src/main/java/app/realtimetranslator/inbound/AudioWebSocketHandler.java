package app.realtimetranslator.inbound;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.AudioChunkReceived;
import app.realtimetranslator.events.SessionClosed;
import app.realtimetranslator.events.SilenceDetected;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.outbound.ServerMessages;
import app.realtimetranslator.session.SessionLifecycleService;
import app.realtimetranslator.session.SessionBusyException;
import app.realtimetranslator.session.SessionEndedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/** Validates complete frames before opening a meeting or accepting pipeline work. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AudioWebSocketHandler extends TextWebSocketHandler {
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Set<String> TYPES = Set.of("AUDIO_CHUNK", "MANUAL_SUMMARY", "CLOSE_SESSION");
    private static final String BOUND_ID = "sessionId";
    private static final String LAST_SEQUENCE = "lastSequenceNumber";
    private static final String CLOSING = "closeRequested";
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties properties;
    private final SessionLifecycleService lifecycle;
    private final ClientSessionRegistry registry;
    private final Clock clock;

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        AudioChunkDto dto;
        try {
            if (message.getPayloadLength() > properties.websocket().maxMessageBytes()) {
                error(socket, "INVALID_MESSAGE", "Message exceeds the configured size limit.", false, null);
                return;
            }
            dto = objectMapper.readValue(message.getPayload(), AudioChunkDto.class);
        } catch (Exception invalidJson) {
            error(socket, "INVALID_MESSAGE", "Expected a valid JSON message.", false, null);
            return;
        }
        if (dto == null || dto.type() == null || !TYPES.contains(dto.type())
                || dto.sessionId() == null || !SESSION_ID.matcher(dto.sessionId()).matches()) {
            error(socket, "INVALID_MESSAGE", "Invalid message type or session ID.", false, null);
            return;
        }
        try {
            String bound = (String) socket.getAttributes().get(BOUND_ID);
            if (bound != null && !bound.equals(dto.sessionId())) {
                fatal(socket, "SESSION_MISMATCH", "This connection belongs to another session.");
                return;
            }
            if (Boolean.TRUE.equals(socket.getAttributes().get(CLOSING))) {
                fatal(socket, "SESSION_ENDED", "This session is closing.");
                return;
            }
            if ("AUDIO_CHUNK".equals(dto.type())) {
                acceptAudio(socket, dto, bound);
                return;
            }
            if (bound == null) {
                // An idle client may end before its first utterance without creating a meeting.
                if ("CLOSE_SESSION".equals(dto.type())) {
                    socket.getAttributes().put(CLOSING, true);
                    registry.send(socket, ServerMessages.sessionClosed(dto.sessionId(), 0));
                } else {
                    registry.send(socket, new ServerMessages.SummarySkipped("SUMMARY_SKIPPED", "NOT_ENOUGH_CONTENT"));
                }
                return;
            }
            if (!lifecycle.isOpen(bound)) {
                fatal(socket, "SESSION_ENDED", "This session has ended.");
            } else if ("MANUAL_SUMMARY".equals(dto.type())) {
                eventPublisher.publishEvent(new SilenceDetected(this, bound, "manual", 0));
            } else {
                requestClose(socket);
            }
        } catch (SessionBusyException busy) {
            fatal(socket, "SESSION_BUSY", "This session is already connected.");
        } catch (SessionEndedException ended) {
            fatal(socket, "SESSION_ENDED", "This session has already ended.");
        } catch (RejectedExecutionException busy) {
            error(socket, "BACKPRESSURE", "Too many audio chunks are queued. Please retry shortly.", true, dto.sequenceNumber());
        } catch (Exception failure) {
            log.warn("[WS001] Message processing failed: cause={}", failure.getClass().getSimpleName());
            error(socket, "INTERNAL", "The message could not be processed.", true, dto.sequenceNumber());
        }
    }

    private void acceptAudio(WebSocketSession socket, AudioChunkDto dto, String bound) {
        if (dto.sequenceNumber() == null || dto.sequenceNumber() < 0 || dto.sampleRate() == null
                || dto.sampleRate() != properties.audio().sampleRate() || dto.data() == null) {
            error(socket, "INVALID_AUDIO", "Audio requires a valid sequence, sample rate and PCM data.", false, dto.sequenceNumber());
            return;
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(dto.data());
        } catch (IllegalArgumentException invalidBase64) {
            error(socket, "INVALID_AUDIO", "Audio data must be base64 encoded.", false, dto.sequenceNumber());
            return;
        }
        long durationNumerator = audio.length * 1000L;
        long bytesPerSecond = properties.audio().sampleRate() * 2L;
        if (audio.length % 2 != 0
                || durationNumerator < bytesPerSecond * properties.audio().minChunkMs()
                || durationNumerator > bytesPerSecond * properties.audio().maxChunkSeconds() * 1000L) {
            error(socket, "INVALID_AUDIO", "PCM16 mono audio duration is outside the configured range.", false, dto.sequenceNumber());
            return;
        }
        if (bound == null) {
            lifecycle.open(dto.sessionId(), socket);
            socket.getAttributes().put(BOUND_ID, dto.sessionId());
            // A disconnect may have arrived while meeting creation waited on the DB.
            // Its callback could not see a bound ID yet, so finish the close here.
            if (!socket.isOpen() || Boolean.TRUE.equals(socket.getAttributes().get(CLOSING))) {
                socket.getAttributes().put(CLOSING, true);
                lifecycle.close(dto.sessionId());
                return;
            }
        } else if (!lifecycle.isOpen(bound)) {
            fatal(socket, "SESSION_ENDED", "This session has ended.");
            return;
        }
        Long last = (Long) socket.getAttributes().get(LAST_SEQUENCE);
        if (last != null && dto.sequenceNumber() <= last) {
            registry.send(socket, ServerMessages.ack(dto.sequenceNumber(), true));
            return;
        }
        // Submission is synchronous; rejection leaves the sequence retryable.
        eventPublisher.publishEvent(new AudioChunkReceived(this, dto.sessionId(), audio, dto.sampleRate(),
                dto.sequenceNumber(), socket, dto.capturedAt() == null ? clock.instant() : dto.capturedAt()));
        socket.getAttributes().put(LAST_SEQUENCE, dto.sequenceNumber());
        registry.send(socket, ServerMessages.ack(dto.sequenceNumber(), false));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        socket.setTextMessageSizeLimit(properties.websocket().maxMessageBytes());
        registry.register(socket);
    }

    private void requestClose(WebSocketSession socket) {
        if (Boolean.TRUE.equals(socket.getAttributes().put(CLOSING, true))) return;
        String bound = (String) socket.getAttributes().get(BOUND_ID);
        if (bound != null) eventPublisher.publishEvent(new SessionClosed(this, bound));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        try {
            requestClose(socket);
        } finally {
            registry.removeUnbound(socket);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable failure) {
        log.warn("[WS001] Transport failed: socket={}, cause={}", socket.getId(), failure.getClass().getSimpleName());
        requestClose(socket);
        try { socket.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
    }

    private void error(WebSocketSession socket, String code, String message, boolean retryable, Long sequence) {
        registry.send(socket, ServerMessages.error(code, message, retryable, sequence));
    }

    private void fatal(WebSocketSession socket, String code, String message) {
        error(socket, code, message, false, null);
        try { socket.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
    }
}
