package com.movieclash;

import com.movieclash.config.GameConfig;
import com.movieclash.manager.PlayerSessionManager;
import com.movieclash.manager.RoomManager;
import com.movieclash.manager.RoundManager;
import com.movieclash.manager.ScoreSynchronizer;
import com.movieclash.model.Room;
import com.movieclash.model.enums.GameMode;
import com.movieclash.model.enums.RoomStatus;
import com.movieclash.service.RoomBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ScoreSynchronizerTest {

    private RoomManager roomManager;
    private PlayerSessionManager sessionManager;
    private RoomBroadcaster mockBroadcaster;
    private RoundManager mockRoundManager;
    private ScoreSynchronizer scoreSynchronizer;

    @BeforeEach
    void setUp() {
        sessionManager = new PlayerSessionManager();
        roomManager = new RoomManager(sessionManager);
        mockBroadcaster = Mockito.mock(RoomBroadcaster.class);
        mockRoundManager = Mockito.mock(RoundManager.class);
        scoreSynchronizer = new ScoreSynchronizer(roomManager, mockBroadcaster, mockRoundManager);
    }

    @Test
    void testRejectsNonClueGiverScoreUpdate() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("HostAlice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = hostRes.room().getRoomId();
        Room room = hostRes.room();

        RoomManager.JoinRoomResult bobRes = roomManager.joinRoom(roomId, "Bob");
        String bobId = bobRes.player().getId();

        room.setStatus(RoomStatus.IN_PROGRESS);
        room.setCurrentClueGiverId("alice-id");

        // Bob tries to send score update (Bob is not clue-giver)
        scoreSynchronizer.processScoreUpdate(roomId, bobId, "evt-1", Map.of(
            "guesserId", bobId,
            "isCorrect", true,
            "points", 150
        ));

        // Score must NOT increase
        assertEquals(0, bobRes.player().getScore());
        Mockito.verify(mockBroadcaster, Mockito.times(1))
                .sendPrivate(Mockito.eq(bobId), Mockito.argThat(env -> "ERROR".equals(env.getType())));
    }

    @Test
    void testPointsAreCappedToConfigMax() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("HostAlice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = hostRes.room().getRoomId();
        Room room = hostRes.room();
        String aliceId = hostRes.hostPlayer().getId();

        RoomManager.JoinRoomResult bobRes = roomManager.joinRoom(roomId, "Bob");
        String bobId = bobRes.player().getId();

        room.setStatus(RoomStatus.IN_PROGRESS);
        room.setCurrentClueGiverId(aliceId);
        room.setRoundDeadline(System.currentTimeMillis() + 60000L);

        // Clue-giver attempts to award 9999 points (hacked browser)
        scoreSynchronizer.processScoreUpdate(roomId, aliceId, "evt-hack-1", Map.of(
            "guesserId", bobId,
            "isCorrect", true,
            "points", 9999,
            "clueGiverPoints", 5000
        ));

        // Bob's points must be clamped to max (100 base + 50 bonus = 150)
        assertEquals(150, bobRes.player().getScore(), "Points must be capped at 150");
        // Alice's clue-giver reward must be capped to 25
        assertEquals(25, hostRes.hostPlayer().getScore(), "Clue giver reward must be capped at 25");
    }

    @Test
    void testDuplicateScoreEventsAreIdempotent() {
        RoomManager.CreateRoomResult hostRes = roomManager.createRoom("HostAlice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = hostRes.room().getRoomId();
        Room room = hostRes.room();
        String aliceId = hostRes.hostPlayer().getId();

        RoomManager.JoinRoomResult bobRes = roomManager.joinRoom(roomId, "Bob");
        String bobId = bobRes.player().getId();

        room.setStatus(RoomStatus.IN_PROGRESS);
        room.setCurrentClueGiverId(aliceId);
        room.setRoundDeadline(System.currentTimeMillis() + 60000L);

        // First score update
        scoreSynchronizer.processScoreUpdate(roomId, aliceId, "evt-dup-1", Map.of(
            "guesserId", bobId,
            "isCorrect", true,
            "points", 150,
            "clueGiverPoints", 25
        ));

        assertEquals(150, bobRes.player().getScore());

        // Duplicate score update with SAME eventId or for same guesser
        scoreSynchronizer.processScoreUpdate(roomId, aliceId, "evt-dup-1", Map.of(
            "guesserId", bobId,
            "isCorrect", true,
            "points", 150,
            "clueGiverPoints", 25
        ));

        // Points must NOT double!
        assertEquals(150, bobRes.player().getScore(), "Score must remain 150 upon duplicate event");
    }
}
