package app.realtimetranslator.outbound;

import app.realtimetranslator.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;

/** All sends, including errors and ACKs, share the same socket decorator. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientSessionRegistry {
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ConcurrentHashMap<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> bindings = new ConcurrentHashMap<>();
    public void register(WebSocketSession socket) {
        sockets.computeIfAbsent(socket.getId(), ignored -> new ConcurrentWebSocketSessionDecorator(socket,
                properties.websocket().sendTimeLimitMs(), properties.websocket().sendBufferLimitBytes()));
    }
    public boolean bind(String sessionId, WebSocketSession socket) {
        register(socket);
        String previous = bindings.putIfAbsent(sessionId, socket.getId());
        return previous == null || previous.equals(socket.getId());
    }
    public boolean isBoundTo(String sessionId, WebSocketSession socket) {
        return socket.getId().equals(bindings.get(sessionId));
    }
    public void unregister(String sessionId) {
        String socketId = bindings.remove(sessionId);
        if (socketId != null) sockets.remove(socketId);
    }
    public void removeUnbound(WebSocketSession socket) {
        if (!bindings.containsValue(socket.getId())) sockets.remove(socket.getId());
    }
    public void send(String sessionId, Object message) {
        String socketId = bindings.get(sessionId);
        if (socketId != null) sendSocket(sockets.get(socketId), message);
    }
    public void send(WebSocketSession socket, Object message) {
        register(socket);
        sendSocket(sockets.get(socket.getId()), message);
    }
    private void sendSocket(WebSocketSession socket, Object message) {
        if (socket == null || !socket.isOpen()) return;
        try {
            socket.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (Exception failure) {
            log.warn("[WS001] Outbound send failed: socket={}, cause={}", socket.getId(),
                    failure.getClass().getSimpleName());
        }
    }
}
