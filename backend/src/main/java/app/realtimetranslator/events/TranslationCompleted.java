package app.realtimetranslator.events;

import app.realtimetranslator.session.ScriptEntry;
import org.springframework.context.ApplicationEvent;

public class TranslationCompleted extends ApplicationEvent {
    private final ScriptEntry entry;

    public TranslationCompleted(Object source, ScriptEntry entry) {
        super(source);
        this.entry = entry;
    }

    public ScriptEntry getEntry() {
        return entry;
    }
}
