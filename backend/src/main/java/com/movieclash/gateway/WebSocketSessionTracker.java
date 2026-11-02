package com.movieclash.gateway;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionTracker {
    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    // sessionId -> roomId
    private final Map<String, String> sessionRoomMap = new ConcurrentHashMap<>();
    // sessionId -> playerId
    private final Map<String, String> sessionPlayerMap = new ConcurrentHashMap<>();
    // playerId -> sessionId
    private final Map<String, String> playerSessionMap = new ConcurrentHashMap<>();

    public void registerSession(WebSocketSession session) {
        activeSessions.put(session.getId(), session);
    }

    public void bindPlayer(String sessionId, String playerId, String roomId) {
        if (sessionId != null && playerId != null) {
            sessionPlayerMap.put(sessionId, playerId);
            playerSessionMap.put(playerId, sessionId);
        }
        if (sessionId != null && roomId != null) {
            sessionRoomMap.put(sessionId, roomId);
        }
    }

    public void removeSession(String sessionId) {
        if (sessionId == null) return;
        activeSessions.remove(sessionId);
        sessionRoomMap.remove(sessionId);
        String playerId = sessionPlayerMap.remove(sessionId);
        if (playerId != null) {
            playerSessionMap.remove(playerId);
        }
    }

    public WebSocketSession getSession(String sessionId) {
        return sessionId != null ? activeSessions.get(sessionId) : null;
    }

    public WebSocketSession getSessionByPlayer(String playerId) {
        String sessionId = playerId != null ? playerSessionMap.get(playerId) : null;
        return sessionId != null ? activeSessions.get(sessionId) : null;
    }

    public String getPlayerId(String sessionId) {
        return sessionId != null ? sessionPlayerMap.get(sessionId) : null;
    }

    public String getRoomId(String sessionId) {
        return sessionId != null ? sessionRoomMap.get(sessionId) : null;
    }
}

