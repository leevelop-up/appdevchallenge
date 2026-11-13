package com.codrive.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class ConnectionRegistry {
    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final ObjectMapper mapper;
    private final Map<UUID, WebSocketSession> vehicles = new ConcurrentHashMap<>();
    private final Map<UUID, WebSocketSession> phones = new ConcurrentHashMap<>();
    private final Map<UUID, Long> vehicleLastSeen = new ConcurrentHashMap<>();

    public WebSocketSession decorate(WebSocketSession ws) {
        return new ConcurrentWebSocketSessionDecorator(ws, 5000, 256 * 1024);
    }

    public WebSocketSession putVehicle(UUID installationId, WebSocketSession ws) {
        vehicleLastSeen.put(installationId, System.currentTimeMillis());
        return vehicles.put(installationId, ws);
    }

    public boolean removeVehicle(UUID installationId, WebSocketSession ws) {
        boolean removed = vehicles.remove(installationId, ws);
        if (removed) {
            vehicleLastSeen.remove(installationId);
        }
        return removed;
    }

    public boolean isVehicleConnected(UUID installationId) {
        WebSocketSession ws = vehicles.get(installationId);
        return ws != null && ws.isOpen();
    }

    public void touchVehicle(UUID installationId) {
        vehicleLastSeen.put(installationId, System.currentTimeMillis());
    }

    public Long vehicleLastSeen(UUID installationId) {
        return vehicleLastSeen.get(installationId);
    }

    public Map<UUID, Long> vehicleLastSeenSnapshot() {
        return Map.copyOf(vehicleLastSeen);
    }

    public WebSocketSession putPhone(UUID requestId, WebSocketSession ws) {
        return phones.put(requestId, ws);
    }

    public boolean removePhone(UUID requestId, WebSocketSession ws) {
        return phones.remove(requestId, ws);
    }

    public boolean isPhoneOnline(UUID requestId) {
        WebSocketSession ws = phones.get(requestId);
        return ws != null && ws.isOpen();
    }

    public boolean sendVehicle(UUID installationId, String type, Object payload) {
        return send(vehicles.get(installationId), type, payload);
    }

    public boolean sendPhone(UUID requestId, String type, Object payload) {
        return send(phones.get(requestId), type, payload);
    }

    public void closeVehicle(UUID installationId, CloseStatus status) {
        close(vehicles.get(installationId), status);
    }

    public void closePhone(UUID requestId, CloseStatus status) {
        close(phones.get(requestId), status);
    }

    public boolean send(WebSocketSession ws, String type, Object payload) {
        if (ws == null || !ws.isOpen()) {
            return false;
        }
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", type);
            msg.put("payload", payload);
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
            return true;
        } catch (Exception e) {
            log.warn("ws send failed type={} : {}", type, e.toString());
            return false;
        }
    }

    private void close(WebSocketSession ws, CloseStatus status) {
        if (ws == null) {
            return;
        }
        try {
            ws.close(status);
        } catch (Exception ignored) {
        }
    }
}
