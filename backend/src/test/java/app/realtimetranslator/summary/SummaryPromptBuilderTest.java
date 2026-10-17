package app.realtimetranslator.summary;

import app.realtimetranslator.session.ScriptEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryPromptBuilderTest {

    @Test
    void buildsVietnameseJsonInstructionsAndBilingualTranscript() {
        SummaryPromptBuilder builder = new SummaryPromptBuilder();
        ScriptEntry entry = entry(7);

        String prompt = builder.systemPrompt();
        String transcript = builder.transcript(List.of(entry));

        assertTrue(prompt.contains("tiếng Việt"));
        assertTrue(prompt.contains("actionItems"));
        assertTrue(transcript.contains("[7] ja: original-7"));
        assertTrue(transcript.contains("vi: translated-7"));
    }

    static ScriptEntry entry(long sequence) {
        return new ScriptEntry("id-" + sequence, "session01", "original-" + sequence,
                "translated-" + sequence, "ja", "vi", Instant.EPOCH, sequence);
    }
}
