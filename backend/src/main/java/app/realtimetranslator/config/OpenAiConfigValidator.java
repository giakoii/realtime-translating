package app.realtimetranslator.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpenAiConfigValidator implements InitializingBean {

    private final AppProperties properties;

    public OpenAiConfigValidator(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        var openAi = properties.openai();
        requireText(openAi.apiKey(), "app.openai.api-key");
        requireText(openAi.baseUrl(), "app.openai.base-url");
        requireText(openAi.whisper().model(), "app.openai.whisper.model");
        requireText(openAi.gpt().model(), "app.openai.gpt.model");
        positive(openAi.whisper().timeoutSeconds(), "app.openai.whisper.timeout-seconds");
        nonNegative(openAi.whisper().maxRetries(), "app.openai.whisper.max-retries");
        validateModelSettings(openAi.gpt().temperature(), openAi.gpt().maxTokens(),
                openAi.gpt().timeoutSeconds(), openAi.gpt().maxRetries(), "app.openai.gpt");
        validateModelSettings(properties.summary().temperature(), properties.summary().maxTokens(),
                properties.summary().timeoutSeconds(), properties.summary().maxRetries(), "app.summary");
        validateModelSettings(properties.title().temperature(), properties.title().maxTokens(),
                properties.title().timeoutSeconds(), properties.title().maxRetries(), "app.title");
        requireText(properties.summary().model(), "app.summary.model");
        requireText(properties.title().model(), "app.title.model");
        positive(properties.translation().contextWindowSize(), "app.translation.context-window-size");
        positive(properties.summary().minEntries(), "app.summary.min-entries");
        positive(properties.summary().headEntries(), "app.summary.head-entries");
        positive(properties.summary().tailEntries(), "app.summary.tail-entries");
        positive(properties.summary().maxFullEntries(), "app.summary.max-full-entries");
        positive(properties.title().maxLength(), "app.title.max-length");
        if (properties.title().maxLength() > 255) {
            throw new IllegalStateException("app.title.max-length must not exceed 255");
        }
        positive(properties.silence().thresholdSeconds(), "app.silence.threshold-seconds");
        positive(properties.silence().checkIntervalMs(), "app.silence.check-interval-ms");
        positive(properties.buffer().flushThreshold(), "app.buffer.flush-threshold");
        positive(properties.buffer().flushRetryCooldownSeconds(), "app.buffer.flush-retry-cooldown-seconds");
        positive(properties.buffer().finalFlushMaxAttempts(), "app.buffer.final-flush-max-attempts");
        requireText(properties.session().defaultSourceLang(), "app.session.default-source-lang");
        requireText(properties.session().defaultTargetLang(), "app.session.default-target-lang");
        validateLanguagePair(properties.session().defaultSourceLang(), properties.session().defaultTargetLang());
        positive(properties.session().maxQueuedChunks(), "app.session.max-queued-chunks");
        positive(properties.session().drainTimeoutSeconds(), "app.session.drain-timeout-seconds");
        positive(properties.audio().sampleRate(), "app.audio.sample-rate");
        positive(properties.audio().minChunkMs(), "app.audio.min-chunk-ms");
        positive(properties.audio().maxChunkSeconds(), "app.audio.max-chunk-seconds");
        if ((long) properties.audio().minChunkMs() > properties.audio().maxChunkSeconds() * 1_000L) {
            throw new IllegalStateException(
                    "app.audio.min-chunk-ms must not exceed app.audio.max-chunk-seconds");
        }
        requireText(properties.websocket().allowedOrigins(), "app.websocket.allowed-origins");
        positive(properties.websocket().maxMessageBytes(), "app.websocket.max-message-bytes");
        positive(properties.websocket().sendTimeLimitMs(), "app.websocket.send-time-limit-ms");
        positive(properties.websocket().sendBufferLimitBytes(), "app.websocket.send-buffer-limit-bytes");
    }

    private static void validateModelSettings(double temperature, int maxTokens, int timeoutSeconds,
                                              int maxRetries, String prefix) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalStateException(prefix + ".temperature must be finite and between 0 and 2");
        }
        positive(maxTokens, prefix + ".max-tokens");
        positive(timeoutSeconds, prefix + ".timeout-seconds");
        nonNegative(maxRetries, prefix + ".max-retries");
    }

    private static void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + " must not be blank");
        }
    }

    private static void positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    private static void nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalStateException(name + " must not be negative");
        }
    }

    private static void validateLanguagePair(String source, String target) {
        if (!("ja".equals(source) || "vi".equals(source))
                || !("ja".equals(target) || "vi".equals(target))) {
            throw new IllegalStateException(
                    "app.session default languages must each be one of: ja, vi");
        }
        if (source.equals(target)) {
            throw new IllegalStateException(
                    "app.session.default-source-lang and default-target-lang must be different");
        }
    }
}
