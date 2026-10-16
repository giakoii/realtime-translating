package app.realtimetranslator.session;

import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.entity.MessageEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import app.realtimetranslator.session.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Persists per-session transcript buffers in commit-sized snapshots. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BufferPersistService {

    private static final DateTimeFormatter FALLBACK_TITLE =
            DateTimeFormatter.ofPattern("'Meeting' yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final SessionRepository sessionRepository;
    private final MeetingRepository meetingRepository;
    private final MessageRepository messageRepository;

    /**
     * Returns the existing meeting or creates it. The database unique constraint is
     * the final arbiter when two connections race to create the same session.
     */
    public UUID getOrCreateMeeting(String sessionId, String sourceLang, String targetLang) {
        MeetingEntity existing = meetingRepository.findBySessionId(sessionId).orElse(null);
        if (existing != null) {
            return requireActive(existing).getId();
        }

        MeetingEntity meeting = new MeetingEntity();
        meeting.setSessionId(sessionId);
        meeting.setSourceLang(sourceLang);
        meeting.setTargetLang(targetLang);
        meeting.setStartedAt(Instant.now());

        try {
            MeetingEntity saved = meetingRepository.save(meeting);
            log.info("[BUF001] Meeting created: sessionId={}, meetingId={}", sessionId, saved.getId());
            return saved.getId();
        } catch (DataIntegrityViolationException race) {
            MeetingEntity winner = meetingRepository.findBySessionId(sessionId).orElseThrow(() -> race);
            return requireActive(winner).getId();
        }
    }

    /** Kept as a compatibility alias for callers from the original BUF001 feature. */
    public UUID createMeeting(String sessionId, String sourceLang, String targetLang) {
        return getOrCreateMeeting(sessionId, sourceLang, targetLang);
    }

    /** Flushes one stable snapshot, blocking behind another flush for this session. */
    public int persistUnflushedMessages(String sessionId) {
        ScriptBuffer buffer = sessionRepository.get(sessionId).orElse(null);
        if (buffer == null) {
            return 0;
        }

        ReentrantLock lock = buffer.flushLock();
        lock.lock();
        try {
            return persistSnapshot(sessionId, buffer);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts a flush without waiting. An empty value means another trigger owns
     * the flush lock; zero means the lock was acquired but there was no work.
     */
    public OptionalInt tryPersistUnflushedMessages(String sessionId) {
        ScriptBuffer buffer = sessionRepository.get(sessionId).orElse(null);
        if (buffer == null) {
            return OptionalInt.of(0);
        }

        ReentrantLock lock = buffer.flushLock();
        if (!lock.tryLock()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(persistSnapshot(sessionId, buffer));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Finalizes a meeting once. The buffer remains reachable if either the final
     * message save or meeting update fails, allowing the close retrier to recover.
     */
    public void endSession(String sessionId) {
        ScriptBuffer buffer = sessionRepository.get(sessionId).orElse(null);
        if (buffer == null) {
            finalizeMeeting(sessionId);
            return;
        }

        ReentrantLock lock = buffer.flushLock();
        lock.lock();
        try {
            int flushed = persistSnapshot(sessionId, buffer);
            finalizeMeeting(sessionId);
            sessionRepository.remove(sessionId);
            log.info("[BUF001] Session ended: sessionId={}, lastFlushed={}", sessionId, flushed);
        } finally {
            lock.unlock();
        }
    }

    private int persistSnapshot(String sessionId, ScriptBuffer buffer) {
        List<ScriptEntry> snapshot = buffer.getUnflushedEntries();
        if (snapshot.isEmpty()) {
            return 0;
        }

        MeetingEntity meeting = meetingRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalStateException("Meeting not found for sessionId=" + sessionId));
        List<MessageEntity> entities = snapshot.stream()
                .map(entry -> toEntity(entry, meeting.getId()))
                .toList();

        // The repository transaction commits before this proxy invocation returns.
        messageRepository.saveAll(entities);
        buffer.markFlushed(snapshot.size());
        log.info("[BUF001] Persisted {} messages for sessionId={}", entities.size(), sessionId);
        return entities.size();
    }

    private void finalizeMeeting(String sessionId) {
        MeetingEntity meeting = meetingRepository.findBySessionId(sessionId).orElse(null);
        if (meeting == null) {
            return;
        }

        int totalMessages = messageRepository.countByMeetingId(meeting.getId());
        // Targeted updates cannot merge a stale null title over a concurrently
        // generated title. markEndedIfNull also preserves close idempotency.
        meetingRepository.markEndedIfNull(meeting.getId(), Instant.now());
        meetingRepository.updateTotalMessages(meeting.getId(), totalMessages);

        MeetingEntity refreshed = meetingRepository.findBySessionId(sessionId).orElse(meeting);
        if (totalMessages > 0 && refreshed.getTitle() == null) {
            meetingRepository.updateTitleIfNull(meeting.getId(), FALLBACK_TITLE.format(meeting.getStartedAt()));
        }
    }

    private MeetingEntity requireActive(MeetingEntity meeting) {
        if (meeting.getEndedAt() != null) {
            throw new SessionEndedException(meeting.getSessionId());
        }
        return meeting;
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
