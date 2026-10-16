package app.realtimetranslator.session;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.SummaryGenerated;
import app.realtimetranslator.events.TranslationCompleted;
import app.realtimetranslator.summary.SummaryPersistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/** Wires summary and threshold events to durable transcript persistence. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PersistTriggerListener {

    private final SessionRepository sessionRepository;
    private final BufferPersistService bufferPersistService;
    private final SummaryPersistService summaryPersistService;
    private final AppProperties properties;
    private final Clock clock;
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();

    @Async("persistExecutor")
    @EventListener
    public void onTranslationCompleted(TranslationCompleted event) {
        String sessionId = event.getEntry().sessionId();
        ScriptBuffer buffer = sessionRepository.get(sessionId).orElse(null);
        if (buffer == null || buffer.unflushedCount() < properties.buffer().flushThreshold()) {
            return;
        }

        Instant now = clock.instant();
        Instant cooldown = retryAfter.get(sessionId);
        if (cooldown != null && now.isBefore(cooldown)) {
            return;
        }

        try {
            OptionalInt result = bufferPersistService.tryPersistUnflushedMessages(sessionId);
            if (result.isEmpty()) {
                return;
            }
            retryAfter.remove(sessionId);

            // Entries can arrive while saveAll is committing. Recheck once so a full
            // second batch does not have to wait for the next translation event.
            ScriptBuffer current = sessionRepository.get(sessionId).orElse(null);
            if (current != null && current.unflushedCount() >= properties.buffer().flushThreshold()) {
                bufferPersistService.tryPersistUnflushedMessages(sessionId);
            }
        } catch (RuntimeException failure) {
            retryAfter.put(sessionId, now.plusSeconds(properties.buffer().flushRetryCooldownSeconds()));
            log.warn("[BUF001] Automatic flush failed: sessionId={}, cause={}",
                    sessionId, failure.getClass().getSimpleName());
        }
    }

    /** A summary row is saved even when its best-effort transcript flush fails. */
    @Order(1)
    @EventListener
    public void onSummaryGenerated(SummaryGenerated event) {
        try {
            bufferPersistService.persistUnflushedMessages(event.getSessionId());
        } catch (RuntimeException failure) {
            log.warn("[BUF001] Summary-triggered flush failed: sessionId={}, cause={}",
                    event.getSessionId(), failure.getClass().getSimpleName());
        }

        try {
            summaryPersistService.save(
                    event.getSessionId(),
                    event.getResult(),
                    event.getTriggerType(),
                    event.getMessagesCount());
        } catch (RuntimeException failure) {
            log.error("[BUF001] Summary persistence failed: sessionId={}, cause={}",
                    event.getSessionId(), failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
