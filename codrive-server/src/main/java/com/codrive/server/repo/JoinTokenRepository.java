package com.codrive.server.repo;

import com.codrive.server.domain.JoinToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JoinTokenRepository extends JpaRepository<JoinToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from JoinToken t where t.tokenHash = :hash")
    Optional<JoinToken> lockByTokenHash(String hash);

    Optional<JoinToken> findByTokenHash(String hash);

    Optional<JoinToken> findByCodeHash(String hash);

    List<JoinToken> findBySessionIdAndConsumedAtIsNullAndInvalidatedAtIsNull(UUID sessionId);
}
