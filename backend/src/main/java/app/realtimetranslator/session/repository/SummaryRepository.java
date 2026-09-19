package app.realtimetranslator.session.repository;

import app.realtimetranslator.session.entity.SummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for the {@code summaries} table.
 */
public interface SummaryRepository extends JpaRepository<SummaryEntity, UUID> {

    /**
     * Retrieves all summaries for a meeting, newest first.
     * Used by history/export feature (EXP001) to display meeting summaries.
     */
    List<SummaryEntity> findByMeetingIdOrderByGeneratedAtDesc(UUID meetingId);
}
