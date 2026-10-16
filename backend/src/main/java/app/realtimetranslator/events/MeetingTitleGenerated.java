package app.realtimetranslator.events;

import org.springframework.context.ApplicationEvent;

public class MeetingTitleGenerated extends ApplicationEvent {
    private final String sessionId;
    private final String title;

    public MeetingTitleGenerated(Object source, String sessionId, String title) {
        super(source);
        this.sessionId = sessionId;
        this.title = title;
    }

    public String getSessionId() { return sessionId; }
    public String getTitle() { return title; }
}
