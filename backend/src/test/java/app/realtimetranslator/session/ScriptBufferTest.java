package app.realtimetranslator.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptBufferTest {

    private ScriptBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new ScriptBuffer("test-session");
    }

    @Test
    void add_incrementsSize() {
        assertEquals(0, buffer.size());
        buffer.add(entry(1));
        assertEquals(1, buffer.size());
        buffer.add(entry(2));
        assertEquals(2, buffer.size());
    }

    @Test
    void getRecentOriginalLines_returnsLastNLines() {
        buffer.add(entry(1, "Hello"));
        buffer.add(entry(2, "World"));
        buffer.add(entry(3, "Foo"));

        List<String> recent = buffer.getRecentOriginalLines(2);

        assertEquals(List.of("World", "Foo"), recent);
    }

    @Test
    void getRecentOriginalLines_whenNExceedsSize_returnsAll() {
        buffer.add(entry(1, "Hello"));

        List<String> recent = buffer.getRecentOriginalLines(10);

        assertEquals(List.of("Hello"), recent);
    }

    @Test
    void getAll_returnsUnmodifiableView() {
        buffer.add(entry(1));

        List<ScriptEntry> all = buffer.getAll();

        assertEquals(1, all.size());
        assertThrows(UnsupportedOperationException.class, () -> all.add(entry(2)));
    }

    @Test
    void flushTracking_newBufferHasAllUnflushed() {
        buffer.add(entry(1));
        buffer.add(entry(2));

        assertEquals(2, buffer.unflushedCount());
        assertEquals(2, buffer.getUnflushedEntries().size());
    }

    @Test
    void markFlushed_movesWatermark() {
        buffer.add(entry(1));
        buffer.add(entry(2));
        buffer.markFlushed(2);

        assertEquals(0, buffer.unflushedCount());
        assertTrue(buffer.getUnflushedEntries().isEmpty());

        // New entries after flush are unflushed
        buffer.add(entry(3));
        assertEquals(1, buffer.unflushedCount());
        assertEquals(3, buffer.getUnflushedEntries().getFirst().sequenceNumber());
    }

    @Test
    void clear_resetsEverything() {
        buffer.add(entry(1));
        buffer.add(entry(2));
        buffer.markFlushed(2);
        buffer.add(entry(3));

        buffer.clear();

        assertEquals(0, buffer.size());
        assertEquals(0, buffer.unflushedCount());
    }

    @Test
    void markFlushed_advancesOnlyByPersistedSnapshotSize() {
        buffer.add(entry(1));
        buffer.add(entry(2));
        List<ScriptEntry> snapshot = buffer.getUnflushedEntries();

        buffer.add(entry(3));
        buffer.markFlushed(snapshot.size());

        assertEquals(2, buffer.flushedCount());
        assertEquals(List.of(3L), buffer.getUnflushedEntries().stream()
                .map(ScriptEntry::sequenceNumber).toList());
    }

    @Test
    void markFlushed_rejectsWatermarkPastBufferEnd() {
        buffer.add(entry(1));

        assertThrows(IllegalStateException.class, () -> buffer.markFlushed(2));
        assertEquals(0, buffer.flushedCount());
    }

    private ScriptEntry entry(long seq) {
        return entry(seq, "text-" + seq);
    }

    private ScriptEntry entry(long seq, String originalText) {
        return ScriptEntry.create(
                "test-session", originalText, "translated-" + seq,
                "ja", "vi", Instant.now(), seq
        );
    }
}
