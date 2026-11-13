package com.codrive.server.service;

import com.codrive.server.config.AppProperties;
import com.codrive.server.domain.*;
import com.codrive.server.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SnapshotService {
    public record All(UUID installationId, Views.VehicleSnapshot vehicle, Map<UUID, Views.PhoneState> phonesByRequestId) {
    }

    private final VehicleSessionRepository sessions;
    private final ParticipantRepository participants;
    private final ParticipantPermissionRepository permissions;
    private final JoinRequestRepository requests;
    private final CapabilityStore caps;
    private final AppProperties props;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public All buildAll(UUID sessionId) {
        VehicleSession s = sessions.findById(sessionId).orElse(null);
        if (s == null) {
            return null;
        }
        List<Participant> active = participants.findBySessionIdAndRevokedAtIsNull(sessionId).stream()
                .filter(Participant::isActive)
                .sorted(Comparator.comparing(Participant::getJoinedAt)).toList();
        CapabilityStore.Report report = caps.get(sessionId);
        String mode = report == null ? "UNKNOWN" : report.mode();
        boolean driving = report != null && report.driving();

        List<Views.ParticipantView> pv = new ArrayList<>();
        Map<UUID, Views.PhoneState> phones = new HashMap<>();
        for (Participant p : active) {
            List<Views.FeatureView> features = featuresOf(p, sessionId);
            pv.add(new Views.ParticipantView(p.getParticipantId(), p.getNickname(), p.getRole(),
                    p.getConnectionStatus(), features));
            phones.put(p.getRequestId(), new Views.PhoneState("APPROVED", p.getNickname(), s.getAlias(),
                    s.getStatus().name(), p.getRole(), active.size(), props.maxParticipants(), mode, features));
        }
        List<Views.PendingView> pending = requests.findBySessionIdAndStatus(sessionId, JoinRequest.Status.PENDING)
                .stream().sorted(Comparator.comparing(JoinRequest::getRequestedAt))
                .map(r -> new Views.PendingView(r.getRequestId(), r.getNickname(), r.getRequestedAt())).toList();
        Views.VehicleSnapshot vehicle = new Views.VehicleSnapshot(sessionId, s.getAlias(), s.getStatus().name(),
                props.maxParticipants(), mode, driving, s.getExpiresAt(), pv, pending);
        return new All(s.getInstallationId(), vehicle, phones);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Views.PhoneState phoneStateFor(JoinRequest r) {
        VehicleSession s = sessions.findById(r.getSessionId()).orElse(null);
        String alias = s == null ? "" : s.getAlias();
        String sessionStatus = s == null ? "ENDED" : s.getStatus().name();
        if (s == null || !s.isLive()) {
            return new Views.PhoneState("ENDED", r.getNickname(), alias, sessionStatus, Participant.Role.NONE,
                    0, props.maxParticipants(), "UNKNOWN", List.of());
        }
        if (r.getStatus() == JoinRequest.Status.APPROVED) {
            Participant p = participants.findByRequestId(r.getRequestId()).orElse(null);
            if (p == null || !p.isActive()) {
                return new Views.PhoneState("ENDED", r.getNickname(), alias, sessionStatus, Participant.Role.NONE,
                        0, props.maxParticipants(), "UNKNOWN", List.of());
            }
            All all = buildAll(r.getSessionId());
            return all.phonesByRequestId().get(r.getRequestId());
        }
        int count = (int) participants.findBySessionIdAndRevokedAtIsNull(r.getSessionId()).stream()
                .filter(Participant::isActive).count();
        CapabilityStore.Report report = caps.get(r.getSessionId());
        return new Views.PhoneState(r.getStatus().name(), r.getNickname(), alias, sessionStatus,
                Participant.Role.NONE, count, props.maxParticipants(),
                report == null ? "UNKNOWN" : report.mode(), List.of());
    }

    private List<Views.FeatureView> featuresOf(Participant p, UUID sessionId) {
        Map<String, ParticipantPermission> byCode = new HashMap<>();
        for (ParticipantPermission pp : permissions.findByParticipantIdAndRevokedAtIsNull(p.getParticipantId())) {
            byCode.put(pp.getFeatureCode(), pp);
        }
        List<Views.FeatureView> out = new ArrayList<>();
        for (Feature f : Feature.forRole(p.getRole())) {
            ParticipantPermission pp = byCode.get(f.name());
            Feature.Access access = pp == null ? Feature.Access.NONE : pp.getAccessLevel();
            CapabilityStore.Entry cap = caps.entry(sessionId, f);
            Feature.Status st = PermissionPolicy.evaluate(f, access, cap);
            if (st == null) {
                continue;
            }
            out.add(new Views.FeatureView(f.name(), f.kind().name(), access.name(), st.name(),
                    cap == null ? null : cap.min(), cap == null ? null : cap.max()));
        }
        return out;
    }
}
