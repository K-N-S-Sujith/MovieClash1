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
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WebSocketGatewayIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    static class TestWebSocketHandler extends TextWebSocketHandler {
        final BlockingQueue<WsEnvelope> received = new LinkedBlockingQueue<>();
        private final ObjectMapper mapper;

        public TestWebSocketHandler(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                WsEnvelope env = mapper.readValue(message.getPayload(), WsEnvelope.class);
                received.offer(env);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    void testPingPong() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestWebSocketHandler handler = new TestWebSocketHandler(objectMapper);
        String url = "ws://localhost:" + port + "/ws/game";

        WebSocketSession session = client.execute(handler, url).get(5, TimeUnit.SECONDS);
        assertTrue(session.isOpen());

        WsEnvelope pingEnv = WsEnvelope.of("PING", null, Map.of("clientTimestamp", 123456L));
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pingEnv)));

        WsEnvelope pongEnv = handler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(pongEnv);
        assertEquals("PONG", pongEnv.getType());
        assertEquals(123456, ((Number) pongEnv.getPayload().get("clientTimestamp")).longValue());
        assertNotNull(pongEnv.getPayload().get("serverTimestamp"));

        session.close();
    }

    @Test
    void testCreateAndJoinRoomLifecycle() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        // Client 1: Host
        TestWebSocketHandler handler1 = new TestWebSocketHandler(objectMapper);
        WebSocketSession session1 = client.execute(handler1, url).get(5, TimeUnit.SECONDS);

        WsEnvelope createMsg = WsEnvelope.of("CREATE_ROOM", null, Map.of(
            "playerName", "HostAlice",
            "gameMode", "INDIVIDUAL"
        ));
        session1.sendMessage(new TextMessage(objectMapper.writeValueAsString(createMsg)));

        WsEnvelope createdEnv = handler1.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(createdEnv);
        assertEquals("ROOM_CREATED", createdEnv.getType());
        String roomId = (String) createdEnv.getPayload().get("roomId");
        assertNotNull(roomId);
        assertEquals(6, roomId.length());
        assertNotNull(createdEnv.getPayload().get("reconnectToken"));

        // Client 2: Joiner
        TestWebSocketHandler handler2 = new TestWebSocketHandler(objectMapper);
        WebSocketSession session2 = client.execute(handler2, url).get(5, TimeUnit.SECONDS);

        WsEnvelope joinMsg = WsEnvelope.of("JOIN_ROOM", roomId, Map.of(
            "roomId", roomId,
            "playerName", "PlayerBob"
        ));
        session2.sendMessage(new TextMessage(objectMapper.writeValueAsString(joinMsg)));

        // Client 2 should receive self PLAYER_JOINED confirmation
        WsEnvelope joinerEnv = handler2.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(joinerEnv);
        assertEquals("PLAYER_JOINED", joinerEnv.getType());
        assertNotNull(joinerEnv.getPayload().get("yourPlayerId"));

        // Client 1 (Host) should receive broadcast PLAYER_JOINED
        WsEnvelope broadcastEnv = handler1.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(broadcastEnv);
        assertEquals("PLAYER_JOINED", broadcastEnv.getType());

        // Close Client 2 and verify disconnect notice on Client 1
        session2.close();

        WsEnvelope disconnectEnv = handler1.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(disconnectEnv);
        assertEquals("PLAYER_DISCONNECTED", disconnectEnv.getType());
        assertEquals("PlayerBob", disconnectEnv.getPayload().get("playerName"));

        session1.close();
    }

    @Test
    void testTeamAssignmentDeltaSynchronization() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/game";

        // Client 1 (Host in TEAM mode)
        TestWebSocketHandler handler1 = new TestWebSocketHandler(objectMapper);
        WebSocketSession session1 = client.execute(handler1, url).get(5, TimeUnit.SECONDS);

        session1.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("CREATE_ROOM", null, Map.of(
                "playerName", "HostAlice",
                "gameMode", "TEAM"
            ))
        )));

        WsEnvelope createdEnv = handler1.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(createdEnv);
        String roomId = (String) createdEnv.getPayload().get("roomId");

        // Client 2 joins
        TestWebSocketHandler handler2 = new TestWebSocketHandler(objectMapper);
        WebSocketSession session2 = client.execute(handler2, url).get(5, TimeUnit.SECONDS);

        session2.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("JOIN_ROOM", roomId, Map.of(
                "roomId", roomId,
                "playerName", "Bob"
            ))
        )));

        WsEnvelope joinerEnv = handler2.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(joinerEnv);
        handler1.received.poll(5, TimeUnit.SECONDS); // clear broadcast on client 1

        // Client 2 switches team to TEAM_B
        session2.sendMessage(new TextMessage(objectMapper.writeValueAsString(
            WsEnvelope.of("ASSIGN_TEAM", roomId, Map.of(
                "teamId", "TEAM_B"
            ))
        )));

        // Both clients should receive ROOM_UPDATED delta
        WsEnvelope delta1 = handler1.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(delta1, "Host should receive ROOM_UPDATED delta");
        assertEquals("ROOM_UPDATED", delta1.getType());
        assertNotNull(delta1.getPayload().get("delta"));

        WsEnvelope delta2 = handler2.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(delta2, "Joiner should receive ROOM_UPDATED delta");
        assertEquals("ROOM_UPDATED", delta2.getType());

        session1.close();
        session2.close();
    }
}

