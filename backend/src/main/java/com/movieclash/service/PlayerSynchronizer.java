package com.movieclash.service;

import com.movieclash.manager.RoomManager;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.dto.PlayerDTO;
import com.movieclash.model.dto.RoomSnapshotDTO;
import com.movieclash.model.dto.TeamDTO;
import com.movieclash.model.protocol.WsEnvelope;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PlayerSynchronizer {
    private final RoomManager roomManager;
    private final RoomBroadcaster broadcaster;

    public PlayerSynchronizer(RoomManager roomManager, RoomBroadcaster broadcaster) {
        this.roomManager = roomManager;
        this.broadcaster = broadcaster;
    }

    public RoomSnapshotDTO createSnapshot(Room room) {
        return RoomSnapshotDTO.from(room);
    }

    /**
     * Broadcasts a delta containing updated players list and teams to a room
     */
    public void broadcastRosterDelta(String roomId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.executeLocked(() -> {
            List<PlayerDTO> playerDTOs = room.getPlayers().values().stream()
                .map(PlayerDTO::from)
                .sorted(Comparator.comparingInt(PlayerDTO::score).reversed())
                .toList();

            Map<String, TeamDTO> teamDTOs = room.getTeams().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> TeamDTO.from(e.getValue())));

            Map<String, Object> delta = new HashMap<>();
            delta.put("players", playerDTOs);
            if (room.getGameMode() == com.movieclash.model.enums.GameMode.TEAM) {
                delta.put("teams", teamDTOs);
            }
            delta.put("hostId", room.getHostId());

            broadcaster.broadcastToRoom(roomId, WsEnvelope.of("ROOM_UPDATED", roomId, Map.of("delta", delta)));
        });
    }

    /**
     * Broadcasts updated player presence (connected/disconnected)
     */
    public void broadcastPresenceDelta(String roomId, String playerId, boolean connected) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        Player player = room.getPlayer(playerId);
        if (player == null) return;

        Map<String, Object> delta = Map.of(
            "playerId", playerId,
            "connected", connected
        );

        broadcaster.broadcastToRoom(roomId, WsEnvelope.of("ROOM_UPDATED", roomId, Map.of("delta", delta)));
    }
}

