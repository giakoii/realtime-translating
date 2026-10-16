package app.realtimetranslator.session;

/** Raised when another live WebSocket connection owns the session identifier. */
public class SessionBusyException extends RuntimeException {

    public SessionBusyException(String sessionId) {
        super("Session is already active: " + sessionId);
    }
}
