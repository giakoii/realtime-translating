package app.realtimetranslator.session.repository;

import app.realtimetranslator.session.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for the {@code messages} table.
 */
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    /**
     * Retrieves all messages for a session in chronological order.
     * Used by history/export feature (EXP001) to reconstruct meeting transcript.
     */
    List<MessageEntity> findBySessionIdOrderBySequenceNumberAsc(String sessionId);

    /**
     * Counts persisted messages for a meeting.
     * Used to set meetings.total_messages when session ends.
     */
    int countByMeetingId(UUID meetingId);
}
