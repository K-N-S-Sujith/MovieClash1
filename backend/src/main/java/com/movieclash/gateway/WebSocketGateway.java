package com.movieclash.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.coordinator.GameCoordinator;
import com.movieclash.model.protocol.ErrorCode;
import com.movieclash.model.protocol.WsEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class WebSocketGateway extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WebSocketGateway.class);
    private static final int MAX_MESSAGE_SIZE = 64 * 1024; // 64KB max payload size

    private final WebSocketSessionTracker sessionTracker;
    private final RateLimiter rateLimiter;
    private final GameCoordinator gameCoordinator;
    private final ObjectMapper objectMapper;

    public WebSocketGateway(WebSocketSessionTracker sessionTracker,
                            RateLimiter rateLimiter,
                            GameCoordinator gameCoordinator,
                            ObjectMapper objectMapper) {
        this.sessionTracker = sessionTracker;
        this.rateLimiter = rateLimiter;
        this.gameCoordinator = gameCoordinator;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connected: session [{}] from {}", session.getId(), session.getRemoteAddress());
        session.setTextMessageSizeLimit(MAX_MESSAGE_SIZE);
        sessionTracker.registerSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!rateLimiter.tryAcquire(session.getId())) {
            log.warn("Rate limit exceeded for session [{}]", session.getId());
            sendError(session, ErrorCode.RATE_LIMIT_EXCEEDED, "Message rate limit exceeded. Please slow down.");
            return;
        }

        String payload = message.getPayload();
        try {
            WsEnvelope envelope = objectMapper.readValue(payload, WsEnvelope.class);
            gameCoordinator.dispatch(session, envelope);
        } catch (Exception e) {
            log.warn("Invalid WebSocket message from session [{}]: {}", session.getId(), e.getMessage());
            sendError(session, ErrorCode.INVALID_PAYLOAD, "Failed to parse message envelope JSON");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket closed: session [{}] with status {}", session.getId(), status);
        rateLimiter.removeSession(session.getId());
        gameCoordinator.handleDisconnect(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error on session [{}]: {}", session.getId(), exception.getMessage());
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {}
    }

    private void sendError(WebSocketSession session, ErrorCode code, String message) {
        try {
            if (session.isOpen()) {
                WsEnvelope errorEnvelope = WsEnvelope.error(null, code, message);
                String json = objectMapper.writeValueAsString(errorEnvelope);
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send error to session [{}]: {}", session.getId(), e.getMessage());
        }
    }
}

