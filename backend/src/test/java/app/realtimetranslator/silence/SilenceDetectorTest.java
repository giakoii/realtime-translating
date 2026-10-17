package app.realtimetranslator.silence;

import app.realtimetranslator.config.AppProperties;
import app.realtimetranslator.events.AudioChunkReceived;
import app.realtimetranslator.events.SessionClosed;
import app.realtimetranslator.events.SilenceDetected;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SilenceDetectorTest {

    @Test
    void triggersOnceAndRearmsAfterNewAudio() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AppProperties properties = mock(AppProperties.class);
        when(properties.silence()).thenReturn(new AppProperties.Silence(30, 1_000));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SilenceDetector detector = new SilenceDetector(publisher, properties, clock);
        AudioChunkReceived audio = mock(AudioChunkReceived.class);
        when(audio.getSessionId()).thenReturn("session01");

        detector.onAudioChunk(audio);
        clock.advanceSeconds(29);
        detector.checkForSilence();
        verifyNoInteractions(publisher);

        clock.advanceSeconds(1);
        detector.checkForSilence();
        detector.checkForSilence();
        verify(publisher, times(1)).publishEvent(argThat(event ->
                event instanceof SilenceDetected silence
                        && silence.getSessionId().equals("session01")
                        && silence.getSilenceSeconds() == 30));

        clock.advanceSeconds(5);
        detector.onAudioChunk(audio);
        clock.advanceSeconds(30);
        detector.checkForSilence();
        verify(publisher, times(2)).publishEvent(any(SilenceDetected.class));
        assertEquals(6, clock.calls, "Clock must be sampled once per handler/check invocation");
    }

    @Test
    void closeRemovesDetectionState() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AppProperties properties = mock(AppProperties.class);
        when(properties.silence()).thenReturn(new AppProperties.Silence(30, 1_000));
        MutableClock clock = new MutableClock(Instant.EPOCH);
        SilenceDetector detector = new SilenceDetector(publisher, properties, clock);
        AudioChunkReceived audio = mock(AudioChunkReceived.class);
        when(audio.getSessionId()).thenReturn("session01");

        detector.onAudioChunk(audio);
        detector.onSessionClosed(new SessionClosed(this, "session01"));
        clock.advanceSeconds(60);
        detector.checkForSilence();

        verifyNoInteractions(publisher);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private int calls;

        private MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { calls++; return instant; }
    }
}
