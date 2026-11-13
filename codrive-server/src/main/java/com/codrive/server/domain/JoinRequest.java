package com.codrive.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "join_requests")
@Getter @Setter @NoArgsConstructor
public class JoinRequest {
    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED }

    @Id
    @Column(name = "request_id")
    private UUID requestId;
    @Column(name = "session_id")
    private UUID sessionId;
    private String nickname;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String tokenHash;
    private Instant requestedAt;
    private Instant resolvedAt;
}
