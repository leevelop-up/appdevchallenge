package com.codrive.server.repo;

import com.codrive.server.domain.ParticipantPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ParticipantPermissionRepository
        extends JpaRepository<ParticipantPermission, ParticipantPermission.Key> {
    List<ParticipantPermission> findByParticipantIdAndRevokedAtIsNull(UUID participantId);

    List<ParticipantPermission> findByParticipantId(UUID participantId);
}
