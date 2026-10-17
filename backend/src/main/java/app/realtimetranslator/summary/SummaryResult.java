package app.realtimetranslator.summary;

import java.util.List;

public record SummaryResult(String content, List<ActionItem> actionItems) {
}
