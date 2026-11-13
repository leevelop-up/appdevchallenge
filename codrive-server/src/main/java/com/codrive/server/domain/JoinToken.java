package com.codrive.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "join_tokens")
@Getter @Setter @NoArgsConstructor
public class JoinToken {
    @Id
    @Column(name = "token_id")
    private UUID tokenId;
    @Column(name = "session_id")
    private UUID sessionId;
    private String tokenHash;
    private String codeHash;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant invalidatedAt;

    public boolean isUsable(Instant now) {
        return consumedAt == null && invalidatedAt == null && expiresAt.isAfter(now);
    }
}
