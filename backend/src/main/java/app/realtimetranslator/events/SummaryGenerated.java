package app.realtimetranslator.events;

import app.realtimetranslator.summary.SummaryResult;
import org.springframework.context.ApplicationEvent;

public class SummaryGenerated extends ApplicationEvent {
    private final String sessionId;
    private final SummaryResult result;
    private final String triggerType;
    private final int messagesCount;

    public SummaryGenerated(Object source, String sessionId, SummaryResult result,
                            String triggerType, int messagesCount) {
        super(source);
        this.sessionId = sessionId;
        this.result = result;
        this.triggerType = triggerType;
        this.messagesCount = messagesCount;
    }

    public String getSessionId() { return sessionId; }
    public SummaryResult getResult() { return result; }
    public String getTriggerType() { return triggerType; }
    public int getMessagesCount() { return messagesCount; }
}
