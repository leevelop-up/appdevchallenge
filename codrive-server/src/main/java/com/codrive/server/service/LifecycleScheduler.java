package com.codrive.server.service;

import com.codrive.server.config.AppProperties;
import com.codrive.server.domain.JoinRequest;
import com.codrive.server.domain.VehicleSession;
import com.codrive.server.repo.JoinRequestRepository;
import com.codrive.server.repo.VehicleSessionRepository;
import com.codrive.server.ws.ConnectionRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LifecycleScheduler {
    private static final Logger log = LoggerFactory.getLogger(LifecycleScheduler.class);

    private final VehicleSessionRepository sessions;
    private final JoinRequestRepository requests;
    private final SessionService sessionService;
    private final CommandService commandService;
    private final ConnectionRegistry registry;
    private final AppProperties props;
    private final ApplicationEventPublisher events;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        try {
            for (VehicleSession s : sessions.findByStatusInAndExpiresAtBefore(
                    List.of(VehicleSession.Status.ACTIVE, VehicleSession.Status.SUSPENDED), now)) {
                sessionService.endSession(s, "EXPIRED");
            }

            long hbCutoff = System.currentTimeMillis() - props.vehicleHeartbeatTimeoutSeconds() * 1000L;
            for (VehicleSession s : sessions.findByStatus(VehicleSession.Status.ACTIVE)) {
                UUID inst = s.getInstallationId();
                boolean connected = registry.isVehicleConnected(inst);
                Long lastSeen = registry.vehicleLastSeen(inst);
                if (connected && lastSeen != null && lastSeen < hbCutoff) {
                    registry.closeVehicle(inst, CloseStatus.SESSION_NOT_RELIABLE);
                    sessionService.onVehicleDisconnected(inst);
                } else if (!connected && s.getCreatedAt().plusSeconds(props.vehicleGraceSeconds()).isBefore(now)) {
                    sessionService.onVehicleDisconnected(inst);
                }
            }

            for (VehicleSession s : sessions.findByStatus(VehicleSession.Status.SUSPENDED)) {
                if (s.getSuspendedAt() != null
                        && s.getSuspendedAt().plusSeconds(props.vehicleGraceSeconds()).isBefore(now)) {
                    sessionService.endSession(s, "NETWORK_TIMEOUT");
                }
            }

            Instant reqCutoff = now.minusSeconds(props.joinRequestTtlSeconds());
            for (JoinRequest r : requests.findByStatusAndRequestedAtBefore(JoinRequest.Status.PENDING, reqCutoff)) {
                r.setStatus(JoinRequest.Status.EXPIRED);
                r.setResolvedAt(now);
                events.publishEvent(new Events.ToPhone(r.getRequestId(), "JOIN_REJECTED",
                        Map.of("reason", "EXPIRED"), true));
                events.publishEvent(new Events.SessionChanged(r.getSessionId()));
            }

            commandService.expireStaleCommands();
        } catch (Exception e) {
            log.warn("lifecycle tick failed: {}", e.toString());
        }
    }
}
