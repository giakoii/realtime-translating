package app.realtimetranslator.summary;

import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.entity.SummaryEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import app.realtimetranslator.session.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SummaryPersistService {

    private final MeetingRepository meetingRepository;
    private final SummaryRepository summaryRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public UUID save(String sessionId, SummaryResult result, String triggerType, int messagesCount) {
        MeetingEntity meeting = meetingRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalStateException("Meeting not found for sessionId=" + sessionId));
        SummaryEntity entity = new SummaryEntity();
        entity.setMeetingId(meeting.getId());
        entity.setTriggerType(triggerType);
        entity.setContent(result.content());
        entity.setActionItems(result.actionItems() == null
                ? null
                : objectMapper.writeValueAsString(result.actionItems()));
        entity.setMessagesCount(messagesCount);
        return summaryRepository.save(entity).getId();
    }
}
