package com.movieclash;

import com.movieclash.config.GameConfig;
import com.movieclash.manager.PlayerSessionManager;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.model.protocol.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RoomManagerTest {

    private RoomManager roomManager;
    private PlayerSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new PlayerSessionManager();
        roomManager = new RoomManager(sessionManager);
    }

    @Test
    void testCreateRoomGeneratesSixCharRoomCodeAndHost() {
        RoomManager.CreateRoomResult result = roomManager.createRoom("HostAlice", GameMode.INDIVIDUAL, new GameConfig());
        assertNotNull(result);
        assertNotNull(result.room());
        assertEquals(6, result.room().getRoomId().length(), "Room ID should be 6 characters");
        assertEquals("HostAlice", result.hostPlayer().getName());
        assertTrue(result.hostPlayer().isHost());
        assertNotNull(result.reconnectToken());
        assertEquals(RoomStatus.LOBBY, result.room().getStatus());
        assertEquals(1, result.room().getPlayers().size());
    }

    @Test
    void testJoinRoomAddsPlayerSuccessfully() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("Alice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = hostRes.room().getRoomId();

        RoomManager.JoinRoomResult joinRes = roomManager.joinRoom(roomId, "Bob");
        assertNotNull(joinRes);
        assertEquals("Bob", joinRes.player().getName());
        assertFalse(joinRes.player().isHost());
        assertNotNull(joinRes.reconnectToken());

        Room room = roomManager.getRoom(roomId);
        assertEquals(2, room.getPlayers().size());
        assertNotNull(room.getPlayer(joinRes.player().getId()));
    }

    @Test
    void testJoinRoomEnforcesMaxPlayerCap() {
        GameConfig config = new GameConfig();
        config.setMaxRoomPlayers(3); // Cap at 3 players for test

        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("P1", GameMode.INDIVIDUAL, config);
        String roomId = hostRes.room().getRoomId();

        roomManager.joinRoom(roomId, "P2");
        roomManager.joinRoom(roomId, "P3");

        // 4th player should fail
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            roomManager.joinRoom(roomId, "P4");
        });
        assertEquals(ErrorCode.ROOM_FULL.name(), ex.getMessage());
    }

    @Test
    void testLeaveRoomTransfersHostWhenHostLeaves() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("Alice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = hostRes.room().getRoomId();
        String aliceId = hostRes.hostPlayer().getId();

        RoomManager.JoinRoomResult bobRes = roomManager.joinRoom(roomId, "Bob");
        String bobId = bobRes.player().getId();

        RoomManager.LeaveRoomResult leaveRes = roomManager.leaveRoom(roomId, aliceId);
        assertFalse(leaveRes.roomDeleted());
        assertEquals(bobId, leaveRes.newHostId(), "Host should transfer to Bob");

        Room room = roomManager.getRoom(roomId);
        assertEquals(bobId, room.getHostId());
        assertTrue(room.getPlayer(bobId).isHost());
        assertEquals(1, room.getPlayers().size());
    }

    @Test
    void testLeaveRoomCleansUpEmptyRoom() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("SoloAlice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = hostRes.room().getRoomId();
        String aliceId = hostRes.hostPlayer().getId();

        RoomManager.LeaveRoomResult leaveRes = roomManager.leaveRoom(roomId, aliceId);
        assertTrue(leaveRes.roomDeleted(), "Room should be deleted when last player leaves");
        assertNull(roomManager.getRoom(roomId), "Room should not exist in room map");
    }

    @Test
    void testSanitizePlayerName() {
        assertEquals("CleanName", roomManager.sanitizePlayerName("<script>alert('xss')</script>CleanName"));
        assertEquals("VeryLongPlayerName", roomManager.sanitizePlayerName("VeryLongPlayerNameWithMoreThanEighteenChars"));
        assertEquals("Normal", roomManager.sanitizePlayerName("   Normal   \n\t"));
    }

    @Test
    void testTeamModeAutoBalancesAndAllowsManualAssignment() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("LeaderA", GameMode.TEAM, new GameConfig());
        String roomId = hostRes.room().getRoomId();
        assertEquals("TEAM_A", hostRes.hostPlayer().getTeamId(), "Host should be assigned to TEAM_A");

        // Player 2 auto-balanced to TEAM_B
        RoomManager.JoinRoomResult p2Res = roomManager.joinRoom(roomId, "PlayerB");
        assertEquals("TEAM_B", p2Res.player().getTeamId(), "Second player should balance to TEAM_B");

        // Player 3 auto-balanced to TEAM_A
        RoomManager.JoinRoomResult p3Res = roomManager.joinRoom(roomId, "PlayerC");
        assertEquals("TEAM_A", p3Res.player().getTeamId());

        // Player 3 manually switches to TEAM_B
        boolean moved = roomManager.assignTeam(roomId, p3Res.player().getId(), "TEAM_B");
        assertTrue(moved);
        assertEquals("TEAM_B", p3Res.player().getTeamId());
        assertEquals(2, roomManager.getRoom(roomId).getTeams().get("TEAM_B").getPlayers().size());
    }

    @Test
    void testTeamModeTeamLimitAndMaxPlayers() {
        // Test creating room with 4 teams
        RoomManager.CreateRoomResult res = roomManager.createRoom("Leader", GameMode.TEAM, new GameConfig(), 4);
        Room room = res.room();
        assertEquals(4, room.getTeams().size());
        assertTrue(room.getTeams().containsKey("TEAM_A"));
        assertTrue(room.getTeams().containsKey("TEAM_B"));
        assertTrue(room.getTeams().containsKey("TEAM_C"));
        assertTrue(room.getTeams().containsKey("TEAM_D"));

        // Fill TEAM_A up to 5 players
        String roomId = room.getRoomId();
        for (int i = 2; i <= 5; i++) {
            RoomManager.JoinRoomResult jr = roomManager.joinRoom(roomId, "A" + i);
            roomManager.assignTeam(roomId, jr.player().getId(), "TEAM_A");
        }
        assertEquals(5, room.getTeams().get("TEAM_A").getPlayers().size());

        // 6th player moving to TEAM_A should be rejected (max 5 players per team)
        RoomManager.JoinRoomResult p6 = roomManager.joinRoom(roomId, "ExtraPlayer");
        boolean moved = roomManager.assignTeam(roomId, p6.player().getId(), "TEAM_A");
        assertFalse(moved, "Should not exceed 5 players per team");
    }

    @Test
    void testDuplicatePlayerNameAutoNumbered() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("Alex", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = hostRes.room().getRoomId();

        RoomManager.JoinRoomResult p2 = roomManager.joinRoom(roomId, "Alex");
        assertEquals("Alex 2", p2.player().getName());

        RoomManager.JoinRoomResult p3 = roomManager.joinRoom(roomId, "Alex");
        assertEquals("Alex 3", p3.player().getName());
    }
}
