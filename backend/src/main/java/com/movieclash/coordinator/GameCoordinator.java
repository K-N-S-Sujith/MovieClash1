package com.movieclash.coordinator;

import com.movieclash.config.GameConfig;
import com.movieclash.gateway.WebSocketSessionTracker;
import com.movieclash.manager.MovieCatalogService;
import com.movieclash.manager.PlayerSessionManager;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Movie;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.Team;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import com.movieclash.model.protocol.WsEnvelope;
import com.movieclash.service.RoomBroadcaster;
import com.movieclash.util.AnswerValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;

@Service
public class GameCoordinator {
    private static final Logger log = LoggerFactory.getLogger(GameCoordinator.class);

    private final RoomManager roomManager;
    private final PlayerSessionManager playerSessionManager;
    private final WebSocketSessionTracker sessionTracker;
    private final RoomBroadcaster broadcaster;
    private final com.movieclash.service.PlayerSynchronizer playerSynchronizer;
    private final com.movieclash.manager.RoundManager roundManager;
    private final com.movieclash.manager.ScoreSynchronizer scoreSynchronizer;
    private final com.movieclash.service.ChatRelayService chatRelayService;
    private final com.movieclash.manager.ReconnectionManager reconnectionManager;
    private final com.movieclash.manager.CancellationManager cancellationManager;
    private final MovieCatalogService movieCatalogService;

    public GameCoordinator(RoomManager roomManager,
                           PlayerSessionManager playerSessionManager,
                           WebSocketSessionTracker sessionTracker,
                           RoomBroadcaster broadcaster,
                           com.movieclash.service.PlayerSynchronizer playerSynchronizer,
                           com.movieclash.manager.RoundManager roundManager,
                           com.movieclash.manager.ScoreSynchronizer scoreSynchronizer,
                           com.movieclash.service.ChatRelayService chatRelayService,
                           com.movieclash.manager.ReconnectionManager reconnectionManager,
                           com.movieclash.manager.CancellationManager cancellationManager,
                           MovieCatalogService movieCatalogService) {
        this.roomManager = roomManager;
        this.playerSessionManager = playerSessionManager;
        this.sessionTracker = sessionTracker;
        this.broadcaster = broadcaster;
        this.playerSynchronizer = playerSynchronizer;
        this.roundManager = roundManager;
        this.scoreSynchronizer = scoreSynchronizer;
        this.chatRelayService = chatRelayService;
        this.reconnectionManager = reconnectionManager;
        this.cancellationManager = cancellationManager;
        this.movieCatalogService = movieCatalogService;
    }

