package app.realtimetranslator;

import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.entity.MessageEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import app.realtimetranslator.session.repository.MessageRepository;
import app.realtimetranslator.session.repository.SummaryRepository;
import app.realtimetranslator.testsupport.AbstractPostgresIntegrationTest;
import app.realtimetranslator.testsupport.FakeOpenAiServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.buffer.flush-threshold=3",
                "app.silence.threshold-seconds=3600",
                "app.openai.whisper.max-retries=0",
                "app.openai.gpt.max-retries=0",
                "app.summary.max-retries=0",
                "app.title.max-retries=0"
        })
@ExtendWith(OutputCaptureExtension.class)
class BackendWebSocketIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final FakeOpenAiServer OPEN_AI = new FakeOpenAiServer();

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.openai.base-url", OPEN_AI::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        OPEN_AI.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SummaryRepository summaryRepository;

    @BeforeEach
    void clearDatabase() {
        meetingRepository.deleteAll();
    }

    @Test
    void fullFlowAcceptsLargeFramesPreservesOrderSummarizesTitlesAndCloses(CapturedOutput output) throws Exception {
        for (int sequence = 0; sequence < 4; sequence++) {
            OPEN_AI.enqueueTranscription("発言 " + sequence, "japanese");
            OPEN_AI.enqueueChatCompletion("Bản dịch " + sequence);
        }
        OPEN_AI.enqueueChatCompletion("{\"content\":\"## Tóm tắt\",\"actionItems\":[{\"who\":\"Lan\",\"what\":\"Review\",\"deadline\":null}]}");
        OPEN_AI.enqueueChatCompletion("Cuộc họp kiểm thử");

        BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
        var client = new StandardWebSocketClient();
        WebSocketSession socket = client.execute(new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbound.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                // The explicit CLOSE_SESSION response is asserted before socket cleanup.
            }
        }, "ws://127.0.0.1:" + port + "/ws/audio").get(10, TimeUnit.SECONDS);

        String sessionId = "sess_e2e_20260917";
        byte[] pcm = deterministicPcm(100_000);
        for (int sequence = 0; sequence < 4; sequence++) {
            String frame = audioFrame(sessionId, sequence, pcm);
            assertThat(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isGreaterThanOrEqualTo(130 * 1024);
            socket.sendMessage(new TextMessage(frame));
        }

        List<JsonNode> translations = awaitType(inbound, "TRANSLATION", 4, Duration.ofSeconds(20));
        assertThat(translations).extracting(node -> node.path("sequenceNumber").asLong())
                .containsExactly(0L, 1L, 2L, 3L);
        assertThat(translations).extracting(node -> node.path("translatedText").asText())
                .containsExactly("Bản dịch 0", "Bản dịch 1", "Bản dịch 2", "Bản dịch 3");

        socket.sendMessage(new TextMessage("{\"type\":\"MANUAL_SUMMARY\",\"sessionId\":\"" + sessionId + "\"}"));
        Map<String, JsonNode> summaryFrames = awaitTypes(
                inbound, Set.of("SUMMARY", "TITLE"), Duration.ofSeconds(20));
        JsonNode summaryFrame = summaryFrames.get("SUMMARY");
        JsonNode titleFrame = summaryFrames.get("TITLE");
        assertThat(summaryFrame.path("messagesCount").asInt()).isEqualTo(4);
        assertThat(summaryFrame.path("summary").path("actionItems").isArray()).isTrue();
        assertThat(titleFrame.path("title").asText()).isEqualTo("Cuộc họp kiểm thử");

        socket.sendMessage(new TextMessage("{\"type\":\"CLOSE_SESSION\",\"sessionId\":\"" + sessionId + "\"}"));
        JsonNode closeFrame = awaitType(inbound, "SESSION_CLOSED", 1, Duration.ofSeconds(20)).getFirst();
        assertThat(closeFrame.path("totalMessages").asInt()).isEqualTo(4);

        awaitCondition(Duration.ofSeconds(10), () -> meetingRepository.findBySessionId(sessionId)
                .map(meeting -> meeting.getEndedAt() != null && meeting.getTotalMessages() == 4)
                .orElse(false));

        MeetingEntity meeting = meetingRepository.findBySessionId(sessionId).orElseThrow();
        List<MessageEntity> messages = messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
        assertThat(messages).extracting(MessageEntity::getSequenceNumber).containsExactly(0L, 1L, 2L, 3L);
        assertThat(summaryRepository.findByMeetingIdOrderByGeneratedAtDesc(meeting.getId())).hasSize(1);
        assertThat(meeting.getTitle()).isEqualTo("Cuộc họp kiểm thử");
        assertThat(OPEN_AI.requestCount(FakeOpenAiServer.TRANSCRIPTIONS_PATH)).isEqualTo(4);
        assertThat(OPEN_AI.requestCount(FakeOpenAiServer.CHAT_COMPLETIONS_PATH)).isEqualTo(6);
        assertThat(output).doesNotContain(
                "test-key",
                "発言 0",
                Base64.getEncoder().encodeToString(pcm).substring(0, 64));

        socket.close();
    }

    private String audioFrame(String sessionId, long sequence, byte[] pcm) {
        return "{\"type\":\"AUDIO_CHUNK\",\"sessionId\":\"" + sessionId
                + "\",\"data\":\"" + Base64.getEncoder().encodeToString(pcm)
                + "\",\"sampleRate\":16000,\"sequenceNumber\":" + sequence
                + ",\"capturedAt\":\"" + Instant.parse("2026-09-17T14:30:22Z").plusSeconds(sequence) + "\"}";
    }

    private byte[] deterministicPcm(int length) {
        byte[] pcm = new byte[length];
        for (int index = 0; index < length; index += 2) {
            short sample = (short) ((index / 2 * 257) % 8192 - 4096);
            pcm[index] = (byte) sample;
            pcm[index + 1] = (byte) (sample >>> 8);
        }
        return pcm;
    }

    private List<JsonNode> awaitType(
            BlockingQueue<String> inbound,
            String expectedType,
            int count,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<JsonNode> matches = new ArrayList<>();
        while (matches.size() < count && System.nanoTime() < deadline) {
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            String payload = inbound.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (payload == null) {
                break;
            }
            JsonNode message = objectMapper.readTree(payload);
            if (expectedType.equals(message.path("type").asText())) {
                matches.add(message);
            } else if ("ERROR".equals(message.path("type").asText())) {
                throw new AssertionError("Backend returned an error while awaiting " + expectedType + ": " + payload);
            }
        }
        assertThat(matches)
                .as("%s WebSocket frame(s) of type %s", count, expectedType)
                .hasSize(count);
        return matches;
    }

    private Map<String, JsonNode> awaitTypes(
            BlockingQueue<String> inbound,
            Set<String> expectedTypes,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<String, JsonNode> matches = new HashMap<>();
        while (!matches.keySet().containsAll(expectedTypes) && System.nanoTime() < deadline) {
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            String payload = inbound.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (payload == null) {
                break;
            }
            JsonNode message = objectMapper.readTree(payload);
            String type = message.path("type").asText();
            if (expectedTypes.contains(type)) {
                matches.put(type, message);
            } else if ("ERROR".equals(type)) {
                throw new AssertionError("Backend returned an error while awaiting " + expectedTypes + ": " + payload);
            }
        }
        assertThat(matches.keySet()).containsAll(expectedTypes);
        return matches;
    }

    private void awaitCondition(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
