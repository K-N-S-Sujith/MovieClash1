package com.movieclash.manager;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlayerSessionManager {
    private final SecureRandom secureRandom = new SecureRandom();
    // Maps webSocketSessionId -> playerId
    private final Map<String, String> sessionToPlayer = new ConcurrentHashMap<>();
    // Maps playerId -> webSocketSessionId
    private final Map<String, String> playerToSession = new ConcurrentHashMap<>();

    public String generateReconnectToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public void bindSession(String sessionId, String playerId) {
        if (sessionId != null && playerId != null) {
            sessionToPlayer.put(sessionId, playerId);
            playerToSession.put(playerId, sessionId);
        }
    }

    public void unbindSession(String sessionId) {
        if (sessionId != null) {
            String playerId = sessionToPlayer.remove(sessionId);
            if (playerId != null) {
                playerToSession.remove(playerId);
            }
        }
    }

    public String getPlayerIdBySession(String sessionId) {
        return sessionId != null ? sessionToPlayer.get(sessionId) : null;
    }

    public String getSessionIdByPlayer(String playerId) {
        return playerId != null ? playerToSession.get(playerId) : null;
    }
}

