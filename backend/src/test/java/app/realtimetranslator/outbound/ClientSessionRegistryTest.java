package app.realtimetranslator.outbound;

import app.realtimetranslator.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientSessionRegistryTest {
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private ClientSessionRegistry registry;

    @BeforeEach
    void setUp() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.websocket()).thenReturn(new AppProperties.Websocket("*", 2_097_152, 5_000, 524_288));
        when(socket.getId()).thenReturn("socket-1");
        lenient().when(socket.isOpen()).thenReturn(true);
        registry = new ClientSessionRegistry(JsonMapper.builder().build(), properties);
        assertTrue(registry.bind("session_01", socket));
    }

    @Test
    void concurrentSendsUseDecoratorAndNeverCallUnderlyingSocketConcurrently() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        doAnswer(invocation -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            try {
                Thread.sleep(10);
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(socket).sendMessage(any());

        int count = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < count; i++) {
                int sequence = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    registry.send("session_01", ServerMessages.ack(sequence, false));
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
        }

        verify(socket, times(count)).sendMessage(any(TextMessage.class));
        assertEquals(1, maximum.get());
    }

    @Test
    void serializesInstantAsIsoTimestamp() throws Exception {
        registry.send("session_01", new ServerMessages.Translation("TRANSLATION", "session_01", 4,
                "original", "translated", "ja", "vi", Instant.parse("2026-09-17T14:30:22.410Z")));

        var message = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(message.capture());
        assertTrue(message.getValue().getPayload().contains("\"timestamp\":\"2026-09-17T14:30:22.410Z\""));
    }

    @Test
    void sendsAfterUnbindCannotReachOldSocket() throws Exception {
        registry.unregister("session_01");
        registry.send("session_01", ServerMessages.ack(1, false));
        verify(socket, never()).sendMessage(any());
    }

    @Test
    void secondSocketCannotTakeAnActiveBinding() {
        WebSocketSession other = mock(WebSocketSession.class);
        when(other.getId()).thenReturn("socket-2");
        assertFalse(registry.bind("session_01", other));
        assertTrue(registry.isBoundTo("session_01", socket));
    }
}
