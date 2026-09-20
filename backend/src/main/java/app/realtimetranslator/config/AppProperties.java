package app.realtimetranslator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for {@code app.*} configuration.
 * Immutable record — Spring Boot binds constructor params from YAML at startup.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(OpenAi openai) {

    public record OpenAi(String apiKey, Whisper whisper) {

        public record Whisper(
                String model,
                int timeoutSeconds,
                int maxRetries
        ) {}
    }
}
