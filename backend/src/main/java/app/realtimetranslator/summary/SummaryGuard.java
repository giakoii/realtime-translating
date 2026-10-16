package app.realtimetranslator.summary;

import app.realtimetranslator.events.SessionClosed;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Serializes summaries per session and remembers only successfully summarized content. */
@Component
public class SummaryGuard {

    public enum StartResult { STARTED, IN_PROGRESS, NO_NEW_CONTENT }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public StartResult tryStart(String sessionId, int messagesCount) {
        AtomicReference<StartResult> result = new AtomicReference<>();
        states.compute(sessionId, (id, current) -> {
            State state = current == null ? new State(false, 0, 0, false, -1) : current;
            if (state.running()) {
                result.set(StartResult.IN_PROGRESS);
                return state;
            }
            if (messagesCount <= state.lastSummarizedCount()) {
                result.set(StartResult.NO_NEW_CONTENT);
                return state;
            }
            result.set(StartResult.STARTED);
            return new State(true, state.lastSummarizedCount(), messagesCount, state.closed(),
                    Thread.currentThread().threadId());
        });
        return result.get();
    }

    public void finish(String sessionId, boolean success) {
        states.computeIfPresent(sessionId, (id, state) -> {
            if (!state.running() || state.ownerThreadId() != Thread.currentThread().threadId()) {
                return state;
            }
            if (state.closed()) {
                return null;
            }
            int lastCount = success ? state.inProgressCount() : state.lastSummarizedCount();
            return new State(false, lastCount, 0, false, -1);
        });
    }

    @EventListener
    public void onSessionClosed(SessionClosed event) {
        states.computeIfPresent(event.getSessionId(), (id, state) -> state.running()
                ? new State(true, state.lastSummarizedCount(), state.inProgressCount(), true,
                        state.ownerThreadId())
                : null);
    }

    private record State(boolean running, int lastSummarizedCount, int inProgressCount,
                         boolean closed, long ownerThreadId) {}
}
