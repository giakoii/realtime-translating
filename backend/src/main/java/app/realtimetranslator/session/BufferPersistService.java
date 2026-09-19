package app.realtimetranslator.session;

import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.entity.MessageEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import app.realtimetranslator.session.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Batch-persists {@link ScriptBuffer} entries to PostgreSQL.
 *
 * Three trigger points (event listeners wired in later features):
 * 1. Summary generated -> flush unflushed entries
 * 2. Session close (WebSocket disconnect) -> flush all + cleanup
 * 3. Buffer reaches 100 entries -> auto-flush in background
 *
 * Uses saveAll() for batch insert — Hibernate batch_size=50 is configured in
 * application.yaml to reduce DB round trips vs one INSERT per message.
 *
 * @see ScriptBuffer
 * @see SessionRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BufferPersistService {

    private final SessionRepository sessionRepository;
    private final MeetingRepository meetingRepository;
    private final MessageRepository messageRepository;

    /**
     * Creates a meeting record when a session starts.
     *
     * @return the meeting's database ID
     */
    @Transactional
    public UUID createMeeting(String sessionId, String sourceLang, String targetLang) {
        MeetingEntity meeting = new MeetingEntity();
        meeting.setSessionId(sessionId);
        meeting.setSourceLang(sourceLang);
        meeting.setTargetLang(targetLang);
        meeting.setStartedAt(Instant.now());

        MeetingEntity saved = meetingRepository.save(meeting);
        log.info("[BUF001] Meeting created: sessionId={}, meetingId={}", sessionId, saved.getId());
        return saved.getId();
    }

    /**
     * Flushes only unflushed entries to DB, then advances the watermark.
     *
     * @return number of messages persisted
     */
    @Transactional
    public int persistUnflushedMessages(String sessionId) {
        ScriptBuffer buffer = sessionRepository.get(sessionId).orElse(null);
        if (buffer == null) {
            log.warn("[BUF001] No active buffer for sessionId={}", sessionId);
            return 0;
        }

        List<ScriptEntry> unflushed = buffer.getUnflushedEntries();
        if (unflushed.isEmpty()) {
            return 0;
        }

        MeetingEntity meeting = meetingRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Meeting not found for sessionId=" + sessionId));

        List<MessageEntity> entities = unflushed.stream()
                .map(entry -> toEntity(entry, meeting.getId()))
                .toList();

        // saveAll triggers Hibernate batch insert (batch_size=50 in config)
        // to avoid one INSERT round-trip per message.
        messageRepository.saveAll(entities);
        buffer.markFlushed();

        log.info("[BUF001] Persisted {} messages for sessionId={}", entities.size(), sessionId);
        return entities.size();
    }

    /**
     * Ends a session: flush remaining entries, update meeting record, free memory.
     */
    @Transactional
    public void endSession(String sessionId) {
        int flushed = persistUnflushedMessages(sessionId);

        MeetingEntity meeting = meetingRepository.findBySessionId(sessionId).orElse(null);
        if (meeting != null) {
            meeting.setEndedAt(Instant.now());
            meeting.setTotalMessages(messageRepository.countByMeetingId(meeting.getId()));
            meetingRepository.save(meeting);
        }

        // Release in-memory buffer — entries are now in PostgreSQL.
        sessionRepository.remove(sessionId);
        log.info("[BUF001] Session ended: sessionId={}, lastFlushed={}", sessionId, flushed);
    }

    private MessageEntity toEntity(ScriptEntry entry, UUID meetingId) {
        MessageEntity entity = new MessageEntity();
        entity.setMeetingId(meetingId);
        entity.setSessionId(entry.sessionId());
        entity.setSequenceNumber(entry.sequenceNumber());
        entity.setOriginalText(entry.originalText());
        entity.setTranslatedText(entry.translatedText());
        entity.setSourceLang(entry.sourceLang());
        entity.setTargetLang(entry.targetLang());
        entity.setSpokenAt(entry.timestamp());
        return entity;
    }
}
