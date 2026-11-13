package com.codrive.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "participants")
@Getter @Setter @NoArgsConstructor
public class Participant {
    public enum Role { NONE, MAP, HVAC, LIGHT, MUSIC }

    public enum ConnectionStatus { ONLINE, OFFLINE, DISCONNECTED, ENDED }

    @Id
    @Column(name = "participant_id")
    private UUID participantId;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "request_id")
    private UUID requestId;
    private String nickname;
    @Enumerated(EnumType.STRING)
    private Role role;
    @Enumerated(EnumType.STRING)
    private ConnectionStatus connectionStatus;
    private String tokenHash;
    private Instant joinedAt;
    private Instant disconnectedAt;
    private Instant revokedAt;

    public boolean isActive() {
        return revokedAt == null
                && (connectionStatus == ConnectionStatus.ONLINE || connectionStatus == ConnectionStatus.OFFLINE);
    }
}
