package app.realtimetranslator.summary;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.SummaryGenerated;
import app.realtimetranslator.outbound.GptClient;
import app.realtimetranslator.session.ScriptEntry;
import app.realtimetranslator.session.SessionLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SummaryPipeline {

    private final GptClient gptClient;
    private final SummaryPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties properties;
    private final SessionLifecycleService sessionLifecycleService;

    public SummaryResult execute(String sessionId, List<ScriptEntry> entries, String triggerType) {
        List<ScriptEntry> selected = selectEntries(entries);
        String raw = gptClient.summarize(promptBuilder.systemPrompt(), promptBuilder.transcript(selected));
        SummaryResult result = parse(raw);
        SummaryGenerated event = new SummaryGenerated(
                this, sessionId, result, triggerType, entries.size());
        sessionLifecycleService.runIfOpen(sessionId, () -> eventPublisher.publishEvent(event));
        return result;
    }

    List<ScriptEntry> selectEntries(List<ScriptEntry> entries) {
        AppProperties.Summary config = properties.summary();
        if (entries.size() <= config.maxFullEntries()) {
            return List.copyOf(entries);
        }
        int head = Math.min(config.headEntries(), entries.size());
        int tailStart = Math.max(head, entries.size() - config.tailEntries());
        List<ScriptEntry> selected = new ArrayList<>(head + entries.size() - tailStart);
        selected.addAll(entries.subList(0, head));
        selected.addAll(entries.subList(tailStart, entries.size()));
        return List.copyOf(selected);
    }

    private SummaryResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Summary provider returned blank content");
        }
        SummaryResult parsed;
        try {
            parsed = objectMapper.readValue(raw, SummaryResult.class);
        } catch (RuntimeException exception) {
            log.warn("[SUM001] GPT response was not valid summary JSON; using raw content");
            return new SummaryResult(raw, null);
        }
        if (parsed == null || parsed.content() == null || parsed.content().isBlank()) {
            throw new IllegalStateException("Summary JSON content is blank");
        }
        if (parsed.actionItems() == null || parsed.actionItems().stream()
                .anyMatch(item -> item == null || item.what() == null || item.what().isBlank())) {
            throw new IllegalStateException("Summary JSON actionItems are invalid");
        }
        return parsed;
    }
}
