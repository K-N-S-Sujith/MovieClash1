package com.movieclash.model.dto;

import com.movieclash.model.Player;

public record PlayerDTO(
    String id,
    String name,
    boolean connected,
    int score,
    String teamId,
    boolean isHost,
    boolean isCoHost,
    boolean spectator
) {
    public static PlayerDTO from(Player player) {
        if (player == null) return null;
        return new PlayerDTO(
            player.getId(),
            player.getName(),
            player.isConnected(),
            player.getScore(),
            player.getTeamId(),
            player.isHost(),
            player.isCoHost(),
            player.isSpectator()
        );
    }
}

