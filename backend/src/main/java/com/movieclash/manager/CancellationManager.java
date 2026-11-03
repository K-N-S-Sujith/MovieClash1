package com.movieclash.manager;

import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import com.movieclash.model.protocol.WsEnvelope;
import com.movieclash.service.RoomBroadcaster;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class CancellationManager {
    private static final Logger log = LoggerFactory.getLogger(CancellationManager.class);

    private final RoomManager roomManager;
    private final RoomBroadcaster broadcaster;
    private final RoundManager roundManager;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // roomId -> active CancelVoteSession
    private final Map<String, CancelVoteSession> activeVotes = new ConcurrentHashMap<>();

    public CancellationManager(RoomManager roomManager,
                               RoomBroadcaster broadcaster,
                               @Lazy RoundManager roundManager) {
        this.roomManager = roomManager;
        this.broadcaster = broadcaster;
        this.roundManager = roundManager;
    }

    public static class CancelVoteSession {
        private final String roomId;
        private final String proposerId;
        private final String proposerName;
        private final Set<String> eligiblePlayerIds;
        private final Map<String, Boolean> votes = new ConcurrentHashMap<>();
        private ScheduledFuture<?> timeoutFuture;

        public CancelVoteSession(String roomId, String proposerId, String proposerName, Set<String> eligiblePlayerIds) {
            this.roomId = roomId;
            this.proposerId = proposerId;
            this.proposerName = proposerName;
            this.eligiblePlayerIds = eligiblePlayerIds;
        }

        public String getRoomId() { return roomId; }
        public String getProposerId() { return proposerId; }
        public String getProposerName() { return proposerName; }
        public Set<String> getEligiblePlayerIds() { return eligiblePlayerIds; }
        public Map<String, Boolean> getVotes() { return votes; }

        public void setTimeoutFuture(ScheduledFuture<?> future) {
            this.timeoutFuture = future;
        }

        public void cancelTimer() {
            if (timeoutFuture != null && !timeoutFuture.isDone()) {
                timeoutFuture.cancel(false);
            }
        }
    }

    public void handleCancelPropose(String roomId, String playerId) {
        if (roomId == null || playerId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.IN_PROGRESS) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Game is not in progress"));
                return;
            }

            if (!playerId.equals(room.getHostId())) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.NOT_HOST, "Only the room host can propose cancelling the game"));
                return;
            }

            if (activeVotes.containsKey(roomId)) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "A cancellation vote is already in progress"));
                return;
            }

            Player host = room.getPlayer(playerId);
            if (host == null) return;

            if (host.isSpectator()) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Spectators cannot propose game cancellation"));
                return;
            }

            Set<String> eligibleActivePlayers = room.getPlayers().values().stream()
                    .filter(p -> p.isConnected() && !p.isSpectator())
                    .map(Player::getId)
                    .collect(Collectors.toSet());

            // If host is the only connected active player, cancel immediately
            if (eligibleActivePlayers.size() <= 1) {
                log.info("Host [{}] is solo active player. Cancelling game immediately in room [{}]", host.getName(), roomId);
                roundManager.cancelGame(room);
                broadcaster.broadcastToRoom(roomId, WsEnvelope.of("GAME_CANCELLED", roomId, Map.of(
                    "reason", "Host cancelled game (no other active players)"
                )));
                return;
            }

            long timeoutMs = room.getConfig().getCancelVoteTimeoutMs();
            CancelVoteSession session = new CancelVoteSession(roomId, playerId, host.getName(), eligibleActivePlayers);
            // Proposer automatically votes YES
            session.getVotes().put(playerId, true);

            ScheduledFuture<?> timer = scheduler.schedule(() -> {
                handleVoteTimeout(roomId);
            }, timeoutMs, TimeUnit.MILLISECONDS);

            session.setTimeoutFuture(timer);
            activeVotes.put(roomId, session);

            log.info("Host [{}] proposed game cancellation in room [{}]. Timeout: {}ms, eligible active voters: {}",
                    host.getName(), roomId, timeoutMs, eligibleActivePlayers.size());

            Map<String, Object> requestedPayload = Map.of(
                "proposerId", playerId,
                "proposerName", host.getName(),
                "timeoutMs", timeoutMs,
                "totalEligible", eligibleActivePlayers.size()
            );

            // Send vote request strictly to eligible active players, NOT spectators
            for (String activePlayerId : eligibleActivePlayers) {
                broadcaster.sendPrivate(activePlayerId, WsEnvelope.of("CANCEL_VOTE_REQUESTED", roomId, requestedPayload));
            }
        });
    }

    public void handleCancelVote(String roomId, String playerId, boolean vote) {
        if (roomId == null || playerId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            CancelVoteSession session = activeVotes.get(roomId);
            if (session == null) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "No cancellation vote is currently active"));
                return;
            }

            if (!session.getEligiblePlayerIds().contains(playerId)) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "You are not eligible to vote in this cancellation"));
                return;
            }

            if (session.getVotes().containsKey(playerId)) {
                broadcaster.sendPrivate(playerId, WsEnvelope.error(roomId, ErrorCode.ALREADY_VOTED, "You have already cast your vote"));
                return;
            }

            session.getVotes().put(playerId, vote);
            log.info("Player [{}] voted [{}] for cancellation in room [{}]", playerId, vote ? "YES" : "NO", roomId);

            evaluateVoteOutcome(room, session);
        });
    }

    private void evaluateVoteOutcome(Room room, CancelVoteSession session) {
        int totalEligible = session.getEligiblePlayerIds().size();
        int majorityThreshold = (totalEligible / 2) + 1;

        long yesVotes = session.getVotes().values().stream().filter(v -> v).count();
        long noVotes = session.getVotes().values().stream().filter(v -> !v).count();

        // 1. Majority YES approved
        if (yesVotes >= majorityThreshold) {
            log.info("Cancellation APPROVED in room [{}] ({} YES / {} total)", room.getRoomId(), yesVotes, totalEligible);
            session.cancelTimer();
            activeVotes.remove(room.getRoomId());

            roundManager.cancelGame(room);

            Map<String, Object> resultPayload = Map.of(
                "approved", true,
                "yesVotes", yesVotes,
                "noVotes", noVotes,
                "totalEligible", totalEligible
            );
            broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("CANCEL_VOTE_RESULT", room.getRoomId(), resultPayload));

            Map<String, Object> cancelledPayload = Map.of(
                "reason", "Majority approved cancellation vote (" + yesVotes + "/" + totalEligible + " voted YES)"
            );
            broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("GAME_CANCELLED", room.getRoomId(), cancelledPayload));
            return;
        }

        // 2. Majority NO or impossible to reach majority
        long remainingVotes = totalEligible - session.getVotes().size();
        if (noVotes >= majorityThreshold || (yesVotes + remainingVotes) < majorityThreshold) {
            log.info("Cancellation REJECTED in room [{}] ({} NO / {} total)", room.getRoomId(), noVotes, totalEligible);
            session.cancelTimer();
            activeVotes.remove(room.getRoomId());

            Map<String, Object> resultPayload = Map.of(
                "approved", false,
                "yesVotes", yesVotes,
                "noVotes", noVotes,
                "totalEligible", totalEligible
            );
            broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("CANCEL_VOTE_RESULT", room.getRoomId(), resultPayload));
        }
    }

    private void handleVoteTimeout(String roomId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            CancelVoteSession session = activeVotes.remove(roomId);
            if (session == null) return;

            int totalEligible = session.getEligiblePlayerIds().size();
            int majorityThreshold = (totalEligible / 2) + 1;
            long yesVotes = session.getVotes().values().stream().filter(v -> v).count();
            long noVotes = session.getVotes().values().stream().filter(v -> !v).count();

            if (yesVotes >= majorityThreshold) {
                log.info("Cancellation vote passed on timeout in room [{}]", roomId);
                roundManager.cancelGame(room);

                broadcaster.broadcastToRoom(roomId, WsEnvelope.of("CANCEL_VOTE_RESULT", roomId, Map.of(
                    "approved", true,
                    "yesVotes", yesVotes,
                    "noVotes", noVotes,
                    "totalEligible", totalEligible
                )));

                broadcaster.broadcastToRoom(roomId, WsEnvelope.of("GAME_CANCELLED", roomId, Map.of(
                    "reason", "Cancellation vote approved on timeout (" + yesVotes + "/" + totalEligible + " voted YES)"
                )));
            } else {
                log.info("Cancellation vote failed to reach majority on timeout in room [{}]", roomId);
                broadcaster.broadcastToRoom(roomId, WsEnvelope.of("CANCEL_VOTE_RESULT", roomId, Map.of(
                    "approved", false,
                    "yesVotes", yesVotes,
                    "noVotes", noVotes,
                    "totalEligible", totalEligible
                )));
            }
        });
    }

    public boolean hasActiveVote(String roomId) {
        return roomId != null && activeVotes.containsKey(roomId);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}

