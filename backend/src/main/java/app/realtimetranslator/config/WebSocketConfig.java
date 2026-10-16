package app.realtimetranslator.config;

import app.realtimetranslator.inbound.AudioWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import java.util.Arrays;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers WebSocket endpoints for audio streaming.
 * Using raw WebSocket (not STOMP) because audio chunks are binary blobs
 * that don't need pub/sub routing — just a direct client-server pipe.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioWebSocketHandler audioWebSocketHandler;
    private final AppProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(audioWebSocketHandler, "/ws/audio")
                .setAllowedOrigins(Arrays.stream(properties.websocket().allowedOrigins().split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.websocket().maxMessageBytes());
        return container;
    }
}
