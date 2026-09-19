package app.realtimetranslator.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages one {@link ScriptBuffer} per active meeting session.
 *
 * Singleton bean — each session gets its own ScriptBuffer keyed by sessionId.
 * Buffer is created lazily on the first audio chunk and removed after persist on session end.
 *
 * ConcurrentHashMap because multiple WebSocket handler threads may create or access
 * buffers concurrently (one thread per client connection). computeIfAbsent guarantees
 * at-most-once creation per session ID.
 *
 * @see ScriptBuffer
 * @see BufferPersistService
 */
@Component
public class SessionRepository {

    private final Map<String, ScriptBuffer> buffers = new ConcurrentHashMap<>();

    /**
     * Returns the buffer for the given session, creating one if absent.
     * computeIfAbsent guarantees concurrent calls for the same sessionId
     * always return the same instance.
     */
    public ScriptBuffer getOrCreate(String sessionId) {
        return buffers.computeIfAbsent(sessionId, ScriptBuffer::new);
    }

    /**
     * Returns the buffer if the session is still active.
     */
    public Optional<ScriptBuffer> get(String sessionId) {
        return Optional.ofNullable(buffers.get(sessionId));
    }

    /**
     * Removes the buffer, releasing its memory.
     * Must only be called after all entries have been persisted to DB.
     */
    public void remove(String sessionId) {
        buffers.remove(sessionId);
    }

    /**
     * Number of currently active (buffered) sessions.
     */
    public int activeSessionCount() {
        return buffers.size();
    }
}
