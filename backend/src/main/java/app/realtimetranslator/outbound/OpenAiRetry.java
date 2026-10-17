package app.realtimetranslator.outbound;

import java.io.IOException;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Retry policy shared by OpenAI clients. */
@Slf4j
public final class OpenAiRetry {
    private OpenAiRetry() {}

    public static <T> T execute(int maxRetries, Supplier<T> operation) {
        RestClientException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return operation.get();
            } catch (RestClientException failure) {
                lastFailure = failure;
                if (!isRetryable(failure) || attempt == maxRetries) {
                    throw failure;
                }
                long backoffMillis = 250L << Math.min(attempt, 10);
                log.warn("[OPENAI] Provider call failed; retrying attempt {}/{} after {} ms",
                        attempt + 1, maxRetries, backoffMillis);
                sleep(backoffMillis, failure);
            }
        }
        throw lastFailure;
    }

    public static boolean isRetryable(RestClientException failure) {
        if (failure instanceof RestClientResponseException responseFailure) {
            int status = responseFailure.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        if (failure instanceof ResourceAccessException) {
            return true;
        }
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis, RestClientException originalFailure) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Interrupted during provider retry backoff", originalFailure);
        }
    }
}
