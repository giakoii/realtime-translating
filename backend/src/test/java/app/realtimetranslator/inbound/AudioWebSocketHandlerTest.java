package app.realtimetranslator.inbound;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.AudioChunkReceived;
import app.realtimetranslator.events.SessionClosed;
import app.realtimetranslator.events.SilenceDetected;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.outbound.ServerMessages;
import app.realtimetranslator.session.SessionLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AudioWebSocketHandlerTest {
    private static final String SESSION = "session_01";
    private static final Instant NOW = Instant.parse("2026-09-17T14:30:22.410Z");

    @Mock ApplicationEventPublisher publisher;
    @Mock WebSocketSession socket;
    @Mock SessionLifecycleService lifecycle;
    @Mock ClientSessionRegistry registry;

    private final Map<String, Object> attributes = new HashMap<>();
    private AudioWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(socket.getAttributes()).thenReturn(attributes);
        lenient().when(socket.isOpen()).thenReturn(true);
        lenient().when(lifecycle.isOpen(anyString())).thenReturn(true);
        AppProperties properties = mock(AppProperties.class);
        lenient().when(properties.audio()).thenReturn(new AppProperties.Audio(16000, 200, 30));
        when(properties.websocket()).thenReturn(new AppProperties.Websocket("*", 2_097_152, 5_000, 524_288));
        handler = new AudioWebSocketHandler(JsonMapper.builder().build(), publisher, properties,
                lifecycle, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validAudioUsesIntegerAndLongDtoFieldsAndCapturesServerTimestamp() {
        long sequence = (long) Integer.MAX_VALUE + 10;
        handler.handleTextMessage(socket, audio(SESSION, pcm(200), 16000, sequence, null));

        ArgumentCaptor<AudioChunkReceived> events = ArgumentCaptor.forClass(AudioChunkReceived.class);
        verify(publisher).publishEvent(events.capture());
        AudioChunkReceived event = events.getValue();
        assertEquals(16000, event.getSampleRate());
        assertEquals(sequence, event.getSequenceNumber());
        assertEquals(NOW, event.getCapturedAt());
        assertArrayEquals(pcm(200), event.getAudioBytes());
        verify(lifecycle).open(SESSION, socket);
        verify(registry).send(socket, ServerMessages.ack(sequence, false));
    }

    @Test
    void clientCaptureTimestampIsPreserved() {
        Instant captured = NOW.minusSeconds(9);
        handler.handleTextMessage(socket, audio(SESSION, pcm(200), 16000, 1, captured));

        ArgumentCaptor<AudioChunkReceived> events = ArgumentCaptor.forClass(AudioChunkReceived.class);
        verify(publisher).publishEvent(events.capture());
        assertEquals(captured, events.getValue().getCapturedAt());
    }

    @Test
    void rejectsBadJsonUnknownTypeAndInvalidSessionIdWithoutOpeningMeeting() {
        handler.handleTextMessage(socket, new TextMessage("{"));
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"BOGUS\",\"sessionId\":\"session_01\"}"));
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"MANUAL_SUMMARY\",\"sessionId\":\"bad\"}"));

        verify(registry, times(3)).send(eq(socket), argThat(message ->
                message instanceof ServerMessages.Error error && error.code().equals("INVALID_MESSAGE")));
        verifyNoInteractions(publisher);
        verify(lifecycle, never()).open(anyString(), any());
    }

    @Test
    void rejectsMissingSequenceBase64OddDurationBoundsAndWrongRate() {
        TextMessage[] invalid = {
                rawAudio("null", "16000", Base64.getEncoder().encodeToString(pcm(200))),
                rawAudio("0", "16000", "%%%"),
                rawAudio("0", "16000", Base64.getEncoder().encodeToString(new byte[6401])),
                rawAudio("0", "16000", Base64.getEncoder().encodeToString(pcm(199))),
                rawAudio("0", "16000", Base64.getEncoder().encodeToString(pcm(30_001))),
                rawAudio("0", "8000", Base64.getEncoder().encodeToString(pcm(200)))
        };

        for (TextMessage message : invalid) handler.handleTextMessage(socket, message);

        verify(registry, times(invalid.length)).send(eq(socket), argThat(message ->
                message instanceof ServerMessages.Error error && error.code().equals("INVALID_AUDIO")));
        verifyNoInteractions(publisher);
        verify(lifecycle, never()).open(anyString(), any());
    }

    @Test
    void mismatchIsFatalAndNeverOpensOrPublishes() throws Exception {
        attributes.put("sessionId", "session_old");
        handler.handleTextMessage(socket, audio(SESSION, pcm(200), 16000, 1, null));

        verify(registry).send(eq(socket), argThat(message ->
                message instanceof ServerMessages.Error error && error.code().equals("SESSION_MISMATCH")));
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(publisher);
        verify(lifecycle, never()).open(anyString(), any());
    }

    @Test
    void controlsBeforeFirstUtteranceDoNotOpenMeeting() {
        handler.handleTextMessage(socket, control("MANUAL_SUMMARY", SESSION));
        handler.handleTextMessage(socket, control("CLOSE_SESSION", SESSION));

        verify(lifecycle, never()).open(anyString(), any());
        verify(publisher, never()).publishEvent(any());
        verify(registry).send(socket, new ServerMessages.SummarySkipped("SUMMARY_SKIPPED", "NOT_ENOUGH_CONTENT"));
        verify(registry).send(socket, ServerMessages.sessionClosed(SESSION, 0));
    }

    @Test
    void duplicateOnlyAcknowledgesAndDoesNotEnterPipeline() {
        attributes.put("sessionId", SESSION);
        attributes.put("lastSequenceNumber", 7L);
        handler.handleTextMessage(socket, audio(SESSION, pcm(200), 16000, 7, null));

        verify(registry).send(socket, ServerMessages.ack(7, true));
        verifyNoInteractions(publisher);
    }

    @Test
    void backpressureDoesNotAdvanceSequenceSoRetryCanBeAccepted() {
        doThrow(new RejectedExecutionException()).doNothing().when(publisher).publishEvent(any(AudioChunkReceived.class));
        handler.handleTextMessage(socket, audio(SESSION, pcm(200), 16000, 4, null));
        assertNull(attributes.get("lastSequenceNumber"));

        handler.handleTextMessage(socket, audio(SESSION, pcm(200), 16000, 4, null));

        verify(publisher, times(2)).publishEvent(any(AudioChunkReceived.class));
        assertEquals(4L, attributes.get("lastSequenceNumber"));
        verify(registry).send(eq(socket), argThat(message ->
                message instanceof ServerMessages.Error error && error.code().equals("BACKPRESSURE")));
        verify(registry).send(socket, ServerMessages.ack(4, false));
    }

    @Test
    void closeFrameAndSocketClosePublishOneCloseEvent() {
        attributes.put("sessionId", SESSION);
        handler.handleTextMessage(socket, control("CLOSE_SESSION", SESSION));
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);

        verify(publisher, times(1)).publishEvent(argThat(event ->
                event instanceof SessionClosed closed && closed.getSessionId().equals(SESSION)));
    }

    @Test
    void manualSummaryForOpenSessionPublishesTrigger() {
        attributes.put("sessionId", SESSION);
        handler.handleTextMessage(socket, control("MANUAL_SUMMARY", SESSION));

        verify(publisher).publishEvent(argThat(event -> event instanceof SilenceDetected silence
                && silence.getSessionId().equals(SESSION) && silence.getTriggerType().equals("manual")));
    }

    @Test
    void disconnectWhileOpenIsBlockedClosesLifecycleAndDoesNotPublishAudio() throws Exception {
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        AtomicBoolean connected = new AtomicBoolean(true);
        when(socket.isOpen()).thenAnswer(ignored -> connected.get());
        doAnswer(invocation -> {
            openEntered.countDown();
            assertTrue(releaseOpen.await(2, TimeUnit.SECONDS));
            return null;
        }).when(lifecycle).open(SESSION, socket);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var handling = executor.submit(() ->
                    handler.handleTextMessage(socket, audio(SESSION, pcm(200), 16000, 1, null)));
            assertTrue(openEntered.await(2, TimeUnit.SECONDS));
            connected.set(false);
            handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
            releaseOpen.countDown();
            handling.get(2, TimeUnit.SECONDS);
        }

        verify(lifecycle).close(SESSION);
        verify(publisher, never()).publishEvent(any(AudioChunkReceived.class));
        verify(registry, never()).send(socket, ServerMessages.ack(1, false));
    }

    private TextMessage audio(String sessionId, byte[] bytes, int rate, long sequence, Instant capturedAt) {
        String timestamp = capturedAt == null ? "" : ",\"capturedAt\":\"" + capturedAt + "\"";
        return new TextMessage("{\"type\":\"AUDIO_CHUNK\",\"sessionId\":\"" + sessionId
                + "\",\"data\":\"" + Base64.getEncoder().encodeToString(bytes)
                + "\",\"sampleRate\":" + rate + ",\"sequenceNumber\":" + sequence + timestamp + "}");
    }

    private TextMessage rawAudio(String sequence, String rate, String data) {
        return new TextMessage("{\"type\":\"AUDIO_CHUNK\",\"sessionId\":\"" + SESSION
                + "\",\"data\":\"" + data + "\",\"sampleRate\":" + rate
                + ",\"sequenceNumber\":" + sequence + "}");
    }

    private TextMessage control(String type, String sessionId) {
        return new TextMessage("{\"type\":\"" + type + "\",\"sessionId\":\"" + sessionId + "\"}");
    }

    private byte[] pcm(int durationMs) {
        return new byte[16000 * 2 * durationMs / 1000];
    }
}
