package com.movieclash.config;

import com.movieclash.gateway.WebSocketGateway;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketGateway webSocketGateway;

    public WebSocketConfig(WebSocketGateway webSocketGateway) {
        this.webSocketGateway = webSocketGateway;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketGateway, "/ws/game")
                .setAllowedOriginPatterns("*");
    }
}

