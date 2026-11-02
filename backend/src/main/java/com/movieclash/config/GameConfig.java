package com.movieclash.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Centralized Game Rules & Timing Configuration.
 * Defaults are sent to clients upon GAME_STARTED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameConfig {
    private int roundDurationSec = 60;
    private int totalRounds = 2;
    private int correctGuessPoints = 100;
    private int firstGuessBonus = 50;
    private int clueGiverRewardPerGuesser = 25;
    private int maxRoomPlayers = 20;
    private int minRoomPlayers = 2;
    private long reconnectGracePeriodMs = 300000L; // 5-minute reconnect grace window
    private long cancelVoteTimeoutMs = 15000L;
    private int maxMessageLength = 200;
    private long clueGiverDisconnectTimeoutMs = 15000L;
    private boolean autoStartRound = true;

    public GameConfig() {}

    public int getRoundDurationSec() {
        return roundDurationSec;
    }

    public void setRoundDurationSec(int roundDurationSec) {
        this.roundDurationSec = roundDurationSec;
    }

    public int getTotalRounds() {
        return totalRounds;
    }

    public void setTotalRounds(int totalRounds) {
        this.totalRounds = totalRounds;
    }

    public int getCorrectGuessPoints() {
        return correctGuessPoints;
    }

    public void setCorrectGuessPoints(int correctGuessPoints) {
        this.correctGuessPoints = correctGuessPoints;
    }

    public int getFirstGuessBonus() {
        return firstGuessBonus;
    }

    public void setFirstGuessBonus(int firstGuessBonus) {
        this.firstGuessBonus = firstGuessBonus;
    }

    public int getClueGiverRewardPerGuesser() {
        return clueGiverRewardPerGuesser;
    }

    public void setClueGiverRewardPerGuesser(int clueGiverRewardPerGuesser) {
        this.clueGiverRewardPerGuesser = clueGiverRewardPerGuesser;
    }

    public int getMaxRoomPlayers() {
        return maxRoomPlayers;
    }

    public void setMaxRoomPlayers(int maxRoomPlayers) {
        this.maxRoomPlayers = maxRoomPlayers;
    }

    public int getMinRoomPlayers() {
        return minRoomPlayers;
    }

    public void setMinRoomPlayers(int minRoomPlayers) {
        this.minRoomPlayers = minRoomPlayers;
    }

    public long getReconnectGracePeriodMs() {
        return reconnectGracePeriodMs;
    }

    public void setReconnectGracePeriodMs(long reconnectGracePeriodMs) {
        this.reconnectGracePeriodMs = reconnectGracePeriodMs;
    }

    public long getCancelVoteTimeoutMs() {
        return cancelVoteTimeoutMs;
    }

    public void setCancelVoteTimeoutMs(long cancelVoteTimeoutMs) {
        this.cancelVoteTimeoutMs = cancelVoteTimeoutMs;
    }

    public int getMaxMessageLength() {
        return maxMessageLength;
    }

    public void setMaxMessageLength(int maxMessageLength) {
        this.maxMessageLength = maxMessageLength;
    }

    public long getClueGiverDisconnectTimeoutMs() {
        return clueGiverDisconnectTimeoutMs;
    }

    public void setClueGiverDisconnectTimeoutMs(long clueGiverDisconnectTimeoutMs) {
        this.clueGiverDisconnectTimeoutMs = clueGiverDisconnectTimeoutMs;
    }

    public boolean isAutoStartRound() {
        return autoStartRound;
    }

    public void setAutoStartRound(boolean autoStartRound) {
        this.autoStartRound = autoStartRound;
    }
}

