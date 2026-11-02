package com.movieclash.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.movieclash.config.LiveKitProperties;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class LiveKitService {
    private static final Logger log = LoggerFactory.getLogger(LiveKitService.class);
    private static final long TOKEN_VALIDITY_SECONDS = 3600L; // 1 hour token

    private final LiveKitProperties properties;

    public LiveKitService(LiveKitProperties properties) {
        this.properties = properties;
    }

    public String generateToken(Room room, Player player, boolean canPublish) {
        String apiKey = properties.getApiKey();
        String apiSecret = properties.getApiSecret();

        if (apiKey == null || apiSecret == null) {
            throw new IllegalStateException("LiveKit API key or secret is not configured");
        }

        Algorithm algorithm = Algorithm.HMAC256(apiSecret);
        long nowMs = System.currentTimeMillis();

        Map<String, Object> videoClaim = new HashMap<>();
        videoClaim.put("room", room.getRoomId());
        videoClaim.put("roomJoin", true);
        videoClaim.put("canPublish", canPublish);
        videoClaim.put("canSubscribe", true);
        videoClaim.put("canPublishData", false);

        String token = JWT.create()
                .withIssuer(apiKey)
                .withSubject(player.getId())
                .withClaim("name", player.getName())
                .withNotBefore(new Date(nowMs - 5000L)) // 5 seconds clock drift allowance
                .withExpiresAt(new Date(nowMs + (TOKEN_VALIDITY_SECONDS * 1000L)))
                .withClaim("video", videoClaim)
                .sign(algorithm);

        log.info("Generated LiveKit access token for player [{}] ({}) in room [{}], canPublish: {}",
                player.getName(), player.getId(), room.getRoomId(), canPublish);
        return token;
    }

    public String getLiveKitUrl() {
        return properties.getUrl();
    }
}

