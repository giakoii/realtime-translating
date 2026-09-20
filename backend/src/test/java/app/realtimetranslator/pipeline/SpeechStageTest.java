package app.realtimetranslator.pipeline;

import app.realtimetranslator.outbound.WhisperClient;
import app.realtimetranslator.outbound.WhisperResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpeechStageTest {

    @Mock
    private WhisperClient whisperClient;

    private SpeechStage speechStage;

    @BeforeEach
    void setUp() {
        speechStage = new SpeechStage(whisperClient);
    }

    @Test
    void process_setsOriginalTextAndLanguage() {
        when(whisperClient.transcribe(any(), anyInt()))
                .thenReturn(new WhisperResponse("こんにちは", "ja"));

        var context = contextWithAudio();
        speechStage.process(context);

        assertFalse(context.isAborted());
        assertEquals("こんにちは", context.getOriginalText());
        assertEquals("ja", context.getDetectedLanguage());
    }

    @Test
    void process_abortsOnNullResponse() {
        when(whisperClient.transcribe(any(), anyInt()))
                .thenReturn(null);

        var context = contextWithAudio();
        speechStage.process(context);

        assertTrue(context.isAborted());
        assertEquals("Empty transcription", context.getAbortReason());
    }

    @Test
    void process_abortsOnBlankText() {
        when(whisperClient.transcribe(any(), anyInt()))
                .thenReturn(new WhisperResponse("   ", "ja"));

        var context = contextWithAudio();
        speechStage.process(context);

        assertTrue(context.isAborted());
        assertEquals("Empty transcription", context.getAbortReason());
    }

    @Test
    void process_abortsOnNullText() {
        when(whisperClient.transcribe(any(), anyInt()))
                .thenReturn(new WhisperResponse(null, null));

        var context = contextWithAudio();
        speechStage.process(context);

        assertTrue(context.isAborted());
    }

    @Test
    void process_trimsWhitespace() {
        when(whisperClient.transcribe(any(), anyInt()))
                .thenReturn(new WhisperResponse("  Xin chào  ", "vi"));

        var context = contextWithAudio();
        speechStage.process(context);

        assertEquals("Xin chào", context.getOriginalText());
        assertEquals("vi", context.getDetectedLanguage());
    }

    private PipelineContext contextWithAudio() {
        var context = new PipelineContext();
        context.setAudioBytes(new byte[]{1, 2, 3});
        context.setSampleRate(16000);
        context.setSequenceNumber(1);
        return context;
    }
}
