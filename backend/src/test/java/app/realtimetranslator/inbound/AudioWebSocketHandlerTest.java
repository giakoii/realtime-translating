package app.realtimetranslator.inbound;

import app.realtimetranslator.events.AudioChunkReceived;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AudioWebSocketHandlerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private WebSocketSession webSocketSession;

    private AudioWebSocketHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new AudioWebSocketHandler(objectMapper, eventPublisher);
    }

    @Test
    void handleTextMessage_parsesJsonAndPublishesEvent() throws Exception {
        byte[] pcm = {0x01, 0x02, 0x03, 0x04};
        String base64Data = Base64.getEncoder().encodeToString(pcm);

        String json = """
                {
                    "type": "AUDIO_CHUNK",
                    "sessionId": "sess-123",
                    "data": "%s",
                    "sampleRate": 16000,
                    "sequenceNumber": 5
                }
                """.formatted(base64Data);

        handler.handleTextMessage(webSocketSession, new TextMessage(json));

        var captor = ArgumentCaptor.forClass(AudioChunkReceived.class);
        verify(eventPublisher).publishEvent(captor.capture());

        AudioChunkReceived event = captor.getValue();
        assertEquals("sess-123", event.getSessionId());
        assertArrayEquals(pcm, event.getAudioBytes());
        assertEquals(16000, event.getSampleRate());
        assertEquals(5, event.getSequenceNumber());
        assertSame(webSocketSession, event.getWebSocketSession());
    }

    @Test
    void handleTextMessage_sendsAckBack() throws Exception {
        byte[] pcm = {0x01};
        String base64Data = Base64.getEncoder().encodeToString(pcm);

        String json = """
                {
                    "type": "AUDIO_CHUNK",
                    "sessionId": "sess-1",
                    "data": "%s",
                    "sampleRate": 16000,
                    "sequenceNumber": 42
                }
                """.formatted(base64Data);

        handler.handleTextMessage(webSocketSession, new TextMessage(json));

        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(webSocketSession).sendMessage(captor.capture());

        String ackPayload = captor.getValue().getPayload();
        assertTrue(ackPayload.contains("\"type\":\"CHUNK_RECEIVED\""));
        assertTrue(ackPayload.contains("\"sequenceNumber\":42"));
        assertTrue(ackPayload.contains("\"status\":\"processing\""));
    }
}
