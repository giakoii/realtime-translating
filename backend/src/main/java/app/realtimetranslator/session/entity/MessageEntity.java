package app.realtimetranslator.session.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code messages} table.
 * Each row is one translated utterance within a meeting.
 *
 * Uses raw UUID for meeting_id instead of @ManyToOne because messages are
 * bulk-inserted via saveAll() — a JPA relationship would trigger N+1 selects
 * on the parent Meeting during batch persist.
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Raw FK instead of @ManyToOne to avoid N+1 on batch insert.
    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    // Denormalized from meeting for faster queries without JOIN.
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "translated_text", nullable = false, columnDefinition = "TEXT")
    private String translatedText;

    @Column(name = "source_lang", nullable = false, length = 8)
    private String sourceLang;

    @Column(name = "target_lang", nullable = false, length = 8)
    private String targetLang;

    // Client-side timestamp of when the speech was detected, not DB insert time.
    @Column(name = "spoken_at", nullable = false)
    private Instant spokenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
