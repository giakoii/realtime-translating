package app.realtimetranslator.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * In-process OpenAI substitute for provider and end-to-end tests.
 * Supports FIFO canned responses, HTTP errors, latency, and request capture.
 */
public final class FakeOpenAiServer implements AutoCloseable {

    public static final String TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions";
    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ConcurrentLinkedQueue<Response>> responses = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<CapturedRequest>> requests = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, Function<CapturedRequest, Response>> fallbacks = new ConcurrentHashMap<>();

    public FakeOpenAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create fake OpenAI server", exception);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void enqueueTranscription(String text, String language) {
        enqueue(TRANSCRIPTIONS_PATH, 200,
                "{\"text\":\"" + jsonEscape(text) + "\",\"language\":\""
                        + jsonEscape(language) + "\"}");
    }

    public void enqueueChatCompletion(String content) {
        enqueue(CHAT_COMPLETIONS_PATH, 200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                        + jsonEscape(content) + "\"}}]}");
    }

    public void enqueueError(String path, int status, String body) {
        enqueue(path, status, body);
    }

    public void enqueue(String path, int status, String body) {
        enqueue(path, status, body, Duration.ZERO);
    }

    public void enqueue(String path, int status, String body, Duration latency) {
        responses.computeIfAbsent(path, ignored -> new ConcurrentLinkedQueue<>())
                .add(new Response(status, body, latency));
    }

    /** Installs unlimited deterministic responses for browser-driven full-stack tests. */
    public void useDeterministicDefaults() {
        AtomicInteger transcriptSequence = new AtomicInteger();
        AtomicInteger translationSequence = new AtomicInteger();
        fallbacks.put(TRANSCRIPTIONS_PATH, ignored -> new Response(200,
                "{\"text\":\"発言 " + transcriptSequence.getAndIncrement()
                        + "\",\"language\":\"japanese\"}", Duration.ZERO));
        fallbacks.put(CHAT_COMPLETIONS_PATH, request -> {
            String body = request.bodyAsUtf8();
            String content;
            if (body.contains("response_format")) {
                content = "{\"content\":\"## Tóm tắt kiểm thử\",\"actionItems\":["
                        + "{\"who\":\"Lan\",\"what\":\"Review\",\"deadline\":null}]}";
            } else if (body.contains("Create one concise meeting title")) {
                content = "Cuộc họp kiểm thử";
            } else {
                content = "Bản dịch " + translationSequence.getAndIncrement();
            }
            return new Response(200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                            + jsonEscape(content) + "\"}}]}", Duration.ZERO);
        });
    }

    public int requestCount(String path) {
        return requestCounts.getOrDefault(path, new AtomicInteger()).get();
    }

    public List<CapturedRequest> requests(String path) {
        return List.copyOf(requests.getOrDefault(path, new ConcurrentLinkedQueue<>()));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        CapturedRequest captured = new CapturedRequest(
                exchange.getRequestMethod(),
                path,
                exchange.getRequestHeaders().entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> List.copyOf(entry.getValue()))),
                requestBody);
        requests.computeIfAbsent(path, ignored -> new ConcurrentLinkedQueue<>()).add(captured);
        requestCounts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();

        Response response = responses.computeIfAbsent(path, ignored -> new ConcurrentLinkedQueue<>()).poll();
        if (response == null) {
            Function<CapturedRequest, Response> fallback = fallbacks.get(path);
            response = fallback == null
                    ? new Response(404, "{\"error\":{\"message\":\"No canned response\"}}", Duration.ZERO)
                    : fallback.apply(captured);
        }

        try {
            if (!response.latency().isZero()) {
                Thread.sleep(response.latency());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }

        byte[] responseBody = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private record Response(int status, String body, Duration latency) {
        private Response {
            body = body == null ? "" : body;
            latency = latency == null ? Duration.ZERO : latency;
        }
    }

    public record CapturedRequest(
            String method,
            String path,
            Map<String, List<String>> headers,
            byte[] body
    ) {
        public CapturedRequest {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public String bodyAsUtf8() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
