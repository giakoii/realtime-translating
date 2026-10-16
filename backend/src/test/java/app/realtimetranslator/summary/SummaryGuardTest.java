package app.realtimetranslator.summary;

import app.realtimetranslator.events.SessionClosed;
import org.junit.jupiter.api.Test;

import static app.realtimetranslator.summary.SummaryGuard.StartResult.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SummaryGuardTest {

    @Test
    void preventsOverlapAndRequiresNewContentAfterSuccess() {
        SummaryGuard guard = new SummaryGuard();

        assertEquals(STARTED, guard.tryStart("session01", 3));
        assertEquals(IN_PROGRESS, guard.tryStart("session01", 4));
        guard.finish("session01", true);
        assertEquals(NO_NEW_CONTENT, guard.tryStart("session01", 3));
        assertEquals(STARTED, guard.tryStart("session01", 4));
    }

    @Test
    void failurePreservesPreviousSuccessfulCountAndCloseClearsState() {
        SummaryGuard guard = new SummaryGuard();
        assertEquals(STARTED, guard.tryStart("session01", 5));
        guard.finish("session01", false);
        assertEquals(STARTED, guard.tryStart("session01", 5));
        guard.finish("session01", true);

        guard.onSessionClosed(new SessionClosed(this, "session01"));
        assertEquals(STARTED, guard.tryStart("session01", 1));
    }

    @Test
    void closeRetainsOwnershipUntilRunningSummaryFinishes() {
        SummaryGuard guard = new SummaryGuard();
        assertEquals(STARTED, guard.tryStart("session01", 3));

        guard.onSessionClosed(new SessionClosed(this, "session01"));

        assertEquals(IN_PROGRESS, guard.tryStart("session01", 4));
        guard.finish("session01", true);
        assertEquals(STARTED, guard.tryStart("session01", 1));
    }
}
