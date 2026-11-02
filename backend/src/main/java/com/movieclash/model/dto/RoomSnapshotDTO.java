package com.movieclash.model.dto;

import com.movieclash.config.GameConfig;
import com.movieclash.model.Room;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record RoomSnapshotDTO(
    String roomId,
    String hostId,
    GameMode gameMode,
    RoomStatus status,
    GameConfig config,
    List<PlayerDTO> players,
    Map<String, TeamDTO> teams,
    int currentRound,
    int totalRounds,
    String currentClueGiverId,
    String currentClueGiverTeam,
    String coHostId,
    String creatorUserId
) {
    public static RoomSnapshotDTO from(Room room) {
        if (room == null) return null;

        List<PlayerDTO> playerDTOs = room.getPlayers().values().stream()
            .map(PlayerDTO::from)
            .toList();

        Map<String, TeamDTO> teamDTOs = room.getTeams().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> TeamDTO.from(e.getValue())));

        return new RoomSnapshotDTO(
            room.getRoomId(),
            room.getHostId(),
            room.getGameMode(),
            room.getStatus(),
            room.getConfig(),
            playerDTOs,
            teamDTOs,
            room.getCurrentRound(),
            room.getTotalRounds(),
            room.getCurrentClueGiverId(),
            room.getCurrentClueGiverTeam(),
            room.getCoHostId(),
            room.getCreatorUserId()
        );
    }
}

