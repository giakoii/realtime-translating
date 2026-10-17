package app.realtimetranslator.outbound;

import java.time.Duration;

import app.realtimetranslator.config.AppProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Synchronous client for OpenAI audio transcription. */
@Component
public class WhisperClient {
    private static final String TRANSCRIPTIONS_PATH = "/audio/transcriptions";

    private final RestClient restClient;
    private final String transcriptionsUrl;
    private final String model;
    private final int maxRetries;

    public WhisperClient(AppProperties properties) {
        var openAi = properties.openai();
        var whisper = openAi.whisper();
        this.model = whisper.model();
        this.maxRetries = whisper.maxRetries();
        this.transcriptionsUrl = OpenAiHttp.endpoint(openAi.baseUrl(), TRANSCRIPTIONS_PATH);
        this.restClient = OpenAiHttp.newClient(openAi.baseUrl(), openAi.apiKey(),
                Duration.ofSeconds(whisper.timeoutSeconds()));
    }

    public WhisperResponse transcribe(byte[] pcmAudio, int sampleRate) {
        byte[] wavAudio = AudioConverter.addWavHeader(pcmAudio, sampleRate, 1, 16);
        return OpenAiRetry.execute(maxRetries, () -> doTranscribe(wavAudio));
    }

    private WhisperResponse doTranscribe(byte[] wavAudio) {
        var audioResource = new ByteArrayResource(wavAudio) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        };

        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType("audio/wav"));
        fileHeaders.setContentDispositionFormData("file", audioResource.getFilename());

        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new HttpEntity<>(audioResource, fileHeaders));
        body.add("model", model);
        body.add("response_format", "verbose_json");

        return restClient.post()
                .uri(transcriptionsUrl)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(WhisperResponse.class);
    }
}
