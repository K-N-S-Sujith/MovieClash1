package com.movieclash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Room;
import com.movieclash.model.protocol.WsEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TeamBattleModeIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomManager roomManager;

    @Test
    void testTeamBattleLifecycleAndRules() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        // 1. Host creates room in TEAM mode
        WebSocketGatewayIT.TestWebSocketHandler aliceHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession aliceSession = client.execute(aliceHandler, url).get(5, TimeUnit.SECONDS);

        aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CREATE_ROOM", null, Map.of(
                "playerName", "Alice",
                "gameMode", "TEAM",
                "config", Map.of(
                    "roundDurationSec", 30,
                    "totalRounds", 2, // 2 turns per team = 4 total rounds
                    "correctGuessPoints", 100,
                    "firstGuessBonus", 50,
                    "clueGiverRewardPerGuesser", 25
                )
            ))
        )));

        WsEnvelope roomCreatedEnv = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roomCreatedEnv);
        String roomId = (String) roomCreatedEnv.getPayload().get("roomId");
        String aliceId = (String) roomCreatedEnv.getPayload().get("playerId");

        // 2. Bob joins (auto-balanced to TEAM_B)
        WebSocketGatewayIT.TestWebSocketHandler bobHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession bobSession = client.execute(bobHandler, url).get(5, TimeUnit.SECONDS);

        bobSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of("roomId", roomId, "playerName", "Bob"))
        )));

        WsEnvelope bobJoined = bobHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(bobJoined);
        String bobId = (String) bobJoined.getPayload().get("yourPlayerId");
        aliceHandler.received.poll(5, TimeUnit.SECONDS); // Drain broadcast

        // 3. Charlie joins (auto-balanced to TEAM_A)
        WebSocketGatewayIT.TestWebSocketHandler charlieHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession charlieSession = client.execute(charlieHandler, url).get(5, TimeUnit.SECONDS);

        charlieSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of("roomId", roomId, "playerName", "Charlie"))
        )));

        WsEnvelope charlieJoined = charlieHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(charlieJoined);
        String charlieId = (String) charlieJoined.getPayload().get("yourPlayerId");
        aliceHandler.received.poll(5, TimeUnit.SECONDS);
        bobHandler.received.poll(5, TimeUnit.SECONDS);

        // 4. Dave joins (auto-balanced to TEAM_B)
        WebSocketGatewayIT.TestWebSocketHandler daveHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession daveSession = client.execute(daveHandler, url).get(5, TimeUnit.SECONDS);

        daveSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of("roomId", roomId, "playerName", "Dave"))
        )));

        WsEnvelope daveJoined = daveHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(daveJoined);
        String daveId = (String) daveJoined.getPayload().get("yourPlayerId");
        aliceHandler.received.poll(5, TimeUnit.SECONDS);
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);

        // Verify team rosters: Team A = [Alice, Charlie], Team B = [Bob, Dave]
        Room room = roomManager.getRoom(roomId);
        assertEquals("TEAM_A", room.getPlayer(aliceId).getTeamId());
        assertEquals("TEAM_A", room.getPlayer(charlieId).getTeamId());
        assertEquals("TEAM_B", room.getPlayer(bobId).getTeamId());
        assertEquals("TEAM_B", room.getPlayer(daveId).getTeamId());

        // 5. Host (Alice) starts game
        aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        // Drain GAME_STARTED for all 4 players
        WsEnvelope gStartedAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(gStartedAlice);
        assertEquals("GAME_STARTED", gStartedAlice.getType());
        assertEquals(4, gStartedAlice.getPayload().get("totalRounds")); // 2 teams * 2 turns = 4 rounds
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);
        daveHandler.received.poll(5, TimeUnit.SECONDS);

        // Drain ROUND_STARTED for all 4 players
        WsEnvelope rStartedAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(rStartedAlice);
        assertEquals("ROUND_STARTED", rStartedAlice.getType());
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);
        daveHandler.received.poll(5, TimeUnit.SECONDS);

        // Team Alpha is first: clue-giver is Alice (from Team A)
        String r1ClueGiverId = (String) rStartedAlice.getPayload().get("clueGiverId");
        assertEquals(aliceId, r1ClueGiverId);
        assertEquals("TEAM_A", rStartedAlice.getPayload().get("clueGiverTeam"));

        // Alice receives secret CLUE_GIVER_ANSWER privately
        WsEnvelope secretMovieAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(secretMovieAlice);
        assertEquals("CLUE_GIVER_ANSWER", secretMovieAlice.getType());
        String secretTitle = (String) secretMovieAlice.getPayload().get("title");
        assertNotNull(secretTitle);

        // 6. Charlie (Alice's teammate on TEAM_A) tries to submit guess -> BLOCKED!
        charlieSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("SUBMIT_GUESS", roomId, Map.of("guessText", secretTitle))
        )));

        WsEnvelope charlieBlocked = charlieHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(charlieBlocked);
        assertEquals("ERROR", charlieBlocked.getType());
        assertEquals("UNAUTHORIZED", charlieBlocked.getPayload().get("code"));

        // 7. Bob (TEAM_B) submits correct guess -> Privately forwarded to clue-giver (Alice)
        bobSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("SUBMIT_GUESS", roomId, Map.of("guessText", secretTitle))
        )));

        WsEnvelope forwardToAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(forwardToAlice);
        assertEquals("FORWARD_GUESS", forwardToAlice.getType());
        assertEquals(bobId, forwardToAlice.getPayload().get("guesserId"));
        assertEquals("TEAM_B", forwardToAlice.getPayload().get("guesserTeam"));

        // 8. Alice (clue-giver) validates and sends authoritative SCORE_UPDATE
        aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("SCORE_UPDATE", roomId, Map.of(
                "guesserId", bobId,
                "guesserTeam", "TEAM_B",
                "isCorrect", true,
                "points", 150, // 100 + 50 first guess bonus
                "clueGiverPoints", 25,
                "guessText", secretTitle
            ))
        )));

        // Bob receives private GUESS_RESULT
        WsEnvelope bobResult = bobHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(bobResult);
        assertEquals("GUESS_RESULT", bobResult.getType());
        assertEquals(150, bobResult.getPayload().get("points"));

        // All 4 receive SCORE_BROADCAST with updated team scores!
        WsEnvelope scoreBcAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(scoreBcAlice);
        assertEquals("SCORE_BROADCAST", scoreBcAlice.getType());
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);
        daveHandler.received.poll(5, TimeUnit.SECONDS);

        @SuppressWarnings("unchecked")
        Map<String, Object> teamsInBroadcast = (Map<String, Object>) scoreBcAlice.getPayload().get("teams");
        assertNotNull(teamsInBroadcast);

        @SuppressWarnings("unchecked")
        Map<String, Object> teamB = (Map<String, Object>) teamsInBroadcast.get("TEAM_B");
        assertEquals(150, teamB.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> teamA = (Map<String, Object>) teamsInBroadcast.get("TEAM_A");
        assertEquals(25, teamA.get("score")); // Alice's team earned +25 for Team B's correct guess

        // 9. Early round finish: Since Team B was the only opposing team, round ends early!
        WsEnvelope roundFinishedAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roundFinishedAlice);
        assertEquals("ROUND_FINISHED", roundFinishedAlice.getType());
        assertEquals("ALL_GUESSED", roundFinishedAlice.getPayload().get("reason"));

        // 10. Intra-team rotation verification:
        // Turn order was built as: [Alice (A), Bob (B), Charlie (A), Dave (B)]
        assertEquals(4, room.getTurnOrder().size());
        assertEquals(aliceId, room.getTurnOrder().get(0));
        assertEquals(bobId, room.getTurnOrder().get(1));
        assertEquals(charlieId, room.getTurnOrder().get(2)); // Charlie rotates as next clue-giver for Team A!
        assertEquals(daveId, room.getTurnOrder().get(3));    // Dave rotates as next clue-giver for Team B!

        aliceSession.close();
        bobSession.close();
        charlieSession.close();
        daveSession.close();
    }
}

