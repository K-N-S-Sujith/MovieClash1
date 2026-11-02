package com.movieclash.model.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsEnvelope {
    private String type;
    private String eventId;
    private String roomId;
    private Map<String, Object> payload;

    public WsEnvelope() {}

    public WsEnvelope(String type, String eventId, String roomId, Map<String, Object> payload) {
        this.type = type;
        this.eventId = eventId != null ? eventId : UUID.randomUUID().toString();
        this.roomId = roomId;
        this.payload = payload;
    }

    public static WsEnvelope of(String type, String roomId, Map<String, Object> payload) {
        return new WsEnvelope(type, UUID.randomUUID().toString(), roomId, payload);
    }

    public static WsEnvelope error(String roomId, ErrorCode code, String message) {
        return new WsEnvelope("ERROR", UUID.randomUUID().toString(), roomId, Map.of(
            "code", code.name(),
            "message", message
        ));
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}

