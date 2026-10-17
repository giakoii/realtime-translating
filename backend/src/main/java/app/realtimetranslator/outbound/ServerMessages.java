package app.realtimetranslator.outbound;

import app.realtimetranslator.summary.ActionItem;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;

/** Wire records keep the frontend protocol independent of Spring events. */
public final class ServerMessages {
    private ServerMessages() {}
    public record Ack(String type, long sequenceNumber, String status) {}
    public record Error(String type, String code, String message, boolean retryable, Long sequenceNumber) {}
    public record Translation(String type, String sessionId, long sequenceNumber,
                              String originalText, String translatedText, String sourceLang,
                              String targetLang, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {}
    public record SummaryBody(String rawContent, List<ActionItem> actionItems) {}
    public record Summary(String type, String sessionId, String triggerType, int messagesCount,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) Instant generatedAt, SummaryBody summary) {}
    public record Title(String type, String sessionId, String title) {}
    public record SummarySkipped(String type, String reason) {}
    public record SessionClosed(String type, String sessionId, int totalMessages) {}
    public static Ack ack(long sequenceNumber, boolean duplicate) {
        return new Ack("CHUNK_RECEIVED", sequenceNumber, duplicate ? "duplicate" : "processing");
    }
    public static Error error(String code, String message, boolean retryable, Long sequenceNumber) {
        return new Error("ERROR", code, message, retryable, sequenceNumber);
    }
    public static SessionClosed sessionClosed(String sessionId, int totalMessages) {
        return new SessionClosed("SESSION_CLOSED", sessionId, totalMessages);
    }
}
