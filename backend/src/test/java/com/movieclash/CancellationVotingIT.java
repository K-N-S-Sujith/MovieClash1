package com.movieclash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Room;
import com.movieclash.model.enums.RoomStatus;
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
public class CancellationVotingIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomManager roomManager;

    @Test
    void testCancellationVotingApprovalFlow() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        // 1. Host (Alice) creates room
        WebSocketGatewayIT.TestWebSocketHandler aliceHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession aliceSession = client.execute(aliceHandler, url).get(5, TimeUnit.SECONDS);

        aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CREATE_ROOM", null, Map.of("playerName", "Alice", "gameMode", "INDIVIDUAL"))
        )));

        WsEnvelope roomCreated = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roomCreated);
        String roomId = (String) roomCreated.getPayload().get("roomId");
        String aliceId = (String) roomCreated.getPayload().get("playerId");

        // 2. Bob joins
        WebSocketGatewayIT.TestWebSocketHandler bobHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession bobSession = client.execute(bobHandler, url).get(5, TimeUnit.SECONDS);

        bobSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of("roomId", roomId, "playerName", "Bob"))
        )));

        WsEnvelope bobJoined = bobHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(bobJoined);
        String bobId = (String) bobJoined.getPayload().get("yourPlayerId");
        aliceHandler.received.poll(5, TimeUnit.SECONDS);

        // 3. Charlie joins
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

        // 4. Alice starts game
        aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        // Drain GAME_STARTED, ROUND_STARTED, and CLUE_GIVER_ANSWER
        aliceHandler.received.poll(5, TimeUnit.SECONDS);
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);

        aliceHandler.received.poll(5, TimeUnit.SECONDS);
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);

        // Drain CLUE_GIVER_ANSWER from whoever is clue-giver
        Room room = roomManager.getRoom(roomId);
        assertEquals(RoomStatus.IN_PROGRESS, room.getStatus());
        if (aliceId.equals(room.getCurrentClueGiverId())) {
            aliceHandler.received.poll(5, TimeUnit.SECONDS);
        } else if (bobId.equals(room.getCurrentClueGiverId())) {
            bobHandler.received.poll(5, TimeUnit.SECONDS);
        } else {
            charlieHandler.received.poll(5, TimeUnit.SECONDS);
        }

        // 5. Non-host (Bob) attempts to propose cancel -> NOT_HOST error
        bobSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CANCEL_PROPOSE", roomId, Map.of())
        )));

        WsEnvelope notHostErr = bobHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(notHostErr);
        assertEquals("ERROR", notHostErr.getType());
        assertEquals("NOT_HOST", notHostErr.getPayload().get("code"));

        // 6. Host (Alice) proposes cancellation -> CANCEL_VOTE_REQUESTED to all 3
        aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CANCEL_PROPOSE", roomId, Map.of())
        )));

        WsEnvelope reqAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        WsEnvelope reqBob = bobHandler.received.poll(5, TimeUnit.SECONDS);
        WsEnvelope reqCharlie = charlieHandler.received.poll(5, TimeUnit.SECONDS);

        assertNotNull(reqAlice);
        assertEquals("CANCEL_VOTE_REQUESTED", reqAlice.getType());
        assertNotNull(reqBob);
        assertEquals("CANCEL_VOTE_REQUESTED", reqBob.getType());
        assertNotNull(reqCharlie);
        assertEquals("CANCEL_VOTE_REQUESTED", reqCharlie.getType());

        // 7. Bob votes NO
        bobSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CANCEL_VOTE", roomId, Map.of("vote", false))
        )));

        // 8. Bob attempts to vote again -> ALREADY_VOTED error
        bobSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CANCEL_VOTE", roomId, Map.of("vote", false))
        )));

        WsEnvelope alreadyVotedErr = bobHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(alreadyVotedErr);
        assertEquals("ERROR", alreadyVotedErr.getType());
        assertEquals("ALREADY_VOTED", alreadyVotedErr.getPayload().get("code"));

        // 9. Charlie votes YES -> Majority reached (Alice YES + Charlie YES = 2/3 votes)
        charlieSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CANCEL_VOTE", roomId, Map.of("vote", true))
        )));

        // All 3 receive CANCEL_VOTE_RESULT (approved: true)
        WsEnvelope resAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(resAlice);
        assertEquals("CANCEL_VOTE_RESULT", resAlice.getType());
        assertEquals(true, resAlice.getPayload().get("approved"));
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);

        // All 3 receive GAME_CANCELLED
        WsEnvelope cancelAlice = aliceHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(cancelAlice);
        assertEquals("GAME_CANCELLED", cancelAlice.getType());
        bobHandler.received.poll(5, TimeUnit.SECONDS);
        charlieHandler.received.poll(5, TimeUnit.SECONDS);

        // Room returns to LOBBY
        assertEquals(RoomStatus.LOBBY, room.getStatus());

        aliceSession.close();
        bobSession.close();
        charlieSession.close();
    }
}

