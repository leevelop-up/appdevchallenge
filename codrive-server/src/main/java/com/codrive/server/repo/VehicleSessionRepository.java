package com.codrive.server.repo;

import com.codrive.server.domain.VehicleSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleSessionRepository extends JpaRepository<VehicleSession, UUID> {
    Optional<VehicleSession> findFirstByInstallationIdAndStatusInOrderByCreatedAtDesc(
            UUID installationId, Collection<VehicleSession.Status> statuses);

    List<VehicleSession> findByInstallationIdAndStatusIn(UUID installationId, Collection<VehicleSession.Status> statuses);

    List<VehicleSession> findByStatus(VehicleSession.Status status);

    List<VehicleSession> findByStatusInAndExpiresAtBefore(Collection<VehicleSession.Status> statuses, Instant t);
}
