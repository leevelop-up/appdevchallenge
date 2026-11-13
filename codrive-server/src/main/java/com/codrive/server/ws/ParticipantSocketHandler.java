package com.codrive.server.ws;

import com.codrive.server.domain.JoinRequest;
import com.codrive.server.service.ApiException;
import com.codrive.server.service.CommandService;
import com.codrive.server.service.SessionService;
import com.codrive.server.service.SnapshotService;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ParticipantSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ParticipantSocketHandler.class);
    private static final String ATTR_REQUEST = "requestId";
    private static final String ATTR_DECORATED = "decorated";

    private final ConnectionRegistry registry;
    private final SessionService sessionService;
    private final CommandService commandService;
    private final SnapshotService snapshots;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-auth-timeout");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        raw.getAttributes().put(ATTR_DECORATED, registry.decorate(raw));
        timer.schedule(() -> {
            if (raw.getAttributes().get(ATTR_REQUEST) == null) {
                try {
                    raw.close(CloseStatus.POLICY_VIOLATION.withReason("auth timeout"));
                } catch (Exception ignored) {
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        WebSocketSession ws = (WebSocketSession) raw.getAttributes().get(ATTR_DECORATED);
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            JsonNode p = root.path("payload");
            if (type.equals("AUTH")) {
                auth(raw, ws, p.path("token").asText(null));
                return;
            }
            UUID requestId = (UUID) raw.getAttributes().get(ATTR_REQUEST);
            if (requestId == null) {
                registry.send(ws, "ERROR", Map.of("code", "NOT_AUTHENTICATED"));
                return;
            }

            Optional<SessionService.PhoneIdentity> id = sessionService.authenticatePhone(
                    (String) raw.getAttributes().get("rawToken"));
            if (id.isEmpty()) {
                registry.send(ws, "ERROR", Map.of("code", "AUTH_FAILED"));
                return;
            }
            handleAuthed(ws, type, p, id.get());
        } catch (ApiException e) {
            registry.send(ws, "ERROR", Map.of("code", e.code(), "message", e.getMessage()));
        } catch (Exception e) {
            log.warn("participant message failed: {}", e.toString());
            registry.send(ws, "ERROR", Map.of("code", "BAD_MESSAGE"));
        }
    }

    private void auth(WebSocketSession raw, WebSocketSession ws, String token) {
        Optional<SessionService.PhoneIdentity> id = sessionService.authenticatePhone(token);
        if (id.isEmpty()) {
            registry.send(ws, "ERROR", Map.of("code", "AUTH_FAILED"));
            try {
                raw.close(CloseStatus.POLICY_VIOLATION.withReason("auth failed"));
            } catch (Exception ignored) {
            }
            return;
        }
        JoinRequest r = id.get().request();
        raw.getAttributes().put(ATTR_REQUEST, r.getRequestId());
        raw.getAttributes().put("rawToken", token);
        WebSocketSession old = registry.putPhone(r.getRequestId(), ws);
        if (old != null && old != ws) {
            try {
                old.close(CloseStatus.POLICY_VIOLATION.withReason("replaced"));
            } catch (Exception ignored) {
            }
        }
        sessionService.setPhoneOnline(r.getRequestId(), true);
        registry.send(ws, "AUTH_OK", snapshots.phoneStateFor(r));
    }

    private void handleAuthed(WebSocketSession ws, String type, JsonNode p, SessionService.PhoneIdentity id) {
        switch (type) {
            case "PING" -> registry.send(ws, "PONG", Map.of());
            case "VEHICLE_COMMAND" -> {
                requireParticipant(id);
                @SuppressWarnings("unchecked")
                Map<String, Object> params = mapper.convertValue(p.path("params"), Map.class);
                UUID cmd = commandService.submit(id.participant(), p.path("action").asText(""), params);
                registry.send(ws, "COMMAND_ACCEPTED", Map.of("requestId", cmd, "clientId", p.path("clientId").asText("")));
            }
            case "VEHICLE_STATE_REQUEST" -> {
                requireParticipant(id);
                commandService.requestState(id.participant(), p.path("domain").asText(""));
            }
            case "LEAVE" -> {
                if (id.participant() != null) {
                    sessionService.removeParticipant(id.participant(), false);
                } else {
                    sessionService.cancelRequest(id.request());
                }
                try {
                    ws.close(CloseStatus.NORMAL);
                } catch (Exception ignored) {
                }
            }
            default -> registry.send(ws, "ERROR", Map.of("code", "UNKNOWN_TYPE"));
        }
    }

    private static void requireParticipant(SessionService.PhoneIdentity id) {
        if (id.participant() == null || !id.participant().isActive()) {
            throw ApiException.forbidden("NOT_APPROVED", "차량에서 승인되지 않았거나 연결이 종료되었습니다.");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        UUID requestId = (UUID) raw.getAttributes().get(ATTR_REQUEST);
        WebSocketSession ws = (WebSocketSession) raw.getAttributes().get(ATTR_DECORATED);
        if (requestId != null && ws != null && registry.removePhone(requestId, ws)) {
            sessionService.setPhoneOnline(requestId, false);
        }
    }
}
