package app.realtimetranslator.summary;

import app.realtimetranslator.session.ScriptEntry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SummaryPromptBuilder {

    public String systemPrompt() {
        return """
                Bạn là trợ lý tóm tắt cuộc họp song ngữ Nhật-Việt. Hãy viết bản tóm tắt ngắn gọn, chính xác bằng tiếng Việt.
                Chỉ sử dụng thông tin có trong hội thoại, không suy đoán. Giữ nguyên tên riêng và thuật ngữ kỹ thuật.
                Trả về đúng một JSON object với cấu trúc:
                {"content":"Nội dung tóm tắt dạng Markdown","actionItems":[{"who":"Người phụ trách","what":"Việc cần làm","deadline":null}]}
                Nếu không có việc cần làm, actionItems là mảng rỗng.
                """.strip();
    }

    public String transcript(List<ScriptEntry> entries) {
        StringBuilder transcript = new StringBuilder();
        for (ScriptEntry entry : entries) {
            transcript.append('[').append(entry.sequenceNumber()).append("] ")
                    .append(entry.sourceLang()).append(": ")
                    .append(entry.originalText()).append('\n');
            if (entry.translatedText() != null && !entry.translatedText().isBlank()) {
                transcript.append("  ").append(entry.targetLang()).append(": ")
                        .append(entry.translatedText()).append('\n');
            }
        }
        return transcript.toString();
    }
}
