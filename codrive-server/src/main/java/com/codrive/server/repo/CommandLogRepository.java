package com.codrive.server.repo;

import com.codrive.server.domain.CommandLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommandLogRepository extends JpaRepository<CommandLog, UUID> {
    List<CommandLog> findByStatusInAndUpdatedAtBefore(Collection<CommandLog.Status> statuses, Instant t);

    List<CommandLog> findBySessionId(UUID sessionId);
}
