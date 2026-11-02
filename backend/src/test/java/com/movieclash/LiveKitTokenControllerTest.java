package com.movieclash;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieclash.config.GameConfig;
import com.movieclash.config.LiveKitProperties;
import com.movieclash.manager.RoomManager;
import com.movieclash.model.Room;
import com.movieclash.model.enums.GameMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class LiveKitTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private LiveKitProperties liveKitProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testMissingParametersReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/livekit/token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testNonExistentRoomReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/livekit/token")
                .param("roomId", "NONEXIST")
                .param("playerId", "any-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Room not found"));
    }

    @Test
    void testNonExistentPlayerReturnsUnauthorized() throws Exception {
        RoomManager.CreateRoomResult res = roomManager.createRoom("HostA", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = res.room().getRoomId();

        mockMvc.perform(get("/api/livekit/token")
                .param("roomId", roomId)
                .param("playerId", "fake-player-id"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Player not in room"));
    }

    @Test
    void testGetTokenForGuesserIssuesSubscriberToken() throws Exception {
        RoomManager.CreateRoomResult res = roomManager.createRoom("Alice", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = res.room().getRoomId();
        String aliceId = res.hostPlayer().getId();

        RoomManager.JoinRoomResult joinRes = roomManager.joinRoom(roomId, "Bob");
        String bobId = joinRes.player().getId();

        // Bob is a guesser (not clue-giver)
        MvcResult result = mockMvc.perform(get("/api/livekit/token")
                .param("roomId", roomId)
                .param("playerId", bobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canPublish").value(false))
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.playerId").value(bobId))
                .andExpect(jsonPath("$.playerName").value("Bob"))
                .andReturn();

        Map<?, ?> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        String token = (String) response.get("token");
        assertNotNull(token);

        // Verify JWT with HMAC256 using configured secret
        Algorithm algorithm = Algorithm.HMAC256(liveKitProperties.getApiSecret());
        DecodedJWT decoded = JWT.require(algorithm)
                .withIssuer(liveKitProperties.getApiKey())
                .build()
                .verify(token);

        assertEquals(bobId, decoded.getSubject());
        assertEquals("Bob", decoded.getClaim("name").asString());

        Map<String, Object> videoClaim = decoded.getClaim("video").asMap();
        assertNotNull(videoClaim);
        assertEquals(roomId, videoClaim.get("room"));
        assertEquals(true, videoClaim.get("roomJoin"));
        assertEquals(false, videoClaim.get("canPublish")); // Guesser cannot publish
        assertEquals(true, videoClaim.get("canSubscribe"));
    }

    @Test
    void testGetTokenForActiveClueGiverIssuesPublisherToken() throws Exception {
        RoomManager.CreateRoomResult res = roomManager.createRoom("Alice", GameMode.INDIVIDUAL, new GameConfig());
        Room room = res.room();
        String roomId = room.getRoomId();
        String aliceId = res.hostPlayer().getId();

        // Set Alice as current active clue-giver
        room.setCurrentClueGiverId(aliceId);

        MvcResult result = mockMvc.perform(get("/api/livekit/token")
                .param("roomId", roomId)
                .param("playerId", aliceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canPublish").value(true))
                .andReturn();

        Map<?, ?> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        String token = (String) response.get("token");

        Algorithm algorithm = Algorithm.HMAC256(liveKitProperties.getApiSecret());
        DecodedJWT decoded = JWT.require(algorithm).build().verify(token);

        Map<String, Object> videoClaim = decoded.getClaim("video").asMap();
        assertEquals(true, videoClaim.get("canPublish"), "Clue-giver must have canPublish: true");
    }

    @Test
    void testPostTokenEndpoint() throws Exception {
        RoomManager.CreateRoomResult res = roomManager.createRoom("HostBob", GameMode.INDIVIDUAL, new GameConfig());
        String roomId = res.room().getRoomId();
        String bobId = res.hostPlayer().getId();

        mockMvc.perform(post("/api/livekit/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "roomId", roomId,
                    "playerId", bobId
                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.livekitUrl").value(liveKitProperties.getUrl()));
    }
}

