package app.realtimetranslator.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppPropertiesTest {

    @Test
    void omittedSettingsUseDocumentedDefaults() {
        AppProperties properties = bind(Map.of("app.openai.api-key", "test-key"));

        assertEquals("https://api.openai.com/v1", properties.openai().baseUrl());
        assertEquals("whisper-1", properties.openai().whisper().model());
        assertEquals("gpt-4o", properties.openai().gpt().model());
        assertEquals(5, properties.translation().contextWindowSize());
        assertEquals(3, properties.summary().minEntries());
        assertEquals(100, properties.buffer().flushThreshold());
        assertEquals("ja", properties.session().defaultSourceLang());
        assertEquals(16000, properties.audio().sampleRate());
        assertEquals(false, properties.logging().logTranscripts());
    }

    @Test
    void explicitValuesOverrideDefaults() {
        AppProperties properties = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.openai.gpt.max-tokens", "77",
                "app.buffer.flush-threshold", "12",
                "app.session.default-target-lang", "ja"));

        assertEquals(77, properties.openai().gpt().maxTokens());
        assertEquals(12, properties.buffer().flushThreshold());
        assertEquals("ja", properties.session().defaultTargetLang());
    }

    @Test
    void validatorRejectsBlankKeyAndInvalidRanges() {
        AppProperties blankKey = bind(Map.of());
        assertThrows(IllegalStateException.class,
                () -> new OpenAiConfigValidator(blankKey).afterPropertiesSet());

        AppProperties zeroTimeout = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.openai.whisper.timeout-seconds", "0"));
        assertThrows(IllegalStateException.class,
                () -> new OpenAiConfigValidator(zeroTimeout).afterPropertiesSet());
    }

    @Test
    void validatorRejectsUnsupportedOrIdenticalLanguagePairs() {
        AppProperties unsupported = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.session.default-source-lang", "en"));
        assertThrows(IllegalStateException.class,
                () -> new OpenAiConfigValidator(unsupported).afterPropertiesSet());

        AppProperties identical = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.session.default-source-lang", "vi",
                "app.session.default-target-lang", "vi"));
        assertThrows(IllegalStateException.class,
                () -> new OpenAiConfigValidator(identical).afterPropertiesSet());
    }

    @Test
    void validatorRejectsNonFiniteTemperature() {
        AppProperties properties = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.openai.gpt.temperature", "NaN"));

        assertThrows(IllegalStateException.class,
                () -> new OpenAiConfigValidator(properties).afterPropertiesSet());
    }

    @Test
    void validatorRejectsMinimumAudioChunkLongerThanMaximum() {
        AppProperties properties = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.audio.min-chunk-ms", "2001",
                "app.audio.max-chunk-seconds", "2"));

        assertThrows(IllegalStateException.class,
                () -> new OpenAiConfigValidator(properties).afterPropertiesSet());
    }

    @Test
    void validatorRejectsTitleLengthAboveDatabaseLimit() {
        AppProperties properties = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.title.max-length", "256"));

        assertThrows(IllegalStateException.class,
                () -> new OpenAiConfigValidator(properties).afterPropertiesSet());
    }

    @Test
    void validatorAcceptsInclusiveConfigurationBoundaries() {
        AppProperties properties = bind(Map.of(
                "app.openai.api-key", "test-key",
                "app.openai.gpt.temperature", "2",
                "app.audio.min-chunk-ms", "2000",
                "app.audio.max-chunk-seconds", "2",
                "app.title.max-length", "255"));

        assertDoesNotThrow(() -> new OpenAiConfigValidator(properties).afterPropertiesSet());
    }

    private AppProperties bind(Map<String, String> values) {
        var source = new MapConfigurationPropertySource(values);
        return new Binder(source).bind("app", Bindable.of(AppProperties.class)).orElseGet(() ->
                new Binder(new MapConfigurationPropertySource(Map.of("app.openai.api-key", "")))
                        .bind("app", Bindable.of(AppProperties.class)).get());
    }
}
