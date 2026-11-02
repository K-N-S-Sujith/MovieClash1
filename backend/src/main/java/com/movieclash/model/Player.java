package com.movieclash.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Player {
    private String id;
    private String name;
    private String userId; // Standard persistent user identifier

    @JsonIgnore
    private String reconnectToken;

    @JsonIgnore
    private String webSocketSessionId;

    private boolean connected;
    private int score;
    private String teamId;
    private boolean isHost;
    private boolean isCoHost;

    @JsonIgnore
    private long lastHeartbeat;

    @JsonIgnore
    private long disconnectedAt;

    public Player() {
        this.connected = true;
        this.score = 0;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public Player(String id, String name, String reconnectToken) {
        this.id = id;
        this.name = name;
        this.reconnectToken = reconnectToken;
        this.connected = true;
        this.score = 0;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getReconnectToken() {
        return reconnectToken;
    }

    public void setReconnectToken(String reconnectToken) {
        this.reconnectToken = reconnectToken;
    }

    public String getWebSocketSessionId() {
        return webSocketSessionId;
    }

    public void setWebSocketSessionId(String webSocketSessionId) {
        this.webSocketSessionId = webSocketSessionId;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public void addScore(int delta) {
        this.score += delta;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public boolean isHost() {
        return isHost;
    }

    public void setHost(boolean host) {
        isHost = host;
    }

    public boolean isCoHost() {
        return isCoHost;
    }

    public void setCoHost(boolean coHost) {
        isCoHost = coHost;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public long getDisconnectedAt() {
        return disconnectedAt;
    }

    public void setDisconnectedAt(long disconnectedAt) {
        this.disconnectedAt = disconnectedAt;
    }
}

