package com.movieclash.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.movieclash.config.GameConfig;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Room {
    private final String roomId;
    private String hostId;
    private String coHostId;
    private String creatorUserId;
    private String originalHostPlayerId;
    private GameMode gameMode;
    private RoomStatus status;
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private GameConfig config;

    // Round lifecycle state
    private int currentRound = 0;
    private int totalRounds = 0;
    private String currentClueGiverId;
    private String currentClueGiverTeam;
    private List<String> turnOrder = new ArrayList<>();
    private int turnIndex = 0;
    private long roundStartTime = 0L;
    private long roundDeadline = 0L;
    private long lastActivityTime = System.currentTimeMillis();

    @JsonIgnore
    private final AtomicInteger guessSequenceCounter = new AtomicInteger(0);

    @JsonIgnore
    private final Set<String> roundCorrectGuessers = ConcurrentHashMap.newKeySet();

    @JsonIgnore
    private final Set<String> roundScoredTeams = ConcurrentHashMap.newKeySet();

    @JsonIgnore
    private Movie currentMovie;

    @JsonIgnore
    private final Set<String> usedMovieIds = ConcurrentHashMap.newKeySet();

    @JsonIgnore
    private final Deque<ChatMessage> recentChat = new ConcurrentLinkedDeque<>();

    @JsonIgnore
    private final ReentrantLock roomLock = new ReentrantLock();

    public Room(String roomId, String hostId, GameMode gameMode, GameConfig config) {
        this(roomId, hostId, gameMode, config, 2);
    }

    public Room(String roomId, String hostId, GameMode gameMode, GameConfig config, int teamCount) {
        this.roomId = roomId;
        this.hostId = hostId;
        this.gameMode = gameMode;
        this.status = RoomStatus.LOBBY;
        this.config = config != null ? config : new GameConfig();

        if (gameMode == GameMode.TEAM) {
            initTeams(teamCount);
        }
    }

    public void initTeams(int count) {
        teams.clear();
        int safeCount = Math.min(5, Math.max(2, count));
        String[] teamKeys = {"TEAM_A", "TEAM_B", "TEAM_C", "TEAM_D", "TEAM_E"};
        String[] teamNames = {"Team Alpha", "Team Beta", "Team Gamma", "Team Delta", "Team Epsilon"};
        for (int i = 0; i < safeCount; i++) {
            teams.put(teamKeys[i], new Team(teamKeys[i], teamNames[i]));
        }
    }

    public <T> T executeLocked(Supplier<T> action) {
        roomLock.lock();
        try {
            this.lastActivityTime = System.currentTimeMillis();
            return action.get();
        } finally {
            roomLock.unlock();
        }
    }

    public void executeLocked(Runnable action) {
        roomLock.lock();
        try {
            this.lastActivityTime = System.currentTimeMillis();
            action.run();
        } finally {
            roomLock.unlock();
        }
    }

    public String getRoomId() {
        return roomId;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
        players.values().forEach(p -> p.setHost(p.getId().equals(hostId)));
        if (hostId != null && hostId.equals(this.coHostId)) {
            this.setCoHostId(null);
        }
    }

    public String getCoHostId() {
        return coHostId;
    }

    public void setCoHostId(String coHostId) {
        this.coHostId = coHostId;
        players.values().forEach(p -> p.setCoHost(p.getId().equals(coHostId)));
    }

    public String getCreatorUserId() {
        return creatorUserId;
    }

    public void setCreatorUserId(String creatorUserId) {
        this.creatorUserId = creatorUserId;
    }

    public String getOriginalHostPlayerId() {
        return originalHostPlayerId;
    }

    public void setOriginalHostPlayerId(String originalHostPlayerId) {
        this.originalHostPlayerId = originalHostPlayerId;
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public void setGameMode(GameMode gameMode) {
        this.gameMode = gameMode;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public Map<String, Player> getPlayers() {
        return players;
    }

    public List<Player> getPlayersList() {
        return new ArrayList<>(players.values());
    }

    public Player getPlayer(String playerId) {
        return players.get(playerId);
    }

    public void addPlayer(Player player) {
        players.put(player.getId(), player);
        if (player.getId().equals(hostId)) {
            player.setHost(true);
        }
    }

    public Player removePlayer(String playerId) {
        Player removed = players.remove(playerId);
        if (removed != null && removed.getTeamId() != null) {
            Team team = teams.get(removed.getTeamId());
            if (team != null) {
                team.getPlayers().removeIf(p -> p.getId().equals(playerId));
            }
        }
        return removed;
    }

    public Map<String, Team> getTeams() {
        return teams;
    }

    public GameConfig getConfig() {
        return config;
    }

    public void setConfig(GameConfig config) {
        this.config = config;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }

    public int getTotalRounds() {
        return totalRounds;
    }

    public void setTotalRounds(int totalRounds) {
        this.totalRounds = totalRounds;
    }

    public String getCurrentClueGiverId() {
        return currentClueGiverId;
    }

    public void setCurrentClueGiverId(String currentClueGiverId) {
        this.currentClueGiverId = currentClueGiverId;
    }

    public String getCurrentClueGiverTeam() {
        return currentClueGiverTeam;
    }

    public void setCurrentClueGiverTeam(String currentClueGiverTeam) {
        this.currentClueGiverTeam = currentClueGiverTeam;
    }

    public List<String> getTurnOrder() {
        return turnOrder;
    }

    public void setTurnOrder(List<String> turnOrder) {
        this.turnOrder = turnOrder;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(int turnIndex) {
        this.turnIndex = turnIndex;
    }

    public long getRoundStartTime() {
        return roundStartTime;
    }

    public void setRoundStartTime(long roundStartTime) {
        this.roundStartTime = roundStartTime;
    }

    public long getRoundDeadline() {
        return roundDeadline;
    }

    public void setRoundDeadline(long roundDeadline) {
        this.roundDeadline = roundDeadline;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public void setLastActivityTime(long lastActivityTime) {
        this.lastActivityTime = lastActivityTime;
    }

    public int nextGuessSequence() {
        return guessSequenceCounter.incrementAndGet();
    }

    public void resetGuessSequence() {
        guessSequenceCounter.set(0);
    }

    public Set<String> getRoundCorrectGuessers() {
        return roundCorrectGuessers;
    }

    public Set<String> getRoundScoredTeams() {
        return roundScoredTeams;
    }

    public Movie getCurrentMovie() {
        return currentMovie;
    }

    public void setCurrentMovie(Movie currentMovie) {
        this.currentMovie = currentMovie;
        if (currentMovie != null) {
            usedMovieIds.add(currentMovie.getId());
        }
    }

    public Set<String> getUsedMovieIds() {
        return usedMovieIds;
    }

    public void addChatMessage(ChatMessage msg) {
        recentChat.add(msg);
        while (recentChat.size() > 50) {
            recentChat.pollFirst();
        }
    }

    public List<ChatMessage> getRecentChat() {
        return new ArrayList<>(recentChat);
    }
}

