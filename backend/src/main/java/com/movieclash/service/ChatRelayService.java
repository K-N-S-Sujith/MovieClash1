package com.movieclash.service;

import com.movieclash.manager.RoomManager;
import com.movieclash.model.ChatMessage;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import com.movieclash.model.protocol.WsEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatRelayService {
    private static final Logger log = LoggerFactory.getLogger(ChatRelayService.class);
    private static final int MAX_MESSAGE_LENGTH = 200;
    private static final long RATE_LIMIT_WINDOW_MS = 1000L; // 1 msg/sec per player

    private final RoomManager roomManager;
    private final RoomBroadcaster broadcaster;
    // playerId -> last message timestamp in ms
    private final Map<String, Long> lastChatTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Long> lastClueTimestamps = new ConcurrentHashMap<>();

    public ChatRelayService(RoomManager roomManager, RoomBroadcaster broadcaster) {
        this.roomManager = roomManager;
        this.broadcaster = broadcaster;
    }

    public void handleClueMessage(String roomId, String playerId, String messageText) {
        if (roomId == null || playerId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            // 1. Verify round is in progress
            if (room.getStatus() != RoomStatus.IN_PROGRESS) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Round is not active"));
                return;
            }

            // 2. Verify sender is the active clue-giver or a member of the active clue team
            boolean isClueTeamMember = (room.getGameMode() == com.movieclash.model.enums.GameMode.TEAM
                    && room.getCurrentClueGiverTeam() != null
                    && room.getPlayer(playerId) != null
                    && room.getCurrentClueGiverTeam().equals(room.getPlayer(playerId).getTeamId()));

            if (!playerId.equals(room.getCurrentClueGiverId()) && !isClueTeamMember) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.NOT_CLUE_GIVER, "Only the active clue team can post clues"));
                return;
            }

            // 3. Rate limiting (1 msg/sec)
            if (!checkClueRateLimit(playerId)) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.RATE_LIMIT_EXCEEDED, "Rate limit: 1 clue per second"));
                return;
            }

            // 4. Sanitize and validate length
            String sanitized = sanitizeMessage(messageText);
            if (sanitized.isEmpty()) return;

            long now = System.currentTimeMillis();
            Map<String, Object> cluePayload = Map.of(
                "clueGiverId", playerId,
                "message", sanitized,
                "timestamp", now
            );

            log.info("Clue posted in room [{}]: \"{}\"", roomId, sanitized);
            broadcaster.broadcastToRoom(roomId, WsEnvelope.of("CLUE_BROADCAST", roomId, cluePayload));
        });
    }

    public void handleChatMessage(String roomId, String playerId, String messageText) {
        if (roomId == null || playerId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            Player sender = room.getPlayer(playerId);
            if (sender == null) return;

            // 1. Block guessers from chatting during an active round (prevents answer leaks)
            if (room.getStatus() == RoomStatus.IN_PROGRESS) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED,
                        "Chat is disabled for guessers during an active round to prevent leaks. Submit guesses via the guess box!"));
                return;
            }

            // 2. Rate limiting (1 msg/sec)
            if (!checkChatRateLimit(playerId)) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.RATE_LIMIT_EXCEEDED,
                        "Chat rate limit: 1 message per second"));
                return;
            }

            // 3. Sanitize and validate length
            String sanitized = sanitizeMessage(messageText);
            if (sanitized.isEmpty()) return;

            long now = System.currentTimeMillis();
            ChatMessage chatMsg = new ChatMessage(playerId, sender.getName(), sanitized, now);

            // Store in rolling 50-message buffer for reconnecting players
            room.addChatMessage(chatMsg);

            Map<String, Object> chatPayload = Map.of(
                "senderId", playerId,
                "senderName", sender.getName(),
                "message", sanitized,
                "timestamp", now
            );

            log.info("Chat in room [{}] by [{}]: \"{}\"", roomId, sender.getName(), sanitized);
            broadcaster.broadcastToRoom(roomId, WsEnvelope.of("CHAT_BROADCAST", roomId, chatPayload));
        });
    }

    private boolean checkClueRateLimit(String playerId) {
        long now = System.currentTimeMillis();
        Long lastTime = lastClueTimestamps.get(playerId);
        if (lastTime != null && (now - lastTime) < RATE_LIMIT_WINDOW_MS) {
            return false;
        }
        lastClueTimestamps.put(playerId, now);
        return true;
    }

    private boolean checkChatRateLimit(String playerId) {
        long now = System.currentTimeMillis();
        Long lastTime = lastChatTimestamps.get(playerId);
        if (lastTime != null && (now - lastTime) < RATE_LIMIT_WINDOW_MS) {
            return false;
        }
        lastChatTimestamps.put(playerId, now);
        return true;
    }

    public String sanitizeMessage(String input) {
        if (input == null) return "";
        // Strip HTML tags and newlines
        String cleaned = input.replaceAll("<[^>]*>", "").replaceAll("[\\r\\n\\t]", " ").trim();
        if (cleaned.length() > MAX_MESSAGE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_MESSAGE_LENGTH).trim();
        }
        return cleaned;
    }
}
