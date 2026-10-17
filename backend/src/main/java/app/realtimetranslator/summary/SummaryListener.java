package app.realtimetranslator.summary;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.SilenceDetected;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.pipeline.SessionTaskExecutor;
import app.realtimetranslator.session.ScriptEntry;
import app.realtimetranslator.session.SessionLifecycleService;
import app.realtimetranslator.session.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
@Slf4j
public class SummaryListener {

    private static final String MANUAL = "manual";

    private final SessionRepository sessionRepository;
    private final SessionLifecycleService sessionLifecycleService;
    private final SummaryGuard guard;
    private final SummaryPipeline pipeline;
    private final ClientSessionRegistry registry;
    private final AppProperties properties;
    private final SessionTaskExecutor sessionTaskExecutor;
    private final Executor summaryExecutor;

    public SummaryListener(SessionRepository sessionRepository,
                           SessionLifecycleService sessionLifecycleService,
                           SummaryGuard guard,
                           SummaryPipeline pipeline,
                           ClientSessionRegistry registry,
                           AppProperties properties,
                           SessionTaskExecutor sessionTaskExecutor,
                           @Qualifier("summaryExecutor") Executor summaryExecutor) {
        this.sessionRepository = sessionRepository;
        this.sessionLifecycleService = sessionLifecycleService;
        this.guard = guard;
        this.pipeline = pipeline;
        this.registry = registry;
        this.properties = properties;
        this.sessionTaskExecutor = sessionTaskExecutor;
        this.summaryExecutor = summaryExecutor;
    }

    @EventListener
    public void onSilenceDetected(SilenceDetected event) {
        if ("auto_silence".equals(event.getTriggerType())) {
            try {
                sessionTaskExecutor.submitControl(event.getSessionId(), () -> dispatchAuto(event));
            } catch (RejectedExecutionException ignored) {
                // Closing and closed sessions intentionally discard automatic summaries.
            }
            return;
        }
        try {
            summaryExecutor.execute(() -> prepare(event, null));
        } catch (RejectedExecutionException rejected) {
            sendFailure(event.getSessionId());
        }
    }

    private void dispatchAuto(SilenceDetected event) {
        String sessionId = event.getSessionId();
        if (!sessionLifecycleService.isOpen(sessionId)) {
            return;
        }
        List<ScriptEntry> drainedEntries = snapshot(sessionId);
        try {
            summaryExecutor.execute(() -> prepare(event, drainedEntries));
        } catch (RejectedExecutionException ignored) {
            // Automatic summaries wait for new audio after executor saturation.
        }
    }

    private void prepare(SilenceDetected event, List<ScriptEntry> drainedEntries) {
        String sessionId = event.getSessionId();
        boolean manual = MANUAL.equals(event.getTriggerType());
        if (!sessionLifecycleService.isOpen(sessionId)) {
            return;
        }

        List<ScriptEntry> entries = drainedEntries == null ? snapshot(sessionId) : drainedEntries;
        if (entries.size() < properties.summary().minEntries()) {
            if (manual) {
                sendSkipped(sessionId, "NOT_ENOUGH_CONTENT");
            }
            return;
        }

        SummaryGuard.StartResult start = guard.tryStart(sessionId, entries.size());
        if (start != SummaryGuard.StartResult.STARTED) {
            if (manual) {
                sendSkipped(sessionId, start.name());
            }
            return;
        }

        generate(event, entries);
    }

    private void generate(SilenceDetected event, List<ScriptEntry> entries) {
        String sessionId = event.getSessionId();
        boolean success = false;
        try {
            if (!sessionLifecycleService.isOpen(sessionId)) {
                return;
            }
            pipeline.execute(sessionId, entries, event.getTriggerType());
            success = true;
        } catch (RuntimeException exception) {
            log.warn("[SUM001] Summary failed for sessionId={}, cause={}",
                    sessionId, exception.getClass().getSimpleName());
            sendFailure(sessionId);
        } finally {
            guard.finish(sessionId, success);
        }
    }

    private void sendSkipped(String sessionId, String reason) {
        registry.send(sessionId, Map.of("type", "SUMMARY_SKIPPED", "reason", reason));
    }

    private List<ScriptEntry> snapshot(String sessionId) {
        return sessionRepository.get(sessionId)
                .map(buffer -> List.copyOf(buffer.getAll()))
                .orElse(List.of());
    }

    private void sendFailure(String sessionId) {
        registry.send(sessionId, Map.of(
                "type", "ERROR",
                "code", "SUMMARY_FAILED",
                "message", "Unable to generate summary",
                "retryable", true));
    }
}
