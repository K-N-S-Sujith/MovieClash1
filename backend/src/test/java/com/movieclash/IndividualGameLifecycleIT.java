package com.movieclash;

import com.fasterxml.jackson.databind.ObjectMapper;
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
public class IndividualGameLifecycleIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testIndividualGameStartAndRoundLifecycle() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        // 1. Host creates room
        WebSocketGatewayIT.TestWebSocketHandler hostHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession hostSession = client.execute(hostHandler, url).get(5, TimeUnit.SECONDS);

        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CREATE_ROOM", null, Map.of(
                "playerName", "HostAlice",
                "gameMode", "INDIVIDUAL"
            ))
        )));

        WsEnvelope roomCreatedEnv = hostHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roomCreatedEnv);
        String roomId = (String) roomCreatedEnv.getPayload().get("roomId");
        String hostPlayerId = (String) roomCreatedEnv.getPayload().get("playerId");

        // 2. Guesser joins room
        WebSocketGatewayIT.TestWebSocketHandler guesserHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession guesserSession = client.execute(guesserHandler, url).get(5, TimeUnit.SECONDS);

        guesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of(
                "roomId", roomId,
                "playerName", "Bob"
            ))
        )));

        WsEnvelope guesserJoined = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(guesserJoined);
        hostHandler.received.poll(5, TimeUnit.SECONDS); // Drain broadcast on host

        // 3. Guesser attempts to start game -> should fail (NOT_HOST)
        guesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        WsEnvelope notHostError = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(notHostError);
        assertEquals("ERROR", notHostError.getType());
        assertEquals("NOT_HOST", notHostError.getPayload().get("code"));

        // 4. Host starts game -> GAME_STARTED and ROUND_STARTED broadcast
        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        // Both receive GAME_STARTED
        WsEnvelope hostGameStarted = hostHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(hostGameStarted);
        assertEquals("GAME_STARTED", hostGameStarted.getType());

        WsEnvelope guesserGameStarted = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(guesserGameStarted);
        assertEquals("GAME_STARTED", guesserGameStarted.getType());

        // Both receive ROUND_STARTED
        WsEnvelope hostRoundStarted = hostHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(hostRoundStarted);
        assertEquals("ROUND_STARTED", hostRoundStarted.getType());
        assertNotNull(hostRoundStarted.getPayload().get("deadline"));
        assertNotNull(hostRoundStarted.getPayload().get("serverTime"));

        WsEnvelope guesserRoundStarted = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(guesserRoundStarted);
        assertEquals("ROUND_STARTED", guesserRoundStarted.getType());

        String clueGiverId = (String) hostRoundStarted.getPayload().get("clueGiverId");
        assertNotNull(clueGiverId);

        // 5. Verify private delivery of CLUE_GIVER_ANSWER:
        // Clue-giver MUST receive it. Non-clue-giver MUST NEVER receive it.
        if (clueGiverId.equals(hostPlayerId)) {
            WsEnvelope secretAnswer = hostHandler.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(secretAnswer, "Clue-giver must receive CLUE_GIVER_ANSWER");
            assertEquals("CLUE_GIVER_ANSWER", secretAnswer.getType());
            assertNotNull(secretAnswer.getPayload().get("title"));

            // Guesser queue must NOT have CLUE_GIVER_ANSWER
            WsEnvelope guesserNext = guesserHandler.received.poll(500, TimeUnit.MILLISECONDS);
            assertNull(guesserNext, "Guesser must NOT receive CLUE_GIVER_ANSWER");
        } else {
            WsEnvelope secretAnswer = guesserHandler.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(secretAnswer, "Clue-giver must receive CLUE_GIVER_ANSWER");
            assertEquals("CLUE_GIVER_ANSWER", secretAnswer.getType());
            assertNotNull(secretAnswer.getPayload().get("title"));

            WsEnvelope hostNext = hostHandler.received.poll(500, TimeUnit.MILLISECONDS);
            assertNull(hostNext, "Host (guesser this round) must NOT receive CLUE_GIVER_ANSWER");
        }

        hostSession.close();
        guesserSession.close();
    }
}

