package app.realtimetranslator.outbound;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.realtimetranslator.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GptClient {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final AppProperties properties;
    private final String chatCompletionsUrl;
    private final RestClient translationClient;
    private final RestClient summaryClient;
    private final RestClient titleClient;

    public GptClient(AppProperties properties) {
        this.properties = properties;
        var openAi = properties.openai();
        this.chatCompletionsUrl = OpenAiHttp.endpoint(openAi.baseUrl(), CHAT_COMPLETIONS_PATH);
        this.translationClient = OpenAiHttp.newClient(openAi.baseUrl(), openAi.apiKey(),
                Duration.ofSeconds(openAi.gpt().timeoutSeconds()));
        this.summaryClient = OpenAiHttp.newClient(openAi.baseUrl(), openAi.apiKey(),
                Duration.ofSeconds(properties.summary().timeoutSeconds()));
        this.titleClient = OpenAiHttp.newClient(openAi.baseUrl(), openAi.apiKey(),
                Duration.ofSeconds(properties.title().timeoutSeconds()));
    }

    public String translate(String text, String fromLang, String toLang, List<String> contextLines) {
        var cfg = properties.openai().gpt();
        var messages = new ArrayList<ChatRequest.Message>();
        messages.add(new ChatRequest.Message("system",
                "Translate spoken text from " + fromLang + " to " + toLang
                        + ". Return only the translation and preserve meaning and tone."));
        if (contextLines != null && !contextLines.isEmpty()) {
            messages.add(new ChatRequest.Message("system",
                    "Previous source-language context:\n" + String.join("\n", contextLines)));
        }
        messages.add(new ChatRequest.Message("user", text));
        var request = new ChatRequest(cfg.model(), List.copyOf(messages), cfg.temperature(),
                cfg.maxTokens(), null);
        return invoke(translationClient, request, cfg.maxRetries());
    }

    public String summarize(String systemPrompt, String transcript) {
        var cfg = properties.summary();
        var request = new ChatRequest(cfg.model(), List.of(
                new ChatRequest.Message("system", systemPrompt),
                new ChatRequest.Message("user", transcript)
        ), cfg.temperature(), cfg.maxTokens(), new ChatRequest.ResponseFormat("json_object"));
        return invoke(summaryClient, request, cfg.maxRetries());
    }

    public String generateTitle(String summaryContent) {
        var cfg = properties.title();
        var request = new ChatRequest(cfg.model(), List.of(
                new ChatRequest.Message("system",
                        "Create one concise meeting title. Return only the title, without quotes or Markdown."),
                new ChatRequest.Message("user", summaryContent)
        ), cfg.temperature(), cfg.maxTokens(), null);
        return invoke(titleClient, request, cfg.maxRetries());
    }

    private String invoke(RestClient client, ChatRequest request, int maxRetries) {
        return OpenAiRetry.execute(maxRetries, () -> extractContent(client.post()
                .uri(chatCompletionsUrl)
                .body(request)
                .retrieve()
                .body(ChatResponse.class)));
    }

    private String extractContent(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst() == null
                || response.choices().getFirst().message() == null
                || response.choices().getFirst().message().content() == null) {
            throw new RestClientException("OpenAI returned a malformed chat completion response");
        }
        return response.choices().getFirst().message().content();
    }
}
