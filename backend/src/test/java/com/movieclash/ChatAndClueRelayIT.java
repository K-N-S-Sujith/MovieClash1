package com.movieclash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.manager.RoomManager;
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
public class ChatAndClueRelayIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomManager roomManager;

    @Test
    void testChatAndClueMessagingLifecycle() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        // 1. Host creates room
        WebSocketGatewayIT.TestWebSocketHandler hostHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession hostSession = client.execute(hostHandler, url).get(5, TimeUnit.SECONDS);

        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CREATE_ROOM", null, Map.of("playerName", "HostAlice", "gameMode", "INDIVIDUAL"))
        )));

        WsEnvelope roomCreated = hostHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(roomCreated);
        String roomId = (String) roomCreated.getPayload().get("roomId");
        String hostId = (String) roomCreated.getPayload().get("playerId");

        // 2. Joiner joins
        WebSocketGatewayIT.TestWebSocketHandler joinerHandler = new WebSocketGatewayIT.TestWebSocketHandler(objectMapper);
        WebSocketSession joinerSession = client.execute(joinerHandler, url).get(5, TimeUnit.SECONDS);

        joinerSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of("roomId", roomId, "playerName", "Bob"))
        )));

        assertNotNull(joinerHandler.received.poll(5, TimeUnit.SECONDS));
        assertNotNull(hostHandler.received.poll(5, TimeUnit.SECONDS));

        // 3. Lobby Chat: Joiner sends CHAT_MESSAGE
        joinerSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CHAT_MESSAGE", roomId, Map.of("message", "Hello everyone!"))
        )));

        WsEnvelope chatBcHost = hostHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(chatBcHost);
        assertEquals("CHAT_BROADCAST", chatBcHost.getType());
        assertEquals("Hello everyone!", chatBcHost.getPayload().get("message"));

        WsEnvelope chatBcJoiner = joinerHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(chatBcJoiner);
        assertEquals("CHAT_BROADCAST", chatBcJoiner.getType());

        // Verify message was saved in room's 50-message memory buffer
        assertEquals(1, roomManager.getRoom(roomId).getRecentChat().size());

        // 4. Host starts game
        hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("START_GAME", roomId, Map.of())
        )));

        // Drain start events
        assertNotNull(hostHandler.received.poll(5, TimeUnit.SECONDS)); // GAME_STARTED
        assertNotNull(joinerHandler.received.poll(5, TimeUnit.SECONDS));
        WsEnvelope hostRound = hostHandler.received.poll(5, TimeUnit.SECONDS); // ROUND_STARTED
        WsEnvelope joinerRound = joinerHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(hostRound);
        assertNotNull(joinerRound);

        String clueGiverId = (String) hostRound.getPayload().get("clueGiverId");
        WebSocketSession clueGiverSession = clueGiverId.equals(hostId) ? hostSession : joinerSession;
        WebSocketGatewayIT.TestWebSocketHandler clueGiverHandler = clueGiverId.equals(hostId) ? hostHandler : joinerHandler;

        WebSocketSession guesserSession = clueGiverId.equals(hostId) ? joinerSession : hostSession;
        WebSocketGatewayIT.TestWebSocketHandler guesserHandler = clueGiverId.equals(hostId) ? joinerHandler : hostHandler;

        // Drain CLUE_GIVER_ANSWER
        assertNotNull(clueGiverHandler.received.poll(5, TimeUnit.SECONDS));

        // 5. Guesser tries to send CHAT_MESSAGE during active round -> BLOCKED!
        guesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CHAT_MESSAGE", roomId, Map.of("message", "Is it Star Wars?"))
        )));

        WsEnvelope chatBlockedError = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(chatBlockedError);
        assertEquals("ERROR", chatBlockedError.getType());
        assertEquals("UNAUTHORIZED", chatBlockedError.getPayload().get("code"));

        // 6. Guesser tries to send CLUE_MESSAGE -> BLOCKED (NOT_CLUE_GIVER)
        guesserSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CLUE_MESSAGE", roomId, Map.of("message", "Fake clue"))
        )));

        WsEnvelope clueBlockedError = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(clueBlockedError);
        assertEquals("ERROR", clueBlockedError.getType());
        assertEquals("NOT_CLUE_GIVER", clueBlockedError.getPayload().get("code"));

        // 7. Clue-giver sends CLUE_MESSAGE -> Broadcast to whole room!
        clueGiverSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CLUE_MESSAGE", roomId, Map.of("message", "Directed by Christopher Nolan"))
        )));

        WsEnvelope clueBc1 = clueGiverHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(clueBc1);
        assertEquals("CLUE_BROADCAST", clueBc1.getType());
        assertEquals("Directed by Christopher Nolan", clueBc1.getPayload().get("message"));

        WsEnvelope clueBc2 = guesserHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(clueBc2);
        assertEquals("CLUE_BROADCAST", clueBc2.getType());

        hostSession.close();
        joinerSession.close();
    }
}

