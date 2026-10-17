package app.realtimetranslator.events;

import org.springframework.context.ApplicationEvent;

public class SessionClosed extends ApplicationEvent {
    private final String sessionId;

    public SessionClosed(Object source, String sessionId) {
        super(source);
        this.sessionId = sessionId;
    }

    public String getSessionId() { return sessionId; }
}
