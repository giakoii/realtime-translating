package app.realtimetranslator.session.repository;

import app.realtimetranslator.session.entity.MeetingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the {@code meetings} table.
 */
public interface MeetingRepository extends JpaRepository<MeetingEntity, UUID> {

    /**
     * Looks up a meeting by its WebSocket session ID.
     * Used by BufferPersistService to find the meeting FK for batch message insert.
     */
    Optional<MeetingEntity> findBySessionId(String sessionId);
}
