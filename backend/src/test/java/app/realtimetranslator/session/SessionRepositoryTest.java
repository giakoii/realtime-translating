package app.realtimetranslator.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionRepositoryTest {

    private SessionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SessionRepository();
    }

    @Test
    void getOrCreate_createsNewBuffer() {
        ScriptBuffer buffer = repository.getOrCreate("session-1");

        assertNotNull(buffer);
        assertEquals("session-1", buffer.getSessionId());
    }

    @Test
    void getOrCreate_returnsSameInstance() {
        ScriptBuffer first = repository.getOrCreate("session-1");
        ScriptBuffer second = repository.getOrCreate("session-1");

        assertSame(first, second);
    }

    @Test
    void get_returnsEmptyWhenNotExists() {
        assertTrue(repository.get("nonexistent").isEmpty());
    }

    @Test
    void get_returnsBufferWhenExists() {
        repository.getOrCreate("session-1");

        assertTrue(repository.get("session-1").isPresent());
    }

    @Test
    void remove_deletesBuffer() {
        repository.getOrCreate("session-1");
        repository.remove("session-1");

        assertTrue(repository.get("session-1").isEmpty());
        assertEquals(0, repository.activeSessionCount());
    }

    @Test
    void activeSessionCount_tracksCorrectly() {
        assertEquals(0, repository.activeSessionCount());
        repository.getOrCreate("s1");
        repository.getOrCreate("s2");
        assertEquals(2, repository.activeSessionCount());
        repository.remove("s1");
        assertEquals(1, repository.activeSessionCount());
    }
}
