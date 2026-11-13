package com.codrive.server.repo;

import com.codrive.server.domain.JoinRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, UUID> {
    Optional<JoinRequest> findByTokenHash(String hash);

    List<JoinRequest> findBySessionIdAndStatus(UUID sessionId, JoinRequest.Status status);

    List<JoinRequest> findBySessionIdAndStatusIn(UUID sessionId, Collection<JoinRequest.Status> statuses);

    List<JoinRequest> findByStatusAndRequestedAtBefore(JoinRequest.Status status, Instant t);
}
