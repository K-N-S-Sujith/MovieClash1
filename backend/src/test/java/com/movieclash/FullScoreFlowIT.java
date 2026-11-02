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
public class FullScoreFlowIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testCompleteGuessAndScoreBroadcastCycle() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        // 1. Host creates room
        WebSocketGatewayIT.TestWebSocketHandler hostHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession hostSession = client.execute(hostHandler, url).get(5, TimeUnit.SECONDS);

        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CREATE_ROOM", null, Map.of("playerName", "HostAlice", "gameMode", "INDIVIDUAL"))
        )));

        WsEnvelope roomCreatedEnv = hostHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roomCreatedEnv);
        String roomId = (String) roomCreatedEnv.getPayload().get("roomId");
        String hostPlayerId = (String) roomCreatedEnv.getPayload().get("playerId");

        // 2. Guesser joins
        WebSocketGatewayIT.TestWebSocketHandler guesserHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession guesserSession = client.execute(guesserHandler, url).get(5, TimeUnit.SECONDS);

        guesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of("roomId", roomId, "playerName", "Bob"))
        )));

        WsEnvelope joinConfirmation = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(joinConfirmation);
        String guesserPlayerId = (String) joinConfirmation.getPayload().get("yourPlayerId");
        assertNotNull(hostHandler.received.poll(5, TimeUnit.SECONDS)); // drain broadcast on host

        // 3. Host starts game
        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        // Drain GAME_STARTED and ROUND_STARTED
        assertNotNull(hostHandler.received.poll(5, TimeUnit.SECONDS));
        assertNotNull(guesserHandler.received.poll(5, TimeUnit.SECONDS));
        WsEnvelope hostRound = hostHandler.received.poll(5, TimeUnit.SECONDS);
        WsEnvelope guesserRound = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(hostRound);
        assertNotNull(guesserRound);

        String clueGiverId = (String) hostRound.getPayload().get("clueGiverId");
        WebSocketSession clueGiverSession = clueGiverId.equals(hostPlayerId) ? hostSession : guesserSession;
        WebSocketGatewayIT.TestWebSocketHandler clueGiverHandler = clueGiverId.equals(hostPlayerId) ? hostHandler : guesserHandler;

        WebSocketSession activeGuesserSession = clueGiverId.equals(hostPlayerId) ? guesserSession : hostSession;
        WebSocketGatewayIT.TestWebSocketHandler activeGuesserHandler = clueGiverId.equals(hostPlayerId) ? guesserHandler : hostHandler;
        String activeGuesserId = clueGiverId.equals(hostPlayerId) ? guesserPlayerId : hostPlayerId;

        // Drain CLUE_GIVER_ANSWER from clue-giver
        WsEnvelope secretAnswer = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(secretAnswer);

        // 4. Guesser submits guess
        activeGuesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("SUBMIT_GUESS", roomId, Map.of("guessText", "Inception"))
        )));

        // Clue-giver receives FORWARD_GUESS
        WsEnvelope forwarded = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(forwarded);
        assertEquals("FORWARD_GUESS", forwarded.getType());
        int seq = ((Number) forwarded.getPayload().get("guessSeq")).intValue();

        // 5. Clue-giver validates and sends SCORE_UPDATE
        clueGiverSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("SCORE_UPDATE", roomId, Map.of(
                "guesserId", activeGuesserId,
                "guessSeq", seq,
                "isCorrect", true,
                "points", 150,
                "clueGiverPoints", 25,
                "guessText", "Inception"
            ))
        )));

        // 6. Guesser receives private GUESS_RESULT
        WsEnvelope guessResult = activeGuesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(guessResult, "Guesser must receive private GUESS_RESULT");
        assertEquals("GUESS_RESULT", guessResult.getType());
        assertTrue((Boolean) guessResult.getPayload().get("isCorrect"));
        assertEquals(150, ((Number) guessResult.getPayload().get("points")).intValue());

        // 7. Both players receive SCORE_BROADCAST
        WsEnvelope scoreBc1 = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(scoreBc1);
        assertEquals("SCORE_BROADCAST", scoreBc1.getType());
        assertEquals(150, ((Number) scoreBc1.getPayload().get("guesserPoints")).intValue());
        assertEquals(25, ((Number) scoreBc1.getPayload().get("clueGiverPoints")).intValue());

        WsEnvelope scoreBc2 = activeGuesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(scoreBc2);
        assertEquals("SCORE_BROADCAST", scoreBc2.getType());

        // 8. Because all eligible guessers have solved it, ROUND_FINISHED must be triggered early!
        WsEnvelope roundFinished1 = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roundFinished1, "Clue-giver must receive early ROUND_FINISHED");
        assertEquals("ROUND_FINISHED", roundFinished1.getType());
        assertEquals("ALL_GUESSED", roundFinished1.getPayload().get("reason"));

        WsEnvelope roundFinished2 = activeGuesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roundFinished2, "Guesser must receive early ROUND_FINISHED");
        assertEquals("ROUND_FINISHED", roundFinished2.getType());

        hostSession.close();
        guesserSession.close();
    }
}

