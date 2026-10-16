package app.realtimetranslator.silence;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.AudioChunkReceived;
import app.realtimetranslator.events.SessionClosed;
import app.realtimetranslator.events.SilenceDetected;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Detects silence from the absence of audio chunks, rather than inspecting PCM data. */
@Component
@RequiredArgsConstructor
public class SilenceDetector {

    static final String AUTO_SILENCE = "auto_silence";

    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties properties;
    private final Clock clock;
    private final Map<String, SilenceState> states = new ConcurrentHashMap<>();

    @EventListener
    public void onAudioChunk(AudioChunkReceived event) {
        Instant now = clock.instant();
        states.put(event.getSessionId(), new SilenceState(now, false));
    }

    @Scheduled(fixedDelayString = "${app.silence.check-interval-ms:1000}")
    public void checkForSilence() {
        Instant now = clock.instant();
        long threshold = properties.silence().thresholdSeconds();
        var detected = new ArrayList<DetectedSilence>();

        states.forEach((sessionId, ignored) -> states.computeIfPresent(sessionId, (id, state) -> {
            long elapsed = Math.max(0, Duration.between(state.lastAudio(), now).getSeconds());
            if (!state.triggered() && elapsed >= threshold) {
                detected.add(new DetectedSilence(id, elapsed));
                return new SilenceState(state.lastAudio(), true);
            }
            return state;
        }));

        detected.forEach(item -> eventPublisher.publishEvent(
                new SilenceDetected(this, item.sessionId(), AUTO_SILENCE, item.seconds())));
    }

    @EventListener
    public void onSessionClosed(SessionClosed event) {
        states.remove(event.getSessionId());
    }

    private record SilenceState(Instant lastAudio, boolean triggered) {}
    private record DetectedSilence(String sessionId, long seconds) {}
}
