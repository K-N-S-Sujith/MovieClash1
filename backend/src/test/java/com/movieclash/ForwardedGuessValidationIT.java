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
public class ForwardedGuessValidationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testForwardedGuessWorkflow() throws Exception {
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

        assertNotNull(guesserHandler.received.poll(5, TimeUnit.SECONDS)); // Bob's PLAYER_JOINED
        assertNotNull(hostHandler.received.poll(5, TimeUnit.SECONDS));    // Alice's broadcast PLAYER_JOINED

        // 3. Start Game
        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        // Drain GAME_STARTED and ROUND_STARTED on both
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

        // Drain CLUE_GIVER_ANSWER from clue-giver's queue
        WsEnvelope secretAnswer = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(secretAnswer);
        assertEquals("CLUE_GIVER_ANSWER", secretAnswer.getType());

        // 4. Guesser submits guess "Inception"
        activeGuesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("SUBMIT_GUESS", roomId, Map.of("guessText", "Inception"))
        )));

        // 5. Clue-giver MUST receive FORWARD_GUESS
        WsEnvelope forwarded = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(forwarded, "Clue-giver must receive FORWARD_GUESS");
        assertEquals("FORWARD_GUESS", forwarded.getType());
        assertEquals("Inception", forwarded.getPayload().get("guessText"));
        assertEquals(1, ((Number) forwarded.getPayload().get("guessSeq")).intValue());
        assertNotNull(forwarded.getPayload().get("serverTimestamp"));

        // Guesser must NOT receive FORWARD_GUESS
        WsEnvelope leaked = activeGuesserHandler.received.poll(500, TimeUnit.MILLISECONDS);
        assertNull(leaked, "Guesser must NOT receive FORWARD_GUESS");

        // 6. Clue-giver attempts to submit guess -> must receive ERROR
        clueGiverSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("SUBMIT_GUESS", roomId, Map.of("guessText", "CheatingGuess"))
        )));

        WsEnvelope cgError = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(cgError);
        assertEquals("ERROR", cgError.getType());

        hostSession.close();
        guesserSession.close();
    }
}

