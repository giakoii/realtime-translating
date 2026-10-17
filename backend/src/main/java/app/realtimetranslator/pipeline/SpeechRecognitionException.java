package app.realtimetranslator.pipeline;

/** Separates STT failures from downstream failures without exposing provider responses. */
public class SpeechRecognitionException extends RuntimeException {
    public SpeechRecognitionException(Throwable cause) { super("Speech transcription failed", cause); }
}
