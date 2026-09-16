package app.realtimetranslator.session;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory buffer that accumulates {@link ScriptEntry} records for one meeting session.
 *
 * Serves two roles: (1) provide recent original lines as GPT context window for
 * translation coherence, and (2) stage entries for batch persist to PostgreSQL
 * instead of one INSERT per utterance.
 *
 * @see SessionRepository
 * @see BufferPersistService
 */
public class ScriptBuffer {

    private final String sessionId;

    // CopyOnWriteArrayList because reads (overlay, context window, summary) vastly
    // outnumber writes (one per utterance) — no explicit locking needed.
    private final List<ScriptEntry> entries = new CopyOnWriteArrayList<>();

    // Tracks how far we've flushed to DB, so each flush cycle only persists new entries
    // without clearing the buffer (clearing would break in-flight summary generation).
    private final AtomicInteger lastFlushedIndex = new AtomicInteger(0);

    public ScriptBuffer(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Appends a translated entry. Called by BufferStage after each STT -> Translation cycle.
     */
    public void add(ScriptEntry entry) {
        entries.add(entry);
    }

    /**
     * Returns N most recent original-language lines for GPT context window.
     *
     * Translation prompt includes recent lines so GPT-4o can maintain contextual
     * coherence — e.g. resolving pronouns across consecutive utterances.
     */
    public List<String> getRecentOriginalLines(int n) {
        int size = entries.size();
        int from = Math.max(0, size - n);
        return entries.subList(from, size).stream()
                .map(ScriptEntry::originalText)
                .toList();
    }

    /**
     * Returns unmodifiable view of all entries. Used by SummaryPipeline to read
     * full transcript when generating a summary after silence detection.
     */
    public List<ScriptEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Returns entries added since the last {@link #markFlushed()} call.
     *
     * @return defensive copy — safe to iterate while pipeline adds new entries
     */
    public List<ScriptEntry> getUnflushedEntries() {
        int flushed = lastFlushedIndex.get();
        int size = entries.size();
        if (flushed >= size) {
            return List.of();
        }
        // Defensive copy to avoid ConcurrentModificationException if new
        // entries arrive while the persist service iterates.
        return List.copyOf(entries.subList(flushed, size));
    }

    /**
     * Advances flush watermark to current size. Call after successful batch persist
     * to prevent those entries from being persisted again.
     */
    public void markFlushed() {
        lastFlushedIndex.set(entries.size());
    }

    /**
     * Count of entries not yet flushed. Used to trigger auto-flush when reaching
     * threshold (default 100) to bound memory in very long meetings.
     */
    public int unflushedCount() {
        return entries.size() - lastFlushedIndex.get();
    }

    /**
     * Clears all entries and resets watermark. Called after session ends and
     * everything is persisted, before SessionRepository drops the reference.
     */
    public void clear() {
        entries.clear();
        lastFlushedIndex.set(0);
    }

    public int size() {
        return entries.size();
    }
}
