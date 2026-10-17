package app.realtimetranslator.events;

import org.springframework.context.ApplicationEvent;

public class SilenceDetected extends ApplicationEvent {
    private final String sessionId;
    private final String triggerType;
    private final long silenceSeconds;

    public SilenceDetected(Object source, String sessionId, String triggerType, long silenceSeconds) {
        super(source);
        this.sessionId = sessionId;
        this.triggerType = triggerType;
        this.silenceSeconds = silenceSeconds;
    }

    public String getSessionId() { return sessionId; }
    public String getTriggerType() { return triggerType; }
    public long getSilenceSeconds() { return silenceSeconds; }
}
