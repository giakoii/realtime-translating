package app.realtimetranslator.session;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record representing a single translated utterance in the meeting timeline.
 *
 * Record (not class) because entries are shared between pipeline thread and summary
 * thread — immutability eliminates field-level synchronization.
 *
 * @param id              UUID string, generated at creation time
 * @param sessionId       the meeting session this entry belongs to
 * @param originalText    STT result in source language
 * @param translatedText  GPT-4o translation in target language
 * @param sourceLang      ISO language code of original speech (e.g. "ja", "vi")
 * @param targetLang      ISO language code of translation output
 * @param timestamp       when the utterance was spoken (client-side)
 * @param sequenceNumber  monotonic order within session, used for DB ordering
 */
public record ScriptEntry(
    String id,
    String sessionId,
    String originalText,
    String translatedText,
    String sourceLang,
    String targetLang,
    Instant timestamp,
    long sequenceNumber
) {

    /**
     * Creates a new ScriptEntry with an auto-generated UUID.
     *
     * A separate {@code from(TranslationCompleted)} factory will be added
     * when the events module (TRL001) is implemented.
     */
    public static ScriptEntry create(
            String sessionId,
            String originalText,
            String translatedText,
            String sourceLang,
            String targetLang,
            Instant timestamp,
            long sequenceNumber
    ) {
        return new ScriptEntry(
                UUID.randomUUID().toString(),
                sessionId,
                originalText,
                translatedText,
                sourceLang,
                targetLang,
                timestamp,
                sequenceNumber
        );
    }
}
