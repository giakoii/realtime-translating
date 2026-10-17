package app.realtimetranslator.session;

/** Raised when a client attempts to reuse a session that has already ended. */
public class SessionEndedException extends RuntimeException {

    public SessionEndedException(String sessionId) {
        super("Session has already ended: " + sessionId);
    }
}
