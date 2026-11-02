package com.movieclash.controller;

import com.movieclash.manager.RoomManager;
import com.movieclash.model.Player;
import com.movieclash.model.Room;
import com.movieclash.service.LiveKitService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/livekit")
@CrossOrigin(origins = "*")
public class LiveKitTokenController {

    private final RoomManager roomManager;
    private final LiveKitService liveKitService;

    public LiveKitTokenController(RoomManager roomManager, LiveKitService liveKitService) {
        this.roomManager = roomManager;
        this.liveKitService = liveKitService;
    }

    public record TokenRequest(String roomId, String playerId) {}

    @GetMapping("/token")
    public ResponseEntity<?> getTokenGet(
            @RequestParam("roomId") String roomId,
            @RequestParam("playerId") String playerId) {
        return generateTokenResponse(roomId, playerId);
    }

    @PostMapping("/token")
    public ResponseEntity<?> getTokenPost(@RequestBody TokenRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
        }
        return generateTokenResponse(request.roomId(), request.playerId());
    }

    private ResponseEntity<?> generateTokenResponse(String roomId, String playerId) {
        if (roomId == null || playerId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "roomId and playerId are required"));
        }

        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Room not found"));
        }

        Player player = room.getPlayer(playerId);
        if (player == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Player not in room"));
        }

        // Only the current clue-giver may publish audio
        boolean isClueGiver = playerId.equals(room.getCurrentClueGiverId());

        String token = liveKitService.generateToken(room, player, isClueGiver);

        return ResponseEntity.ok(Map.of(
            "token", token,
            "livekitUrl", liveKitService.getLiveKitUrl(),
            "canPublish", isClueGiver,
            "roomId", room.getRoomId(),
            "playerId", player.getId(),
            "playerName", player.getName()
        ));
    }
}

