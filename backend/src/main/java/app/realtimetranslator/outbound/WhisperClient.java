package app.realtimetranslator.outbound;

import app.realtimetranslator.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends audio to OpenAI Whisper API and returns the transcription.
 * Uses Spring's {@link RestClient} (synchronous) with simple retry logic.
 */
@Slf4j
@Component
public class WhisperClient {

    private static final String WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";

    private final RestClient restClient;
    private final String model;
    private final int maxRetries;

    public WhisperClient(AppProperties props, RestClient.Builder restClientBuilder) {
        var whisperCfg = props.openai().whisper();
        this.model = whisperCfg.model();
        this.maxRetries = whisperCfg.maxRetries();

        this.restClient = restClientBuilder
                .defaultHeader("Authorization", "Bearer " + props.openai().apiKey())
                .build();
    }

    /**
     * Transcribes raw PCM audio via Whisper. Converts PCM → WAV internally
     * because Whisper requires a recognized audio container format.
     *
     * @return transcription result with text and detected language
     * @throws RestClientException if all retries are exhausted
     */
    public WhisperResponse transcribe(byte[] pcmAudio, int sampleRate) {
        byte[] wavAudio = AudioConverter.addWavHeader(pcmAudio, sampleRate, 1, 16);

        RestClientException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    long backoffMs = (long) Math.pow(2, attempt) * 500;
                    log.warn("[STT001] Whisper retry {}/{} after {}ms", attempt, maxRetries, backoffMs);
                    Thread.sleep(backoffMs);
                }

                return doTranscribe(wavAudio);

            } catch (RestClientException e) {
                lastException = e;
                log.warn("[STT001] Whisper call failed (attempt {}): {}", attempt + 1, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RestClientException("Interrupted during retry backoff", e);
            }
        }

        throw lastException;
    }

    private WhisperResponse doTranscribe(byte[] wavAudio) {
        var audioResource = new ByteArrayResource(wavAudio) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        };

        var bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", audioResource)
                .contentType(MediaType.parseMediaType("audio/wav"));
        bodyBuilder.part("model", model);
        bodyBuilder.part("response_format", "verbose_json");

        return restClient.post()
                .uri(WHISPER_URL)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(bodyBuilder.build())
                .retrieve()
                .body(WhisperResponse.class);
    }
}
