package com.movieclash.manager;

import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.Team;
import com.movieclash.model.dto.PlayerDTO;
import com.movieclash.model.dto.TeamDTO;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import com.movieclash.model.protocol.WsEnvelope;
import com.movieclash.service.RoomBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ScoreSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(ScoreSynchronizer.class);

    private final RoomManager roomManager;
    private final RoomBroadcaster broadcaster;
    private final RoundManager roundManager;
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public ScoreSynchronizer(RoomManager roomManager,
                             RoomBroadcaster broadcaster,
                             @Lazy RoundManager roundManager) {
        this.roomManager = roomManager;
        this.broadcaster = broadcaster;
        this.roundManager = roundManager;
    }

    public void processScoreUpdate(String roomId, String senderPlayerId, String eventId, Map<String, Object> payload) {
        if (roomId == null || senderPlayerId == null) return;

        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            // 1. Verify room and round are active
            if (room.getStatus() != RoomStatus.IN_PROGRESS) {
                log.warn("Score update rejected: room [{}] not in progress", roomId);
                return;
            }

            // 2. Verify sender is the authorized clue-giver or clue-team member
            Player sender = room.getPlayer(senderPlayerId);
            boolean isClueTeamMember = (room.getGameMode() == GameMode.TEAM && sender != null && sender.getTeamId() != null
                    && sender.getTeamId().equals(room.getCurrentClueGiverTeam()));
            if (!senderPlayerId.equals(room.getCurrentClueGiverId()) && !isClueTeamMember) {
                log.warn("Score update rejected: sender [{}] is neither clue-giver [{}] nor in clue team [{}]",
                        senderPlayerId, room.getCurrentClueGiverId(), room.getCurrentClueGiverTeam());
                broadcaster.sendPrivate(senderPlayerId, WsEnvelope.error(roomId, ErrorCode.NOT_CLUE_GIVER,
                        "Only the active clue team can validate and submit score updates."));
                return;
            }

            // 3. Deadline check (with 2s grace for network transit)
            long now = System.currentTimeMillis();
            if (now > room.getRoundDeadline() + 2000L) {
                log.warn("Score update rejected: round deadline expired for room [{}]", roomId);
                return;
            }

            // 4. Idempotency check: unique eventId
            if (eventId != null && !processedEventIds.add(eventId)) {
                log.warn("Score update rejected: duplicate eventId [{}]", eventId);
                return;
            }

            // 5. Extract and validate payload fields
            String guesserId = (String) payload.get("guesserId");
            Boolean isCorrect = (Boolean) payload.get("isCorrect");
            Number rawPoints = (Number) payload.get("points");
            Number rawCgPoints = (Number) payload.get("clueGiverPoints");
            String guessText = (String) payload.get("guessText");

            if (guesserId == null || isCorrect == null || !isCorrect) {
                return;
            }

            Player guesser = room.getPlayer(guesserId);
            Player clueGiver = room.getPlayer(room.getCurrentClueGiverId() != null ? room.getCurrentClueGiverId() : senderPlayerId);
            if (guesser == null || clueGiver == null) {
                return;
            }

            // 6. Check for duplicate scoring for this guesser in current round
            if (room.getRoundCorrectGuessers().contains(guesserId)) {
                log.warn("Guesser [{}] already scored in current round in room [{}]", guesserId, roomId);
                return;
            }

            // 7. Validate points cap
            int maxAllowedGuesserPoints = room.getConfig().getCorrectGuessPoints() + room.getConfig().getFirstGuessBonus();
            int points = rawPoints != null ? Math.min(Math.max(0, rawPoints.intValue()), maxAllowedGuesserPoints) : 100;

            int maxAllowedCgPoints = room.getConfig().getClueGiverRewardPerGuesser();
            int cgPoints = rawCgPoints != null ? Math.min(Math.max(0, rawCgPoints.intValue()), maxAllowedCgPoints) : 25;

            // 8. Apply score updates
            if (room.getGameMode() == GameMode.TEAM) {
                String guesserTeamId = guesser.getTeamId();
                String cgTeamId = clueGiver.getTeamId();

                if (guesserTeamId == null || guesserTeamId.equals(cgTeamId)) {
                    log.warn("Guesser [{}] in team battle belongs to clue-giver team [{}]", guesserId, cgTeamId);
                    return;
                }

                if (room.getRoundScoredTeams().contains(guesserTeamId)) {
                    log.warn("Team [{}] already scored in current round in room [{}]", guesserTeamId, roomId);
                    return;
                }

                Team guesserTeam = room.getTeams().get(guesserTeamId);
                Team cgTeam = room.getTeams().get(cgTeamId);

                if (guesserTeam != null) {
                    guesser.addScore(points);
                    guesserTeam.addScore(points);
                }

                if (cgTeam != null) {
                    clueGiver.addScore(cgPoints);
                    cgTeam.addScore(cgPoints);
                }

                room.getRoundScoredTeams().add(guesserTeamId);
            } else {
                guesser.addScore(points);
                clueGiver.addScore(cgPoints);
            }

            room.getRoundCorrectGuessers().add(guesserId);
            log.info("Score validated in room [{}]: guesser [{}] (+{} pts), clue-giver [{}] (+{} pts)",
                    roomId, guesser.getName(), points, clueGiver.getName(), cgPoints);

            // 9. Send private GUESS_RESULT to the guesser
            Map<String, Object> guessResultPayload = Map.of(
                "isCorrect", true,
                "points", points,
                "guessText", guessText != null ? guessText : ""
            );
            broadcaster.sendPrivate(guesserId, WsEnvelope.of("GUESS_RESULT", roomId, guessResultPayload));

            // 10. Broadcast SCORE_BROADCAST to all players in the room
            List<PlayerDTO> sortedScoreboard = room.getPlayers().values().stream()
                    .map(PlayerDTO::from)
                    .sorted(Comparator.comparingInt(PlayerDTO::score).reversed())
                    .toList();

            Map<String, TeamDTO> teamsMap = room.getTeams().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> TeamDTO.from(e.getValue())));

            Map<String, Object> scoreBroadcastPayload = Map.of(
                "guesserId", guesserId,
                "guesserName", guesser.getName(),
                "guesserTeam", guesser.getTeamId() != null ? guesser.getTeamId() : "",
                "clueGiverId", senderPlayerId,
                "clueGiverName", clueGiver.getName(),
                "guesserPoints", points,
                "clueGiverPoints", cgPoints,
                "scoreboard", sortedScoreboard,
                "teams", teamsMap
            );
            broadcaster.broadcastToRoom(roomId, WsEnvelope.of("SCORE_BROADCAST", roomId, scoreBroadcastPayload));

            // 11. Check if all eligible guessers have scored (early round termination)
            roundManager.checkEarlyRoundEnd(room);
        });
    }

    public boolean isEventProcessed(String eventId) {
        return eventId != null && processedEventIds.contains(eventId);
    }
}

