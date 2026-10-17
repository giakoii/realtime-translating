package app.realtimetranslator.pipeline;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.outbound.GptClient;
import app.realtimetranslator.session.ScriptBuffer;
import app.realtimetranslator.session.ScriptEntry;
import app.realtimetranslator.session.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TranslationStageTest {
    private final GptClient client = mock(GptClient.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private TranslationStage stage;
    private ScriptBuffer buffer;

    @BeforeEach
    void setUp() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.translation()).thenReturn(new AppProperties.Translation(2));
        buffer = new ScriptBuffer("session_01");
        buffer.add(entry(1, "first"));
        buffer.add(entry(2, "second"));
        buffer.add(entry(3, "third"));
        when(sessions.get("session_01")).thenReturn(Optional.of(buffer));
        stage = new TranslationStage(client, sessions, properties);
    }

    @Test
    void japaneseTargetsVietnameseAndUsesConfiguredRecentContext() {
        when(client.translate("current", "ja", "vi", List.of("second", "third"))).thenReturn("  ban dich  ");
        PipelineContext context = context("ja");

        stage.process(context);

        assertEquals("vi", context.getTargetLanguage());
        assertEquals("ban dich", context.getTranslatedText());
        assertFalse(context.isAborted());
    }

    @Test
    void vietnameseTargetsJapanese() {
        when(client.translate(anyString(), eq("vi"), eq("ja"), anyList())).thenReturn("訳");
        PipelineContext context = context("vi");
        stage.process(context);
        assertEquals("ja", context.getTargetLanguage());
        assertEquals("訳", context.getTranslatedText());
    }

    @Test
    void translationFailureFallsBackToEmptyTextWithoutAborting() {
        when(client.translate(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("provider"));
        PipelineContext context = context("ja");
        stage.process(context);
        assertEquals("", context.getTranslatedText());
        assertFalse(context.isAborted());
    }

    @Test
    void missingSessionOrUnsupportedLanguageAbortsBeforeProviderCall() {
        PipelineContext unsupported = context("en");
        stage.process(unsupported);
        when(sessions.get("session_01")).thenReturn(Optional.empty());
        PipelineContext zombie = context("ja");
        stage.process(zombie);
        assertTrue(unsupported.isAborted());
        assertTrue(zombie.isAborted());
        verifyNoInteractions(client);
    }

    private PipelineContext context(String language) {
        PipelineContext context = new PipelineContext();
        context.setSessionId("session_01");
        context.setOriginalText("current");
        context.setDetectedLanguage(language);
        return context;
    }

    private ScriptEntry entry(long sequence, String text) {
        return ScriptEntry.create("session_01", text, "translated", "ja", "vi", Instant.EPOCH, sequence);
    }
}
