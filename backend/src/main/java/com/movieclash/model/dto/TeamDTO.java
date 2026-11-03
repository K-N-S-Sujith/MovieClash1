package com.movieclash.model.dto;

import com.movieclash.model.Team;
import java.util.List;

public record TeamDTO(
    String id,
    String name,
    int score,
    List<PlayerDTO> players
) {
    public static TeamDTO from(Team team) {
        if (team == null) return null;
        List<PlayerDTO> playerDTOs = team.getPlayers() != null
            ? team.getPlayers().stream()
                .filter(p -> !p.isSpectator())
                .map(PlayerDTO::from)
                .toList()
            : List.of();
        return new TeamDTO(team.getId(), team.getName(), team.getScore(), playerDTOs);
    }
}

