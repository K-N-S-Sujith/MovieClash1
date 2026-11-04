package com.movieclash.manager;

import com.movieclash.gateway.WebSocketSessionTracker;
import com.movieclash.model.Movie;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.dto.PlayerDTO;
import com.movieclash.model.dto.RoomSnapshotDTO;
import com.movieclash.model.dto.TeamDTO;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import com.movieclash.model.protocol.WsEnvelope;
import com.movieclash.service.RoomBroadcaster;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class ReconnectionManager {
    private static final Logger log = LoggerFactory.getLogger(ReconnectionManager.class);

    private final RoomManager roomManager;
    private final PlayerSessionManager playerSessionManager;
    private final WebSocketSessionTracker sessionTracker;
    private final RoomBroadcaster broadcaster;
    private final RoundManager roundManager;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // roomId -> pending clue-giver disconnect timeout task
    private final Map<String, ScheduledFuture<?>> clueGiverTimeoutFutures = new ConcurrentHashMap<>();

    public ReconnectionManager(RoomManager roomManager,
                                 PlayerSessionManager playerSessionManager,
                                 WebSocketSessionTracker sessionTracker,
                                 RoomBroadcaster broadcaster,
                                 @Lazy RoundManager roundManager) {
        this.roomManager = roomManager;
        this.playerSessionManager = playerSessionManager;
        this.sessionTracker = sessionTracker;
        this.broadcaster = broadcaster;
        this.roundManager = roundManager;
    }

    public void handlePlayerDisconnect(String roomId, String playerId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            Player player = room.getPlayer(playerId);
            if (player == null) return;

            player.setConnected(false);
            player.setDisconnectedAt(System.currentTimeMillis());
            log.info("Player [{}] ({}) disconnected from room [{}]. Grace window active.",
                    player.getName(), playerId, roomId);

            // Broadcast PLAYER_DISCONNECTED to remaining room members
            broadcaster.broadcastToRoom(roomId, WsEnvelope.of("PLAYER_DISCONNECTED", roomId, Map.of(
                "playerId", playerId,
                "playerName", player.getName(),
                "gracePeriodMs", room.getConfig().getReconnectGracePeriodMs()
            )));

            // If clue-giver disconnected mid-round, pause the round and wait until they reconnect (do NOT skip turn automatically)
            if (playerId.equals(room.getCurrentClueGiverId()) && room.getStatus() == RoomStatus.IN_PROGRESS) {
                log.warn("Active clue-giver [{}] disconnected in room [{}]. Pausing round until reconnection.",
                        player.getName(), roomId);
                cancelClueGiverTimeout(roomId);
                roundManager.pauseRound(roomId);
            }
        });
    }

    public void handleReconnect(WebSocketSession session, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        String rawRoomId = (String) payload.get("roomId");
        final String roomId = rawRoomId != null ? rawRoomId : envelope.getRoomId();
        String playerId = (String) payload.get("playerId");
        String reconnectToken = (String) payload.get("reconnectToken");

        if (roomId == null || playerId == null || reconnectToken == null) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.INVALID_PAYLOAD,
                    "roomId, playerId, and reconnectToken are required for RECONNECT"));
            return;
        }

        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.ROOM_NOT_FOUND,
                    "Room not found or expired"));
            return;
        }

        room.executeLocked(() -> {
            Player player = room.getPlayer(playerId);
            if (player == null) {
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.RECONNECT_FAILED,
                        "Player not found in room"));
                return;
            }

            // Verify secure reconnect token
            if (!reconnectToken.equals(player.getReconnectToken())) {
                log.warn("Reconnect rejected: token mismatch for player [{}] in room [{}]", playerId, roomId);
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED,
                        "Invalid reconnect token"));
                return;
            }

            // Check grace window
            long now = System.currentTimeMillis();
            long gracePeriod = room.getConfig().getReconnectGracePeriodMs();
            if (!player.isConnected() && (now - player.getDisconnectedAt()) > gracePeriod) {
                log.warn("Reconnect rejected: grace period expired for player [{}] in room [{}]", playerId, roomId);
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.EXPIRED_SESSION,
                        "Reconnect grace period expired"));
                return;
            }

            // Successfully reconnected
            player.setConnected(true);
            player.setWebSocketSessionId(session.getId());
            playerSessionManager.bindSession(session.getId(), playerId);
            sessionTracker.bindPlayer(session.getId(), playerId, roomId);

            // If reconnecting player is the original creator/host, restore host privileges
            boolean isOriginalCreator = (player.getUserId() != null && player.getUserId().equals(room.getCreatorUserId()))
                    || playerId.equals(room.getOriginalHostPlayerId());

            if (isOriginalCreator && !playerId.equals(room.getHostId())) {
                log.info("Original host [{}] ({}) reconnected to room [{}] and reclaimed primary host status",
                        player.getName(), playerId, roomId);
                room.setHostId(playerId);
                player.setHost(true);
                if (playerId.equals(room.getCoHostId())) {
                    room.setCoHostId(null);
                }
                broadcaster.broadcastToRoom(roomId, WsEnvelope.of("HOST_RESTORED", roomId, Map.of(
                    "hostId", playerId,
                    "hostName", player.getName(),
                    "coHostId", room.getCoHostId() != null ? room.getCoHostId() : ""
                )));
            }

            // If clue-giver reconnected, cancel timeout and resume round if paused
            if (playerId.equals(room.getCurrentClueGiverId())) {
                cancelClueGiverTimeout(roomId);
                if (room.isRoundPaused()) {
                    roundManager.resumeRound(roomId);
                }
                log.info("Clue-giver [{}] reconnected in room [{}].", player.getName(), roomId);
            }

            log.info("Player [{}] ({}) successfully reconnected to room [{}]", player.getName(), playerId, roomId);

            // Build full restoration state for the reconnected player
            List<PlayerDTO> scoreboard = room.getPlayers().values().stream()
                    .map(PlayerDTO::from)
                    .sorted(Comparator.comparingInt(PlayerDTO::score).reversed())
                    .toList();

            Map<String, TeamDTO> teamsMap = room.getTeams().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> TeamDTO.from(e.getValue())));

            Player clueGiver = room.getCurrentClueGiverId() != null ? room.getPlayer(room.getCurrentClueGiverId()) : null;

            Map<String, Object> restorePayload = new HashMap<>();
            restorePayload.put("yourPlayerId", playerId);
            restorePayload.put("player", PlayerDTO.from(player));
            restorePayload.put("room", RoomSnapshotDTO.from(room));
            restorePayload.put("status", room.getStatus().name());
            restorePayload.put("gameMode", room.getGameMode().name());
            restorePayload.put("currentRound", room.getCurrentRound());
            restorePayload.put("totalRounds", room.getTotalRounds());
            restorePayload.put("clueGiverId", room.getCurrentClueGiverId());
            restorePayload.put("clueGiverName", clueGiver != null ? clueGiver.getName() : "");
            restorePayload.put("clueGiverTeam", room.getCurrentClueGiverTeam() != null ? room.getCurrentClueGiverTeam() : "");
            restorePayload.put("serverTime", now);
            restorePayload.put("deadline", room.getRoundDeadline());
            restorePayload.put("roundDurationMs", room.getConfig().getRoundDurationSec() * 1000L);
            restorePayload.put("scoreboard", scoreboard);
            restorePayload.put("teams", teamsMap);
            restorePayload.put("recentChat", new ArrayList<>(room.getRecentChat()));

            broadcaster.sendToSessionDirect(session, WsEnvelope.of("PLAYER_RECONNECTED", roomId, restorePayload));

            // Resend secret movie privately to clue-giver (or clue-team member in Team mode) if reconnected mid-round
            boolean isClueTeamMember = (room.getGameMode() == com.movieclash.model.enums.GameMode.TEAM && player.getTeamId() != null
                    && player.getTeamId().equals(room.getCurrentClueGiverTeam()));
            if (room.getStatus() == RoomStatus.IN_PROGRESS && (playerId.equals(room.getCurrentClueGiverId()) || isClueTeamMember)) {
                Movie movie = room.getCurrentMovie();
                if (movie != null) {
                    Map<String, Object> answerPayload = Map.of(
                        "movieId", movie.getId(),
                        "title", movie.getTitle(),
                        "year", movie.getYear(),
                        "aliases", movie.getAliases(),
                        "hint", movie.getHint() != null ? movie.getHint() : ""
                    );
                    log.info("Resending private CLUE_GIVER_ANSWER to reconnected clue team member [{}]", player.getName());
                    broadcaster.sendPrivate(playerId, WsEnvelope.of("CLUE_GIVER_ANSWER", roomId, answerPayload));
                }
            }

            // Broadcast PLAYER_RECONNECTED delta to other players
            Map<String, Object> bcPayload = Map.of(
                "player", PlayerDTO.from(player),
                "room", RoomSnapshotDTO.from(room)
            );
            broadcaster.broadcastToRoomExcept(roomId, playerId, WsEnvelope.of("PLAYER_RECONNECTED", roomId, bcPayload));
        });
    }

    private void handleClueGiverTimeout(String roomId, String clueGiverId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            Player clueGiver = room.getPlayer(clueGiverId);
            if (clueGiver != null && !clueGiver.isConnected() && room.getStatus() == RoomStatus.IN_PROGRESS) {
                log.warn("Clue-giver [{}] timed out during disconnect in room [{}]. Skipping turn.",
                        clueGiver.getName(), roomId);
                roundManager.finishRound(roomId, "CLUE_GIVER_TIMEOUT");
            }
        });
    }

    public void cancelClueGiverTimeout(String roomId) {
        ScheduledFuture<?> future = clueGiverTimeoutFutures.remove(roomId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    @Scheduled(fixedRate = 30000)
    public void cleanupStaleDisconnectedPlayers() {
        long now = System.currentTimeMillis();

        for (Room room : roomManager.getAllRooms().values()) {
            room.executeLocked(() -> {
                long gracePeriod = room.getConfig().getReconnectGracePeriodMs();

                List<String> toRemove = new ArrayList<>();
                for (Player p : room.getPlayers().values()) {
                    if (!p.isConnected() && (now - p.getDisconnectedAt()) > gracePeriod) {
                        toRemove.add(p.getId());
                    }
                }

                for (String pId : toRemove) {
                    Player p = room.getPlayer(pId);
                    String name = p != null ? p.getName() : "Unknown";
                    log.info("Removing player [{}] ({}) from room [{}]: grace period expired.", name, pId, room.getRoomId());
                    RoomManager.LeaveRoomResult res = roomManager.leaveRoom(room.getRoomId(), pId);

                    if (!res.roomDeleted()) {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("playerId", pId);
                        payload.put("playerName", name);
                        if (res.newHostId() != null) {
                            payload.put("newHostId", res.newHostId());
                        }
                        broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("PLAYER_LEFT", room.getRoomId(), payload));
                    }
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
