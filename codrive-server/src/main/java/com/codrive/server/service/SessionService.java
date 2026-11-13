package com.codrive.server.service;

import com.codrive.server.config.AppProperties;
import com.codrive.server.domain.*;
import com.codrive.server.repo.*;
import com.codrive.server.ws.ConnectionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SessionService {
    public record OpenResult(VehicleSession session, boolean created) {
    }

    private static final List<VehicleSession.Status> LIVE =
            List.of(VehicleSession.Status.ACTIVE, VehicleSession.Status.SUSPENDED);

    private final VehicleSessionRepository sessions;
    private final JoinTokenRepository joinTokens;
    private final JoinRequestRepository requests;
    private final ParticipantRepository participants;
    private final ParticipantPermissionRepository permissions;
    private final CommandLogRepository commands;
    private final CapabilityStore caps;
    private final TokenService tokens;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final ConnectionRegistry registry;

    @Transactional
    public OpenResult openSession(UUID installationId, String launchId) {
        if (launchId == null || launchId.isBlank() || launchId.length() > 64) {
            throw ApiException.badRequest("BAD_LAUNCH_ID", "launchId 가 필요합니다.");
        }
        Instant now = Instant.now();
        List<VehicleSession> live = sessions.findByInstallationIdAndStatusIn(installationId, LIVE);
        if (live.size() == 1 && live.get(0).getLaunchId().equals(launchId)
                && live.get(0).getExpiresAt().isAfter(now)) {
            return new OpenResult(live.get(0), false);
        }
        for (VehicleSession old : live) {
            endSession(old, "VEHICLE_RESTART");
        }
        VehicleSession s = new VehicleSession();
        s.setSessionId(UUID.randomUUID());
        s.setInstallationId(installationId);
        s.setLaunchId(launchId);
        s.setAlias("CO-DRIVE " + tokens.newJoinCode().substring(0, 4));
        s.setStatus(VehicleSession.Status.ACTIVE);
        s.setCreatedAt(now);
        s.setExpiresAt(now.plus(Duration.ofHours(props.sessionMaxHours())));
        s.setLastVehicleSeenAt(now);
        sessions.save(s);
        return new OpenResult(s, true);
    }

    @Transactional(readOnly = true)
    public VehicleSession ownedSession(UUID installationId, UUID sessionId) {
        VehicleSession s = sessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("NO_SESSION", "세션을 찾을 수 없습니다."));
        if (!s.getInstallationId().equals(installationId)) {
            throw ApiException.forbidden("NOT_YOUR_SESSION", "다른 차량의 세션입니다.");
        }
        return s;
    }

    @Transactional
    public Views.IssuedToken issueJoinToken(UUID installationId, UUID sessionId) {
        VehicleSession s = ownedSession(installationId, sessionId);
        if (!s.isLive()) {
            throw ApiException.conflict("SESSION_ENDED", "종료된 세션입니다.");
        }
        if (activeParticipants(sessionId).size() >= props.maxParticipants()) {
            throw ApiException.conflict("FULL", "최대 " + props.maxParticipants() + "명까지 연결할 수 있습니다.");
        }
        Instant now = Instant.now();
        for (JoinToken old : joinTokens.findBySessionIdAndConsumedAtIsNullAndInvalidatedAtIsNull(sessionId)) {
            old.setInvalidatedAt(now);
        }
        String raw = tokens.newToken();
        String code = tokens.newJoinCode();
        JoinToken t = new JoinToken();
        t.setTokenId(UUID.randomUUID());
        t.setSessionId(sessionId);
        t.setTokenHash(tokens.hash(raw));
        t.setCodeHash(tokens.hash(code));
        t.setCreatedAt(now);
        t.setExpiresAt(now.plusSeconds(props.qrTtlSeconds()));
        joinTokens.save(t);
        String url = props.publicBaseUrl().replaceAll("/+$", "") + "/join?token=" + raw;
        return new Views.IssuedToken(raw, code, t.getExpiresAt(), props.qrTtlSeconds(), url);
    }

    @Transactional
    public void endSession(UUID installationId, UUID sessionId, String reason) {
        endSession(ownedSession(installationId, sessionId), reason);
    }

    @Transactional
    public void endSession(VehicleSession s, String reason) {
        if (!s.isLive()) {
            return;
        }
        Instant now = Instant.now();
        s.setStatus(VehicleSession.Status.ENDED);
        s.setEndedAt(now);
        s.setEndReason(reason);
        sessions.save(s);

        List<JoinRequest> open = requests.findBySessionIdAndStatusIn(s.getSessionId(),
                List.of(JoinRequest.Status.PENDING, JoinRequest.Status.APPROVED));
        for (Participant p : participants.findBySessionIdAndRevokedAtIsNull(s.getSessionId())) {
            p.setRevokedAt(now);
            p.setDisconnectedAt(now);
            p.setConnectionStatus(Participant.ConnectionStatus.ENDED);
            permissions.findByParticipantId(p.getParticipantId()).forEach(pp -> pp.setRevokedAt(now));
        }
        for (JoinToken t : joinTokens.findBySessionIdAndConsumedAtIsNullAndInvalidatedAtIsNull(s.getSessionId())) {
            t.setInvalidatedAt(now);
        }
        for (JoinRequest r : open) {
            if (r.getStatus() == JoinRequest.Status.PENDING) {
                r.setStatus(JoinRequest.Status.CANCELLED);
                r.setResolvedAt(now);
            }
            events.publishEvent(new Events.ToPhone(r.getRequestId(), "SESSION_ENDED",
                    Map.of("reason", reason), true));
        }
        for (CommandLog c : commands.findBySessionId(s.getSessionId())) {
            if (!c.getStatus().isFinal()) {
                c.setStatus(CommandLog.Status.FAILED);
                c.setErrorCode("SESSION_ENDED");
                c.setCompletedAt(now);
            }
        }
        caps.clear(s.getSessionId());
        events.publishEvent(new Events.ToVehicle(s.getInstallationId(), "SESSION_ENDED", Map.of("reason", reason)));
    }

    @Transactional
    public void endAllLive(UUID installationId, String reason) {
        for (VehicleSession s : sessions.findByInstallationIdAndStatusIn(installationId, LIVE)) {
            endSession(s, reason);
        }
    }

    @Transactional
    public void onVehicleConnected(UUID installationId) {
        for (VehicleSession s : sessions.findByInstallationIdAndStatusIn(installationId, LIVE)) {
            s.setLastVehicleSeenAt(Instant.now());
            if (s.getStatus() == VehicleSession.Status.SUSPENDED) {
                s.setStatus(VehicleSession.Status.ACTIVE);
                s.setSuspendedAt(null);
                broadcastToPhones(s.getSessionId(), "VEHICLE_RECONNECTED", Map.of());
            }
            events.publishEvent(new Events.SessionChanged(s.getSessionId()));
        }
    }

    @Transactional
    public void onVehicleDisconnected(UUID installationId) {
        for (VehicleSession s : sessions.findByInstallationIdAndStatusIn(installationId, LIVE)) {
            if (s.getStatus() == VehicleSession.Status.ACTIVE) {
                s.setStatus(VehicleSession.Status.SUSPENDED);
                s.setSuspendedAt(Instant.now());
                broadcastToPhones(s.getSessionId(), "SESSION_SUSPENDED", Map.of("reason", "VEHICLE_DISCONNECTED"));
                events.publishEvent(new Events.SessionChanged(s.getSessionId()));
            }
        }
    }

    @Transactional(readOnly = true)
    public Views.JoinPreview preview(String token, String code) {
        JoinToken t = findToken(token, code);
        if (t == null) {
            return new Views.JoinPreview("", 0, props.maxParticipants(), false, "INVALID_TOKEN");
        }
        VehicleSession s = sessions.findById(t.getSessionId()).orElse(null);
        if (s == null || !s.isLive()) {
            return new Views.JoinPreview("", 0, props.maxParticipants(), false, "SESSION_ENDED");
        }
        int count = activeParticipants(s.getSessionId()).size();
        String reason = null;
        if (!t.isUsable(Instant.now())) {
            reason = t.getConsumedAt() != null ? "TOKEN_USED" : "TOKEN_EXPIRED";
        } else if (s.getStatus() == VehicleSession.Status.SUSPENDED) {
            reason = "VEHICLE_OFFLINE";
        } else if (count >= props.maxParticipants()) {
            reason = "FULL";
        }
        return new Views.JoinPreview(s.getAlias(), count, props.maxParticipants(), reason == null, reason);
    }

    public record JoinResult(UUID requestId, String participantToken, String sessionAlias) {
    }

    @Transactional
    public JoinResult createJoinRequest(String token, String code, String nicknameRaw) {
        String nickname = nicknameRaw == null ? "" : nicknameRaw.trim().replaceAll("\\p{Cntrl}", "");
        if (nickname.isEmpty() || nickname.length() > 20) {
            throw ApiException.badRequest("BAD_NICKNAME", "닉네임은 1~20자로 입력해주세요.");
        }
        JoinToken peek = findToken(token, code);
        if (peek == null) {
            throw ApiException.notFound("INVALID_TOKEN", "유효하지 않은 QR 코드입니다.");
        }
        JoinToken t = joinTokens.lockByTokenHash(peek.getTokenHash()).orElseThrow();
        Instant now = Instant.now();
        if (!t.isUsable(now)) {
            throw ApiException.conflict(t.getConsumedAt() != null ? "TOKEN_USED" : "TOKEN_EXPIRED",
                    "만료되었거나 이미 사용된 QR 코드입니다. 차량 화면의 새 QR 코드를 스캔해주세요.");
        }
        VehicleSession s = sessions.findById(t.getSessionId()).orElseThrow();
        if (!s.isLive()) {
            throw ApiException.conflict("SESSION_ENDED", "종료된 연결입니다.");
        }
        if (s.getStatus() == VehicleSession.Status.SUSPENDED) {
            throw ApiException.conflict("VEHICLE_OFFLINE", "차량 앱과 연결되어 있지 않습니다.");
        }
        List<Participant> active = activeParticipants(s.getSessionId());
        if (active.size() >= props.maxParticipants()) {
            throw ApiException.conflict("FULL", "이 차량은 이미 최대 인원이 연결되어 있습니다.");
        }
        if (active.stream().anyMatch(p -> p.getNickname().equalsIgnoreCase(nickname))) {
            throw ApiException.conflict("NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.");
        }
        t.setConsumedAt(now);

        String secret = tokens.newToken();
        JoinRequest r = new JoinRequest();
        r.setRequestId(UUID.randomUUID());
        r.setSessionId(s.getSessionId());
        r.setNickname(nickname);
        r.setStatus(JoinRequest.Status.PENDING);
        r.setTokenHash(tokens.hash(secret));
        r.setRequestedAt(now);
        requests.save(r);

        events.publishEvent(new Events.ToVehicle(s.getInstallationId(), "JOIN_REQUEST",
                Map.of("requestId", r.getRequestId(), "nickname", nickname, "requestedAt", now)));
        events.publishEvent(new Events.SessionChanged(s.getSessionId()));
        return new JoinResult(r.getRequestId(), secret, s.getAlias());
    }

    @Transactional
    public void cancelRequest(JoinRequest detached) {
        JoinRequest r = requests.findById(detached.getRequestId()).orElse(null);
        if (r == null || r.getStatus() != JoinRequest.Status.PENDING) {
            return;
        }
        r.setStatus(JoinRequest.Status.CANCELLED);
        r.setResolvedAt(Instant.now());
        events.publishEvent(new Events.SessionChanged(r.getSessionId()));
    }

    @Transactional
    public void approve(UUID installationId, UUID sessionId, UUID requestId) {
        VehicleSession s = ownedSession(installationId, sessionId);
        JoinRequest r = pendingRequest(s, requestId);
        if (!s.isLive()) {
            throw ApiException.conflict("SESSION_ENDED", "종료된 세션입니다.");
        }
        if (activeParticipants(sessionId).size() >= props.maxParticipants()) {
            throw ApiException.conflict("FULL", "최대 인원이 연결되어 있습니다. 기존 연결을 해제한 뒤 다시 승인하세요.");
        }
        Instant now = Instant.now();
        r.setStatus(JoinRequest.Status.APPROVED);
        r.setResolvedAt(now);
        Participant p = new Participant();
        p.setParticipantId(UUID.randomUUID());
        p.setSessionId(sessionId);
        p.setRequestId(requestId);
        p.setNickname(r.getNickname());
        p.setRole(Participant.Role.NONE);
        p.setConnectionStatus(registry.isPhoneOnline(requestId)
                ? Participant.ConnectionStatus.ONLINE : Participant.ConnectionStatus.OFFLINE);
        p.setTokenHash(r.getTokenHash());
        p.setJoinedAt(now);
        participants.save(p);
        events.publishEvent(new Events.ToPhone(requestId, "JOIN_APPROVED", Map.of("nickname", r.getNickname()), false));
        events.publishEvent(new Events.SessionChanged(sessionId));
    }

    @Transactional
    public void reject(UUID installationId, UUID sessionId, UUID requestId) {
        VehicleSession s = ownedSession(installationId, sessionId);
        JoinRequest r = pendingRequest(s, requestId);
        r.setStatus(JoinRequest.Status.REJECTED);
        r.setResolvedAt(Instant.now());
        events.publishEvent(new Events.ToPhone(requestId, "JOIN_REJECTED", Map.of(), true));
        events.publishEvent(new Events.SessionChanged(sessionId));
    }

    @Transactional
    public void assignRole(UUID installationId, UUID sessionId, UUID participantId, Participant.Role role) {
        VehicleSession s = ownedSession(installationId, sessionId);
        Participant p = activeParticipant(s, participantId);
        if (role == null || role == Participant.Role.NONE) {
            throw ApiException.badRequest("BAD_ROLE", "역할은 MAP, HVAC, LIGHT 중 하나여야 합니다.");
        }
        Instant now = Instant.now();
        boolean first = p.getRole() == Participant.Role.NONE;
        Set<String> wanted = new HashSet<>();
        Feature.forRole(role).forEach(f -> wanted.add(f.name()));
        Map<String, ParticipantPermission> existing = new HashMap<>();
        permissions.findByParticipantId(participantId).forEach(pp -> existing.put(pp.getFeatureCode(), pp));
        existing.values().forEach(pp -> {
            if (!wanted.contains(pp.getFeatureCode())) {
                pp.setRevokedAt(now);
            }
        });
        for (Feature f : Feature.forRole(role)) {
            ParticipantPermission pp = existing.get(f.name());
            if (pp == null) {
                permissions.save(new ParticipantPermission(participantId, f.name(), f.defaultAccess(), now));
            } else {
                pp.setAccessLevel(f.defaultAccess());
                pp.setGrantedAt(now);
                pp.setRevokedAt(null);
            }
        }
        p.setRole(role);
        events.publishEvent(new Events.ToPhone(p.getRequestId(), first ? "ROLE_ASSIGNED" : "ROLE_UPDATED",
                Map.of("role", role.name()), false));
        events.publishEvent(new Events.SessionChanged(sessionId));
    }

    @Transactional
    public void setAccess(UUID installationId, UUID sessionId, UUID participantId, String featureCode,
                          Feature.Access access) {
        VehicleSession s = ownedSession(installationId, sessionId);
        Participant p = activeParticipant(s, participantId);
        Feature f = Feature.parse(featureCode);
        if (f == null || f.role() != p.getRole()) {
            throw ApiException.badRequest("BAD_FEATURE", "이 참여자의 역할에 없는 기능입니다.");
        }
        boolean ok = f.kind() == Feature.Kind.VIEW
                ? (access == Feature.Access.NONE || access == Feature.Access.VIEW)
                : (access == Feature.Access.NONE || access == Feature.Access.CONTROL);
        if (!ok) {
            throw ApiException.badRequest("BAD_ACCESS", "이 기능에 설정할 수 없는 접근 수준입니다.");
        }
        ParticipantPermission pp = permissions.findById(new ParticipantPermission.Key(participantId, featureCode)).orElseGet(() -> new ParticipantPermission(participantId, featureCode, access, Instant.now()));
        pp.setAccessLevel(access);
        pp.setRevokedAt(null);
        permissions.save(pp);
        events.publishEvent(new Events.ToPhone(p.getRequestId(), "ROLE_UPDATED",
                Map.of("role", p.getRole().name()), false));
        events.publishEvent(new Events.SessionChanged(sessionId));
    }

    @Transactional
    public void removeParticipant(UUID installationId, UUID sessionId, UUID participantId) {
        VehicleSession s = ownedSession(installationId, sessionId);
        removeParticipant(activeParticipant(s, participantId), true);
    }

    @Transactional
    public void removeParticipant(Participant detached, boolean notifyPhone) {
        Participant p = participants.findById(detached.getParticipantId()).orElse(null);
        if (p == null || p.getRevokedAt() != null) {
            return;
        }
        Instant now = Instant.now();
        p.setRevokedAt(now);
        p.setDisconnectedAt(now);
        p.setConnectionStatus(Participant.ConnectionStatus.DISCONNECTED);
        permissions.findByParticipantId(p.getParticipantId()).forEach(pp -> pp.setRevokedAt(now));
        if (notifyPhone) {
            events.publishEvent(new Events.ToPhone(p.getRequestId(), "ROLE_REVOKED", Map.of(), true));
        }
        events.publishEvent(new Events.SessionChanged(p.getSessionId()));
    }

    public record PhoneIdentity(JoinRequest request, Participant participant) {
    }

    @Transactional(readOnly = true)
    public Optional<PhoneIdentity> authenticatePhone(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = tokens.hash(rawToken);
        return requests.findByTokenHash(hash).map(r ->
                new PhoneIdentity(r, participants.findByRequestId(r.getRequestId()).orElse(null)));
    }

    @Transactional
    public void setPhoneOnline(UUID requestId, boolean online) {
        participants.findByRequestId(requestId).ifPresent(p -> {
            if (!p.isActive()) {
                return;
            }
            Participant.ConnectionStatus next = online
                    ? Participant.ConnectionStatus.ONLINE : Participant.ConnectionStatus.OFFLINE;
            if (p.getConnectionStatus() != next) {
                p.setConnectionStatus(next);
                events.publishEvent(new Events.SessionChanged(p.getSessionId()));
            }
        });
    }

    @Transactional
    public void broadcastToPhones(UUID sessionId, String type, Object payload) {
        for (JoinRequest r : requests.findBySessionIdAndStatusIn(sessionId,
                List.of(JoinRequest.Status.PENDING, JoinRequest.Status.APPROVED))) {
            events.publishEvent(new Events.ToPhone(r.getRequestId(), type, payload, false));
        }
    }

    private List<Participant> activeParticipants(UUID sessionId) {
        return participants.findBySessionIdAndRevokedAtIsNull(sessionId).stream()
                .filter(Participant::isActive).toList();
    }

    private JoinToken findToken(String token, String code) {
        if (token != null && !token.isBlank()) {
            return joinTokens.findByTokenHash(tokens.hash(token)).orElse(null);
        }
        if (code != null && !code.isBlank()) {
            return joinTokens.findByCodeHash(tokens.hash(TokenService.normalizeCode(code))).orElse(null);
        }
        return null;
    }

    private JoinRequest pendingRequest(VehicleSession s, UUID requestId) {
        JoinRequest r = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("NO_REQUEST", "참여 요청을 찾을 수 없습니다."));
        if (!r.getSessionId().equals(s.getSessionId())) {
            throw ApiException.forbidden("NOT_YOUR_SESSION", "다른 세션의 요청입니다.");
        }
        if (r.getStatus() != JoinRequest.Status.PENDING) {
            throw ApiException.conflict("ALREADY_RESOLVED", "이미 처리된 요청입니다.");
        }
        return r;
    }

    private Participant activeParticipant(VehicleSession s, UUID participantId) {
        Participant p = participants.findById(participantId)
                .orElseThrow(() -> ApiException.notFound("NO_PARTICIPANT", "참여자를 찾을 수 없습니다."));
        if (!p.getSessionId().equals(s.getSessionId())) {
            throw ApiException.forbidden("NOT_YOUR_SESSION", "다른 세션의 참여자입니다.");
        }
        if (!p.isActive()) {
            throw ApiException.conflict("NOT_ACTIVE", "이미 연결이 종료된 참여자입니다.");
        }
        return p;
    }
}
