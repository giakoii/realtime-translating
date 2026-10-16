package app.realtimetranslator.pipeline;

import app.realtimetranslator.outbound.WhisperClient;
import app.realtimetranslator.outbound.WhisperResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeechStageTest {
    private final WhisperClient client = mock(WhisperClient.class);
    private final SpeechStage stage = new SpeechStage(client);

    @Test
    void normalizesFullLanguageNameToIsoAndTrimsText() {
        when(client.transcribe(any(), eq(16000))).thenReturn(new WhisperResponse("  hello  ", "japanese"));
        PipelineContext context = context();
        stage.process(context);
        assertEquals("hello", context.getOriginalText());
        assertEquals("ja", context.getDetectedLanguage());
        assertFalse(context.isAborted());
    }

    @Test
    void unsupportedLanguageAbortsWithoutSettingDetectedLanguage() {
        when(client.transcribe(any(), anyInt())).thenReturn(new WhisperResponse("hello", "english"));
        PipelineContext context = context();
        stage.process(context);
        assertTrue(context.isAborted());
        assertEquals("Unsupported language", context.getAbortReason());
        assertNull(context.getDetectedLanguage());
    }

    @Test
    void nullOrBlankTranscriptionAborts() {
        when(client.transcribe(any(), anyInt()))
                .thenReturn(null)
                .thenReturn(new WhisperResponse("  ", "vi"));
        PipelineContext first = context();
        PipelineContext second = context();
        stage.process(first);
        stage.process(second);
        assertEquals("Empty transcription", first.getAbortReason());
        assertEquals("Empty transcription", second.getAbortReason());
    }

    @Test
    void providerExceptionIsWrappedWithOriginalCause() {
        IllegalStateException cause = new IllegalStateException("provider down");
        when(client.transcribe(any(), anyInt())).thenThrow(cause);
        SpeechRecognitionException thrown = assertThrows(SpeechRecognitionException.class,
                () -> stage.process(context()));
        assertSame(cause, thrown.getCause());
    }

    private PipelineContext context() {
        PipelineContext context = new PipelineContext();
        context.setAudioBytes(new byte[] {0, 0});
        context.setSampleRate(16000);
        return context;
    }
}
