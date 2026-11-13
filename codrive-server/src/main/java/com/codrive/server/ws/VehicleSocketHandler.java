package com.codrive.server.ws;

import com.codrive.server.service.CommandService;
import com.codrive.server.service.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class VehicleSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(VehicleSocketHandler.class);
    static final String ATTR_INSTALLATION = "installationId";
    private static final String ATTR_DECORATED = "decorated";

    private final ConnectionRegistry registry;
    private final SessionService sessionService;
    private final CommandService commandService;
    private final ObjectMapper mapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        UUID inst = (UUID) raw.getAttributes().get(ATTR_INSTALLATION);
        WebSocketSession ws = registry.decorate(raw);
        raw.getAttributes().put(ATTR_DECORATED, ws);
        WebSocketSession old = registry.putVehicle(inst, ws);
        if (old != null && old != ws) {
            try {
                old.close(CloseStatus.POLICY_VIOLATION.withReason("replaced"));
            } catch (Exception ignored) {
            }
        }
        sessionService.onVehicleConnected(inst);
        registry.send(ws, "HELLO", Map.of("heartbeatSeconds", 10));
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        UUID inst = (UUID) raw.getAttributes().get(ATTR_INSTALLATION);
        WebSocketSession ws = (WebSocketSession) raw.getAttributes().get(ATTR_DECORATED);
        registry.touchVehicle(inst);
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            JsonNode p = root.path("payload");
            switch (type) {
                case "PING" -> registry.send(ws, "PONG", Map.of());
                case "CAPABILITY_REPORT" -> commandService.onCapabilityReport(inst, p.path("mode").asText("UNKNOWN"),
                        p.path("driving").asBoolean(false), Json.features(p.path("features")));
                case "COMMAND_ACK" -> {
                    UUID id = Json.uuid(p, "requestId");
                    if (id != null) {
                        commandService.onAck(inst, id, p.path("awaitingDriver").asBoolean(false));
                    }
                }
                case "COMMAND_RESULT" -> {
                    UUID id = Json.uuid(p, "requestId");
                    if (id != null) {
                        commandService.onResult(inst, id, p.path("status").asText("FAILED"),
                                p.path("errorCode").asText(null), p.path("simulated").asBoolean(false),
                                p.path("message").asText(null));
                    }
                }
                case "VEHICLE_STATE" -> {
                    UUID target = Json.uuid(p, "targetRequestId");
                    if (target != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> values = mapper.convertValue(p.path("values"), Map.class);
                        commandService.forwardState(inst, target, p.path("domain").asText(""), values,
                                p.path("simulated").asBoolean(false));
                    }
                }
                case "POWER_OFF" -> sessionService.endAllLive(inst, "POWER_OFF");
                default -> registry.send(ws, "ERROR", Map.of("code", "UNKNOWN_TYPE"));
            }
        } catch (Exception e) {
            log.warn("vehicle message failed: {}", e.toString());
            registry.send(ws, "ERROR", Map.of("code", "BAD_MESSAGE"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        UUID inst = (UUID) raw.getAttributes().get(ATTR_INSTALLATION);
        WebSocketSession ws = (WebSocketSession) raw.getAttributes().get(ATTR_DECORATED);
        if (inst != null && ws != null && registry.removeVehicle(inst, ws)) {
            sessionService.onVehicleDisconnected(inst);
        }
    }
}
