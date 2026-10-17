package app.realtimetranslator.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Retries buffers retained after all immediate final-flush attempts fail. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClosedSessionFlushRetrier {

    private static final int MAX_ATTEMPTS = 10;

    private final BufferPersistService bufferPersistService;
    private final SessionRepository sessionRepository;
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    public void track(String sessionId) {
        attempts.putIfAbsent(sessionId, 0);
    }

    @Scheduled(fixedDelay = 30_000)
    public void retryClosedSessions() {
        for (String sessionId : attempts.keySet()) {
            int attempt = attempts.computeIfPresent(sessionId, (ignored, current) -> current + 1);
            try {
                bufferPersistService.endSession(sessionId);
                attempts.remove(sessionId);
                log.info("[BUF001] Recovered final flush: sessionId={}, attempt={}", sessionId, attempt);
            } catch (RuntimeException failure) {
                if (attempt >= MAX_ATTEMPTS) {
                    attempts.remove(sessionId);
                    int remaining = sessionRepository.get(sessionId)
                            .map(ScriptBuffer::unflushedCount)
                            .orElse(0);
                    log.error("[BUF001] Final flush abandoned after {} retries: sessionId={}, unpersisted={}",
                            MAX_ATTEMPTS, sessionId, remaining);
                } else {
                    log.warn("[BUF001] Final flush retry failed: sessionId={}, attempt={}, cause={}",
                            sessionId, attempt, failure.getClass().getSimpleName());
                }
            }
        }
    }

    int trackedSessionCount() {
        return attempts.size();
    }
}
