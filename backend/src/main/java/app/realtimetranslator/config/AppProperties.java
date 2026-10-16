package app.realtimetranslator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Type-safe, immutable binding for all {@code app.*} settings. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue OpenAi openai,
        @DefaultValue Translation translation,
        @DefaultValue Summary summary,
        @DefaultValue Title title,
        @DefaultValue Silence silence,
        @DefaultValue Buffer buffer,
        @DefaultValue Session session,
        @DefaultValue Audio audio,
        @DefaultValue Websocket websocket,
        @DefaultValue Logging logging
) {

    public record OpenAi(
            @DefaultValue("") String apiKey,
            @DefaultValue("https://api.openai.com/v1") String baseUrl,
            @DefaultValue Whisper whisper,
            @DefaultValue Gpt gpt
    ) {
        public record Whisper(
                @DefaultValue("whisper-1") String model,
                @DefaultValue("10") int timeoutSeconds,
                @DefaultValue("2") int maxRetries
        ) {}

        public record Gpt(
                @DefaultValue("gpt-4o") String model,
                @DefaultValue("0.3") double temperature,
                @DefaultValue("500") int maxTokens,
                @DefaultValue("8") int timeoutSeconds,
                @DefaultValue("2") int maxRetries
        ) {}
    }

    public record Translation(@DefaultValue("5") int contextWindowSize) {}

    public record Summary(
            @DefaultValue("gpt-4o") String model,
            @DefaultValue("0.5") double temperature,
            @DefaultValue("2000") int maxTokens,
            @DefaultValue("30") int timeoutSeconds,
            @DefaultValue("1") int maxRetries,
            @DefaultValue("3") int minEntries,
            @DefaultValue("50") int headEntries,
            @DefaultValue("200") int tailEntries,
            @DefaultValue("1000") int maxFullEntries
    ) {}

    public record Title(
            @DefaultValue("gpt-4o") String model,
            @DefaultValue("0.3") double temperature,
            @DefaultValue("40") int maxTokens,
            @DefaultValue("8") int timeoutSeconds,
            @DefaultValue("1") int maxRetries,
            @DefaultValue("120") int maxLength
    ) {}

    public record Silence(
            @DefaultValue("30") long thresholdSeconds,
            @DefaultValue("1000") long checkIntervalMs
    ) {}

    public record Buffer(
            @DefaultValue("100") int flushThreshold,
            @DefaultValue("5") long flushRetryCooldownSeconds,
            @DefaultValue("3") int finalFlushMaxAttempts
    ) {}

    public record Session(
            @DefaultValue("ja") String defaultSourceLang,
            @DefaultValue("vi") String defaultTargetLang,
            @DefaultValue("20") int maxQueuedChunks,
            @DefaultValue("20") int drainTimeoutSeconds
    ) {}

    public record Audio(
            @DefaultValue("16000") int sampleRate,
            @DefaultValue("200") int minChunkMs,
            @DefaultValue("30") int maxChunkSeconds
    ) {}

    public record Websocket(
            @DefaultValue("http://localhost:5173,tauri://localhost,http://tauri.localhost") String allowedOrigins,
            @DefaultValue("2097152") int maxMessageBytes,
            @DefaultValue("5000") int sendTimeLimitMs,
            @DefaultValue("524288") int sendBufferLimitBytes
    ) {}

    public record Logging(@DefaultValue("false") boolean logTranscripts) {}
}
