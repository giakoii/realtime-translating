package app.realtimetranslator.outbound;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import app.realtimetranslator.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiClientsTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void gptTranslationReturnsAssistantContent() throws Exception {
        AtomicInteger calls = start(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"xin chao\"}}]}"));

        String result = new GptClient(properties(Map.of())).translate("hello", "ja", "vi", List.of("context"));

        assertEquals("xin chao", result);
        assertEquals(1, calls.get());
        assertEquals("/v1/chat/completions", lastPath.get());
    }

    @Test
    void chatRequestUsesOpenAiSnakeCaseFields() throws Exception {
        var request = new ChatRequest("gpt-4o", List.of(
                new ChatRequest.Message("user", "hello")), .3, 42,
                new ChatRequest.ResponseFormat("json_object"));

        String json = JsonMapper.builder().build().writeValueAsString(request);

        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"max_tokens\":42"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"response_format\""));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("maxTokens"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("responseFormat"));
    }

    @Test
    void retryPolicyRetries429ButNot400() throws Exception {
        AtomicInteger calls = start(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/chat/completions") && callsPending(exchange) == 1) {
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}" );
            }
        });
        String result = new GptClient(properties(Map.of("app.openai.gpt.max-retries", "1")))
                .translate("hello", "ja", "vi", List.of());
        assertEquals("ok", result);
        assertEquals(2, calls.get());

        server.stop(0);
        calls = start(exchange -> respond(exchange, 400, "bad request"));
        GptClient client = new GptClient(properties(Map.of("app.openai.gpt.max-retries", "2")));
        assertThrows(RestClientException.class,
                () -> client.translate("hello", "ja", "vi", List.of()));
        assertEquals(1, calls.get());
    }

    @Test
    void whisperRetriesServerErrorAndUsesConfiguredEndpoint() throws Exception {
        AtomicInteger calls = start(exchange -> {
            if (callsPending(exchange) == 1) {
                respond(exchange, 500, "temporary");
            } else {
                respond(exchange, 200, "{\"text\":\"hello\",\"language\":\"japanese\"}");
            }
        });

        WhisperResponse result = new WhisperClient(properties(Map.of(
                "app.openai.whisper.max-retries", "1"))).transcribe(new byte[] {0, 0}, 16000);

        assertEquals("hello", result.text());
        assertEquals(2, calls.get());
        assertEquals("/v1/audio/transcriptions", lastPath.get());
    }

    @Test
    void malformedResponseFailsWithoutRetry() throws Exception {
        AtomicInteger calls = start(exchange -> respond(exchange, 200, "not-json"));
        GptClient client = new GptClient(properties(Map.of("app.openai.gpt.max-retries", "2")));

        assertThrows(RestClientException.class,
                () -> client.translate("hello", "ja", "vi", List.of()));
        assertEquals(1, calls.get());
    }

    @Test
    void configuredTimeoutAbortsSlowProviderCall() throws Exception {
        AtomicInteger calls = start(exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"late\"}}]}");
        });
        GptClient client = new GptClient(properties(Map.of(
                "app.openai.gpt.timeout-seconds", "1",
                "app.openai.gpt.max-retries", "0")));

        assertThrows(RestClientException.class,
                () -> client.translate("hello", "ja", "vi", List.of()));
        assertEquals(1, calls.get());
    }

    private AtomicInteger currentCalls;
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    private int callsPending(HttpExchange ignored) {
        return currentCalls.get();
    }

    private AtomicInteger start(ExchangeHandler handler) throws IOException {
        currentCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            currentCalls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            handler.handle(exchange);
        });
        server.start();
        return currentCalls;
    }

    private AppProperties properties(Map<String, String> overrides) {
        var values = new java.util.HashMap<String, String>();
        values.put("app.openai.api-key", "test-key");
        values.put("app.openai.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("app", Bindable.of(AppProperties.class)).get();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
