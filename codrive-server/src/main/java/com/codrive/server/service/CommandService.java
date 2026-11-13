package com.codrive.server.service;

import com.codrive.server.config.AppProperties;
import com.codrive.server.domain.*;
import com.codrive.server.repo.*;
import com.codrive.server.ws.ConnectionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CommandService {
    private static final java.util.Set<String> MUSIC_COMMANDS = java.util.Set.of("PLAY", "PAUSE", "NEXT", "PREVIOUS");
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final VehicleSessionRepository sessions;
    private final ParticipantPermissionRepository permissions;
    private final CommandLogRepository commands;
    private final JoinRequestRepository requests;
    private final CapabilityStore caps;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final ConnectionRegistry registry;
    private final ObjectMapper mapper;
    private final ParticipantRepository participantsLookup;

    @Transactional
    public UUID submit(Participant p, String action, Map<String, Object> params) {
        VehicleSession s = sessions.findById(p.getSessionId()).orElseThrow();
        if (!s.isLive() || !p.isActive()) {
            throw ApiException.forbidden("SESSION_ENDED", "연결이 종료되었습니다.");
        }
        if (s.getStatus() == VehicleSession.Status.SUSPENDED) {
            throw ApiException.conflict("SESSION_SUSPENDED", "차량 연결이 일시 끊겨 요청을 처리할 수 없습니다.");
        }
        Feature f = Feature.parse(action);
        if (f == null || f.kind() != Feature.Kind.CONTROL) {
            throw ApiException.badRequest("UNKNOWN_ACTION", "알 수 없는 요청입니다.");
        }
        if (f.role() != p.getRole()) {
            throw ApiException.forbidden("NOT_YOUR_ROLE", "내 역할에서 사용할 수 없는 기능입니다.");
        }
        Feature.Access access = accessOf(p, f);
        CapabilityStore.Entry cap = caps.entry(s.getSessionId(), f);
        Feature.Status status = PermissionPolicy.evaluate(f, access, cap);

        CommandLog log = new CommandLog();
        log.setRequestId(UUID.randomUUID());
        log.setSessionId(s.getSessionId());
        log.setParticipantId(p.getParticipantId());
        log.setAction(action);
        log.setStatus(CommandLog.Status.RECEIVED);
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(log.getCreatedAt());
        log.setSimulated(status == Feature.Status.MOCK_ONLY);
        try {
            log.setParams(mapper.writeValueAsString(params == null ? Map.of() : params));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("INVALID_PARAMS", "요청 값을 읽을 수 없습니다.");
        }

        if (!PermissionPolicy.usable(f, status)) {
            CommandLog.Status st = (status == Feature.Status.NOT_SUPPORTED)
                    ? CommandLog.Status.NOT_SUPPORTED : CommandLog.Status.PERMISSION_DENIED;
            finish(log, st, status == null ? "NOT_SHARED" : status.name(), false, p.getRequestId());
            commands.save(log);
            return log.getRequestId();
        }
        String invalid = validate(f, params, cap);
        if (invalid != null) {
            throw ApiException.badRequest("INVALID_PARAMS", invalid);
        }
        if (!registry.isVehicleConnected(s.getInstallationId())) {
            finish(log, CommandLog.Status.FAILED, "VEHICLE_OFFLINE", false, p.getRequestId());
            commands.save(log);
            return log.getRequestId();
        }
        log.setStatus(CommandLog.Status.AUTHORIZED);
        log.setStatus(CommandLog.Status.SENT_TO_VEHICLE);
        commands.save(log);
        events.publishEvent(new Events.ToVehicle(s.getInstallationId(), "VEHICLE_COMMAND", Map.of(
                "requestId", log.getRequestId(), "action", action,
                "params", params == null ? Map.of() : params,
                "from", p.getNickname())));
        pushStatus(log, p.getRequestId(), null);
        return log.getRequestId();
    }

    @Transactional
    public void requestState(Participant p, String domain) {
        Feature f = switch (domain == null ? "" : domain) {
            case "HVAC" -> Feature.HVAC_VIEW;
            case "LIGHT" -> Feature.LIGHT_VIEW;
            case "MUSIC" -> Feature.MUSIC_VIEW;
            default -> null;
        };
        if (f == null || f.role() != p.getRole()) {
            throw ApiException.forbidden("NOT_YOUR_ROLE", "내 역할에서 조회할 수 없는 정보입니다.");
        }
        VehicleSession s = sessions.findById(p.getSessionId()).orElseThrow();
        if (!s.isLive() || !p.isActive()) {
            throw ApiException.forbidden("SESSION_ENDED", "연결이 종료되었습니다.");
        }
        Feature.Status st = PermissionPolicy.evaluate(f, accessOf(p, f), caps.entry(s.getSessionId(), f));
        if (!PermissionPolicy.usable(f, st)) {
            throw ApiException.forbidden("NOT_AVAILABLE", "지금은 조회할 수 없습니다.");
        }
        events.publishEvent(new Events.ToVehicle(s.getInstallationId(), "VEHICLE_STATE_REQUEST",
                Map.of("targetRequestId", p.getRequestId(), "domain", domain)));
    }

    @Transactional
    public void onAck(UUID installationId, UUID requestId, boolean awaitingDriver) {
        CommandLog log = ownedCommand(installationId, requestId);
        if (log != null && !log.getStatus().isFinal()) {
            log.setStatus(awaitingDriver ? CommandLog.Status.AWAITING_DRIVER : CommandLog.Status.APPLYING);
            log.setUpdatedAt(Instant.now());
            pushStatus(log, phoneRequestId(log), null);
        }
    }

    @Transactional
    public void onResult(UUID installationId, UUID requestId, String statusRaw, String errorCode,
                         boolean simulated, String message) {
        CommandLog log = ownedCommand(installationId, requestId);
        if (log == null || log.getStatus().isFinal()) {
            return;
        }
        CommandLog.Status st;
        try {
            st = CommandLog.Status.valueOf(statusRaw);
        } catch (Exception e) {
            st = CommandLog.Status.FAILED;
        }
        if (!st.isFinal()) {
            st = CommandLog.Status.FAILED;
        }
        log.setSimulated(simulated);
        finish(log, st, errorCode, simulated, phoneRequestId(log));
        if (message != null && !message.isBlank()) {
            pushStatus(log, phoneRequestId(log), message);
        }
    }

    @Transactional
    public void onCapabilityReport(UUID installationId, String mode, boolean driving,
                                   Map<Feature, CapabilityStore.Entry> features) {
        sessions.findFirstByInstallationIdAndStatusInOrderByCreatedAtDesc(installationId,
                List.of(VehicleSession.Status.ACTIVE, VehicleSession.Status.SUSPENDED)).ifPresent(s -> {
            caps.put(s.getSessionId(), new CapabilityStore.Report(mode, driving, features));
            events.publishEvent(new Events.SessionChanged(s.getSessionId()));
        });
    }

    @Transactional(readOnly = true)
    public void forwardState(UUID installationId, UUID targetRequestId, String domain,
                             Map<String, Object> values, boolean simulated) {
        JoinRequest r = requests.findById(targetRequestId).orElse(null);
        if (r == null) {
            return;
        }
        VehicleSession s = sessions.findById(r.getSessionId()).orElse(null);
        if (s == null || !s.isLive() || !s.getInstallationId().equals(installationId)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("domain", domain);
        payload.put("values", values);
        payload.put("simulated", simulated);
        events.publishEvent(new Events.ToPhone(targetRequestId, "VEHICLE_STATE", payload, false));
    }

    @Transactional
    public void expireStaleCommands() {
        Instant now = Instant.now();

        for (CommandLog log : commands.findByStatusInAndUpdatedAtBefore(
                List.of(CommandLog.Status.SENT_TO_VEHICLE, CommandLog.Status.APPLYING, CommandLog.Status.AUTHORIZED,
                        CommandLog.Status.RECEIVED), now.minusSeconds(props.commandTimeoutSeconds()))) {
            finish(log, CommandLog.Status.TIMED_OUT, "TIMED_OUT", false, phoneRequestId(log));
        }

        for (CommandLog log : commands.findByStatusInAndUpdatedAtBefore(
                List.of(CommandLog.Status.AWAITING_DRIVER), now.minusSeconds(props.proposalTimeoutSeconds()))) {
            finish(log, CommandLog.Status.TIMED_OUT, "DRIVER_NO_RESPONSE", false, phoneRequestId(log));
        }
    }

    private void finish(CommandLog log, CommandLog.Status st, String errorCode, boolean simulated, UUID phoneReq) {
        log.setStatus(st);
        log.setErrorCode(st == CommandLog.Status.APPLIED ? null : errorCode);
        log.setSimulated(simulated || log.isSimulated());
        log.setCompletedAt(Instant.now());
        log.setUpdatedAt(log.getCompletedAt());
        pushStatus(log, phoneReq, null);
    }

    private void pushStatus(CommandLog log, UUID phoneReq, String message) {
        if (phoneReq == null) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", log.getRequestId());
        m.put("action", log.getAction());
        m.put("status", log.getStatus().name());
        m.put("errorCode", log.getErrorCode());
        m.put("simulated", log.isSimulated());
        if (message != null) {
            m.put("message", message);
        }
        events.publishEvent(new Events.ToPhone(phoneReq, "COMMAND_RESULT", m, false));
    }

    private UUID phoneRequestId(CommandLog log) {
        return permissionsOwnerRequest(log.getParticipantId());
    }

    private final Map<UUID, UUID> participantToRequest = new java.util.concurrent.ConcurrentHashMap<>();

    private UUID permissionsOwnerRequest(UUID participantId) {
        return participantToRequest.computeIfAbsent(participantId, id ->
                participantsLookup.findById(id).map(Participant::getRequestId).orElse(null));
    }

    private CommandLog ownedCommand(UUID installationId, UUID requestId) {
        CommandLog log = commands.findById(requestId).orElse(null);
        if (log == null) {
            return null;
        }
        VehicleSession s = sessions.findById(log.getSessionId()).orElse(null);
        return (s != null && s.getInstallationId().equals(installationId)) ? log : null;
    }

    private Feature.Access accessOf(Participant p, Feature f) {
        return permissions.findByParticipantIdAndRevokedAtIsNull(p.getParticipantId()).stream()
                .filter(pp -> pp.getFeatureCode().equals(f.name()))
                .map(ParticipantPermission::getAccessLevel).findFirst().orElse(Feature.Access.NONE);
    }

    static String validate(Feature f, Map<String, Object> params, CapabilityStore.Entry cap) {
        if (params == null) {
            return "요청 값이 필요합니다.";
        }
        switch (f) {
            case NAV_DESTINATION, NAV_WAYPOINT -> {
                Object name = params.get("name");
                Double lat = num(params.get("lat"));
                Double lng = num(params.get("lng"));
                if (!(name instanceof String n) || n.isBlank() || n.length() > 100) {
                    return "장소 이름이 올바르지 않습니다.";
                }
                if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    return "좌표가 올바르지 않습니다.";
                }
            }
            case HVAC_TEMPERATURE -> {
                return range(num(params.get("celsius")), cap, "온도");
            }
            case HVAC_FAN -> {
                return range(num(params.get("level")), cap, "풍량");
            }
            case LIGHT_BRIGHTNESS -> {
                return range(num(params.get("level")), cap, "밝기");
            }
            case LIGHT_COLOR -> {
                Object c = params.get("hex");
                if (!(c instanceof String s) || !HEX_COLOR.matcher(s).matches()) {
                    return "색상 값이 올바르지 않습니다. (#RRGGBB)";
                }
            }
            case MUSIC_CONTROL -> {
                Object c = params.get("command");
                if (!(c instanceof String s) || !MUSIC_COMMANDS.contains(s)) {
                    return "음악 명령은 PLAY, PAUSE, NEXT, PREVIOUS 중 하나여야 합니다.";
                }
            }
            default -> {
                return "지원하지 않는 요청입니다.";
            }
        }
        return null;
    }

    private static String range(Double v, CapabilityStore.Entry cap, String label) {
        if (cap == null || cap.min() == null || cap.max() == null) {
            return label + " 값의 허용 범위를 차량이 알려주지 않아 변경할 수 없습니다.";
        }
        if (v == null || v < cap.min() || v > cap.max()) {
            return label + " 값은 " + cap.min() + " ~ " + cap.max() + " 범위여야 합니다.";
        }
        return null;
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
