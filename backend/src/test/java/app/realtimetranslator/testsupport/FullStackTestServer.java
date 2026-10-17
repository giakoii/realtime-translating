package app.realtimetranslator.testsupport;

import app.realtimetranslator.RealtimeTranslatorApplication;
import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.entity.MessageEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import app.realtimetranslator.session.repository.MessageRepository;
import app.realtimetranslator.session.repository.SummaryRepository;
import app.realtimetranslator.session.ClosedSessionFlushRetrier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Launches the real backend with fake providers and a test-only persistence probe. */
public final class FullStackTestServer {

    private static final int BACKEND_PORT = 8081;
    private static final int VERIFY_PORT = 8082;

    private FullStackTestServer() {
    }

    public static void main(String[] args) throws Exception {
        String databaseUrl = requiredEnvironment("TEST_DATABASE_URL");
        String databaseUsername = environmentOrDefault("TEST_DATABASE_USERNAME", "postgres");
        String databasePassword = environmentOrDefault("TEST_DATABASE_PASSWORD", "postgres");

        FakeOpenAiServer openAi = new FakeOpenAiServer();
        openAi.useDeterministicDefaults();

        String[] testArgs = {
                "--server.port=" + BACKEND_PORT,
                "--spring.datasource.url=" + databaseUrl,
                "--spring.datasource.username=" + databaseUsername,
                "--spring.datasource.password=" + databasePassword,
                "--app.openai.api-key=test-key",
                "--app.openai.base-url=" + openAi.baseUrl(),
                "--app.openai.whisper.max-retries=0",
                "--app.openai.gpt.max-retries=0",
                "--app.summary.max-retries=0",
                "--app.summary.min-entries=3",
                "--app.title.max-retries=0",
                "--app.buffer.flush-threshold=3",
                "--app.buffer.final-flush-max-attempts=1",
                "--app.silence.threshold-seconds=3600",
                "--spring.datasource.hikari.connection-timeout=1000",
                "--spring.datasource.hikari.validation-timeout=1000",
                "--app.websocket.allowed-origins=http://127.0.0.1:5173,http://localhost:5173,http://127.0.0.1:4173,http://localhost:4173"
        };
        String[] mergedArgs = Arrays.copyOf(testArgs, testArgs.length + args.length);
        System.arraycopy(args, 0, mergedArgs, testArgs.length, args.length);

        ConfigurableApplicationContext context = SpringApplication.run(
                RealtimeTranslatorApplication.class, mergedArgs);
        HttpServer verifier = startVerifier(context);

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            verifier.stop(0);
            openAi.close();
            context.close();
        }));

        System.out.printf(
                "FULL_STACK_READY backend=ws://127.0.0.1:%d/ws/audio verify=http://127.0.0.1:%d/verify fakeOpenAi=%s%n",
                BACKEND_PORT, VERIFY_PORT, openAi.baseUrl());
        new CountDownLatch(1).await();
    }

    private static HttpServer startVerifier(ConfigurableApplicationContext context) throws IOException {
        MeetingRepository meetings = context.getBean(MeetingRepository.class);
        MessageRepository messages = context.getBean(MessageRepository.class);
        SummaryRepository summaries = context.getBean(SummaryRepository.class);
        ClosedSessionFlushRetrier retrier = context.getBean(ClosedSessionFlushRetrier.class);
        ObjectMapper mapper = context.getBean(ObjectMapper.class);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", VERIFY_PORT), 0);
        server.createContext("/verify", exchange -> verify(exchange, meetings, messages, summaries, mapper));
        server.createContext("/retry", exchange -> retry(exchange, retrier));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void retry(HttpExchange exchange, ClosedSessionFlushRetrier retrier) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        retrier.retryClosedSessions();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static void verify(
            HttpExchange exchange,
            MeetingRepository meetings,
            MessageRepository messages,
            SummaryRepository summaries,
            ObjectMapper mapper
    ) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String sessionId = queryParameter(exchange.getRequestURI().getRawQuery(), "sessionId");
        MeetingEntity meeting = sessionId == null ? null : meetings.findBySessionId(sessionId).orElse(null);
        var result = new LinkedHashMap<String, Object>();
        result.put("meetingCount", meeting == null ? 0 : 1);
        if (meeting == null) {
            result.put("messageCount", 0);
            result.put("summaryCount", 0);
            result.put("title", null);
            result.put("endedAt", null);
            result.put("messagesOrdered", true);
        } else {
            var persistedMessages = messages.findBySessionIdOrderBySequenceNumberAsc(sessionId);
            result.put("messageCount", persistedMessages.size());
            result.put("summaryCount", summaries.findByMeetingIdOrderByGeneratedAtDesc(meeting.getId()).size());
            result.put("title", meeting.getTitle());
            result.put("endedAt", meeting.getEndedAt());
            result.put("messagesOrdered", isStrictlyOrdered(persistedMessages));
        }

        byte[] body = mapper.writeValueAsBytes(result);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static boolean isStrictlyOrdered(java.util.List<MessageEntity> messages) {
        for (int index = 1; index < messages.size(); index++) {
            if (messages.get(index - 1).getSequenceNumber() >= messages.get(index).getSequenceNumber()) {
                return false;
            }
        }
        return true;
    }

    private static String queryParameter(String query, String expectedName) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && expectedName.equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must point to an isolated PostgreSQL database");
        }
        return value;
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
