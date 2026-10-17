package app.realtimetranslator.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ClosedSessionFlushRetrierTest {

    @Test
    void retryClosedSessions_stopsTrackingAfterSuccessfulFlush() {
        BufferPersistService persistService = mock(BufferPersistService.class);
        ClosedSessionFlushRetrier retrier = new ClosedSessionFlushRetrier(
                persistService, new SessionRepository());
        retrier.track("session-1");

        retrier.retryClosedSessions();

        verify(persistService).endSession("session-1");
        assertEquals(0, retrier.trackedSessionCount());
    }
}