    public void dispatch(WebSocketSession session, WsEnvelope envelope) {
        String type = envelope.getType();
        if (type == null) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(envelope.getRoomId(), ErrorCode.INVALID_PAYLOAD, "Missing event type"));
            return;
        }

        switch (type) {
            case "PING" -> handlePing(session, envelope);
            case "CREATE_ROOM" -> handleCreateRoom(session, envelope);
            case "JOIN_ROOM" -> handleJoinRoom(session, envelope);
            case "LEAVE_ROOM" -> handleLeaveRoom(session, envelope);
            case "ASSIGN_TEAM" -> handleAssignTeam(session, envelope);
            case "START_GAME" -> handleStartGame(session, envelope);
            case "NEXT_ROUND" -> handleNextRound(session, envelope);
            case "CHOOSE_MOVIE" -> handleChooseMovie(session, envelope);
            case "SUGGEST_MOVIE" -> handleSuggestMovie(session, envelope);
            case "SUBMIT_GUESS" -> handleSubmitGuess(session, envelope);
            case "SCORE_UPDATE" -> handleScoreUpdate(session, envelope);
            case "CLUE_MESSAGE" -> handleClueMessage(session, envelope);
            case "CHAT_MESSAGE" -> handleChatMessage(session, envelope);
            case "RECONNECT" -> reconnectionManager.handleReconnect(session, envelope);
            case "CANCEL_PROPOSE" -> handleCancelPropose(session, envelope);
            case "CANCEL_VOTE" -> handleCancelVote(session, envelope);
            default -> log.debug("Unhandled event type [{}]", type);
        }
    }

    private void handleCancelPropose(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId != null && playerId != null) {
            cancellationManager.handleCancelPropose(roomId, playerId);
        }
    }

    private void handleCancelVote(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        Boolean vote = (Boolean) payload.get("vote");

        if (roomId != null && playerId != null && vote != null) {
            cancellationManager.handleCancelVote(roomId, playerId, vote);
        }
    }

    private void handleClueMessage(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        String message = (String) payload.get("message");

        if (roomId != null && playerId != null) {
            chatRelayService.handleClueMessage(roomId, playerId, message);
        }
    }

    private void handleChatMessage(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        String message = (String) payload.get("message");

        if (roomId != null && playerId != null) {
            chatRelayService.handleChatMessage(roomId, playerId, message);
        }
    }

    private void handleScoreUpdate(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId != null && playerId != null) {
            scoreSynchronizer.processScoreUpdate(roomId, playerId, envelope.getEventId(), envelope.getPayload());
        }
    }

    private void handleStartGame(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId == null || playerId == null) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Session not bound to room"));
            return;
        }

        try {
            roundManager.startGame(roomId, playerId);
        } catch (IllegalStateException e) {
            ErrorCode code = ErrorCode.UNAUTHORIZED;
            try { code = ErrorCode.valueOf(e.getMessage()); } catch (Exception ignored) {}
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, code, e.getMessage()));
        } catch (Exception e) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.INVALID_PAYLOAD, e.getMessage()));
        }
    }

    private void handleNextRound(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId != null && playerId != null) {
            try {
                roundManager.startNextRound(roomId, playerId);
            } catch (IllegalStateException e) {
                ErrorCode code = ErrorCode.NOT_HOST;
                try { code = ErrorCode.valueOf(e.getMessage()); } catch (Exception ignored) {}
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, code, e.getMessage()));
            }
        }
    }

    private void handleSubmitGuess(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId == null || playerId == null) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Session not bound to room"));
            return;
        }

        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.ROOM_NOT_FOUND, "Room not found"));
            return;
        }

        room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.IN_PROGRESS) {
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Round is not active"));
                return;
            }

            long now = System.currentTimeMillis();
            if (now > room.getRoundDeadline() + 2000L) { // 2s network transit grace
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Round deadline has passed"));
                return;
            }

            Player guesser = room.getPlayer(playerId);
            if (guesser == null) return;

            // Clue-giver cannot submit guesses
            if (playerId.equals(room.getCurrentClueGiverId())) {
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Clue-giver cannot submit guesses"));
                return;
            }

            // In Team mode, teammates of the clue-giver cannot guess
            if (room.getGameMode() == GameMode.TEAM && guesser.getTeamId() != null
                    && guesser.getTeamId().equals(room.getCurrentClueGiverTeam())) {
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Teammates of clue-giver cannot guess"));
                return;
            }

            // In Team mode, if guesser's team has already scored this round
            if (room.getGameMode() == GameMode.TEAM && guesser.getTeamId() != null
                    && room.getRoundScoredTeams().contains(guesser.getTeamId())) {
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Your team has already scored this round"));
                return;
            }

            // Check if player has already scored this round
            if (room.getRoundCorrectGuessers().contains(playerId)) {
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "You have already scored this round"));
                return;
            }

            Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
            String rawGuess = (String) payload.get("guessText");
            if (rawGuess == null || rawGuess.trim().isEmpty()) {
                return;
            }

            // Sanitize guess text
            String cleanGuess = rawGuess.replaceAll("<[^>]*>", "").replaceAll("[\\r\\n\\t]", " ").trim();
            if (cleanGuess.length() > 100) {
                cleanGuess = cleanGuess.substring(0, 100).trim();
            }

            int seq = room.nextGuessSequence();
            String clueGiverId = room.getCurrentClueGiverId();
            Movie movie = room.getCurrentMovie();
            boolean isMatch = false;
            String matchedWith = "";

            // Authoritative server-side evaluation for instant validation
            if (movie != null && movie.getTitle() != null) {
                AnswerValidator.MatchResult result = AnswerValidator.evaluate(cleanGuess, movie.getTitle(), movie.getAliases());
                isMatch = result.isMatch();
                matchedWith = result.matchedWith() != null ? result.matchedWith() : "";

                if (isMatch) {
                    boolean isFirstCorrect = room.getRoundCorrectGuessers().isEmpty();
                    int points = room.getConfig().getCorrectGuessPoints() + (isFirstCorrect ? room.getConfig().getFirstGuessBonus() : 0);
                    int cgPoints = room.getConfig().getClueGiverRewardPerGuesser();

                    String updateEventId = "srv_score_" + roomId + "_" + room.getCurrentRound() + "_" + playerId + "_" + seq;

                    Map<String, Object> scorePayload = new HashMap<>();
                    scorePayload.put("guesserId", playerId);
                    scorePayload.put("guesserTeam", guesser.getTeamId());
                    scorePayload.put("guessSeq", seq);
                    scorePayload.put("isCorrect", true);
                    scorePayload.put("points", points);
                    scorePayload.put("clueGiverPoints", cgPoints);
                    scorePayload.put("guessText", cleanGuess);

                    scoreSynchronizer.processScoreUpdate(roomId, clueGiverId != null ? clueGiverId : playerId, updateEventId, scorePayload);
                } else {
                    // Send instant feedback to guesser so they don't wait indefinitely
                    Map<String, Object> guessResultPayload = Map.of(
                        "isCorrect", false,
                        "points", 0,
                        "guessText", cleanGuess
                    );
                    broadcaster.sendPrivate(playerId, WsEnvelope.of("GUESS_RESULT", roomId, guessResultPayload));
                }
            }

            // Forward guess to clue-giver (or entire clue team in Team Mode) for audit feed
            Map<String, Object> forwardPayload = Map.of(
                "guesserId", playerId,
                "guesserName", guesser.getName(),
                "guesserTeam", guesser.getTeamId() != null ? guesser.getTeamId() : "",
                "guessText", cleanGuess,
                "guessSeq", seq,
                "isMatch", isMatch,
                "matchedWith", matchedWith,
                "serverTimestamp", now
            );

            if (room.getGameMode() == GameMode.TEAM && room.getCurrentClueGiverTeam() != null) {
                Team clueTeam = room.getTeams().get(room.getCurrentClueGiverTeam());
                if (clueTeam != null) {
                    for (Player p : clueTeam.getPlayers()) {
                        broadcaster.sendPrivate(p.getId(), WsEnvelope.of("FORWARD_GUESS", roomId, forwardPayload));
                    }
                } else if (clueGiverId != null) {
                    broadcaster.sendPrivate(clueGiverId, WsEnvelope.of("FORWARD_GUESS", roomId, forwardPayload));
                }
            } else if (clueGiverId != null) {
                broadcaster.sendPrivate(clueGiverId, WsEnvelope.of("FORWARD_GUESS", roomId, forwardPayload));
            }
        });
    }

    private void handlePing(WebSocketSession session, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        Object clientTimestamp = payload.get("clientTimestamp");

        Map<String, Object> pongPayload = new HashMap<>();
        pongPayload.put("clientTimestamp", clientTimestamp);
        pongPayload.put("serverTimestamp", System.currentTimeMillis());

        broadcaster.sendToSessionDirect(session, WsEnvelope.of("PONG", envelope.getRoomId(), pongPayload));
    }

    private void handleCreateRoom(WebSocketSession session, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        String playerName = (String) payload.get("playerName");
        String userId = (String) payload.get("userId");
        String rawMode = (String) payload.get("gameMode");
        GameMode mode = "TEAM".equalsIgnoreCase(rawMode) ? GameMode.TEAM : GameMode.INDIVIDUAL;
        Number rawTeamCount = (Number) payload.get("teamCount");
        int teamCount = rawTeamCount != null ? rawTeamCount.intValue() : 2;

        GameConfig config = new GameConfig();
        if (payload.get("config") instanceof Map<?, ?> cfgMap) {
            if (cfgMap.get("autoStartRound") instanceof Boolean b) {
                config.setAutoStartRound(b);
            }
            if (cfgMap.get("roundDurationSec") instanceof Number n) {
                config.setRoundDurationSec(n.intValue());
            }
            if (cfgMap.get("totalRounds") instanceof Number n) {
                config.setTotalRounds(n.intValue());
            }
            if (cfgMap.get("correctGuessPoints") instanceof Number n) {
                config.setCorrectGuessPoints(n.intValue());
            }
            if (cfgMap.get("firstGuessBonus") instanceof Number n) {
                config.setFirstGuessBonus(n.intValue());
            }
            if (cfgMap.get("clueGiverRewardPerGuesser") instanceof Number n) {
                config.setClueGiverRewardPerGuesser(n.intValue());
            }
        } else if (payload.get("autoStartRound") instanceof Boolean b) {
            config.setAutoStartRound(b);
        }

        try {
            RoomManager.CreateRoomResult res = roomManager.createRoom(playerName, userId, mode, config, teamCount);
            Room room = res.room();
            Player host = res.hostPlayer();

            // Bind session
            host.setWebSocketSessionId(session.getId());
            playerSessionManager.bindSession(session.getId(), host.getId());
            sessionTracker.bindPlayer(session.getId(), host.getId(), room.getRoomId());

            // Reply with ROOM_CREATED
            Map<String, Object> responsePayload = Map.of(
                "roomId", room.getRoomId(),
                "playerId", host.getId(),
                "reconnectToken", res.reconnectToken(),
                "room", com.movieclash.model.dto.RoomSnapshotDTO.from(room)
            );

            broadcaster.sendToSessionDirect(session, WsEnvelope.of("ROOM_CREATED", room.getRoomId(), responsePayload));
        } catch (Exception e) {
            log.warn("Create room error: {}", e.getMessage());
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(null, ErrorCode.INVALID_NAME, e.getMessage()));
        }
    }

    private void handleChooseMovie(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId == null || playerId == null) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, "Session not bound to room"));
            return;
        }

        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        String title = (String) payload.get("title");
        String hint = (String) payload.get("hint");
        String year = (String) payload.get("year");
        List<String> aliases = null;
        if (payload.get("aliases") instanceof List<?> list) {
            aliases = list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }

        try {
            roundManager.startRoundWithChosenMovie(roomId, playerId, title, hint, year, aliases);
        } catch (IllegalArgumentException e) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.INVALID_PAYLOAD, e.getMessage()));
        } catch (IllegalStateException e) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.UNAUTHORIZED, e.getMessage()));
        } catch (Exception e) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.INVALID_PAYLOAD, "Failed to start round: " + e.getMessage()));
        }
    }

    private void handleSuggestMovie(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String roomId = sessionTracker.getRoomId(sessionId);
        try {
            Room room = roomId != null ? roomManager.getRoom(roomId) : null;
            Set<String> usedIds = room != null ? room.getUsedMovieIds() : Collections.emptySet();
            Movie movie = movieCatalogService.getRandomMovie(usedIds);
            Map<String, Object> payload = Map.of(
                "title", movie.getTitle(),
                "hint", movie.getHint() != null ? movie.getHint() : "",
                "year", movie.getYear(),
                "aliases", movie.getAliases() != null ? movie.getAliases() : List.of()
            );
            broadcaster.sendToSessionDirect(session, WsEnvelope.of("SUGGESTED_MOVIE", roomId, payload));
        } catch (Exception e) {
            log.warn("Suggest movie error: {}", e.getMessage());
        }
    }

    private void handleJoinRoom(WebSocketSession session, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        String roomId = (String) payload.get("roomId");
        String playerName = (String) payload.get("playerName");
        String userId = (String) payload.get("userId");

        if (roomId == null) roomId = envelope.getRoomId();

        try {
            RoomManager.JoinRoomResult res = roomManager.joinRoom(roomId, playerName, userId);
            Room room = res.room();
            Player player = res.player();

            // Bind session
            player.setWebSocketSessionId(session.getId());
            playerSessionManager.bindSession(session.getId(), player.getId());
            sessionTracker.bindPlayer(session.getId(), player.getId(), room.getRoomId());

            // 1. Send confirmation to joining player with sanitized room snapshot
            Map<String, Object> selfPayload = Map.of(
                "yourPlayerId", player.getId(),
                "reconnectToken", res.reconnectToken(),
                "room", com.movieclash.model.dto.RoomSnapshotDTO.from(room)
            );
            broadcaster.sendToSessionDirect(session, WsEnvelope.of("PLAYER_JOINED", room.getRoomId(), selfPayload));

            // 2. Broadcast PLAYER_JOINED to existing players
            Map<String, Object> broadcastPayload = Map.of(
                "player", com.movieclash.model.dto.PlayerDTO.from(player),
                "room", com.movieclash.model.dto.RoomSnapshotDTO.from(room)
            );
            broadcaster.broadcastToRoomExcept(room.getRoomId(), player.getId(), WsEnvelope.of("PLAYER_JOINED", room.getRoomId(), broadcastPayload));

            // 3. If joining player is the host (e.g. original host returned), notify room of host restoration
            if (player.isHost() && room.getPlayers().size() > 1) {
                broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("HOST_RESTORED", room.getRoomId(), Map.of(
                    "hostId", player.getId(),
                    "hostName", player.getName(),
                    "coHostId", room.getCoHostId() != null ? room.getCoHostId() : ""
                )));
            }

        } catch (IllegalStateException e) {
            ErrorCode code = ErrorCode.ROOM_NOT_FOUND;
            try { code = ErrorCode.valueOf(e.getMessage()); } catch (Exception ignored) {}
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, code, e.getMessage()));
        } catch (Exception e) {
            broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.INVALID_PAYLOAD, e.getMessage()));
        }
    }

    private void handleLeaveRoom(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId != null && playerId != null) {
            RoomManager.LeaveRoomResult res = roomManager.leaveRoom(roomId, playerId);
            sessionTracker.removeSession(sessionId);
            playerSessionManager.unbindSession(sessionId);

            if (!res.roomDeleted()) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("playerId", playerId);
                payload.put("playerName", res.playerName());
                if (res.newHostId() != null) {
                    payload.put("newHostId", res.newHostId());
                    payload.put("isCoHost", true);
                }
                broadcaster.broadcastToRoom(roomId, WsEnvelope.of("PLAYER_LEFT", roomId, payload));
            }
        }
    }

    private void handleAssignTeam(WebSocketSession session, WsEnvelope envelope) {
        String sessionId = session.getId();
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);
        Map<String, Object> payload = envelope.getPayload() != null ? envelope.getPayload() : Map.of();
        String targetTeamId = (String) payload.get("teamId");

        if (roomId != null && playerId != null && targetTeamId != null) {
            boolean success = roomManager.assignTeam(roomId, playerId, targetTeamId);
            if (success) {
                playerSynchronizer.broadcastRosterDelta(roomId);
            } else {
                broadcaster.sendToSessionDirect(session, WsEnvelope.error(roomId, ErrorCode.INVALID_TEAM, "Could not assign team"));
            }
        }
    }

    public void handleDisconnect(String sessionId) {
        String playerId = sessionTracker.getPlayerId(sessionId);
        String roomId = sessionTracker.getRoomId(sessionId);

        if (roomId != null && playerId != null) {
            reconnectionManager.handlePlayerDisconnect(roomId, playerId);
        }

        sessionTracker.removeSession(sessionId);
        playerSessionManager.unbindSession(sessionId);
    }
}

