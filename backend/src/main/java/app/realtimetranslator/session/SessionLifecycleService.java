package app.realtimetranslator.session;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.SessionClosed;
import app.realtimetranslator.outbound.ClientSessionRegistry;
import app.realtimetranslator.outbound.ServerMessages;
import app.realtimetranslator.pipeline.SessionTaskExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the in-memory and database lifecycle of a WebSocket meeting session. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionLifecycleService {

    private final BufferPersistService bufferPersistService;
    private final SessionRepository sessionRepository;
    private final SessionTaskExecutor sessionTaskExecutor;
    private final ClosedSessionFlushRetrier closedSessionFlushRetrier;
    private final ClientSessionRegistry registry;
    private final AppProperties properties;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Opens one meeting and binds its sole owning WebSocket connection. */
    public ScriptBuffer open(String sessionId, WebSocketSession webSocketSession) {
        State previous = states.putIfAbsent(sessionId, State.OPEN);
        if (previous != null) {
            if (previous == State.OPEN && registry.isBoundTo(sessionId, webSocketSession)) {
                return sessionRepository.get(sessionId)
                        .orElseThrow(() -> new SessionBusyException(sessionId));
            }
            if (previous == State.CLOSED) {
                throw new SessionEndedException(sessionId);
            }
            throw new SessionBusyException(sessionId);
        }

        if (!registry.bind(sessionId, webSocketSession)) {
            states.remove(sessionId, State.OPEN);
            throw new SessionBusyException(sessionId);
        }

        try {
            bufferPersistService.getOrCreateMeeting(
                    sessionId,
                    properties.session().defaultSourceLang(),
                    properties.session().defaultTargetLang());
            return sessionRepository.getOrCreate(sessionId);
        } catch (SessionEndedException ended) {
            states.replace(sessionId, State.OPEN, State.CLOSED);
            registry.unregister(sessionId);
            throw ended;
        } catch (RuntimeException failure) {
            states.remove(sessionId, State.OPEN);
            registry.unregister(sessionId);
            throw failure;
        }
    }

    public boolean isOpen(String sessionId) {
        return states.get(sessionId) == State.OPEN;
    }

    /**
     * Runs a short synchronous publication only while the session is open. The
     * per-key map computation is atomic with {@link #close(String)}, preventing a
     * generated summary from being published after close has started.
     */
    public boolean runIfOpen(String sessionId, Runnable action) {
        AtomicBoolean ran = new AtomicBoolean();
        states.computeIfPresent(sessionId, (ignored, state) -> {
            if (state == State.OPEN) {
                action.run();
                ran.set(true);
            }
            return state;
        });
        return ran.get();
    }

    @Order(0)
    @EventListener
    public void onSessionClosed(SessionClosed event) {
        close(event.getSessionId());
    }

    /** Marks the session closing and appends finalization after all accepted chunks. */
    public void close(String sessionId) {
        AtomicBoolean closing = new AtomicBoolean();
        states.computeIfPresent(sessionId, (ignored, state) -> {
            if (state == State.OPEN) {
                closing.set(true);
                return State.CLOSING;
            }
            return state;
        });
        if (!closing.get()) {
            return;
        }

        sessionTaskExecutor.submitClose(sessionId, () -> finalizeClose(sessionId));
    }

    private void finalizeClose(String sessionId) {
        int totalMessages = sessionRepository.get(sessionId).map(ScriptBuffer::size).orElse(0);
        int maxAttempts = properties.buffer().finalFlushMaxAttempts();
        boolean persisted = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                bufferPersistService.endSession(sessionId);
                persisted = true;
                break;
            } catch (RuntimeException failure) {
                log.warn("[SES001] Final flush failed: sessionId={}, attempt={}/{}, cause={}",
                        sessionId, attempt, maxAttempts, failure.getClass().getSimpleName());
                if (attempt < maxAttempts && !backoff(attempt)) {
                    break;
                }
            }
        }

        if (!persisted) {
            closedSessionFlushRetrier.track(sessionId);
        }

        // Keep the binding until the response is attempted; this also blocks a
        // reconnect from taking ownership during the drain and retry window.
        registry.send(sessionId, ServerMessages.sessionClosed(sessionId, totalMessages));
        registry.unregister(sessionId);
        states.put(sessionId, State.CLOSED);
    }

    private boolean backoff(int attempt) {
        try {
            Thread.sleep(attempt * 1_000L);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private enum State { OPEN, CLOSING, CLOSED }
}
