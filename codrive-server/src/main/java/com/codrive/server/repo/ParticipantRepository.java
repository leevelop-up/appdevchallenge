package com.codrive.server.repo;

import com.codrive.server.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {
    Optional<Participant> findByTokenHash(String hash);

    Optional<Participant> findByRequestId(UUID requestId);

    List<Participant> findBySessionIdOrderByJoinedAtAsc(UUID sessionId);

    List<Participant> findBySessionIdAndRevokedAtIsNull(UUID sessionId);
}
