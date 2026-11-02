package com.movieclash.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.gateway.WebSocketSessionTracker;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.protocol.WsEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

@Service
public class RoomBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(RoomBroadcaster.class);
    private final WebSocketSessionTracker sessionTracker;
    private final RoomManager roomManager;
    private final ObjectMapper objectMapper;

    public RoomBroadcaster(WebSocketSessionTracker sessionTracker, RoomManager roomManager, ObjectMapper objectMapper) {
        this.sessionTracker = sessionTracker;
        this.roomManager = roomManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Broadcasts an envelope to all connected players in a room
     */
    public void broadcastToRoom(String roomId, WsEnvelope envelope) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        TextMessage message = createTextMessage(envelope);
        if (message == null) return;

        for (Player player : room.getPlayers().values()) {
            if (player.isConnected()) {
                WebSocketSession session = sessionTracker.getSessionByPlayer(player.getId());
                sendToSession(session, message);
            }
        }
    }

    /**
     * Broadcasts to all players in a room except the specified player
     */
    public void broadcastToRoomExcept(String roomId, String excludedPlayerId, WsEnvelope envelope) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        TextMessage message = createTextMessage(envelope);
        if (message == null) return;

        for (Player player : room.getPlayers().values()) {
            if (player.isConnected() && !player.getId().equals(excludedPlayerId)) {
                WebSocketSession session = sessionTracker.getSessionByPlayer(player.getId());
                sendToSession(session, message);
            }
        }
    }

    /**
     * Sends a private envelope strictly to a specific player's session
     */
    public void sendPrivate(String playerId, WsEnvelope envelope) {
        WebSocketSession session = sessionTracker.getSessionByPlayer(playerId);
        if (session != null && session.isOpen()) {
            TextMessage message = createTextMessage(envelope);
            if (message != null) {
                sendToSession(session, message);
            }
        }
    }

    /**
     * Sends directly to a WebSocketSession (e.g. for PONG or handshake errors)
     */
    public void sendToSessionDirect(WebSocketSession session, WsEnvelope envelope) {
        if (session != null && session.isOpen()) {
            TextMessage message = createTextMessage(envelope);
            if (message != null) {
                sendToSession(session, message);
            }
        }
    }

    private void sendToSession(WebSocketSession session, TextMessage message) {
        if (session == null || !session.isOpen()) return;
        try {
            // Spring WebSocketSession is not thread-safe for concurrent writes; synchronize on session instance
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Session [{}] closed or unavailable during send: {}", session.getId(), e.getMessage());
        }
    }

    private TextMessage createTextMessage(WsEnvelope envelope) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.error("Failed to serialize WsEnvelope [{}]: {}", envelope.getType(), e.getMessage());
            return null;
        }
    }
}

