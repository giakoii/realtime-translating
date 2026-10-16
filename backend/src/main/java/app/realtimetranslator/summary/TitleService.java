package app.realtimetranslator.summary;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.MeetingTitleGenerated;
import app.realtimetranslator.events.SummaryGenerated;
import app.realtimetranslator.outbound.GptClient;
import app.realtimetranslator.session.entity.MeetingEntity;
import app.realtimetranslator.session.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TitleService {

    private final MeetingRepository meetingRepository;
    private final GptClient gptClient;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties properties;

    @Async("summaryExecutor")
    @Order(2)
    @EventListener
    public void onSummaryGenerated(SummaryGenerated event) {
        generateIfAbsent(event.getSessionId(), event.getResult().content());
    }

    public void generateIfAbsent(String sessionId, String summaryContent) {
        try {
            MeetingEntity meeting = meetingRepository.findBySessionId(sessionId).orElse(null);
            if (meeting == null || meeting.getTitle() != null) {
                return;
            }
            String title = clean(gptClient.generateTitle(summaryContent));
            if (title.isBlank()) {
                return;
            }
            if (meetingRepository.updateTitleIfNull(meeting.getId(), title) == 1) {
                eventPublisher.publishEvent(new MeetingTitleGenerated(this, sessionId, title));
            }
        } catch (RuntimeException exception) {
            log.warn("[SUM001] Title generation failed for sessionId={}, cause={}",
                    sessionId, exception.getClass().getSimpleName());
        }
    }

    String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String title = raw.lines().findFirst().orElse("").trim();
        title = title.replaceFirst("^#{1,6}\\s*", "").trim();
        while (title.length() >= 2 && isMatchingWrapper(title.charAt(0), title.charAt(title.length() - 1))) {
            title = title.substring(1, title.length() - 1).trim();
        }
        title = title.replaceAll("\\s+", " ");
        int maxLength = Math.min(255, properties.title().maxLength());
        return title.length() <= maxLength ? title : title.substring(0, maxLength).stripTrailing();
    }

    private boolean isMatchingWrapper(char first, char last) {
        return (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '`' && last == '`')
                || (first == '*' && last == '*');
    }
}
