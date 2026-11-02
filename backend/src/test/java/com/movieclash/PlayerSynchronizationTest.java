package com.movieclash;

import com.movieclash.config.GameConfig;
import com.movieclash.manager.PlayerSessionManager;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Movie;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.dto.PlayerDTO;
import com.movieclash.model.dto.RoomSnapshotDTO;
import com.movieclash.model.enums.GameMode;
import com.movieclash.service.PlayerSynchronizer;
import com.movieclash.service.RoomBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PlayerSynchronizationTest {

    private RoomManager roomManager;
    private PlayerSessionManager sessionManager;
    private RoomBroadcaster mockBroadcaster;
    private PlayerSynchronizer playerSynchronizer;

    @BeforeEach
    void setUp() {
        sessionManager = new PlayerSessionManager();
        roomManager = new RoomManager(sessionManager);
        mockBroadcaster = Mockito.mock(RoomBroadcaster.class);
        playerSynchronizer = new PlayerSynchronizer(roomManager, mockBroadcaster);
    }

    @Test
    void testPlayerSnapshotExcludesSensitiveInformation() {
        RoomManager.CreateRoomResult res = roomManager.createRoom("HostAlice", GameMode.INDIVIDUAL, new GameConfig());
        Room room = res.room();
        Player host = res.hostPlayer();
        host.setWebSocketSessionId("sess-secret-123");
        room.setCurrentMovie(new Movie("m-001", "Secret Inception", 2010, List.of(), "Dream thief"));

        RoomSnapshotDTO snapshot = playerSynchronizer.createSnapshot(room);
        assertNotNull(snapshot);
        assertEquals(room.getRoomId(), snapshot.roomId());
        assertEquals(1, snapshot.players().size());

        PlayerDTO pDto = snapshot.players().get(0);
        assertEquals("HostAlice", pDto.name());
        assertEquals(0, pDto.score());
        assertTrue(pDto.isHost());

        // Ensure record has no secret fields
        assertFalse(snapshot.toString().contains("sess-secret-123"), "Snapshot must not contain webSocketSessionId");
        assertFalse(snapshot.toString().contains("Secret Inception"), "Snapshot must not contain secret movie before round finish");
    }

    @Test
    void testRosterDeltaBroadcastsSortedScores() {
        RoomManager.CreateRoomResult res = roomManager.createRoom("Alice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = res.room().getRoomId();
        Room room = res.room();

        RoomManager.JoinRoomResult bob = roomManager.joinRoom(roomId, "Bob");
        RoomManager.JoinRoomResult charlie = roomManager.joinRoom(roomId, "Charlie");

        // Alice: 50, Bob: 200, Charlie: 100
        res.hostPlayer().setScore(50);
        bob.player().setScore(200);
        charlie.player().setScore(100);

        playerSynchronizer.broadcastRosterDelta(roomId);

        // Verify broadcaster was called with delta
        Mockito.verify(mockBroadcaster, Mockito.times(1))
                .broadcastToRoom(Mockito.eq(roomId), Mockito.argThat(env -> {
                    assertEquals("ROOM_UPDATED", env.getType());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> delta = (Map<String, Object>) env.getPayload().get("delta");
                    assertNotNull(delta);
                    @SuppressWarnings("unchecked")
                    List<PlayerDTO> players = (List<PlayerDTO>) delta.get("players");
                    assertEquals(3, players.size());
                    assertEquals("Bob", players.get(0).name(), "Highest scorer should be first");
                    assertEquals("Charlie", players.get(1).name());
                    assertEquals("Alice", players.get(2).name());
                    return true;
                }));
    }
}
