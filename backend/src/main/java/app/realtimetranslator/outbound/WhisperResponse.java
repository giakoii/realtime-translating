package app.realtimetranslator.outbound;

/**
 * Maps the JSON response from OpenAI Whisper transcription API.
 * {@code language} is ISO 639-1 (e.g. "ja", "vi", "en").
 */
public record WhisperResponse(
        String text,
        String language
) {}
