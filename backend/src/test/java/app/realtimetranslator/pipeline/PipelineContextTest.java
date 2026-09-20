package app.realtimetranslator.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineContextTest {

    @Test
    void newContext_isNotAborted() {
        var context = new PipelineContext();
        assertFalse(context.isAborted());
        assertNull(context.getAbortReason());
    }

    @Test
    void abort_setsAbortedAndReason() {
        var context = new PipelineContext();
        context.abort("Empty transcription");

        assertTrue(context.isAborted());
        assertEquals("Empty transcription", context.getAbortReason());
    }

    @Test
    void settersAndGetters_roundTrip() {
        var context = new PipelineContext();
        context.setSessionId("sess-1");
        context.setAudioBytes(new byte[]{1, 2, 3});
        context.setSampleRate(16000);
        context.setSequenceNumber(42L);
        context.setOriginalText("hello");
        context.setDetectedLanguage("ja");
        context.setTranslatedText("xin chào");

        assertEquals("sess-1", context.getSessionId());
        assertArrayEquals(new byte[]{1, 2, 3}, context.getAudioBytes());
        assertEquals(16000, context.getSampleRate());
        assertEquals(42L, context.getSequenceNumber());
        assertEquals("hello", context.getOriginalText());
        assertEquals("ja", context.getDetectedLanguage());
        assertEquals("xin chào", context.getTranslatedText());
    }
}
