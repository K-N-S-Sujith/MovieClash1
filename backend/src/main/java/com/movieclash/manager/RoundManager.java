package com.movieclash.manager;

import com.movieclash.config.GameConfig;
import com.movieclash.model.Movie;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.Team;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import com.movieclash.model.protocol.WsEnvelope;
import com.movieclash.service.PlayerSynchronizer;
import com.movieclash.service.RoomBroadcaster;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class RoundManager {
    private static final Logger log = LoggerFactory.getLogger(RoundManager.class);

    private final RoomManager roomManager;
    private final MovieCatalogService movieCatalogService;
    private final RoomBroadcaster broadcaster;
    private final PlayerSynchronizer playerSynchronizer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // Active round deadline futures per room
    private final Map<String, ScheduledFuture<?>> roundTimers = new ConcurrentHashMap<>();
    private final Map<String, List<ScheduledFuture<?>>> roundHintTimers = new ConcurrentHashMap<>();
    private final Map<String, ActiveRoundHintState> activeRoundHints = new ConcurrentHashMap<>();

    private static class ActiveRoundHintState {
        final String originalTitle;
        final int year;
        final String hint;
        final Set<Character> revealedLetters = new HashSet<>();
        final List<Character> uniqueLetters = new ArrayList<>();
        int totalLetters = 0;
        String currentMaskedTitle;

        ActiveRoundHintState(String title, int year, String hint) {
            this.originalTitle = title != null ? title : "";
            this.year = year;
            this.hint = hint != null ? hint : "";

            Set<Character> uniqueSet = new LinkedHashSet<>();
            for (char c : this.originalTitle.toCharArray()) {
                if (Character.isLetter(c)) {
                    this.totalLetters++;
                    uniqueSet.add(Character.toUpperCase(c));
                }
            }
            this.uniqueLetters.addAll(uniqueSet);
            this.currentMaskedTitle = buildMasked(this.originalTitle, this.revealedLetters);
        }

        static String buildMasked(String title, Set<Character> revealed) {
            if (title == null) return "";
            StringBuilder sb = new StringBuilder();
            for (char c : title.toCharArray()) {
                if (Character.isLetter(c)) {
                    char upper = Character.toUpperCase(c);
                    if (revealed.contains(upper)) {
                        sb.append(c);
                    } else {
                        sb.append('_');
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        int countRevealed() {
            int count = 0;
            for (char c : currentMaskedTitle.toCharArray()) {
                if (Character.isLetter(c)) count++;
            }
            return count;
        }

        Character revealNextRandomLetter() {
            List<Character> unrevealed = new ArrayList<>();
            for (Character c : uniqueLetters) {
                if (!revealedLetters.contains(c)) {
                    unrevealed.add(c);
                }
            }
            if (unrevealed.isEmpty()) return null;

            Character chosen = unrevealed.get(ThreadLocalRandom.current().nextInt(unrevealed.size()));
            revealedLetters.add(chosen);
            currentMaskedTitle = buildMasked(originalTitle, revealedLetters);
            return chosen;
        }
    }

    public RoundManager(RoomManager roomManager,
                        MovieCatalogService movieCatalogService,
                        RoomBroadcaster broadcaster,
                        PlayerSynchronizer playerSynchronizer) {
        this.roomManager = roomManager;
        this.movieCatalogService = movieCatalogService;
        this.broadcaster = broadcaster;
        this.playerSynchronizer = playerSynchronizer;
    }

    public void startGame(String roomId, String requestingPlayerId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            throw new IllegalStateException(ErrorCode.ROOM_NOT_FOUND.name());
        }

        room.executeLocked(() -> {
            if (!requestingPlayerId.equals(room.getHostId())) {
                throw new IllegalStateException(ErrorCode.NOT_HOST.name());
            }

            if (room.getStatus() != RoomStatus.LOBBY) {
                throw new IllegalStateException(ErrorCode.GAME_ALREADY_STARTED.name());
            }

            List<String> turns = new ArrayList<>();

            if (room.getGameMode() == GameMode.TEAM) {
                // Team Mode: at least 2 active teams with >= 1 player each, max 5 per team
                List<com.movieclash.model.Team> activeTeams = room.getTeams().values().stream()
                        .filter(t -> !t.getPlayers().isEmpty())
                        .sorted(Comparator.comparing(com.movieclash.model.Team::getId))
                        .toList();

                if (activeTeams.size() < 2) {
                    throw new IllegalStateException("Team Battle requires at least 2 teams with at least 1 player each.");
                }

                for (com.movieclash.model.Team t : activeTeams) {
                    if (t.getPlayers().size() > 5) {
                        throw new IllegalStateException("Team [" + t.getName() + "] exceeds maximum of 5 players.");
                    }
                }

                // Round-robin turns: one clue-giver per team turn, rotating within the team
                int turnsPerTeam = room.getConfig().getTotalRounds();
                Map<String, Integer> teamPointers = new HashMap<>();

                for (int roundCycle = 0; roundCycle < turnsPerTeam; roundCycle++) {
                    for (com.movieclash.model.Team team : activeTeams) {
                        List<Player> teamPlayers = team.getPlayers().stream()
                                .filter(p -> !p.isSpectator())
                                .collect(Collectors.toList());
                        if (teamPlayers.isEmpty()) continue;
                        int currentPointer = teamPointers.getOrDefault(team.getId(), 0);
                        Player clueGiver = teamPlayers.get(currentPointer % teamPlayers.size());
                        turns.add(clueGiver.getId());
                        teamPointers.put(team.getId(), currentPointer + 1);
                    }
                }
            } else {
                List<String> playerIds = room.getPlayers().values().stream()
                        .filter(p -> !p.isSpectator())
                        .map(Player::getId)
                        .collect(Collectors.toList());
                int minPlayers = room.getConfig().getMinRoomPlayers();
                if (playerIds.size() < minPlayers) {
                    throw new IllegalStateException("At least " + minPlayers + " players are required to start");
                }

                Collections.shuffle(playerIds);

                int turnsPerPlayer = room.getConfig().getTotalRounds();
                for (int r = 0; r < turnsPerPlayer; r++) {
                    turns.addAll(playerIds);
                }
            }

            room.setTurnOrder(turns);
            room.setTurnIndex(0);
            room.setCurrentRound(0);
            room.setTotalRounds(turns.size());
            room.setStatus(RoomStatus.IN_PROGRESS);

            log.info("Game started in room [{}] with {} total turns scheduled", roomId, turns.size());

            // Broadcast GAME_STARTED
            Map<String, Object> gameStartedPayload = Map.of(
                "gameMode", room.getGameMode().name(),
                "totalRounds", room.getTotalRounds(),
                "config", room.getConfig()
            );
            broadcaster.broadcastToRoom(roomId, WsEnvelope.of("GAME_STARTED", roomId, gameStartedPayload));

            // Start the first round
            startNextRoundInternal(room);
        });
    }

    public void startNextRound(String roomId, String requestingPlayerId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            Player player = requestingPlayerId != null ? room.getPlayer(requestingPlayerId) : null;
            if (player == null && requestingPlayerId != null) {
                throw new IllegalStateException("Player not found in room");
            }
            startNextRoundInternal(room);
        });
    }

    private void startNextRoundInternal(Room room) {
        // Cancel any pending timer
        cancelRoundTimer(room.getRoomId());

        if (room.getTurnIndex() >= room.getTurnOrder().size()) {
            finishGame(room);
            return;
        }

        String clueGiverId = room.getTurnOrder().get(room.getTurnIndex());
        Player clueGiver = room.getPlayer(clueGiverId);

        // If clue-giver left the room, advance turn
        if (clueGiver == null) {
            room.setTurnIndex(room.getTurnIndex() + 1);
            startNextRoundInternal(room);
            return;
        }

        room.setTurnIndex(room.getTurnIndex() + 1);
        room.setCurrentRound(room.getCurrentRound() + 1);
        room.setCurrentClueGiverId(clueGiverId);
        room.setCurrentClueGiverTeam(clueGiver.getTeamId());
        room.resetGuessSequence();
        room.getRoundCorrectGuessers().clear();
        room.getRoundScoredTeams().clear();

        if (room.getConfig().isAutoStartRound()) {
            // Select movie automatically (used for fast testing)
            Movie movie = movieCatalogService.getRandomMovie(room.getUsedMovieIds());
            startRoundWithMovieInternal(room, movie, clueGiver);
        } else {
            // Manual selection: clue-giver must think of and choose movie first
            room.setCurrentMovie(null);
            room.setRoundStartTime(0L);
            room.setRoundDeadline(0L);

            log.info("Preparing Round {} in room [{}]. Waiting for Clue-Giver: [{}] ({}) to choose secret movie",
                    room.getCurrentRound(), room.getRoomId(), clueGiver.getName(), clueGiverId);

            Map<String, Object> roundPreparingPayload = Map.of(
                "roundNumber", room.getCurrentRound(),
                "totalRounds", room.getTotalRounds(),
                "clueGiverId", clueGiverId,
                "clueGiverName", clueGiver.getName(),
                "clueGiverTeam", clueGiver.getTeamId() != null ? clueGiver.getTeamId() : ""
            );
            broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("ROUND_PREPARING", room.getRoomId(), roundPreparingPayload));
        }
    }

    private void startRoundWithMovieInternal(Room room, Movie movie, Player clueGiver) {
        room.setCurrentMovie(movie);

        long now = System.currentTimeMillis();
        long durationMs = room.getConfig().getRoundDurationSec() * 1000L;
        long deadline = now + durationMs;

        room.setRoundStartTime(now);
        room.setRoundDeadline(deadline);

        log.info("Starting Round {} in room [{}]. Clue-Giver: [{}] ({}), Movie: [{}]",
                room.getCurrentRound(), room.getRoomId(), clueGiver.getName(), clueGiver.getId(), movie.getTitle());

        ActiveRoundHintState hintState = new ActiveRoundHintState(
            movie.getTitle(),
            movie.getYear(),
            movie.getHint()
        );
        activeRoundHints.put(room.getRoomId(), hintState);

        // 1. Broadcast ROUND_STARTED to all players
        Map<String, Object> roundStartedPayload = new HashMap<>();
        roundStartedPayload.put("roundNumber", room.getCurrentRound());
        roundStartedPayload.put("totalRounds", room.getTotalRounds());
        roundStartedPayload.put("clueGiverId", clueGiver.getId());
        roundStartedPayload.put("clueGiverName", clueGiver.getName());
        roundStartedPayload.put("clueGiverTeam", clueGiver.getTeamId() != null ? clueGiver.getTeamId() : "");
        roundStartedPayload.put("serverTime", now);
        roundStartedPayload.put("deadline", deadline);
        roundStartedPayload.put("roundDurationMs", durationMs);
        roundStartedPayload.put("maskedTitle", hintState.currentMaskedTitle);
        roundStartedPayload.put("year", hintState.year > 0 ? String.valueOf(hintState.year) : "");
        roundStartedPayload.put("hint", hintState.hint);
        roundStartedPayload.put("totalLetters", hintState.totalLetters);
        roundStartedPayload.put("revealedCount", 0);

        broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("ROUND_STARTED", room.getRoomId(), roundStartedPayload));

        // 2. PRIVATELY send CLUE_GIVER_ANSWER to the clue-giver (and all teammates in Team mode)
        Map<String, Object> answerPayload = Map.of(
            "movieId", movie.getId(),
            "title", movie.getTitle(),
            "year", movie.getYear(),
            "aliases", movie.getAliases() != null ? movie.getAliases() : List.of(),
            "hint", movie.getHint() != null ? movie.getHint() : ""
        );

        if (room.getGameMode() == GameMode.TEAM && clueGiver.getTeamId() != null) {
            Team clueTeam = room.getTeams().get(clueGiver.getTeamId());
            if (clueTeam != null) {
                log.info("Sending CLUE_GIVER_ANSWER to all {} players in clue team [{}]",
                        clueTeam.getPlayers().size(), clueGiver.getTeamId());
                for (Player p : clueTeam.getPlayers()) {
                    broadcaster.sendPrivate(p.getId(), WsEnvelope.of("CLUE_GIVER_ANSWER", room.getRoomId(), answerPayload));
                }
            } else {
                broadcaster.sendPrivate(clueGiver.getId(), WsEnvelope.of("CLUE_GIVER_ANSWER", room.getRoomId(), answerPayload));
            }
        } else {
            broadcaster.sendPrivate(clueGiver.getId(), WsEnvelope.of("CLUE_GIVER_ANSWER", room.getRoomId(), answerPayload));
        }

        // 3. Schedule round deadline enforcement (duration + small grace)
        final int currentRoundNum = room.getCurrentRound();
        final String roomId = room.getRoomId();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            handleRoundDeadline(roomId, currentRoundNum);
        }, durationMs + 200, TimeUnit.MILLISECONDS);

        roundTimers.put(roomId, timer);

        // 4. Schedule progressive Hangman marquee letter reveals
        List<ScheduledFuture<?>> hintFutures = new ArrayList<>();
        List<Double> percentages = new ArrayList<>();
        if (durationMs >= 80_000L) {
            percentages.add(0.30);
            percentages.add(0.60);
            percentages.add(0.80);
        } else if (durationMs >= 25_000L) {
            percentages.add(0.40);
            percentages.add(0.70);
        } else if (durationMs >= 15_000L) {
            percentages.add(0.50);
        }

        for (double pct : percentages) {
            long delayMs = (long) (durationMs * pct);
            ScheduledFuture<?> hintFuture = scheduler.schedule(() -> {
                triggerLetterHint(roomId, currentRoundNum);
            }, delayMs, TimeUnit.MILLISECONDS);
            hintFutures.add(hintFuture);
        }
        roundHintTimers.put(roomId, hintFutures);
    }

    public void startRoundWithChosenMovie(String roomId, String clueGiverId, String title, String hint, String year, List<String> aliases) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            throw new IllegalStateException(ErrorCode.ROOM_NOT_FOUND.name());
        }

        room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.IN_PROGRESS) {
                throw new IllegalStateException("Game is not active");
            }
            if (!clueGiverId.equals(room.getCurrentClueGiverId())) {
                throw new IllegalStateException("Only current clue-giver can choose the movie");
            }
            if (room.getCurrentMovie() != null) {
                throw new IllegalStateException("Round already started");
            }

            Player clueGiver = room.getPlayer(clueGiverId);
            if (clueGiver == null) {
                throw new IllegalStateException("Clue-giver not found in room");
            }

            String cleanTitle = title != null ? title.replaceAll("<[^>]*>", "").replaceAll("[\\r\\n\\t]", " ").trim() : "";
            if (cleanTitle.isEmpty()) {
                throw new IllegalArgumentException("Movie title cannot be empty");
            }
            if (cleanTitle.length() > 80) {
                cleanTitle = cleanTitle.substring(0, 80).trim();
            }

            String cleanHint = hint != null ? hint.replaceAll("<[^>]*>", "").replaceAll("[\\r\\n\\t]", " ").trim() : "";
            if (cleanHint.length() > 120) {
                cleanHint = cleanHint.substring(0, 120).trim();
            }

            int intYear = 0;
            if (year != null && !year.isBlank()) {
                try {
                    intYear = Integer.parseInt(year.trim());
                } catch (Exception ignored) {}
            }
            List<String> cleanAliases = aliases != null ? aliases.stream()
                    .filter(Objects::nonNull)
                    .map(a -> a.replaceAll("<[^>]*>", "").replaceAll("[\\r\\n\\t]", " ").trim())
                    .filter(a -> !a.isEmpty())
                    .collect(Collectors.toList()) : new ArrayList<>();

            String movieId = UUID.randomUUID().toString().substring(0, 8);
            Movie movie = new Movie(movieId, cleanTitle, intYear, cleanAliases, cleanHint);

            startRoundWithMovieInternal(room, movie, clueGiver);
        });
    }

    public void handleRoundDeadline(String roomId, int roundNumber) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            if (room.getStatus() == RoomStatus.IN_PROGRESS && room.getCurrentRound() == roundNumber) {
                log.info("Round {} deadline reached for room [{}]", roundNumber, roomId);
                finishRoundInternal(room, "TIME_EXPIRED");
            }
        });
    }

    public void checkEarlyRoundEnd(Room room) {
        if (room.getStatus() != RoomStatus.IN_PROGRESS) return;

        String clueGiverId = room.getCurrentClueGiverId();
        Set<String> eligibleGuesserIds;

        if (room.getGameMode() == GameMode.TEAM) {
            String clueGiverTeam = room.getCurrentClueGiverTeam();
            // In team battle, opposing teams that have connected players
            Set<String> opposingTeams = room.getTeams().entrySet().stream()
                    .filter(e -> !e.getKey().equals(clueGiverTeam) && e.getValue().getPlayers().stream().anyMatch(Player::isConnected))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            if (!opposingTeams.isEmpty() && room.getRoundScoredTeams().containsAll(opposingTeams)) {
                log.info("All eligible teams scored in room [{}]. Ending round early.", room.getRoomId());
                finishRoundInternal(room, "ALL_GUESSED");
            }
        } else {
            // In individual battle, all connected non-spectator players except clue-giver
            eligibleGuesserIds = room.getPlayers().values().stream()
                    .filter(p -> p.isConnected() && !p.isSpectator() && !p.getId().equals(clueGiverId))
                    .map(Player::getId)
                    .collect(Collectors.toSet());

            if (!eligibleGuesserIds.isEmpty() && room.getRoundCorrectGuessers().containsAll(eligibleGuesserIds)) {
                log.info("All eligible players scored in room [{}]. Ending round early.", room.getRoomId());
                finishRoundInternal(room, "ALL_GUESSED");
            }
        }
    }

    public void finishRound(String roomId, String reason) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;
        room.executeLocked(() -> finishRoundInternal(room, reason));
    }

    private void finishRoundInternal(Room room, String reason) {
        cancelRoundTimer(room.getRoomId());
        Movie movie = room.getCurrentMovie();

        // Build round scores summary
        List<Map<String, Object>> roundScores = new ArrayList<>();
        for (String guesserId : room.getRoundCorrectGuessers()) {
            Player p = room.getPlayer(guesserId);
            if (p != null) {
                roundScores.add(Map.of(
                    "playerId", p.getId(),
                    "playerName", p.getName(),
                    "pointsEarned", 100 // summary indicator
                ));
            }
        }

        // Broadcast ROUND_FINISHED revealing the movie
        Map<String, Object> roundFinishedPayload = Map.of(
            "reason", reason != null ? reason : "ROUND_ENDED",
            "revealedMovie", movie != null ? Map.of(
                "title", movie.getTitle(),
                "year", movie.getYear()
            ) : Map.of(),
            "roundScores", roundScores,
            "autoAdvance", room.getConfig().isAutoStartRound()
        );
        broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("ROUND_FINISHED", room.getRoomId(), roundFinishedPayload));

        if (room.getConfig().isAutoStartRound()) {
            // Auto-advance to next round after 6.5 seconds (or host can trigger NEXT_ROUND earlier)
            ScheduledFuture<?> nextRoundTimer = scheduler.schedule(() -> {
                Room r = roomManager.getRoom(room.getRoomId());
                if (r != null) {
                    r.executeLocked(() -> {
                        if (r.getStatus() == RoomStatus.IN_PROGRESS) {
                            startNextRoundInternal(r);
                        }
                    });
                }
            }, 6500, TimeUnit.MILLISECONDS);

            roundTimers.put(room.getRoomId(), nextRoundTimer);
        }
    }

    private void finishGame(Room room) {
        cancelRoundTimer(room.getRoomId());
        room.setStatus(RoomStatus.FINISHED);

        // Determine winner(s)
        List<Map<String, Object>> finalScores = new ArrayList<>();
        List<Map<String, Object>> winners = new ArrayList<>();

        if (room.getGameMode() == GameMode.TEAM) {
            List<Map.Entry<String, com.movieclash.model.Team>> sortedTeams = room.getTeams().entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().getScore(), a.getValue().getScore()))
                    .toList();

            int topScore = sortedTeams.isEmpty() ? 0 : sortedTeams.get(0).getValue().getScore();
            for (Map.Entry<String, com.movieclash.model.Team> e : sortedTeams) {
                finalScores.add(Map.of(
                    "id", e.getKey(),
                    "name", e.getValue().getName(),
                    "score", e.getValue().getScore()
                ));
                if (e.getValue().getScore() == topScore) {
                    winners.add(Map.of("id", e.getKey(), "name", e.getValue().getName(), "score", topScore));
                }
            }
        } else {
            List<Player> sortedPlayers = room.getPlayers().values().stream()
                    .sorted(Comparator.comparingInt(Player::getScore).reversed())
                    .toList();

            int topScore = sortedPlayers.isEmpty() ? 0 : sortedPlayers.get(0).getScore();
            for (Player p : sortedPlayers) {
                finalScores.add(Map.of(
                    "id", p.getId(),
                    "name", p.getName(),
                    "score", p.getScore()
                ));
                if (p.getScore() == topScore) {
                    winners.add(Map.of("id", p.getId(), "name", p.getName(), "score", topScore));
                }
            }
        }

        // If no one scored, declare all leaders
        if (winners.isEmpty() && !finalScores.isEmpty()) {
            winners.add(finalScores.get(0));
        }

        log.info("Game finished in room [{}]. Winners: {}", room.getRoomId(), winners);
        Map<String, Object> gameFinishedPayload = Map.of(
            "winners", winners,
            "finalScores", finalScores
        );
        broadcaster.broadcastToRoom(room.getRoomId(), WsEnvelope.of("GAME_FINISHED", room.getRoomId(), gameFinishedPayload));
    }

    public void cancelGame(Room room) {
        cancelRoundTimer(room.getRoomId());
        room.setStatus(RoomStatus.LOBBY);
        room.setCurrentMovie(null);
        room.setCurrentClueGiverId(null);
        room.setCurrentClueGiverTeam(null);
        room.setRoundDeadline(0L);
        room.setRoundStartTime(0L);
        room.getRoundCorrectGuessers().clear();
        room.getRoundScoredTeams().clear();
        log.info("Game cancelled and room [{}] returned to LOBBY status", room.getRoomId());
    }

    public void triggerLetterHint(String roomId, int roundNumber) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.IN_PROGRESS || room.getCurrentRound() != roundNumber) {
                return;
            }

            ActiveRoundHintState hintState = activeRoundHints.get(roomId);
            if (hintState == null) return;

            Character revealedLetter = hintState.revealNextRandomLetter();
            if (revealedLetter != null) {
                log.info("Revealed letter hint [{}] for room [{}] round {}", revealedLetter, roomId, roundNumber);
                Map<String, Object> hintPayload = Map.of(
                    "maskedTitle", hintState.currentMaskedTitle,
                    "revealedLetter", String.valueOf(revealedLetter),
                    "revealedCount", hintState.countRevealed(),
                    "totalLetters", hintState.totalLetters,
                    "roundNumber", roundNumber
                );
                broadcaster.broadcastToRoom(roomId, WsEnvelope.of("LETTER_HINT", roomId, hintPayload));
            }
        });
    }

    public void cancelRoundTimer(String roomId) {
        ScheduledFuture<?> future = roundTimers.remove(roomId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        List<ScheduledFuture<?>> hints = roundHintTimers.remove(roomId);
        if (hints != null) {
            for (ScheduledFuture<?> hintFuture : hints) {
                if (hintFuture != null && !hintFuture.isDone()) {
                    hintFuture.cancel(false);
                }
            }
        }
        activeRoundHints.remove(roomId);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}

