package com.movieclash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Player;
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
public class ReconnectionIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomManager roomManager;

    @Test
    void testGuesserAndClueGiverReconnectionLifecycle() throws Exception {
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
        String hostId = (String) roomCreatedEnv.getPayload().get("playerId");
        String hostReconnectToken = (String) roomCreatedEnv.getPayload().get("reconnectToken");

        // 2. Bob joins room
        WebSocketGatewayIT.TestWebSocketHandler bobHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession bobSession = client.execute(bobHandler, url).get(5, TimeUnit.SECONDS);

        bobSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of("roomId", roomId, "playerName", "Bob"))
        )));

        WsEnvelope bobJoined = bobHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(bobJoined);
        String bobId = (String) bobJoined.getPayload().get("yourPlayerId");
        String bobReconnectToken = (String) bobJoined.getPayload().get("reconnectToken");
        hostHandler.received.poll(5, TimeUnit.SECONDS); // Drain broadcast

        // 3. Host starts game
        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        // Drain GAME_STARTED
        assertNotNull(hostHandler.received.poll(5, TimeUnit.SECONDS));
        assertNotNull(bobHandler.received.poll(5, TimeUnit.SECONDS));

        // Drain ROUND_STARTED
        WsEnvelope hostRound = hostHandler.received.poll(5, TimeUnit.SECONDS);
        WsEnvelope bobRound = bobHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(hostRound);
        assertNotNull(bobRound);

        String clueGiverId = (String) hostRound.getPayload().get("clueGiverId");
        boolean hostIsClueGiver = clueGiverId.equals(hostId);

        WebSocketSession clueGiverSession = hostIsClueGiver ? hostSession : bobSession;
        WebSocketGatewayIT.TestWebSocketHandler clueGiverHandler = hostIsClueGiver ? hostHandler : bobHandler;
        String clueGiverPlayerId = hostIsClueGiver ? hostId : bobId;
        String clueGiverToken = hostIsClueGiver ? hostReconnectToken : bobReconnectToken;

        WebSocketSession guesserSession = hostIsClueGiver ? bobSession : hostSession;
        WebSocketGatewayIT.TestWebSocketHandler guesserHandler = hostIsClueGiver ? bobHandler : hostHandler;
        String guesserPlayerId = hostIsClueGiver ? bobId : hostId;
        String guesserToken = hostIsClueGiver ? bobReconnectToken : hostReconnectToken;

        // Drain secret answer from clue-giver
        WsEnvelope initialSecretAnswer = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(initialSecretAnswer);
        assertEquals("CLUE_GIVER_ANSWER", initialSecretAnswer.getType());
        String secretTitle = (String) initialSecretAnswer.getPayload().get("title");
        assertNotNull(secretTitle);

        // 4. Guesser disconnects
        guesserSession.close();

        // Clue-giver sees PLAYER_DISCONNECTED
        WsEnvelope playerDisc = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(playerDisc);
        assertEquals("PLAYER_DISCONNECTED", playerDisc.getType());
        assertEquals(guesserPlayerId, playerDisc.getPayload().get("playerId"));

        // Verify player is marked disconnected on server
        Room room = roomManager.getRoom(roomId);
        Player guesserPlayer = room.getPlayer(guesserPlayerId);
        assertFalse(guesserPlayer.isConnected());

        // 5. Guesser reconnects on a new WebSocket connection
        WebSocketGatewayIT.TestWebSocketHandler newGuesserHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession newGuesserSession = client.execute(newGuesserHandler, url).get(5, TimeUnit.SECONDS);

        newGuesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("RECONNECT", roomId, Map.of(
                "roomId", roomId,
                "playerId", guesserPlayerId,
                "reconnectToken", guesserToken
            ))
        )));

        // Guesser receives full PLAYER_RECONNECTED restoration snapshot
        WsEnvelope guesserReconnected = newGuesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(guesserReconnected);
        assertEquals("PLAYER_RECONNECTED", guesserReconnected.getType());
        assertEquals(guesserPlayerId, guesserReconnected.getPayload().get("yourPlayerId"));
        assertEquals("IN_PROGRESS", guesserReconnected.getPayload().get("status"));
        assertNotNull(guesserReconnected.getPayload().get("deadline"));
        assertNotNull(guesserReconnected.getPayload().get("scoreboard"));

        // Clue-giver receives PLAYER_RECONNECTED broadcast
        WsEnvelope cgReceivedReconBc = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(cgReceivedReconBc);
        assertEquals("PLAYER_RECONNECTED", cgReceivedReconBc.getType());

        assertTrue(room.getPlayer(guesserPlayerId).isConnected());

        // 6. Test Clue-Giver disconnect and recovery (resends secret CLUE_GIVER_ANSWER!)
        clueGiverSession.close();
        newGuesserHandler.received.poll(5, TimeUnit.SECONDS); // Drain disconnect on guesser

        // Clue-giver opens new socket and reconnects
        WebSocketGatewayIT.TestWebSocketHandler newCgHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession newCgSession = client.execute(newCgHandler, url).get(5, TimeUnit.SECONDS);

        newCgSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("RECONNECT", roomId, Map.of(
                "roomId", roomId,
                "playerId", clueGiverPlayerId,
                "reconnectToken", clueGiverToken
            ))
        )));

        // Clue-giver receives PLAYER_RECONNECTED
        WsEnvelope cgReconnected = newCgHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(cgReconnected);
        assertEquals("PLAYER_RECONNECTED", cgReconnected.getType());

        // Clue-giver receives RESENT private CLUE_GIVER_ANSWER!
        WsEnvelope resentSecretAnswer = newCgHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(resentSecretAnswer, "Clue-giver must privately receive the movie answer upon mid-round reconnect");
        assertEquals("CLUE_GIVER_ANSWER", resentSecretAnswer.getType());
        assertEquals(secretTitle, resentSecretAnswer.getPayload().get("title"));

        newGuesserSession.close();
        newCgSession.close();
    }

    @Test
    void testInvalidTokenAndExpiredGracePeriodRejections() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        WebSocketGatewayIT.TestWebSocketHandler handler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession session = client.execute(handler, url).get(5, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CREATE_ROOM", null, Map.of("playerName", "SoloAlice", "gameMode", "INDIVIDUAL"))
        )));

        WsEnvelope roomCreated = handler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roomCreated);
        String roomId = (String) roomCreated.getPayload().get("roomId");
        String playerId = (String) roomCreated.getPayload().get("playerId");

        // 1. Reconnect with invalid token -> UNAUTHORIZED
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("RECONNECT", roomId, Map.of(
                "roomId", roomId,
                "playerId", playerId,
                "reconnectToken", "wrong-token-abc"
            ))
        )));

        WsEnvelope authError = handler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(authError);
        assertEquals("ERROR", authError.getType());
        assertEquals("UNAUTHORIZED", authError.getPayload().get("code"));

        // 2. Set disconnectedAt in the past beyond grace period -> EXPIRED_SESSION
        Room room = roomManager.getRoom(roomId);
        Player player = room.getPlayer(playerId);
        player.setConnected(false);
        player.setDisconnectedAt(System.currentTimeMillis() - 70000L); // 70 seconds ago (> 60s)

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("RECONNECT", roomId, Map.of(
                "roomId", roomId,
                "playerId", playerId,
                "reconnectToken", player.getReconnectToken()
            ))
        )));

        WsEnvelope expiredError = handler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(expiredError);
        assertEquals("ERROR", expiredError.getType());
        assertEquals("EXPIRED_SESSION", expiredError.getPayload().get("code"));

        session.close();
    }
}

