package app.realtimetranslator.session.repository;

import app.realtimetranslator.session.entity.MeetingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.time.Instant;
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

    @Modifying
    @Transactional
    @Query("update MeetingEntity m set m.title = :title where m.id = :id and m.title is null")
    int updateTitleIfNull(@Param("id") UUID id, @Param("title") String title);

    @Modifying
    @Transactional
    @Query("update MeetingEntity m set m.endedAt = :endedAt where m.id = :id and m.endedAt is null")
    int markEndedIfNull(@Param("id") UUID id, @Param("endedAt") Instant endedAt);

    @Modifying
    @Transactional
    @Query("update MeetingEntity m set m.totalMessages = :totalMessages where m.id = :id")
    int updateTotalMessages(@Param("id") UUID id, @Param("totalMessages") int totalMessages);
}
