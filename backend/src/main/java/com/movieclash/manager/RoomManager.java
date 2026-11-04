package com.movieclash.manager;

import com.movieclash.config.GameConfig;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.Team;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomManager {
    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);
    private static final String ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 32 unambiguous chars
    private static final int ROOM_CODE_LENGTH = 6;
    private static final long ROOM_IDLE_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final PlayerSessionManager sessionManager;
    private final SecureRandom secureRandom = new SecureRandom();

    public RoomManager(PlayerSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public record CreateRoomResult(Room room, Player hostPlayer, String reconnectToken) {}
    public record JoinRoomResult(Room room, Player player, String reconnectToken) {}

    public CreateRoomResult createRoom(String rawHostName, GameMode mode, GameConfig config) {
        return createRoom(rawHostName, null, mode, config, 2);
    }

    public CreateRoomResult createRoom(String rawHostName, GameMode mode, GameConfig config, int teamCount) {
        return createRoom(rawHostName, null, mode, config, teamCount);
    }

    public CreateRoomResult createRoom(String rawHostName, String userId, GameMode mode, GameConfig config, int teamCount) {
        String sanitizedName = sanitizePlayerName(rawHostName);
        if (sanitizedName.isEmpty()) {
            sanitizedName = "Host_" + (1000 + secureRandom.nextInt(9000));
        }

        String roomId = generateUniqueRoomCode();
        String hostId = UUID.randomUUID().toString();
        String reconnectToken = sessionManager.generateReconnectToken();

        Player host = new Player(hostId, sanitizedName, reconnectToken);
        host.setUserId(userId != null && !userId.isBlank() ? userId : "usr_" + UUID.randomUUID().toString().substring(0, 8));
        host.setHost(true);

        Room room = new Room(roomId, hostId, mode != null ? mode : GameMode.INDIVIDUAL, config, teamCount);
        room.setCreatorUserId(host.getUserId());
        room.setOriginalHostPlayerId(hostId);
        room.addPlayer(host);

        if (room.getGameMode() == GameMode.TEAM) {
            host.setTeamId("TEAM_A");
            Team teamA = room.getTeams().get("TEAM_A");
            if (teamA != null) {
                teamA.getPlayers().add(host);
            }
        }

        rooms.put(roomId, room);
        log.info("Created room [{}] in mode {} by host [{}] (userId: {}, playerId: {})",
                roomId, room.getGameMode(), host.getName(), host.getUserId(), hostId);
        return new CreateRoomResult(room, host, reconnectToken);
    }

    public JoinRoomResult joinRoom(String roomId, String rawPlayerName) {
        return joinRoom(roomId, rawPlayerName, null, false);
    }

    public JoinRoomResult joinRoom(String roomId, String rawPlayerName, String userId) {
        return joinRoom(roomId, rawPlayerName, userId, false);
    }

    public JoinRoomResult joinRoom(String roomId, String rawPlayerName, String userId, boolean asSpectator) {
        if (roomId == null) {
            throw new IllegalStateException(ErrorCode.INVALID_ROOM_CODE.name());
        }

        String code = roomId.trim().toUpperCase();
        Room room = rooms.get(code);
        if (room == null) {
            throw new IllegalStateException(ErrorCode.ROOM_NOT_FOUND.name());
        }

        return room.executeLocked(() -> {
            // Check if user is returning by matching persistent userId
            Player existingPlayer = null;
            if (userId != null && !userId.isBlank()) {
                existingPlayer = room.getPlayers().values().stream()
                        .filter(p -> userId.equals(p.getUserId()))
                        .findFirst()
                        .orElse(null);
            }

            if (existingPlayer != null) {
                // Player is returning to the room
                existingPlayer.setConnected(true);
                existingPlayer.setDisconnectedAt(0);

                boolean isCreator = userId.equals(room.getCreatorUserId()) || existingPlayer.getId().equals(room.getOriginalHostPlayerId());
                if (isCreator) {
                    room.setHostId(existingPlayer.getId());
                    existingPlayer.setHost(true);
                    if (existingPlayer.getId().equals(room.getCoHostId())) {
                        room.setCoHostId(null);
                    }
                    log.info("Original host [{}] ({}) returned to room [{}] and reclaimed primary host status",
                            existingPlayer.getName(), existingPlayer.getId(), code);
                }

                return new JoinRoomResult(room, existingPlayer, existingPlayer.getReconnectToken());
            }

            if (!asSpectator && room.getStatus() != RoomStatus.LOBBY) {
                throw new IllegalStateException(ErrorCode.GAME_ALREADY_STARTED.name());
            }

            int maxPlayers = room.getConfig() != null ? room.getConfig().getMaxRoomPlayers() : 20;
            if (room.getPlayers().size() >= maxPlayers) {
                throw new IllegalStateException(ErrorCode.ROOM_FULL.name());
            }

            String sanitizedName = sanitizePlayerName(rawPlayerName);
            if (sanitizedName.isEmpty()) {
                sanitizedName = "Player_" + (1000 + secureRandom.nextInt(9000));
            }

            // Deduplicate name in room if necessary
            String finalName = makeUniqueName(room, sanitizedName);

            String playerId = UUID.randomUUID().toString();
            String reconnectToken = sessionManager.generateReconnectToken();
            Player player = new Player(playerId, finalName, reconnectToken);
            player.setUserId(userId != null && !userId.isBlank() ? userId : "usr_" + UUID.randomUUID().toString().substring(0, 8));
            player.setSpectator(asSpectator);

            boolean isCreator = userId != null && userId.equals(room.getCreatorUserId());
            if (isCreator) {
                room.setHostId(playerId);
                player.setHost(true);
                log.info("Original creator [{}] returned to room [{}] as new session and reclaimed host status",
                        player.getName(), code);
            } else if (room.getPlayers().isEmpty()) {
                room.setHostId(playerId);
                player.setHost(true);
            }

            // In team mode, auto-balance onto team with fewer players (non-spectators only)
            if (!asSpectator && room.getGameMode() == GameMode.TEAM) {
                String targetTeam = findBalancedTeam(room);
                player.setTeamId(targetTeam);
                Team team = room.getTeams().get(targetTeam);
                if (team != null) {
                    team.getPlayers().add(player);
                }
            }

            room.addPlayer(player);
            log.info("Player [{}] ({}) joined room [{}] (isHost: {}, spectator: {})", player.getName(), playerId, code, player.isHost(), player.isSpectator());
            return new JoinRoomResult(room, player, reconnectToken);
        });
    }

    public void setPlayerSpectator(String roomId, String playerId, boolean spectator) {
        Room room = rooms.get(roomId.toUpperCase());
        if (room == null) return;

        room.executeLocked(() -> {
            Player player = room.getPlayer(playerId);
            if (player == null) return;

            player.setSpectator(spectator);

            if (spectator) {
                // If spectator in team mode, remove from any and all teams in the room
                for (Team t : room.getTeams().values()) {
                    t.getPlayers().removeIf(p -> p.getId().equals(playerId));
                }
                player.setTeamId(null);
            } else {
                // If switching back to active player in team mode, assign to balanced team if no team
                if (room.getGameMode() == GameMode.TEAM && player.getTeamId() == null) {
                    String targetTeam = findBalancedTeam(room);
                    player.setTeamId(targetTeam);
                    Team team = room.getTeams().get(targetTeam);
                    if (team != null) {
                        team.getPlayers().removeIf(p -> p.getId().equals(playerId));
                        team.getPlayers().add(player);
                    }
                }
            }
        });
    }

    public record LeaveRoomResult(String newHostId, boolean roomDeleted, String playerName) {}

    public LeaveRoomResult leaveRoom(String roomId, String playerId) {
        if (roomId == null || playerId == null) return new LeaveRoomResult(null, false, null);

        Room room = rooms.get(roomId.toUpperCase());
        if (room == null) return new LeaveRoomResult(null, false, null);

        return room.executeLocked(() -> {
            Player removed = room.removePlayer(playerId);
            String removedName = removed != null ? removed.getName() : null;

            if (room.getPlayers().isEmpty()) {
                // DO NOT delete the room immediately! Keep it alive for grace period so host or friends can join
                room.setLastActivityTime(System.currentTimeMillis());
                log.info("Room [{}] has 0 active players; keeping room alive in grace period.", room.getRoomId());
                return new LeaveRoomResult(null, false, removedName);
            }

            String newHostId = room.getHostId();
            if (playerId.equals(room.getHostId())) {
                // Host left! Promote next player to Co-Host / Acting Host
                Optional<Player> nextHost = room.getPlayers().values().stream()
                        .filter(Player::isConnected)
                        .findFirst();

                if (nextHost.isEmpty()) {
                    nextHost = room.getPlayers().values().stream().findFirst();
                }

                if (nextHost.isPresent()) {
                    newHostId = nextHost.get().getId();
                    room.setCoHostId(newHostId);
                    room.setHostId(newHostId);
                    log.info("Host [{}] left room [{}]. Player [{}] ({}) promoted to Co-Host / Acting Host",
                            removedName, room.getRoomId(), nextHost.get().getName(), newHostId);
                }
            }

            return new LeaveRoomResult(newHostId, false, removedName);
        });
    }

    public boolean assignTeam(String roomId, String playerId, String targetTeamId) {
        if (roomId == null || playerId == null || targetTeamId == null) return false;
        Room room = rooms.get(roomId.trim().toUpperCase());
        if (room == null || room.getGameMode() != GameMode.TEAM) return false;

        return room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.LOBBY) return false;

            Team targetTeam = room.getTeams().get(targetTeamId);
            if (targetTeam == null) return false;

            // Maximum 5 players per team
            if (targetTeam.getPlayers().size() >= 5) {
                log.warn("Cannot move player [{}] to team [{}]: team is full (max 5 players)", playerId, targetTeamId);
                return false;
            }

            Player player = room.getPlayer(playerId);
            if (player == null) return false;

            // When assigning/switching to a team, player becomes an active player (not a spectator)
            player.setSpectator(false);

            // Remove from current team and any other team in room to prevent duplicates
            for (Team t : room.getTeams().values()) {
                t.getPlayers().removeIf(p -> p.getId().equals(playerId));
            }

            // Add to new team
            player.setTeamId(targetTeamId);
            targetTeam.getPlayers().add(player);
            log.info("Player [{}] switched to team [{}] in room [{}]", player.getName(), targetTeamId, roomId);
            return true;
        });
    }

    public boolean createTeam(String roomId, String playerId, String customName) {
        if (roomId == null) return false;
        Room room = rooms.get(roomId.trim().toUpperCase());
        if (room == null || room.getGameMode() != GameMode.TEAM) return false;

        return room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.LOBBY) return false;
            if (room.getTeams().size() >= 5) {
                log.warn("Cannot add team in room [{}]: maximum 5 teams reached", roomId);
                return false;
            }

            String[] teamKeys = {"TEAM_A", "TEAM_B", "TEAM_C", "TEAM_D", "TEAM_E"};
            String[] defaultNames = {"Team Alpha", "Team Beta", "Team Gamma", "Team Delta", "Team Epsilon"};

            String newKey = null;
            String defaultName = "New Team";
            for (int i = 0; i < teamKeys.length; i++) {
                if (!room.getTeams().containsKey(teamKeys[i])) {
                    newKey = teamKeys[i];
                    defaultName = defaultNames[i];
                    break;
                }
            }

            if (newKey == null) return false;

            String name = (customName != null && !customName.trim().isEmpty())
                ? customName.trim()
                : defaultName;
            if (name.length() > 24) name = name.substring(0, 24);

            Team newTeam = new Team(newKey, name);
            room.getTeams().put(newKey, newTeam);
            log.info("Team [{}] ({}) created in room [{}] by player [{}]", name, newKey, roomId, playerId);
            return true;
        });
    }

    public boolean renameTeam(String roomId, String playerId, String teamId, String newName) {
        if (roomId == null || teamId == null || newName == null) return false;
        String cleanName = newName.trim();
        if (cleanName.isEmpty()) return false;
        if (cleanName.length() > 24) cleanName = cleanName.substring(0, 24);

        Room room = rooms.get(roomId.trim().toUpperCase());
        if (room == null || room.getGameMode() != GameMode.TEAM) return false;

        String finalName = cleanName;
        return room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.LOBBY) return false;
            Team team = room.getTeams().get(teamId);
            if (team == null) return false;

            team.setName(finalName);
            log.info("Team [{}] in room [{}] renamed to [{}] by player [{}]", teamId, roomId, finalName, playerId);
            return true;
        });
    }

    public boolean removeTeam(String roomId, String playerId, String teamId) {
        if (roomId == null || teamId == null) return false;
        Room room = rooms.get(roomId.trim().toUpperCase());
        if (room == null || room.getGameMode() != GameMode.TEAM) return false;

        return room.executeLocked(() -> {
            if (room.getStatus() != RoomStatus.LOBBY) return false;
            if (room.getTeams().size() <= 2) {
                log.warn("Cannot remove team [{}] in room [{}]: minimum 2 teams required", teamId, roomId);
                return false;
            }

            Team team = room.getTeams().get(teamId);
            if (team == null) return false;

            if (!team.getPlayers().isEmpty()) {
                log.warn("Cannot remove team [{}] in room [{}]: team still has active players", teamId, roomId);
                return false;
            }

            room.getTeams().remove(teamId);
            log.info("Team [{}] removed in room [{}] by player [{}]", teamId, roomId, playerId);
            return true;
        });
    }

    public boolean disbandRoom(String roomId, String playerId) {
        if (roomId == null || playerId == null) return false;
        Room room = rooms.get(roomId.toUpperCase());
        if (room == null) return false;

        boolean isAuthorized = playerId.equals(room.getHostId())
                || playerId.equals(room.getOriginalHostPlayerId())
                || (room.getCreatorUserId() != null && room.getPlayer(playerId) != null && room.getCreatorUserId().equals(room.getPlayer(playerId).getUserId()));

        if (!isAuthorized) {
            log.warn("Player [{}] is not authorized to disband room [{}]", playerId, roomId);
            return false;
        }

        rooms.remove(roomId.toUpperCase());
        log.info("Room [{}] was permanently disbanded by host/creator [{}]", roomId, playerId);
        return true;
    }

    public Room getRoom(String roomId) {
        return roomId != null ? rooms.get(roomId.toUpperCase()) : null;
    }

    public Map<String, Room> getAllRooms() {
        return Collections.unmodifiableMap(rooms);
    }

    private String generateUniqueRoomCode() {
        for (int attempts = 0; attempts < 1000; attempts++) {
            StringBuilder sb = new StringBuilder(ROOM_CODE_LENGTH);
            for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
                int index = secureRandom.nextInt(ROOM_CODE_ALPHABET.length());
                sb.append(ROOM_CODE_ALPHABET.charAt(index));
            }
            String code = sb.toString();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate unique room code");
    }

    public String sanitizePlayerName(String input) {
        if (input == null) return "";
        // Strip script tags with their contents, any remaining HTML tags, and non-alphanumeric chars
        String cleaned = input.replaceAll("(?i)<script[\\s\\S]*?</script>", "")
                              .replaceAll("<[^>]*>", "")
                              .replaceAll("[^a-zA-Z0-9 _-]", "")
                              .trim();
        if (cleaned.length() > 18) {
            cleaned = cleaned.substring(0, 18).trim();
        }
        return cleaned;
    }

    private String makeUniqueName(Room room, String baseName) {
        Set<String> existingNames = new HashSet<>();
        for (Player p : room.getPlayers().values()) {
            existingNames.add(p.getName().toLowerCase());
        }

        if (!existingNames.contains(baseName.toLowerCase())) {
            return baseName;
        }

        for (int i = 2; i <= 99; i++) {
            String candidate = baseName + " " + i;
            if (!existingNames.contains(candidate.toLowerCase())) {
                return candidate;
            }
        }
        return baseName + " " + UUID.randomUUID().toString().substring(0, 4);
    }

    private String findBalancedTeam(Room room) {
        List<String> sortedTeamKeys = new ArrayList<>(room.getTeams().keySet());
        Collections.sort(sortedTeamKeys);

        String bestTeam = sortedTeamKeys.isEmpty() ? "TEAM_A" : sortedTeamKeys.get(0);
        int minCount = Integer.MAX_VALUE;

        for (String key : sortedTeamKeys) {
            Team team = room.getTeams().get(key);
            int count = team.getPlayers().size();
            // Up to 5 players per team
            if (count < 5 && count < minCount) {
                minCount = count;
                bestTeam = key;
            }
        }
        return bestTeam;
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupIdleRooms() {
        long now = System.currentTimeMillis();
        rooms.entrySet().removeIf(entry -> {
            Room r = entry.getValue();
            boolean isIdle = (now - r.getLastActivityTime()) > ROOM_IDLE_TIMEOUT_MS;
            boolean allDisconnectedOrEmpty = r.getPlayers().isEmpty() ||
                    r.getPlayers().values().stream().noneMatch(Player::isConnected);

            if (isIdle && allDisconnectedOrEmpty) {
                log.info("Cleaning up idle, empty room [{}]", r.getRoomId());
                return true;
            }
            return false;
        });
    }
}
